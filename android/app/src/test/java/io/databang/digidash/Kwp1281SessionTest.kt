package io.databang.digidash

import io.databang.digidash.core.deepobd.AdapterProtocol
import io.databang.digidash.core.deepobd.Kwp1281Protocol
import io.databang.digidash.core.deepobd.Kwp1281Session
import io.databang.digidash.core.deepobd.LoggingSppTransport
import io.databang.digidash.core.deepobd.SppTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records writes and replays scripted reads. */
private class ScriptTransport(private val reads: ArrayDeque<ByteArray>) : SppTransport {
    val writes = mutableListOf<ByteArray>()
    override var isConnected = false
    override fun connect() { isConnected = true }
    override fun write(data: ByteArray) { writes.add(data) }
    override fun read(max: Int, timeoutMillis: Long): ByteArray =
        if (reads.isEmpty()) ByteArray(0) else reads.removeFirst()
    override fun close() { isConnected = false }
}

class Kwp1281SessionTest {

    /** The adapter returns raw K-line bytes (no data/status pairing). */
    private fun raw(vararg data: Int): ByteArray = data.map { it.toByte() }.toByteArray()

    @Test
    fun `group read sends a group-request block and decodes the response`() {
        // Response block: [len][counter][title E7][type a b][03]
        val resp = raw(0x06, 0x02, Kwp1281Protocol.TITLE_GROUP_RESPONSE, 0x01, 100, 46, 0x03)
        val transport = ScriptTransport(ArrayDeque(listOf(resp)))
        val session = Kwp1281Session(transport, blockTimeoutMs = 10)

        val block = session.readGroup(1).getOrThrow()
        assertEquals(1, block.group)
        // type 1 rpm = 0.2*100*46 = 920
        assertEquals("920", block.fields[0].raw)
        // A request block was written wrapped in a K-line telegram.
        assertTrue(transport.writes.isNotEmpty())
    }

    @Test
    fun `dtc read decodes fault codes from response block`() {
        // [len][counter][FC][hi lo status ...][03]; 00515 = 0x0203
        val resp = raw(0x06, 0x02, Kwp1281Protocol.TITLE_DTC_RESPONSE, 0x02, 0x03, 0x24, 0x03)
        val ack = raw(0x03, 0x03, Kwp1281Protocol.TITLE_ACK, 0x03)
        val transport = ScriptTransport(ArrayDeque(listOf(resp, ack)))
        val session = Kwp1281Session(transport, blockTimeoutMs = 10)

        val dtcs = session.readDtc().getOrThrow()
        assertEquals("00515", dtcs.first().code)
    }

    @Test
    fun `logging transport tees tx and rx as hex`() {
        val lines = StringBuilder()
        val inner = ScriptTransport(ArrayDeque(listOf(byteArrayOf(0x55, 0x03))))
        val logged = LoggingSppTransport(inner, { lines.appendLine(it) }, clockMillis = { 0L })
        logged.connect()
        logged.write(byteArrayOf(0x82.toByte(), 0xF1.toByte()))
        logged.read(16, 10)
        val text = lines.toString()
        assertTrue(text.contains("TX 2: 82 F1"))
        assertTrue(text.contains("RX 2: 55 03"))
    }

    @Test
    fun `hex formats bytes uppercase space separated`() {
        assertEquals("00 0A FF", LoggingSppTransport.hex(byteArrayOf(0, 10, 0xFF.toByte())))
    }
}
