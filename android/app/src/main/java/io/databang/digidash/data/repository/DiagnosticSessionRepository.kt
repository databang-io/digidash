package io.databang.digidash.data.repository

import io.databang.digidash.core.diagnostics.ConnectionConfig
import io.databang.digidash.core.diagnostics.ConnectionState
import io.databang.digidash.core.diagnostics.DiagnosticClient
import io.databang.digidash.core.diagnostics.DiagnosticError
import io.databang.digidash.core.diagnostics.asDiagnosticError
import io.databang.digidash.core.ecumodel.EcuModel
import io.databang.digidash.core.ecumodel.EcuModelRepository
import io.databang.digidash.core.interpret.DtcInterpreter
import io.databang.digidash.core.interpret.MeasurementInterpreter
import io.databang.digidash.domain.model.EcuIdentity
import io.databang.digidash.domain.model.InterpretedDtc
import io.databang.digidash.domain.model.InterpretedMeasurement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns one diagnostic session: connect, identify, resolve the ECU Model,
 * poll trip groups (one group per cycle — old KWP1281 ECUs are slow) and
 * publish interpreted measurements.
 *
 * A [sessionMutex] serializes ECU access so a one-off group read or a DTC
 * clear cannot interleave with the polling loop on the slow K-line.
 */
class DiagnosticSessionRepository(
    private val clientProvider: () -> DiagnosticClient,
    private val modelRepositoryProvider: () -> EcuModelRepository,
    private val interpreter: MeasurementInterpreter,
    private val scope: CoroutineScope,
    private val pollIntervalMillis: Long = 800,
) {
    private val _identity = MutableStateFlow<EcuIdentity?>(null)
    val identity: StateFlow<EcuIdentity?> = _identity.asStateFlow()

    private val _model = MutableStateFlow<EcuModel?>(null)
    val model: StateFlow<EcuModel?> = _model.asStateFlow()

    /** Latest interpreted measurement per ECU-model key. */
    private val _measurements = MutableStateFlow<Map<String, InterpretedMeasurement>>(emptyMap())
    val measurements: StateFlow<Map<String, InterpretedMeasurement>> = _measurements.asStateFlow()

    private val _dtcs = MutableStateFlow<List<InterpretedDtc>>(emptyList())
    val dtcs: StateFlow<List<InterpretedDtc>> = _dtcs.asStateFlow()

    private val _dtcCount = MutableStateFlow<Int?>(null)
    val dtcCount: StateFlow<Int?> = _dtcCount.asStateFlow()

    private val _basicSettingsActive = MutableStateFlow(false)
    val basicSettingsActive: StateFlow<Boolean> = _basicSettingsActive.asStateFlow()

    /** Group polled while in Basic Settings (manual documents groups 01-04;
     *  group 1 carries the condition bits + rpm/coolant/lambda). Used only by
     *  the legacy fake-backend polling path. */
    private val basicSettingsGroup = 1

    private val _lastError = MutableStateFlow<DiagnosticError?>(null)
    val lastError: StateFlow<DiagnosticError?> = _lastError.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Session events for the CSV logger (identify, DTC read/cleared, ...). */
    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private var pollJob: Job? = null
    private var stateJob: Job? = null
    private var reconnectJob: Job? = null
    private val sessionMutex = Mutex()

    /** The client resolved for the current/last session. */
    private var client: DiagnosticClient = clientProvider()

    // --- Auto-reconnect state ---
    /** Config of the current session, replayed on reconnect (same dongle + ECU). */
    private var lastConfig: ConnectionConfig? = null
    /** True while the user wants to stay connected (cleared on explicit disconnect). */
    @Volatile private var autoReconnect = false
    /** True while a reconnect sweep is in progress. */
    @Volatile private var reconnecting = false
    /** Reconnect timestamps for the flapping circuit-breaker. */
    private val recentDrops = ArrayDeque<Long>()
    /** Groups the ECU refused — skipped in polling so we don't keep killing the
     *  session (a failed measuring-group request re-inits the KW1281 link). */
    private val deadGroups = mutableSetOf<Int>()
    private val groupFailures = mutableMapOf<Int, Int>()

    suspend fun connect(config: ConnectionConfig): Boolean {
        lastConfig = config
        // Only auto-reconnect real dongle sessions, never the fake backend.
        autoReconnect = !config.useFakeBackend
        reconnecting = false
        reconnectJob?.cancel()
        reconnectJob = null
        // Fresh user-initiated connect: give every group another chance.
        deadGroups.clear()
        groupFailures.clear()
        recentDrops.clear()
        return establish(config)
    }

    /** Connect + identify + resolve model + start polling. Reused on reconnect. */
    private suspend fun establish(config: ConnectionConfig): Boolean {
        _lastError.value = null
        // Resolve the active backend (fake/real) at connect time and follow its
        // connection state; a drop while [autoReconnect] holds starts a sweep.
        client = clientProvider()
        stateJob?.cancel()
        stateJob = scope.launch {
            client.connectionState().collect { st ->
                _connectionState.value =
                    if (reconnecting && st != ConnectionState.CONNECTED) ConnectionState.RECONNECTING else st
                if (st == ConnectionState.ERROR && autoReconnect && !reconnecting) {
                    reconnecting = true
                    _connectionState.value = ConnectionState.RECONNECTING
                    emit(SessionEvent.ConnectionDropped)
                    reconnectJob = scope.launch { superviseReconnect() }
                }
            }
        }
        val connected = client.connect(config)
        if (connected.isFailure) {
            _lastError.value = connected.exceptionOrNull()?.asDiagnosticError()
            return false
        }
        val identity = client.identifyEcu().getOrElse {
            _lastError.value = it.asDiagnosticError()
            return false
        }
        _identity.value = identity
        emit(SessionEvent.EcuIdentified(identity.partNumberRaw))

        val model = runCatching {
            modelRepositoryProvider().findByPartNumber(identity.partNumberNormalized)
        }.getOrNull()
        _model.value = model
        if (model != null) emit(SessionEvent.ModelLoaded(model.displayName))

        refreshDtcs()
        startPolling(model)
        return true
    }

    /** Retry [establish] with exponential backoff while the user stays connected. */
    private suspend fun superviseReconnect() {
        // Flapping circuit-breaker: if drops repeat too fast (e.g. an unsupported
        // group read that keeps re-initialising the link), pause auto-reconnect
        // rather than storm the ECU.
        val now = System.currentTimeMillis()
        recentDrops.addLast(now)
        while (recentDrops.isNotEmpty() && now - recentDrops.first() > 30_000L) recentDrops.removeFirst()
        if (recentDrops.size > 4) {
            autoReconnect = false
            reconnecting = false
            _connectionState.value = ConnectionState.ERROR
            _lastError.value = DiagnosticError.ProtocolError(
                "Repeated connection drops — auto-reconnect paused. Reconnect manually.")
            return
        }
        var delayMs = 1000L
        var attempt = 0
        while (autoReconnect) {
            attempt++
            _connectionState.value = ConnectionState.RECONNECTING
            emit(SessionEvent.Reconnecting(attempt))
            delay(delayMs)
            if (!autoReconnect) break
            val cfg = lastConfig ?: break
            val ok = runCatching { establish(cfg) }.getOrDefault(false)
            if (ok) {
                reconnecting = false
                _connectionState.value = ConnectionState.CONNECTED
                emit(SessionEvent.Reconnected(attempt))
                return
            }
            delayMs = (delayMs * 2).coerceAtMost(10_000L)
        }
        reconnecting = false
    }

    suspend fun disconnect() {
        // User-initiated: stop auto-reconnect and tear everything down cleanly.
        autoReconnect = false
        reconnecting = false
        reconnectJob?.cancel()
        reconnectJob = null
        pollJob?.cancel()
        pollJob = null
        stateJob?.cancel()
        stateJob = null
        client.disconnect()
        _identity.value = null
        _measurements.value = emptyMap()
        _dtcs.value = emptyList()
        _dtcCount.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
        emit(SessionEvent.ConnectionLost)
    }

    /** Read one group on demand (Tech / raw blocks screen). */
    suspend fun readGroupOnce(group: Int): Result<Unit> = sessionMutex.withLock {
        val model = _model.value
        client.readMeasuringBlock(group)
            .onSuccess { block ->
                if (model != null) {
                    val interpreted = interpreter.interpret(block, model)
                    _measurements.value = _measurements.value +
                        interpreted.measurements.associateBy { it.key }
                }
                _lastError.value = null
            }
            .onFailure { _lastError.value = it.asDiagnosticError() }
            .map { }
    }

    suspend fun refreshDtcs(): Result<List<InterpretedDtc>> = sessionMutex.withLock {
        client.readDtc()
            .map { raws -> DtcInterpreter.interpret(raws, _model.value) }
            .onSuccess {
                _dtcs.value = it
                _dtcCount.value = it.size
                emit(SessionEvent.DtcRead(it.size))
            }
            .onFailure { _lastError.value = it.asDiagnosticError() }
    }

    /** Clear DTCs. Callers must confirm with the user first (safety rule). */
    suspend fun clearDtcs(): Result<Unit> = sessionMutex.withLock {
        // Only log the clear once the ECU actually accepted it.
        val codes = _dtcs.value.map { it.code }
        client.clearDtc()
            .onSuccess {
                emit(SessionEvent.DtcCleared(codes))
                _dtcs.value = emptyList()
                _dtcCount.value = 0
            }
            .onFailure { _lastError.value = it.asDiagnosticError() }
    }

    fun logUserNote(note: String) = emit(SessionEvent.UserNote(note))

    /** Enter Basic Settings. Callers must confirm with the user first (safety).
     *  Real adapter: basic settings IS the continuous 0x28 stream (kw1281test —
     *  the mode persists only while 0x28 requests keep arriving); no separate
     *  enter opcode exists. Manual documents groups 01-04 (default 1: the
     *  condition-bits + rpm/coolant/lambda group). */
    suspend fun enterBasicSettings(group: Int? = 0): Result<Unit> = sessionMutex.withLock {
        val deepObd = client as? io.databang.digidash.core.deepobd.DeepObdDiagnosticClient
        if (deepObd != null) {
            val g = (group ?: 1).coerceAtLeast(1)
            deepObd.configureMeasureStream(listOf(g), basicSettings = true)
            _basicSettingsActive.value = true
            emit(SessionEvent.BasicSettingsEntered)
            return Result.success(Unit)
        }
        client.enterBasicSettings(group)
            .onSuccess {
                _basicSettingsActive.value = true
                emit(SessionEvent.BasicSettingsEntered)
            }
            .onFailure { _lastError.value = it.asDiagnosticError() }
    }

    suspend fun exitBasicSettings(): Result<Unit> = sessionMutex.withLock {
        val deepObd = client as? io.databang.digidash.core.deepobd.DeepObdDiagnosticClient
        if (deepObd != null) {
            // Stop the 0x28 stream and resume normal 0x29 group reads; NEVER
            // send 0x06 here (it ends the whole session). The next 0x29 read
            // begins with a deliberate ACK, which also drops the ECU out of
            // its adjust condition.
            val model = _model.value
            val tripGroups = model?.let { m ->
                (m.groups.filter { (_, g) -> g.pollingPriority == "trip" }
                    .keys.mapNotNull { it.toIntOrNull() } +
                    m.tripCardFields().map { it.first })
                    .distinct().sorted().filter { it !in deadGroups }
            }.orEmpty()
            deepObd.configureMeasureStream(tripGroups)
            _basicSettingsActive.value = false
            emit(SessionEvent.BasicSettingsExited)
            return Result.success(Unit)
        }
        client.exitBasicSettings()
            .onSuccess {
                _basicSettingsActive.value = false
                emit(SessionEvent.BasicSettingsExited)
            }
            .onFailure { _lastError.value = it.asDiagnosticError() }
    }

    private fun startPolling(model: EcuModel?) {
        pollJob?.cancel()
        if (model == null) return
        // Poll every group the dashboard needs: the trip-priority groups PLUS
        // any group that holds a trip-card field (throttle, lambda, ignition
        // advance live in "garage" groups but still have dashboard cards).
        val tripPriority = model.groups
            .filter { (_, group) -> group.pollingPriority == "trip" }
            .keys.mapNotNull { it.toIntOrNull() }
        val cardGroups = model.tripCardFields().map { it.first }
        // Typed groups (1-3: lambda V, battery V, injection ms, coolant/intake °C)
        // lead the rotation; legacy block 000 (only live source of ignition
        // advance) closes it.
        val tripGroups = (tripPriority + cardGroups).distinct()
            .sortedBy { if (it == 0) Int.MAX_VALUE else it }
        if (tripGroups.isEmpty()) return

        // Real adapter: the session loop streams measurements continuously
        // (the stream IS the keep-alive; commands preempt at body boundaries).
        val deepObd = client as? io.databang.digidash.core.deepobd.DeepObdDiagnosticClient
        if (deepObd != null) {
            pollJob = scope.launch {
                fun liveGroups() = tripGroups.filter { it !in deadGroups }
                deepObd.configureMeasureStream(liveGroups())
                launch {
                    deepObd.groupFailures.collect { g ->
                        val fails = (groupFailures[g] ?: 0) + 1
                        groupFailures[g] = fails
                        if (fails >= 2 && deadGroups.add(g)) {
                            deepObd.configureMeasureStream(liveGroups())
                        }
                    }
                }
                // NOTE: the adapter-FC voltage telegram was REMOVED here. The raw
                // byte capture (2026-07) proved it: sending 82 F1 F1 FC FC mid-
                // session (between KW1281 blocks) desyncs the ECU dialog and it
                // goes silent -> disconnection, several times per minute. Battery
                // voltage now comes from the ECU itself (group 2 zone 3, decoded
                // and VCDS-matched), so the adapter telegram is not needed.
                deepObd.measurementFlow.collect { block ->
                    val interpreted = interpreter.interpret(block, model)
                    _measurements.value = _measurements.value +
                        interpreted.measurements.associateBy { it.key }
                    _lastError.value = null
                }
            }
            return
        }
        // Fake/demo backend keeps the legacy request/response polling.
        pollJob = scope.launch {
            var i = 0
            while (isActive) {
                // While in Basic Settings, read the ignition-advance group every
                // other cycle so the advance updates fast WITHOUT freezing the
                // other cards (which would otherwise go stale).
                // Skip groups the ECU has refused twice — polling them would keep
                // re-initialising (and dropping) the KW1281 link.
                val live = tripGroups.filter { it !in deadGroups }
                if (live.isEmpty()) {
                    _lastError.value = DiagnosticError.ProtocolError(
                        "No measuring group answered — group reads unavailable on this ECU")
                    break
                }
                val group = when {
                    _basicSettingsActive.value && i % 2 == 0 -> basicSettingsGroup
                    else -> live[(i / if (_basicSettingsActive.value) 2 else 1) % live.size]
                }
                i++
                sessionMutex.withLock {
                    client.readMeasuringBlock(group)
                        .onSuccess { block ->
                            groupFailures[group] = 0
                            val interpreted = interpreter.interpret(block, model)
                            _measurements.value = _measurements.value +
                                interpreted.measurements.associateBy { it.key }
                            _lastError.value = null
                        }
                        .onFailure {
                            _lastError.value = it.asDiagnosticError()
                            val fails = (groupFailures[group] ?: 0) + 1
                            groupFailures[group] = fails
                            if (fails >= 2) deadGroups.add(group)
                        }
                }
                delay(pollIntervalMillis)
            }
        }
    }

    /** Battery from the adapter's FC telegram — same 12V rail as the OBD socket. */
    private fun publishBatteryVoltage(volts: Double) {
        val status = when {
            volts < 10.5 -> io.databang.digidash.domain.model.MeasurementStatus.CRITICAL
            volts < 11.5 || volts > 15.5 -> io.databang.digidash.domain.model.MeasurementStatus.WARNING
            else -> io.databang.digidash.domain.model.MeasurementStatus.NORMAL
        }
        val m = InterpretedMeasurement(
            key = "battery_voltage",
            name = "Battery voltage (adapter)",
            group = 0, fieldIndex = 0,
            rawString = null, value = volts,
            displayValue = String.format(java.util.Locale.US, "%.1f", volts),
            unit = "V", status = status,
            confidence = "high",
            timestampMillis = System.currentTimeMillis(),
        )
        _measurements.value = _measurements.value + (m.key to m)
    }

    private fun emit(event: SessionEvent) {
        _events.tryEmit(event)
    }
}

sealed class SessionEvent {
    data class EcuIdentified(val partNumber: String) : SessionEvent()
    data class ModelLoaded(val displayName: String) : SessionEvent()
    data object ConnectionLost : SessionEvent()
    /** Unexpected drop (socket/ECU) — auto-reconnect is about to start. */
    data object ConnectionDropped : SessionEvent()
    data class Reconnecting(val attempt: Int) : SessionEvent()
    data class Reconnected(val attempt: Int) : SessionEvent()
    data class DtcRead(val count: Int) : SessionEvent()
    data class DtcCleared(val codes: List<String>) : SessionEvent()
    data object BasicSettingsEntered : SessionEvent()
    data object BasicSettingsExited : SessionEvent()
    data class UserNote(val note: String) : SessionEvent()

    fun describe(): String = when (this) {
        is EcuIdentified -> "ECU identified: $partNumber"
        is ModelLoaded -> "ECU model loaded: $displayName"
        ConnectionLost -> "Connection lost"
        ConnectionDropped -> "Connection dropped — reconnecting"
        is Reconnecting -> "Reconnecting (attempt $attempt)"
        is Reconnected -> "Reconnected (after $attempt attempt(s))"
        is DtcRead -> "DTC read: $count fault(s)"
        is DtcCleared -> "DTC cleared: ${codes.joinToString(" ").ifEmpty { "none" }}"
        BasicSettingsEntered -> "Basic Settings entered"
        BasicSettingsExited -> "Basic Settings exited"
        is UserNote -> "Note: $note"
    }
}
