package io.databang.digidash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.databang.digidash.core.diagnostics.ConnectionConfig
import io.databang.digidash.core.diagnostics.ConnectionState
import io.databang.digidash.core.diagnostics.DongleDevice
import io.databang.digidash.core.diagnostics.fake.FakeScenario
import io.databang.digidash.core.ecumodel.EcuModel
import io.databang.digidash.data.repository.DiagnosticSessionRepository
import io.databang.digidash.domain.model.DashboardCardState
import io.databang.digidash.domain.model.EcuIdentity
import io.databang.digidash.domain.model.InterpretedMeasurement
import io.databang.digidash.domain.model.MeasurementStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AppUiState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val identity: EcuIdentity? = null,
    val model: EcuModel? = null,
    val cards: List<DashboardCardState> = emptyList(),
    /** Latest measurements grouped by measuring block for the Tech screen. */
    val techGroups: List<TechGroup> = emptyList(),
    val dtcCount: Int? = null,
    val scenario: FakeScenario = FakeScenario.NORMAL,
    val dongles: List<DongleDevice> = emptyList(),
    val selectedDongle: DongleDevice? = null,
    val bluetoothPermissionNeeded: Boolean = false,
    val remoteRepoUrl: String = "",
    val remoteRepoEnabled: Boolean = false,
    val errorMessage: String? = null,
    val connecting: Boolean = false,
)

data class TechGroup(
    val group: Int,
    val label: String,
    val measurements: List<InterpretedMeasurement>,
)

class AppViewModel(private val container: AppContainer) : ViewModel() {

    private val session = DiagnosticSessionRepository(
        client = container.diagnosticClient,
        modelRepositoryProvider = { container.modelRepository() },
        interpreter = container.interpreter,
        scope = viewModelScope,
    )

    private val _ui = MutableStateFlow(
        AppUiState(
            remoteRepoUrl = container.prefs.getString(AppContainer.PREF_REMOTE_REPO_URL, "") ?: "",
            remoteRepoEnabled = container.prefs.getBoolean(AppContainer.PREF_REMOTE_REPO_ENABLED, false),
            selectedDongle = savedDongle(),
        )
    )
    val ui: StateFlow<AppUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            session.connectionState.collect { state ->
                _ui.update { it.copy(connection = state) }
            }
        }
        viewModelScope.launch {
            session.identity.collect { id -> _ui.update { it.copy(identity = id) } }
        }
        viewModelScope.launch {
            session.model.collect { m -> _ui.update { it.copy(model = m) } }
        }
        viewModelScope.launch {
            session.dtcCount.collect { c -> _ui.update { it.copy(dtcCount = c) } }
        }
        viewModelScope.launch {
            session.lastError.collect { e ->
                _ui.update { it.copy(errorMessage = e?.userMessage()) }
            }
        }
        // Rebuild cards on new measurements and once per second for staleness.
        viewModelScope.launch {
            session.measurements.collect { rebuildDerivedState(it) }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                rebuildDerivedState(session.measurements.value)
            }
        }
        refreshDongles()
    }

    fun connect() {
        val state = _ui.value
        viewModelScope.launch {
            _ui.update { it.copy(connecting = true) }
            session.connect(
                ConnectionConfig(
                    useFakeBackend = true,
                    dongleAddress = state.selectedDongle?.address,
                    dongleName = state.selectedDongle?.name,
                )
            )
            _ui.update { it.copy(connecting = false) }
        }
    }

    fun disconnect() {
        viewModelScope.launch { session.disconnect() }
    }

    fun setScenario(scenario: FakeScenario) {
        container.fakeClient.scenario = scenario
        _ui.update { it.copy(scenario = scenario) }
    }

    fun refreshDongles() {
        val provider = container.dongleProvider
        _ui.update {
            it.copy(
                bluetoothPermissionNeeded = !provider.hasPermission(),
                dongles = provider.pairedDevices(),
            )
        }
    }

    fun bluetoothPermissions(): List<String> = container.dongleProvider.requiredPermissions()

    fun selectDongle(dongle: DongleDevice) {
        container.prefs.edit()
            .putString(AppContainer.PREF_DONGLE_ADDRESS, dongle.address)
            .putString(AppContainer.PREF_DONGLE_NAME, dongle.name)
            .apply()
        _ui.update { it.copy(selectedDongle = dongle) }
    }

    fun setRemoteRepo(url: String, enabled: Boolean) {
        container.prefs.edit()
            .putString(AppContainer.PREF_REMOTE_REPO_URL, url.trim())
            .putBoolean(AppContainer.PREF_REMOTE_REPO_ENABLED, enabled)
            .apply()
        _ui.update { it.copy(remoteRepoUrl = url.trim(), remoteRepoEnabled = enabled) }
    }

    private fun savedDongle(): DongleDevice? {
        val address = container.prefs.getString(AppContainer.PREF_DONGLE_ADDRESS, null)
            ?: return null
        val name = container.prefs.getString(AppContainer.PREF_DONGLE_NAME, null) ?: address
        return DongleDevice(name = name, address = address)
    }

    private fun rebuildDerivedState(measurements: Map<String, InterpretedMeasurement>) {
        val state = _ui.value
        val now = System.currentTimeMillis()
        val model = state.model

        val cards = buildList {
            if (model != null) {
                for ((_, field) in model.tripCardFields()) {
                    val m = measurements[field.key]
                    if (m == null) {
                        add(DashboardCardState.unavailable(field.key, field.name, field.unit))
                    } else {
                        add(
                            DashboardCardState(
                                key = m.key,
                                title = m.name,
                                valueText = m.displayValue,
                                unit = m.unit,
                                status = m.status,
                                stale = now - m.timestampMillis > STALE_AFTER_MILLIS,
                                lowConfidence = m.confidence == "low",
                            )
                        )
                    }
                }
            }
            add(
                when (val count = state.dtcCount) {
                    null -> DashboardCardState.unavailable("dtc_count", "DTC")
                    else -> DashboardCardState(
                        key = "dtc_count",
                        title = "DTC",
                        valueText = count.toString(),
                        unit = "",
                        status = if (count == 0) MeasurementStatus.NORMAL else MeasurementStatus.WARNING,
                    )
                }
            )
        }

        val techGroups = measurements.values
            .groupBy { it.group }
            .toSortedMap()
            .map { (group, list) ->
                TechGroup(
                    group = group,
                    label = model?.group(group)?.label
                        ?: "Group ${EcuModel.groupKey(group)}",
                    measurements = list.sortedBy { it.fieldIndex },
                )
            }

        _ui.update { it.copy(cards = cards, techGroups = techGroups) }
    }

    companion object {
        private const val STALE_AFTER_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(container) as T
            }
    }
}
