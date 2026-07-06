package io.databang.digidash

import io.databang.digidash.core.logging.CsvLogger
import io.databang.digidash.domain.model.InterpretedMeasurement
import io.databang.digidash.domain.model.MeasurementStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class CsvLoggerTest {

    private fun measurement(key: String, raw: String, value: Double?, display: String) =
        InterpretedMeasurement(
            key = key, name = key.uppercase(), group = 1, fieldIndex = 1,
            rawString = raw, value = value, displayValue = display, unit = "rpm",
            status = MeasurementStatus.NORMAL, confidence = "low", timestampMillis = 0L,
        )

    private fun logger(sw: StringWriter): CsvLogger {
        var t = 1000L
        return CsvLogger(
            writer = sw,
            ecuPartNumber = "037906024AG",
            ecuModelFile = "VW Digifant 2E",
            clockMillis = { t.also { t += 500 } },
            nowIso = { "2026-07-06T12:00:00+02:00" },
        )
    }

    @Test
    fun `header is written first`() {
        val sw = StringWriter()
        logger(sw)
        assertTrue(sw.toString().startsWith(CsvLogger.HEADER))
    }

    @Test
    fun `sample row keeps raw and interpreted values`() {
        val sw = StringWriter()
        val log = logger(sw)
        log.logMeasurement("CONNECTED", measurement("rpm", "920", 920.0, "920"))
        val lines = sw.toString().trim().lines()
        assertEquals(2, lines.size)
        val row = lines[1]
        assertTrue(row.contains(",sample,"))
        assertTrue(row.contains("920"))
        assertTrue(row.contains("rpm"))
    }

    @Test
    fun `event row is tagged`() {
        val sw = StringWriter()
        val log = logger(sw)
        log.logEvent("CONNECTED", "DTC cleared: 00515")
        val row = sw.toString().trim().lines()[1]
        assertTrue(row.contains(",event,"))
        assertTrue(row.contains("DTC cleared"))
    }

    @Test
    fun `values with commas are quoted`() {
        val sw = StringWriter()
        val log = logger(sw)
        log.logEvent("CONNECTED", "note, with comma")
        assertTrue(sw.toString().contains("\"note, with comma\""))
    }

    @Test
    fun `file name follows digifant convention`() {
        assertEquals("digifant_20260706_120000.csv", CsvLogger.fileName("20260706_120000"))
    }
}
