package io.databang.digidash.data.repository

import io.databang.digidash.core.diagnostics.ConnectionConfig
import io.databang.digidash.core.diagnostics.ConnectionState
import io.databang.digidash.core.diagnostics.DiagnosticClient
import io.databang.digidash.core.diagnostics.DiagnosticError
import io.databang.digidash.core.diagnostics.asDiagnosticError
import io.databang.digidash.core.ecumodel.EcuModel
import io.databang.digidash.core.ecumodel.EcuModelRepository
import io.databang.digidash.core.interpret.MeasurementInterpreter
import io.databang.digidash.domain.model.EcuIdentity
import io.databang.digidash.domain.model.InterpretedMeasurement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns one diagnostic session: connect, identify, resolve the ECU Model,
 * poll trip groups (one group per cycle — old KWP1281 ECUs are slow) and
 * publish interpreted measurements.
 */
class DiagnosticSessionRepository(
    private val client: DiagnosticClient,
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

    private val _dtcCount = MutableStateFlow<Int?>(null)
    val dtcCount: StateFlow<Int?> = _dtcCount.asStateFlow()

    private val _lastError = MutableStateFlow<DiagnosticError?>(null)
    val lastError: StateFlow<DiagnosticError?> = _lastError.asStateFlow()

    val connectionState: Flow<ConnectionState> = client.connectionState()

    private var pollJob: Job? = null

    suspend fun connect(config: ConnectionConfig): Boolean {
        _lastError.value = null
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

        val model = runCatching {
            modelRepositoryProvider().findByPartNumber(identity.partNumberNormalized)
        }.getOrNull()
        _model.value = model

        client.readDtc().onSuccess { _dtcCount.value = it.size }

        startPolling(model)
        return true
    }

    suspend fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        client.disconnect()
        _identity.value = null
        _measurements.value = emptyMap()
        _dtcCount.value = null
    }

    private fun startPolling(model: EcuModel?) {
        pollJob?.cancel()
        if (model == null) return
        val tripGroups = model.groups
            .filter { (_, group) -> group.pollingPriority == "trip" }
            .keys.mapNotNull { it.toIntOrNull() }
            .sorted()
            .ifEmpty { model.tripCardFields().map { it.first }.distinct() }
        if (tripGroups.isEmpty()) return

        pollJob = scope.launch {
            var i = 0
            while (isActive) {
                val group = tripGroups[i % tripGroups.size]
                i++
                client.readMeasuringBlock(group)
                    .onSuccess { block ->
                        val interpreted = interpreter.interpret(block, model)
                        _measurements.value = _measurements.value +
                            interpreted.measurements.associateBy { it.key }
                        _lastError.value = null
                    }
                    .onFailure { _lastError.value = it.asDiagnosticError() }
                delay(pollIntervalMillis)
            }
        }
    }
}
