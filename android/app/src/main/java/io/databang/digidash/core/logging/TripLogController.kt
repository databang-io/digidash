package io.databang.digidash.core.logging

import io.databang.digidash.core.diagnostics.ConnectionState
import io.databang.digidash.data.repository.DiagnosticSessionRepository
import io.databang.digidash.data.repository.SessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Drives a [CsvLogger] from the diagnostic session: while recording, every
 * measurement snapshot and every session event is appended. Recording is
 * independent of the polling loop, so start/stop is a pure UI action.
 */
class TripLogController(
    private val session: DiagnosticSessionRepository,
    private val logRepository: LogRepository,
    private val scope: CoroutineScope,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _currentFile = MutableStateFlow<String?>(null)
    val currentFile: StateFlow<String?> = _currentFile.asStateFlow()

    private var logger: CsvLogger? = null
    private var file: File? = null
    private var sampleJob: Job? = null
    private var eventJob: Job? = null

    fun start() {
        if (_recording.value) return
        val out = logRepository.newLogFile(compactTimestamp())
        val identity = session.identity.value
        val model = session.model.value
        val csv = CsvLogger(
            writer = logRepository.openWriter(out),
            ecuPartNumber = identity?.partNumberNormalized ?: "unknown",
            ecuModelFile = model?.displayName ?: "none",
            clockMillis = clockMillis,
            nowIso = ::isoNow,
        )
        logger = csv
        file = out
        _currentFile.value = out.name
        _recording.value = true
        csv.logEvent(session.connectionState.value.name, "log started")

        // Log only fields that actually changed since the last poll — the
        // measurements map re-emits in full on every single-group update, so
        // diffing avoids ~6× duplicated rows.
        val lastLogged = HashMap<String, String>()
        sampleJob = scope.launch {
            session.measurements.collect { map ->
                if (!_recording.value) return@collect
                val state = session.connectionState.value.name
                val changed = map.values.filter { m ->
                    val stamp = "${m.rawString}@${m.timestampMillis}"
                    (lastLogged.put(m.key, stamp) != stamp)
                }
                if (changed.isNotEmpty()) {
                    logger?.logMeasurements(state, changed)
                    logger?.flush()
                }
            }
        }
        // Mirror session events.
        eventJob = scope.launch {
            session.events.collect { event: SessionEvent ->
                if (!_recording.value) return@collect
                logger?.logEvent(session.connectionState.value.name, event.describe())
                logger?.flush()
            }
        }
    }

    fun stop() {
        if (!_recording.value) return
        _recording.value = false
        sampleJob?.cancel(); sampleJob = null
        eventJob?.cancel(); eventJob = null
        logger?.logEvent(session.connectionState.value.name, "log stopped")
        logger?.close()
        logger = null
        file = null
        _currentFile.value = null
    }

    fun addNote(note: String) {
        session.logUserNote(note)
    }

    private fun compactTimestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date(clockMillis()))

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date(clockMillis()))
}
