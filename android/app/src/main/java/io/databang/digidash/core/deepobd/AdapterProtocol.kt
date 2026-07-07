package io.databang.digidash.core.deepobd

/**
 * Wire-format primitives for the Deep OBD custom/replacement-firmware adapter,
 * reimplemented from the C# host (see docs/DeepOBD-Observed-API.md). Pure and
 * fully unit-testable — no Bluetooth here.
 *
 * All status/info and K-line request telegrams are BMW-FAST framed and end with
 * an 8-bit sum checksum of the preceding bytes.
 */
object AdapterProtocol {

    const val ESCAPE_CODE = 0xFF
    const val ESCAPE_MASK = 0x80
    const val ESCAPE_XOR = 0x55

    // K-line telegram flags1 bits.
    const val KLINEF1_PARITY_NONE = 0x00
    const val KLINEF1_USE_LLINE = 0x08
    const val KLINEF1_SEND_PULSE = 0x10
    const val KLINEF1_NO_ECHO = 0x20
    const val KLINEF1_FAST_INIT = 0x40
    const val KLINEF1_USE_KLINE = 0x80

    // K-line telegram flags2 bits.
    const val KLINEF2_KWP1281_DETECT = 0x01

    const val KWP1281_TIMEOUT = 60

    /** Sentinel baud that makes the adapter auto-detect the ECU baud (baud/2 = 1). */
    const val BAUD_AUTO = 2

    /** 8-bit additive checksum used by the BMW-FAST framing. */
    fun checksum(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
        var sum = 0
        for (i in from until to) sum = (sum + (bytes[i].toInt() and 0xFF)) and 0xFF
        return sum
    }

    /** Append the checksum byte to [payload], returning a new array. */
    fun withChecksum(payload: ByteArray): ByteArray =
        payload + checksum(payload).toByte()

    /**
     * Build a BMW-FAST status/info telegram `8x F1 F1 <cmd> <cmd> <cks>`.
     * The length nibble in the first byte encodes 2 data bytes (`cmd,cmd`).
     */
    fun infoTelegram(cmd: Int): ByteArray {
        val body = byteArrayOf(0x82.toByte(), 0xF1.toByte(), 0xF1.toByte(), cmd.toByte(), cmd.toByte())
        return withChecksum(body)
    }

    val READ_IGNITION get() = infoTelegram(0xFE)
    val READ_FIRMWARE get() = infoTelegram(0xFD)
    val READ_SERIAL get() = infoTelegram(0xFB)
    val READ_VOLTAGE get() = infoTelegram(0xFC)

    /**
     * K-line data telegram (telType 0x02). The firmware performs the KWP1281
     * block ack/echo handshake itself when [kwp1281] is set.
     *
     * @param baud vehicle baud (e.g. 9600); encoded as baud/2 big-endian
     * @param payload the raw KWP protocol bytes to send to the ECU
     */
    fun kLineTelegram(
        baud: Int,
        payload: ByteArray,
        kwp1281: Boolean = true,
        interByteTime: Int = 0,
    ): ByteArray {
        // KWP1281 data telegram uses setDtr=true → USE_LLINE NOT set → flags1 = NO_ECHO.
        val flags1 = KLINEF1_NO_ECHO
        val flags2 = if (kwp1281) KLINEF2_KWP1281_DETECT else 0
        val half = if (baud == 115200) 0 else baud / 2
        val len = payload.size
        val header = byteArrayOf(
            0x00, 0x02,
            ((half shr 8) and 0xFF).toByte(), (half and 0xFF).toByte(),
            flags1.toByte(), flags2.toByte(),
            interByteTime.toByte(), KWP1281_TIMEOUT.toByte(),
            ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte(),
        )
        return withChecksum(header + payload)
    }

    /**
     * 5-baud slow-init pulse telegram for the engine ECU. `dataBits` frames the
     * KWP address as start bit + 8 data bits + stop bit; the firmware clocks it
     * out at [pulseWidthMs] per bit.
     */
    /** Pulse line-driver flags: both K+L (default, for K-line ECUs like the 2E),
     *  L-line only, or K-line-only (no explicit line bit, DTR-driven). */
    const val PULSE_FLAGS_BOTH = 0x10 or 0x20 or 0x08 or 0x80 // SEND_PULSE|NO_ECHO|USE_LLINE|USE_KLINE = 0xB8
    const val PULSE_FLAGS_L = 0x10 or 0x20 or 0x08            // 0x38
    const val PULSE_FLAGS_K = 0x10 or 0x20                    // 0x30

    fun pulseTelegram(
        address: Int,
        baud: Int = 9600,
        flags1: Int = PULSE_FLAGS_BOTH,
        pulseWidthMs: Int = 200,
        autoKeyByteDelayMs: Int = 10,
    ): ByteArray {
        // 10-bit slow-init frame: start bit (0) + 8 data bits (address, LSB first)
        // + stop bit (1). Packed LSB-first: (addr << 1) | 0x0200 = 2 bytes.
        val dataBits = ((address shl 1) or 0x0200) and 0x03FF
        val bitCount = 10
        // pulse-specific length counts pulseWidth + bitCount + dataBytes = 1+1+2 = 5.
        val payloadLen = 5
        val flags2 = KLINEF2_KWP1281_DETECT
        val half = if (baud == 115200) 0 else baud / 2
        val header = byteArrayOf(
            0x00, 0x02,
            ((half shr 8) and 0xFF).toByte(), (half and 0xFF).toByte(),
            flags1.toByte(), flags2.toByte(),
            0x00, KWP1281_TIMEOUT.toByte(),
            ((payloadLen shr 8) and 0xFF).toByte(), (payloadLen and 0xFF).toByte(),
        )
        val pulse = byteArrayOf(
            pulseWidthMs.toByte(),
            bitCount.toByte(),
            (dataBits and 0xFF).toByte(),
            ((dataBits shr 8) and 0xFF).toByte(),
            autoKeyByteDelayMs.toByte(),
        )
        return withChecksum(header + pulse)
    }

    /** Interpret the sync byte returned after init: 0x55 → 9600, 0x85-ish → 10400. */
    fun baudFromSyncByte(sync: Int): Int? = when {
        sync == 0x55 -> 9600
        (sync and 0x87) == 0x85 -> 10400
        else -> null
    }

    /** Escape-encode a payload for MTC head units that swallow 0x00 bytes. */
    fun escapeEncode(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size)
        for (b in data) {
            val v = b.toInt() and 0xFF
            if (v == 0x00 || v == ESCAPE_CODE) {
                out.add(ESCAPE_CODE.toByte())
                out.add((v xor ESCAPE_MASK).toByte())
            } else {
                out.add(b)
            }
        }
        return out.toByteArray()
    }

    /** Reverse [escapeEncode]. */
    fun escapeDecode(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size)
        var i = 0
        while (i < data.size) {
            val v = data[i].toInt() and 0xFF
            if (v == ESCAPE_CODE && i + 1 < data.size) {
                out.add(((data[i + 1].toInt() and 0xFF) xor ESCAPE_MASK).toByte())
                i += 2
            } else {
                out.add(data[i])
                i++
            }
        }
        return out.toByteArray()
    }
}
