package io.databang.digidash

import io.databang.digidash.core.deepobd.Kwp1281Protocol
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the header/body measuring decode against ONLY real data + reference
 * formulas — no fabricated bodies. Two things are checked:
 *  1. Parsing of the REAL 0x02 group headers captured live from 037906024AG on
 *     2026-07-08 (samples/deepobd/captured-groups-037906024AG.md).
 *  2. The high-formula arithmetic matches the reference table (KLineKWP1281Lib /
 *     gmenounos), as pure function math — NOT a claim of a vehicle reading.
 *
 * No real BODY (0xF4 <=4 bytes) has been captured for the typed groups, so the
 * full header->body pipeline is intentionally NOT asserted here (it would need
 * an invented body). That validation waits for a live capture.
 */
class HeaderBodyDecodeTest {

    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    // REAL: reply to 0x29 01 (group 1), 46-byte title-0x02 header.
    private val group1Header = hex(
        "8B 1A 11 FA E1 E0 BC AD 96 84 75 61 53 48 39 28 1F 19 12 00 " +
        "8C 28 11 A0 64 50 44 3A 32 2C 26 21 1B 16 10 0B 04 00 00 00 " +
        "85 02 00 88 FF 00")

    // REAL: reply to 0x29 02 (group 2).
    private val group2Header = hex(
        "8B 1A 11 FA E1 E0 BC AD 96 84 75 61 53 48 39 28 1F 19 12 00 " +
        "89 32 00 85 18 00 " +
        "8C 28 11 A0 64 50 44 3A 32 2C 26 21 1B 16 10 0B 04 00 00 00")

    @Test
    fun `real group 1 header parses to its four zone formulas`() {
        val recs = Kwp1281Protocol.parseGroupHeader(group1Header)
        assertEquals(listOf(0x8B, 0x8C, 0x85, 0x88), recs.map { it.formula })
        assertEquals(listOf(0x1A, 0x28, 0x02, 0xFF), recs.map { it.nwb })
        assertEquals(17, recs[0].table.size)
        assertEquals(17, recs[1].table.size)
    }

    @Test
    fun `real group 2 header parses to its four zone formulas`() {
        val recs = Kwp1281Protocol.parseGroupHeader(group2Header)
        assertEquals(listOf(0x8B, 0x89, 0x85, 0x8C), recs.map { it.formula })
        assertEquals(listOf(0x1A, 0x32, 0x18, 0x28), recs.map { it.nwb })
    }

    // --- Reference-formula arithmetic (function math, not vehicle data) ---

    @Test
    fun `formula 0x85 voltage is a times b over 256`() {
        // KLineKWP1281Lib case 0x85: MW * NW / 256.
        assertEquals(12.0, Kwp1281Protocol.highFormulaValue(0x85, 24, 128)!!, 0.001)
    }

    @Test
    fun `formula 0x89 injection is a times b times 0_01`() {
        // KLineKWP1281Lib case 0x89: MW * NW * 0.01 ms.
        assertEquals(5.0, Kwp1281Protocol.highFormulaValue(0x89, 50, 10)!!, 0.001)
    }

    @Test
    fun `formula 0x83 ignition angle is a times b times 0_5 minus 30`() {
        // KLineKWP1281Lib case 0x83: (MW * NW * 0.5) - 30 deg.
        assertEquals(20.0, Kwp1281Protocol.highFormulaValue(0x83, 4, 25)!!, 0.001)
    }

    @Test
    fun `table formula 0x8B multiplies the interpolated map by NWb`() {
        val recs = Kwp1281Protocol.parseGroupHeader(group1Header)
        // MWb=0 lands on table[0]=0xFA=250; 250 * NWb(0x1A=26) = 6500.
        assertEquals(6500.0, Kwp1281Protocol.headerBodyValue(recs[0], 0)!!, 1.0)
    }

    @Test
    fun `table formula 0x8C subtracts NWb after interpolation`() {
        val recs = Kwp1281Protocol.parseGroupHeader(group1Header)
        // MWb=0 -> table[0]=0xA0=160 minus NWb(0x28=40) = 120.
        assertEquals(120.0, Kwp1281Protocol.headerBodyValue(recs[1], 0)!!, 0.5)
    }
}
