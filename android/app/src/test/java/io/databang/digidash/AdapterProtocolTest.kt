package io.databang.digidash

import io.databang.digidash.core.deepobd.AdapterProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterProtocolTest {

    @Test
    fun `checksum is 8-bit additive sum`() {
        val bytes = byteArrayOf(0x82.toByte(), 0xF1.toByte(), 0xF1.toByte(), 0xFE.toByte(), 0xFE.toByte())
        // 0x82+0xF1+0xF1+0xFE+0xFE = 0x460 -> low byte 0x60
        assertEquals(0x60, AdapterProtocol.checksum(bytes))
        assertEquals(0x00, AdapterProtocol.checksum(byteArrayOf(0x00)))
    }

    @Test
    fun `read ignition telegram is BMW-FAST framed and self-consistent`() {
        val t = AdapterProtocol.READ_IGNITION
        val expected = byteArrayOf(
            0x82.toByte(), 0xF1.toByte(), 0xF1.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0x60.toByte(),
        )
        assertArrayEquals(expected, t)
        assertEquals(AdapterProtocol.checksum(t, 0, t.size - 1), t[t.size - 1].toInt() and 0xFF)
    }

    @Test
    fun `k-line telegram encodes baud and length and checksum`() {
        val payload = byteArrayOf(0x01)
        val t = AdapterProtocol.kLineTelegram(baud = 9600, payload = payload)
        // header: 00 02, baud/2=4800=0x12C0, flags1, flags2, ib, timeout, len hi/lo, payload, cks
        assertEquals(0x00, t[0].toInt() and 0xFF)
        assertEquals(0x02, t[1].toInt() and 0xFF)
        assertEquals(0x12, t[2].toInt() and 0xFF)
        assertEquals(0xC0, t[3].toInt() and 0xFF)
        // last byte is the checksum of everything before it
        assertEquals(
            AdapterProtocol.checksum(t, 0, t.size - 1),
            t[t.size - 1].toInt() and 0xFF,
        )
        // KWP1281 detect flag set by default
        assertTrue((t[5].toInt() and AdapterProtocol.KLINEF2_KWP1281_DETECT) != 0)
    }

    @Test
    fun `pulse telegram matches EdiabasLib format for addr 1 at 9600`() {
        val t = AdapterProtocol.pulseTelegram(address = 0x01, baud = 9600)
        // 00 02 12 C0 38 01 00 3C 00 05 C8 0A 02 02 0A 2E
        val expected = intArrayOf(
            0x00, 0x02, 0x12, 0xC0, 0x38, 0x01, 0x00, 0x3C,
            0x00, 0x05, 0xC8, 0x0A, 0x02, 0x02, 0x0A, 0x2E,
        ).map { it.toByte() }.toByteArray()
        assertArrayEquals(expected, t)
        // flags1 = SEND_PULSE | NO_ECHO | USE_LLINE
        assertTrue((t[4].toInt() and AdapterProtocol.KLINEF1_SEND_PULSE) != 0)
        assertTrue((t[4].toInt() and AdapterProtocol.KLINEF1_USE_LLINE) != 0)
    }

    @Test
    fun `sync byte maps to baud`() {
        assertEquals(9600, AdapterProtocol.baudFromSyncByte(0x55))
        assertEquals(10400, AdapterProtocol.baudFromSyncByte(0x85))
        assertNull(AdapterProtocol.baudFromSyncByte(0x00))
    }

    @Test
    fun `escape encode and decode round-trip`() {
        val data = byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0x42, 0x00)
        val encoded = AdapterProtocol.escapeEncode(data)
        // 0x00 and 0xFF must not appear raw except as escape markers
        assertArrayEquals(data, AdapterProtocol.escapeDecode(encoded))
    }
}
