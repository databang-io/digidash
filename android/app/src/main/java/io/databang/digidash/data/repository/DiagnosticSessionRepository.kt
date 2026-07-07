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

    /** Group polled while in Basic Settings (ignition advance readout). */
    private val basicSettingsGroup = 11

    private val _lastError = MutableStateFlow<DiagnosticError?>(null)
    val lastError: StateFlow<DiagnosticError?> = _lastError.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Session events for the CSV logger (identify, DTC read/cleared, ...). */
    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private var pollJob: Job? = null
    private var stateJob: Job? = null
    private val sessionMutex = Mutex()

    /** The client resolved for the current/last session. */
    private var client: DiagnosticClient = clientProvider()

    suspend fun connect(config: ConnectionConfig): Boolean {
        _lastError.value = null
        // Resolve the active backend (fake/real) at connect time and follow its
        // connection state.
        client = clientProvider()
        stateJob?.cancel()
        stateJob = scope.launch {
            client.connectionState().collect { _connectionState.value = it }
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

    suspend fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        client.disconnect()
        _identity.value = null
        _measurements.value = emptyMap()
        _dtcs.value = emptyList()
        _dtcCount.value = null
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
        // Preserve current faults in the event log before erasing them.
        emit(SessionEvent.DtcCleared(_dtcs.value.map { it.code }))
        client.clearDtc()
            .onSuccess {
                _dtcs.value = emptyList()
                _dtcCount.value = 0
            }
            .onFailure { _lastError.value = it.asDiagnosticError() }
    }

    fun logUserNote(note: String) = emit(SessionEvent.UserNote(note))

    /** Enter Basic Settings. Callers must confirm with the user first (safety). */
    suspend fun enterBasicSettings(group: Int? = 0): Result<Unit> = sessionMutex.withLock {
        client.enterBasicSettings(group)
            .onSuccess {
                _basicSettingsActive.value = true
                emit(SessionEvent.BasicSettingsEntered)
            }
            .onFailure { _lastError.value = it.asDiagnosticError() }
    }

    suspend fun exitBasicSettings(): Result<Unit> = sessionMutex.withLock {
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
        val tripGroups = (tripPriority + cardGroups).distinct().sorted()
        if (tripGroups.isEmpty()) return

        pollJob = scope.launch {
            var i = 0
            while (isActive) {
                // While in Basic Settings, prioritise the ignition-advance group
                // so the user sees timing change as they rotate the distributor.
                val group = if (_basicSettingsActive.value) basicSettingsGroup
                    else tripGroups[i % tripGroups.size]
                i++
                sessionMutex.withLock {
                    client.readMeasuringBlock(group)
                        .onSuccess { block ->
                            val interpreted = interpreter.interpret(block, model)
                            _measurements.value = _measurements.value +
                                interpreted.measurements.associateBy { it.key }
                            _lastError.value = null
                        }
                        .onFailure { _lastError.value = it.asDiagnosticError() }
                }
                delay(pollIntervalMillis)
            }
        }
    }

    private fun emit(event: SessionEvent) {
        _events.tryEmit(event)
    }
}

sealed class SessionEvent {
    data class EcuIdentified(val partNumber: String) : SessionEvent()
    data class ModelLoaded(val displayName: String) : SessionEvent()
    data object ConnectionLost : SessionEvent()
    data class DtcRead(val count: Int) : SessionEvent()
    data class DtcCleared(val codes: List<String>) : SessionEvent()
    data object BasicSettingsEntered : SessionEvent()
    data object BasicSettingsExited : SessionEvent()
    data class UserNote(val note: String) : SessionEvent()

    fun describe(): String = when (this) {
        is EcuIdentified -> "ECU identified: $partNumber"
        is ModelLoaded -> "ECU model loaded: $displayName"
        ConnectionLost -> "Connection lost"
        is DtcRead -> "DTC read: $count fault(s)"
        is DtcCleared -> "DTC cleared: ${codes.joinToString(" ").ifEmpty { "none" }}"
        BasicSettingsEntered -> "Basic Settings entered"
        BasicSettingsExited -> "Basic Settings exited"
        is UserNote -> "Note: $note"
    }
}
