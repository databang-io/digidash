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

    // Group/DTC block decoding is covered by Kwp1281ProtocolTest; the persistent
    // session loop is validated live on the vehicle (needs a real block exchange).

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
