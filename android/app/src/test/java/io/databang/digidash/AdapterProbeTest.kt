package io.databang.digidash

import io.databang.digidash.core.deepobd.AdapterProbe
import io.databang.digidash.core.deepobd.AdapterProtocol
import io.databang.digidash.core.deepobd.AdapterType
import io.databang.digidash.core.deepobd.SppTransport
import org.junit.Assert.assertEquals
import org.junit.Test

/** In-memory transport that returns scripted responses per write. */
private class ScriptedTransport(
    private val responder: (ByteArray) -> ByteArray,
) : SppTransport {
    override var isConnected = false
    private var pending = ByteArray(0)
    override fun connect() { isConnected = true }
    override fun write(data: ByteArray) { pending = responder(data) }
    override fun read(max: Int, timeoutMillis: Long): ByteArray {
        val out = pending.copyOf(minOf(max, pending.size))
        pending = pending.copyOfRange(out.size, pending.size)
        return out
    }
    override fun close() { isConnected = false }
}

class AdapterProbeTest {

    @Test
    fun `custom adapter answers ignition telegram`() {
        val transport = ScriptedTransport { req ->
            when {
                req.contentEquals(AdapterProtocol.READ_IGNITION) -> {
                    // echo request + a checksummed reply frame
                    val reply = AdapterProtocol.withChecksum(byteArrayOf(0x83.toByte(), 0xF1.toByte(), 0xF1.toByte(), 0x00, 0x00))
                    req + reply
                }
                req.contentEquals(AdapterProtocol.READ_FIRMWARE) ->
                    req + AdapterProtocol.withChecksum(byteArrayOf(0x85.toByte(), 0xF1.toByte(), 0xF1.toByte(), 0x00, 0x00, 0x01, 0x08))
                req.contentEquals(AdapterProtocol.READ_VOLTAGE) ->
                    req + AdapterProtocol.withChecksum(byteArrayOf(0x83.toByte(), 0xF1.toByte(), 0xF1.toByte(), 138.toByte()))
                else -> ByteArray(0)
            }
        }
        val info = AdapterProbe(transport, stepTimeoutMs = 50).probe()
        assertEquals(AdapterType.CUSTOM, info.type)
    }

    @Test
    fun `stock elm327 identified via ATI without DEEPOBD`() {
        val transport = ScriptedTransport { req ->
            val s = String(req, Charsets.US_ASCII)
            when {
                s.startsWith("ATI") -> "ELM327 v1.5\r>".toByteArray()
                s.startsWith("AT@2") -> "?\r>".toByteArray()
                else -> ByteArray(0)
            }
        }
        val info = AdapterProbe(transport, stepTimeoutMs = 50).probe()
        assertEquals(AdapterType.ELM327_STOCK, info.type)
    }

    @Test
    fun `deep obd firmware identified via AT@2`() {
        val transport = ScriptedTransport { req ->
            val s = String(req, Charsets.US_ASCII)
            when {
                s.startsWith("ATI") -> "ELM327 v2.1\r>".toByteArray()
                s.startsWith("AT@2") -> "DEEPOBD 1.0\r>".toByteArray()
                else -> ByteArray(0)
            }
        }
        val info = AdapterProbe(transport, stepTimeoutMs = 50).probe()
        assertEquals(AdapterType.ELM_DEEPOBD, info.type)
    }
}
