package io.databang.digidash

import io.databang.digidash.core.deepobd.Kwp1281Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Kwp1281ProtocolTest {

    @Test
    fun `group 000 decodes 10 raw bytes`() {
        val data = ByteArray(10) { (it + 20).toByte() }
        val fields = Kwp1281Protocol.decodeGroup(0, data)
        assertEquals(10, fields.size)
        assertEquals("20", fields[0].raw)
        assertEquals(1, fields[0].index)
        assertEquals("29", fields[9].raw)
    }

    @Test
    fun `rpm type decodes as 0_2 a b`() {
        // type 0x01 (rpm) with a=100, b=46 -> 0.2*100*46 = 920 rpm
        val data = byteArrayOf(0x01, 100.toByte(), 46.toByte())
        val fields = Kwp1281Protocol.decodeGroup(1, data)
        assertEquals(1, fields.size)
        assertEquals("920", fields[0].raw)
    }

    @Test
    fun `temperature type decodes to celsius`() {
        // type 0x05, a=10, b=188 -> 10*(188-100)*0.1 = 88.0 °C
        val data = byteArrayOf(0x05, 10.toByte(), 188.toByte())
        val fields = Kwp1281Protocol.decodeGroup(1, data)
        assertEquals("88", fields[0].raw)
    }

    @Test
    fun `unknown type shows raw a slash b`() {
        val data = byteArrayOf(0x7F, 5.toByte(), 9.toByte())
        val fields = Kwp1281Protocol.decodeGroup(1, data)
        assertEquals("5/9", fields[0].raw)
    }

    @Test
    fun `dtc response decodes codes and skips terminators`() {
        // 00515 = 0x0203, status 0x27; then 0xFFFF terminator
        val data = byteArrayOf(0x02, 0x03, 0x27, 0xFF.toByte(), 0xFF.toByte(), 0x00)
        val dtcs = Kwp1281Protocol.decodeDtcResponse(data)
        assertEquals(1, dtcs.size)
        assertEquals("00515", dtcs[0].code)
        assertTrue(dtcs[0].statusRaw!!.isNotEmpty())
    }
}
