package io.databang.digidash.core.logging

import io.databang.digidash.domain.model.InterpretedMeasurement
import java.io.Writer

/**
 * Writes trip/diagnostic logs as CSV. Every sample row keeps the raw value
 * alongside the interpreted value (mapping is uncertain, so raw must survive).
 * Event rows share the same file with `row_type=event`.
 *
 * The logger is transport-agnostic: it takes a [Writer] so it can target a
 * file, a share buffer, or a test StringWriter.
 */
class CsvLogger(
    private val writer: Writer,
    private val ecuPartNumber: String,
    private val ecuModelFile: String,
    private val clockMillis: () -> Long,
    private val nowIso: () -> String,
) {
    private val startMillis = clockMillis()
    private var closed = false

    init {
        writer.write(HEADER)
        writer.write("\n")
    }

    /** One row per interpreted field. */
    fun logMeasurement(
        connectionState: String,
        m: InterpretedMeasurement,
    ) {
        writeRow(
            rowType = "sample",
            connectionState = connectionState,
            group = m.group.toString(),
            fieldIndex = m.fieldIndex.toString(),
            rawString = m.rawString,
            rawNumeric = m.value?.toString(),
            interpretedKey = m.key,
            interpretedName = m.name,
            interpretedValue = m.displayValue,
            unit = m.unit,
            status = m.status.name.lowercase(),
            confidence = m.confidence,
            event = null,
        )
    }

    fun logMeasurements(connectionState: String, ms: Collection<InterpretedMeasurement>) {
        ms.forEach { logMeasurement(connectionState, it) }
    }

    /** Event rows: connection/identify/DTC/basic-settings/user notes. */
    fun logEvent(connectionState: String, event: String) {
        writeRow(
            rowType = "event",
            connectionState = connectionState,
            group = null, fieldIndex = null, rawString = null, rawNumeric = null,
            interpretedKey = null, interpretedName = null, interpretedValue = null,
            unit = null, status = null, confidence = null,
            event = event,
        )
    }

    fun flush() = writer.flush()

    fun close() {
        if (closed) return
        closed = true
        writer.flush()
        writer.close()
    }

    private fun writeRow(
        rowType: String,
        connectionState: String,
        group: String?,
        fieldIndex: String?,
        rawString: String?,
        rawNumeric: String?,
        interpretedKey: String?,
        interpretedName: String?,
        interpretedValue: String?,
        unit: String?,
        status: String?,
        confidence: String?,
        event: String?,
    ) {
        val cols = listOf(
            nowIso(),
            (clockMillis() - startMillis).toString(),
            rowType,
            ecuPartNumber,
            ecuModelFile,
            connectionState,
            group.orEmpty(),
            fieldIndex.orEmpty(),
            rawString.orEmpty(),
            rawNumeric.orEmpty(),
            interpretedKey.orEmpty(),
            interpretedName.orEmpty(),
            interpretedValue.orEmpty(),
            unit.orEmpty(),
            status.orEmpty(),
            confidence.orEmpty(),
            event.orEmpty(),
        )
        writer.write(cols.joinToString(",") { escape(it) })
        writer.write("\n")
    }

    private fun escape(value: String): String {
        if (value.isEmpty()) return ""
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuote) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    companion object {
        const val HEADER =
            "timestamp_iso,monotonic_ms,row_type,ecu_part_number,ecu_model_file," +
                "connection_state,group,field_index,raw_string,raw_numeric," +
                "interpreted_key,interpreted_name,interpreted_value,unit,status," +
                "confidence,event"

        /** digifant_YYYYMMDD_HHMMSS.csv */
        fun fileName(compactTimestamp: String): String = "digifant_$compactTimestamp.csv"
    }
}
