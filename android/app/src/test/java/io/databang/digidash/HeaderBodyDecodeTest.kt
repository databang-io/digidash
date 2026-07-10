package io.databang.digidash

import io.databang.digidash.core.deepobd.Kwp1281Protocol
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Header+body measuring decode (KLineKWP1281Lib format) validated against the
 * REAL group headers captured live from the 037906024AG on 2026-07-08 and the
 * values VCDS-Lite displayed on the same car.
 */
class HeaderBodyDecodeTest {

    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    // Captured live: reply to 0x29 01 (46 bytes, title 0x02).
    private val group1Header = hex(
        "8B 1A 11 FA E1 E0 BC AD 96 84 75 61 53 48 39 28 1F 19 12 00 " +
        "8C 28 11 A0 64 50 44 3A 32 2C 26 21 1B 16 10 0B 04 00 00 00 " +
        "85 02 00 88 FF 00")

    // Captured live: reply to 0x29 02.
    private val group2Header = hex(
        "8B 1A 11 FA E1 E0 BC AD 96 84 75 61 53 48 39 28 1F 19 12 00 " +
        "89 32 00 85 18 00 " +
        "8C 28 11 A0 64 50 44 3A 32 2C 26 21 1B 16 10 0B 04 00 00 00")

    @Test
    fun `group 1 header parses to rpm, temperature, voltage, bits`() {
        val recs = Kwp1281Protocol.parseGroupHeader(group1Header)
        assertEquals(listOf(0x8B, 0x8C, 0x85, 0x88), recs.map { it.formula })
        assertEquals(listOf(0x1A, 0x28, 0x02, 0xFF), recs.map { it.nwb })
        assertEquals(17, recs[0].table.size)
        assertEquals(17, recs[1].table.size)
    }

    @Test
    fun `group 2 header parses to rpm, injection, voltage, temperature`() {
        val recs = Kwp1281Protocol.parseGroupHeader(group2Header)
        assertEquals(listOf(0x8B, 0x89, 0x85, 0x8C), recs.map { it.formula })
        assertEquals(listOf(0x1A, 0x32, 0x18, 0x28), recs.map { it.nwb })
    }

    @Test
    fun `voltage formula 0x85 reproduces the VCDS battery reading`() {
        // Group 2 zone 3: NWb=0x18=24 -> V = MWb*24/256; VCDS showed 12.38 V.
        assertEquals(12.375, Kwp1281Protocol.highFormulaValue(0x85, 24, 132)!!, 0.01)
    }

    @Test
    fun `voltage formula 0x85 with NWb=2 is the divide-by-128 lambda scale`() {
        // Group 1 zone 3: NWb=2 -> V = MWb/128; VCDS showed 1.22 V disconnected
        // (label 037906023: open circuit = 164-168 counts -> 1.28-1.31 V).
        assertEquals(1.22, Kwp1281Protocol.highFormulaValue(0x85, 2, 156)!!, 0.01)
    }

    @Test
    fun `injection formula 0x89 reproduces the VCDS reading`() {
        // Group 2 zone 2: NWb=0x32=50 -> ms = MWb*0.5; VCDS showed 5.00 ms.
        assertEquals(5.0, Kwp1281Protocol.highFormulaValue(0x89, 50, 10)!!, 0.001)
    }

    @Test
    fun `rpm formula 0x8B interpolates the header table times NWb`() {
        val recs = Kwp1281Protocol.parseGroupHeader(group1Header)
        // Engine off: high MWb lands at the zero end of the descending table.
        val off = Kwp1281Protocol.headerBodyValue(recs[0], 255)!!
        // Low MWb lands at the top of the table (0xFA=250 * 26 = 6500 rpm cap).
        val max = Kwp1281Protocol.headerBodyValue(recs[0], 0)!!
        assertEquals(6500.0, max, 1.0)
        assert(off < 100.0) { "engine-off rpm should be near zero, was $off" }
    }

    @Test
    fun `temperature formula 0x8C subtracts NWb after interpolation`() {
        val recs = Kwp1281Protocol.parseGroupHeader(group1Header)
        // MWb=0 -> table[0]=0xA0=160 minus NWb(40) = 120 °C (hot end).
        assertEquals(120.0, Kwp1281Protocol.headerBodyValue(recs[1], 0)!!, 0.5)
    }

    @Test
    fun `switch bits render as masked binary`() {
        val fields = Kwp1281Protocol.decodeHeaderBody(group1Header, byteArrayOf(0, 0, 0, 0x02))
        assertEquals("00000010", fields[3].raw)
    }

    @Test
    fun `full decode yields four zones from a four-byte body`() {
        val fields = Kwp1281Protocol.decodeHeaderBody(group2Header, hex("F0 0A 84 10"))
        assertEquals(4, fields.size)
        assertEquals("5", fields[1].raw)      // 10*0.5 ms
        assertEquals("12.38", fields[2].raw)  // 132*24/256 V
    }
}
