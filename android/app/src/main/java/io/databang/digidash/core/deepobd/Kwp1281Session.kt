package io.databang.digidash.core.deepobd

import io.databang.digidash.domain.model.RawDtc
import io.databang.digidash.domain.model.RawField
import io.databang.digidash.domain.model.RawMeasuringBlock

/**
 * Best-effort KWP1281 host state machine over a Deep OBD-style adapter, built
 * from docs/DeepOBD-Observed-API.md. The adapter firmware performs the 5-baud
 * init and the low-level byte ack/echo; this class drives the block-level
 * protocol: pump the ECU's identification blocks (ACK each), then issue
 * group-read / DTC / clear request blocks and parse the responses.
 *
 * KWP1281 framing per block: `[len] [counter] [title] [data...] 0x03`, where
 * `len` = counter+title+data+end byte count, and the block counter increments
 * by one for every block in either direction. In the adapter's auto mode each
 * received byte arrives as a (data, status) pair, so RX is de-paired first.
 *
 * This is expected to need live tuning on the vehicle (ticket 14). Every byte
 * is visible through [LoggingSppTransport], so mismatches are debuggable from a
 * capture. Methods return typed failures and never throw into the client.
 */
/**
 * Runtime-tunable framing knobs so the KWP1281 wire behaviour can be adjusted
 * live at the vehicle (via the debug bridge) without rebuilding — the first
 * real contact will likely need one of these flipped.
 */
data class Kwp1281Config(
    /** Let the adapter firmware auto-detect the ECU baud (default) vs fixed 9600. */
    val autoBaud: Boolean = true,
    /** 5-baud init line driver: "both" (K+L, default), "l", or "k". */
    val initLine: String = "both",
    /** RX de-pairing: the adapter returns raw K-line bytes, so default OFF. */
    val depair: String = "off",
    /** Kotlin builds the full [len][counter][title][data][03] block (else title+data only). */
    val buildFullBlock: Boolean = true,
    /** Send ACK (0x09) blocks ourselves (off if the firmware auto-acks). */
    val sendAcks: Boolean = true,
)

class Kwp1281Session(
    private val transport: SppTransport,
    private val baud: Int = 9600,
    private val ecuAddress: Int = 0x01,
    private val blockTimeoutMs: Long = 1500,
    private val config: Kwp1281Config = Kwp1281Config(),
) {
    private var counter = 0
    private val idBlocks = mutableListOf<String>()

    /** ID ASCII blocks captured during connect (for the debug 'id' command). */
    fun identificationBlocks(): List<String> = idBlocks.toList()

    data class Block(val counter: Int, val title: Int, val data: ByteArray)

    /** 5-baud init + key-byte handshake + pump identification blocks. */
    fun connect(): Result<List<String>> = runCatching {
        // KWP1281 requires ~2600 ms of K-line idle before the 5-baud wake.
        Thread.sleep(2600)
        val flags1 = when (config.initLine) {
            "l" -> AdapterProtocol.PULSE_FLAGS_L
            "k" -> AdapterProtocol.PULSE_FLAGS_K
            else -> AdapterProtocol.PULSE_FLAGS_BOTH
        }
        // In auto-baud mode the pulse carries the BaudAuto sentinel and the
        // adapter firmware measures the ECU sync + sends the ~KB2 ack itself.
        val pulseBaud = if (config.autoBaud) AdapterProtocol.BAUD_AUTO else baud
        val pulse = AdapterProtocol.pulseTelegram(ecuAddress, baud = pulseBaud, flags1 = flags1)
        android.util.Log.i(TAG, "kwp: TX pulse(addr=$ecuAddress auto=${config.autoBaud} line=${config.initLine}) ${hexOf(pulse)}")
        counter = 0
        transport.write(pulse)

        if (config.autoBaud) {
            // Adapter returns 2 bytes = baud/2 big-endian, then 2 key bytes.
            val baudBytes = readExact(2, 4000)
            android.util.Log.i(TAG, "kwp: RX baud ${hexOf(baudBytes)}")
            if (baudBytes.size < 2) error("no wake response (ignition on? K-line?)")
            val detected = (((baudBytes[0].toInt() and 0xFF) shl 8) or (baudBytes[1].toInt() and 0xFF)) shl 1
            android.util.Log.i(TAG, "kwp: detected baud = $detected")
            if (detected == 0) error("invalid baud detected")
            val kb = readExact(2, 1500)
            android.util.Log.i(TAG, "kwp: RX keybytes ${hexOf(kb)}")
            // Firmware sends the ~KB2 complement itself; host does nothing here.
        } else {
            // Legacy path: 1 sync byte + 2 key bytes, host sends ~KB2.
            val init = readRaw(4000)
            android.util.Log.i(TAG, "kwp: RX init ${init.size}: ${hexOf(init)}")
            if (init.size < 3) error("no sync/keybytes from ECU after init")
            val kb2Raw = init[2].toInt() and 0xFF
            Thread.sleep(40)
            transport.write(AdapterProtocol.kLineTelegram(baud, byteArrayOf((kb2Raw.inv() and 0xFF).toByte())))
        }

        // Pump ID blocks: read a block, ACK it, until the ECU sends an ACK block.
        idBlocks.clear()
        var guard = 0
        while (guard++ < 32) {
            val block = readBlock() ?: break
            when (block.title) {
                Kwp1281Protocol.TITLE_ASCII ->
                    idBlocks.add(String(block.data, Charsets.US_ASCII).trim())
                Kwp1281Protocol.TITLE_ACK -> { sendAck(); break }
            }
            sendAck()
        }
        idBlocks.toList()
    }

    fun readGroup(group: Int): Result<RawMeasuringBlock> = runCatching {
        sendBlock(Kwp1281Protocol.TITLE_GROUP_REQUEST, byteArrayOf(group.toByte()))
        val resp = readBlock() ?: error("no group response for $group")
        val fields: List<RawField> = when (resp.title) {
            Kwp1281Protocol.TITLE_GROUP_RESPONSE -> Kwp1281Protocol.decodeGroup(group, resp.data)
            Kwp1281Protocol.TITLE_NO_DATA -> error("group $group not supported by ECU")
            else -> Kwp1281Protocol.decodeGroup(group, resp.data)
        }
        sendAck()
        RawMeasuringBlock(group = group, fields = fields, timestampMillis = System.currentTimeMillis())
    }

    fun readDtc(): Result<List<RawDtc>> = runCatching {
        sendBlock(Kwp1281Protocol.TITLE_DTC_REQUEST, ByteArray(0))
        val dtcs = mutableListOf<RawDtc>()
        var guard = 0
        var block = readBlock()
        while (block != null && guard++ < 16) {
            if (block.title == Kwp1281Protocol.TITLE_DTC_RESPONSE) {
                dtcs += Kwp1281Protocol.decodeDtcResponse(block.data)
            }
            sendAck()
            if (block.title == Kwp1281Protocol.TITLE_ACK) break
            block = readBlock()
        }
        dtcs
    }

    fun clearDtc(): Result<Unit> = runCatching {
        sendBlock(Kwp1281Protocol.TITLE_DTC_CLEAR, ByteArray(0))
        // ECU replies with an ACK block on success.
        readBlock()
        sendAck()
    }

    /**
     * Enter Basic Settings for [group] (adjustment mode) — like a group read but
     * with the basic-setting title, which keeps the ECU cycling that group.
     * Returns the first block of values.
     */
    fun enterBasicSettings(group: Int): Result<RawMeasuringBlock> = runCatching {
        sendBlock(Kwp1281Protocol.TITLE_BASIC_SETTING, byteArrayOf(group.toByte()))
        val resp = readBlock() ?: error("no basic-setting response for $group")
        val fields = Kwp1281Protocol.decodeGroup(group, resp.data)
        sendAck()
        RawMeasuringBlock(group = group, fields = fields, timestampMillis = System.currentTimeMillis())
    }

    fun exitBasicSettings(): Result<Unit> = runCatching {
        sendBlock(Kwp1281Protocol.TITLE_END_OUTPUT, ByteArray(0))
    }

    private fun sendAck() {
        if (config.sendAcks) sendBlock(Kwp1281Protocol.TITLE_ACK, ByteArray(0))
    }

    private fun sendBlock(title: Int, data: ByteArray) {
        val payload = if (config.buildFullBlock) {
            counter = (counter + 1) and 0xFF
            ByteArray(data.size + 4).also {
                it[0] = (data.size + 3).toByte() // len: counter+title+data+end
                it[1] = counter.toByte()
                it[2] = title.toByte()
                data.copyInto(it, 3)
                it[it.size - 1] = 0x03
            }
        } else {
            // Firmware frames the block; we send just the title + data.
            byteArrayOf(title.toByte(), *data)
        }
        transport.write(AdapterProtocol.kLineTelegram(baud = baud, payload = payload))
    }

    private val TAG = "DIGIDASH_DBG"
    private fun hexOf(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun readBlock(): Block? {
        val raw = readRaw(blockTimeoutMs)
        android.util.Log.i(TAG, "kwp: RX block ${raw.size}: ${hexOf(raw)}")
        if (raw.isEmpty()) return null
        val bytes = depair(raw)
        if (bytes.size < 4) return null
        // [len][counter][title][data...][0x03]
        val len = bytes[0].toInt() and 0xFF
        val blkCounter = bytes[1].toInt() and 0xFF
        val title = bytes[2].toInt() and 0xFF
        val endIdx = bytes.indexOfLast { it.toInt() and 0xFF == 0x03 }.let { if (it < 3) bytes.size else it }
        val data = bytes.copyOfRange(3, endIdx.coerceAtMost(bytes.size))
        counter = blkCounter
        return Block(blkCounter, title, data)
    }

    /**
     * De-pair adapter auto-mode RX: bytes arrive as (data, status) pairs. If the
     * stream length is odd (or already de-paired by firmware), fall back to raw.
     */
    private fun depair(raw: ByteArray): ByteArray = when (config.depair) {
        "on" -> if (raw.size >= 2) ByteArray(raw.size / 2) { raw[it * 2] } else raw
        "off" -> raw
        else -> if (raw.size >= 4 && raw.size % 2 == 0 && looksPaired(raw)) {
            ByteArray(raw.size / 2) { raw[it * 2] }
        } else raw
    }

    /** Heuristic: status bytes have the high bits mostly clear (delay*10ms). */
    private fun looksPaired(raw: ByteArray): Boolean {
        var oddHigh = 0
        var i = 1
        while (i < raw.size) {
            if ((raw[i].toInt() and 0x80) != 0) oddHigh++
            i += 2
        }
        return oddHigh == 0
    }

    /** Read exactly [n] bytes (accumulating) or return what arrived before timeout. */
    private fun readExact(n: Int, timeoutMs: Long): ByteArray {
        val out = ArrayList<Byte>(n)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (out.size < n && System.currentTimeMillis() < deadline) {
            val c = transport.read(n - out.size, 200)
            if (c.isNotEmpty()) out.addAll(c.toList())
        }
        return out.toByteArray()
    }

    private fun readRaw(timeoutMs: Long): ByteArray {
        val chunks = ArrayList<Byte>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val c = transport.read(128, 150)
            if (c.isNotEmpty()) {
                chunks.addAll(c.toList())
                // A block ends with 0x03; stop early once we've seen it.
                if (c.any { it.toInt() and 0xFF == 0x03 }) break
            } else if (chunks.isNotEmpty()) {
                break
            }
        }
        return chunks.toByteArray()
    }
}
