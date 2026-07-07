package io.databang.digidash

import io.databang.digidash.core.logging.GenericCsvLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericCsvLogTest {

    @Test
    fun `parses a simple timestamped csv with header`() {
        val csv = """
            time,rpm,coolant
            0,900,80
            1,2500,85
            2,900,88
        """.trimIndent()
        val data = GenericCsvLog.parse(csv)
        assertTrue("rpm" in data.keys)
        assertTrue("coolant" in data.keys)
        // time column used as X, not a series
        assertTrue("time" !in data.keys)
        assertEquals(3, data.series["rpm"]!!.size)
        assertEquals(2500.0, data.series["rpm"]!![1].second, 0.0)
    }

    @Test
    fun `handles semicolon delimiter and marker columns`() {
        // VCDS-Lite-ish: a marker/label column plus a stamp and values.
        val csv = """
            Marker;STAMP;Value1;Value2
            ;0;120;136
            ;1;121;138
            ;2;120;140
        """.trimIndent()
        val data = GenericCsvLog.parse(csv)
        assertTrue(data.keys.isNotEmpty())
        assertTrue(data.series.values.all { it.size == 3 })
    }

    @Test
    fun `falls back to row index when no time column`() {
        val csv = "a,b\n5,10\n5,20\n5,30"
        val data = GenericCsvLog.parse(csv)
        // 'a' is constant (not increasing) so row index is X; both a,b are series
        assertTrue(data.series.isNotEmpty())
    }

    @Test
    fun `empty or junk input yields empty data`() {
        assertTrue(GenericCsvLog.parse("").isEmpty)
        assertTrue(GenericCsvLog.parse("no numbers here\njust text").isEmpty)
    }
}
