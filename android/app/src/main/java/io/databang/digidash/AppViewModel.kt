package io.databang.digidash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.databang.digidash.core.diagnostics.ConnectionConfig
import io.databang.digidash.core.diagnostics.ConnectionState
import io.databang.digidash.core.diagnostics.DongleDevice
import io.databang.digidash.core.diagnostics.fake.FakeScenario
import io.databang.digidash.core.ecumodel.EcuModel
import io.databang.digidash.core.logging.LogFile
import io.databang.digidash.core.logging.TripLogController
import io.databang.digidash.data.repository.DiagnosticSessionRepository
import io.databang.digidash.domain.model.DashboardCardState
import io.databang.digidash.domain.model.DtcSeverity
import io.databang.digidash.domain.model.EcuIdentity
import io.databang.digidash.domain.model.InterpretedDtc
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
    /** Dashboard edit mode: long-press a card to resize/reorder. */
    val dashboardEditMode: Boolean = false,
    /** Card selected in edit mode (shows its size control). */
    val selectedCardKey: String? = null,
    /** Latest measurements grouped by measuring block for the Tech screen. */
    val techGroups: List<TechGroup> = emptyList(),
    val dtcCount: Int? = null,
    val dtcs: List<InterpretedDtc> = emptyList(),
    val dtcBusy: Boolean = false,
    /** GPS ground speed in km/h (null = no fix / no permission). */
    val gpsSpeedKmh: Double? = null,
    val gpsFix: io.databang.digidash.core.location.GpsFix =
        io.databang.digidash.core.location.GpsFix.NO_PERMISSION,
    val gpsSatellites: Int = 0,
    val ignition: IgnitionState = IgnitionState(),
    val recording: Boolean = false,
    val currentLogFile: String? = null,
    val logs: List<LogFile> = emptyList(),
    val importedReplay: io.databang.digidash.core.logging.ReplayData? = null,
    val captureSnapshots: List<CaptureSnapshot> = emptyList(),
    val availableGroups: List<Int> = emptyList(),
    val scenario: FakeScenario = FakeScenario.NORMAL,
    val dongles: List<DongleDevice> = emptyList(),
    val selectedDongle: DongleDevice? = null,
    val scanning: Boolean = false,
    val bluetoothPermissionNeeded: Boolean = false,
    val remoteRepoUrl: String = "",
    val remoteRepoEnabled: Boolean = false,
    val useRealBackend: Boolean = false,
    val alertsEnabled: Boolean = true,
    val captureRawTraffic: Boolean = false,
    val readOnlyMode: Boolean = false,
    /** Min/max per card key over the session (peak hold). */
    val peaks: Map<String, io.databang.digidash.core.history.PeakHold> = emptyMap(),
    val errorMessage: String? = null,
    val connecting: Boolean = false,
) {
    val connected: Boolean get() = connection == ConnectionState.CONNECTED
}

data class TechGroup(
    val group: Int,
    val label: String,
    val measurements: List<InterpretedMeasurement>,
)

/** One labelled group-000 raw snapshot from the guided capture wizard. */
data class CaptureSnapshot(
    val label: String,
    val timeMillis: Long,
    val rawValues: List<String>,
)

/** Automatic checklist items for the ignition assistant, derived from ECU data. */
data class IgnitionState(
    val coolantOk: Boolean = false,
    val idleStable: Boolean = false,
    val batteryOk: Boolean = false,
    val noHallFault: Boolean = false,
    val noCoolantFault: Boolean = false,
    val noThrottleFault: Boolean = false,
    val basicSettingsSupported: Boolean = false,
    val basicSettingsActive: Boolean = false,
    /** Live RPM/advance while in Basic Settings (from the polled adjustment group). */
    val basicRpm: Double? = null,
    val basicAdvance: Double? = null,
) {
    /** True when RPM is within the 2200-2300 timing window. */
    val rpmOnTarget: Boolean get() = basicRpm != null && basicRpm in 2200.0..2300.0
}

class AppViewModel(
    private val container: AppContainer,
    private val sessionHolder: SessionHolder,
) : ViewModel() {

    private val session = sessionHolder.session
    private val tripLog = sessionHolder.tripLog

    private val history = io.databang.digidash.core.history.MeasurementHistory()
    /** Last alert-status per key so we only alert on transitions into a bad state. */
    private val lastAlertStatus = HashMap<String, MeasurementStatus>()

    /** Persisted custom dashboard order (card keys); null = model default order. */
    private var cardOrder: List<String>? =
        container.prefs.getString(AppContainer.PREF_CARD_ORDER, null)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }

    /** Persisted per-card size overrides ("key:SIZE,..."). */
    private val cardSizes: MutableMap<String, io.databang.digidash.domain.model.CardSize> =
        container.prefs.getString(AppContainer.PREF_CARD_SIZES, null)
            ?.split(",")?.mapNotNull { entry ->
                val (k, v) = entry.split(":").let { if (it.size == 2) it[0] to it[1] else return@mapNotNull null }
                runCatching { k to io.databang.digidash.domain.model.CardSize.valueOf(v) }.getOrNull()
            }?.toMap()?.toMutableMap() ?: mutableMapOf()

    /** Size for a card: persisted override, else RPM/GPS default to a big gauge. */
    private fun sizeFor(key: String): io.databang.digidash.domain.model.CardSize =
        cardSizes[key] ?: when (key) {
            "rpm", "gps_speed" -> io.databang.digidash.domain.model.CardSize.WIDE
            else -> io.databang.digidash.domain.model.CardSize.SMALL
        }

    private val _ui = MutableStateFlow(
        AppUiState(
            remoteRepoUrl = container.prefs.getString(AppContainer.PREF_REMOTE_REPO_URL, "") ?: "",
            remoteRepoEnabled = container.prefs.getBoolean(AppContainer.PREF_REMOTE_REPO_ENABLED, false),
            useRealBackend = container.useRealBackend,
            alertsEnabled = container.alertsEnabled,
            captureRawTraffic = container.captureRawTraffic,
            readOnlyMode = container.readOnlyMode,
            selectedDongle = savedDongle(),
        )
    )
    val ui: StateFlow<AppUiState> = _ui.asStateFlow()

    /** One-shot user-facing messages (connection drop/reconnect) for a Snackbar. */
    private val _toasts = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: kotlinx.coroutines.flow.SharedFlow<String> = _toasts

    init {
        viewModelScope.launch {
            session.connectionState.collect { state -> _ui.update { it.copy(connection = state) } }
        }
        viewModelScope.launch {
            session.events.collect { ev ->
                when (ev) {
                    is io.databang.digidash.data.repository.SessionEvent.ConnectionDropped ->
                        _toasts.tryEmit("⚠️ Connection lost — reconnecting…")
                    is io.databang.digidash.data.repository.SessionEvent.Reconnected ->
                        _toasts.tryEmit("✓ Reconnected")
                    else -> {}
                }
            }
        }
        viewModelScope.launch {
            session.identity.collect { id -> _ui.update { it.copy(identity = id) } }
        }
        viewModelScope.launch {
            session.model.collect { m ->
                _ui.update {
                    it.copy(model = m, availableGroups = m?.groups?.keys
                        ?.mapNotNull { k -> k.toIntOrNull() }?.sorted().orEmpty())
                }
            }
        }
        viewModelScope.launch {
            session.dtcCount.collect { c -> _ui.update { it.copy(dtcCount = c) } }
        }
        viewModelScope.launch {
            session.dtcs.collect { list ->
                _ui.update { it.copy(dtcs = list) }
                rebuildDerivedState(session.measurements.value)
            }
        }
        // Unified GPS speed source: the real provider when live, a smooth fake
        // oscillation in demo mode so the card is populated without a vehicle.
        viewModelScope.launch {
            var tick = 0
            while (isActive) {
                val speed: Double? = when {
                    !_ui.value.connected -> null
                    container.useRealBackend -> container.gpsSpeedProvider.speedKmh.value
                    else -> {
                        // Demo: track the demo RPM (virtual gearing) so speed and
                        // rpm gauges move together; fall back to a gentle sweep.
                        val rpm = session.measurements.value.values
                            .firstOrNull { it.key == "rpm" || it.unit == "rpm" }?.value
                        rpm?.let { (it / 33.0).coerceIn(0.0, 160.0) }
                            ?: 48.0 + 42.0 * kotlin.math.sin(tick / 7.0)
                    }
                }
                val fix = if (container.useRealBackend) container.gpsSpeedProvider.fix.value
                    else io.databang.digidash.core.location.GpsFix.FIX
                val sats = container.gpsSpeedProvider.satellitesInView.value
                if (speed != _ui.value.gpsSpeedKmh || fix != _ui.value.gpsFix ||
                    sats != _ui.value.gpsSatellites) {
                    _ui.update { it.copy(gpsSpeedKmh = speed, gpsFix = fix, gpsSatellites = sats) }
                    rebuildDerivedState(session.measurements.value)
                }
                tick++
                kotlinx.coroutines.delay(1000)
            }
        }
        // Best-effort: starts only if location permission is already granted.
        container.gpsSpeedProvider.start()
        viewModelScope.launch {
            session.basicSettingsActive.collect { active ->
                _ui.update { it.copy(ignition = it.ignition.copy(basicSettingsActive = active)) }
            }
        }
        viewModelScope.launch {
            session.lastError.collect { e ->
                _ui.update { it.copy(errorMessage = e?.userMessage()) }
            }
        }
        viewModelScope.launch {
            tripLog.recording.collect { r -> _ui.update { it.copy(recording = r) } }
        }
        viewModelScope.launch {
            tripLog.currentFile.collect { f -> _ui.update { it.copy(currentLogFile = f) } }
        }
        // Rebuild cards on new measurements and once per second for staleness.
        viewModelScope.launch {
            session.measurements.collect {
                recordAndAlert(it)
                rebuildDerivedState(it)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                rebuildDerivedState(session.measurements.value)
            }
        }
        refreshDongles()
        refreshLogs()
    }

    fun connect() {
        val state = _ui.value
        viewModelScope.launch {
            _ui.update { it.copy(connecting = true) }
            session.connect(
                ConnectionConfig(
                    useFakeBackend = !container.useRealBackend,
                    dongleAddress = state.selectedDongle?.address,
                    dongleName = state.selectedDongle?.name,
                )
            )
            // Keep the process alive on OEM power management while connected.
            if (container.useRealBackend) runCatching { container.startSessionService() }
            _ui.update { it.copy(connecting = false) }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            tripLog.stop()
            session.disconnect()
            runCatching { container.stopSessionService() }
        }
    }

    // --- DTC ---

    fun refreshDtcs() {
        viewModelScope.launch {
            _ui.update { it.copy(dtcBusy = true) }
            session.refreshDtcs()
            _ui.update { it.copy(dtcBusy = false) }
        }
    }

    /** Caller (UI) must have shown the confirmation dialog first. */
    fun clearDtcsConfirmed() {
        viewModelScope.launch {
            _ui.update { it.copy(dtcBusy = true) }
            session.clearDtcs()
            _ui.update { it.copy(dtcBusy = false) }
        }
    }

    /** Demo helper: switch the fake backend to the fault scenario and re-read. */
    fun loadDemoFaults() {
        setScenario(FakeScenario.WITH_DTCS)
    }

    // --- Logging ---

    fun toggleRecording() {
        if (tripLog.recording.value) {
            tripLog.stop()
            refreshLogs()
        } else {
            tripLog.start()
        }
    }

    fun addLogNote(note: String) = tripLog.addNote(note)

    // --- Basic Settings (ignition timing) ---

    /** Caller (UI) must have shown the confirmation dialog first. */
    fun enterBasicSettingsConfirmed() {
        viewModelScope.launch { session.enterBasicSettings(group = 0) }
    }

    fun exitBasicSettings() {
        viewModelScope.launch { session.exitBasicSettings() }
    }

    fun refreshLogs() {
        _ui.update { it.copy(logs = container.logRepository.list()) }
    }

    fun deleteLog(log: LogFile) {
        container.logRepository.delete(log)
        refreshLogs()
    }

    fun logFilePath(log: LogFile): String = log.path

    fun parseLog(path: String): io.databang.digidash.core.logging.ReplayData =
        io.databang.digidash.core.logging.LogReplay.parse(java.io.File(path))

    /** Import an external CSV (e.g. VCDS-Lite LOG output) for graphing. */
    fun importCsv(text: String) {
        val data = io.databang.digidash.core.logging.GenericCsvLog.parse(text)
        _ui.update { it.copy(importedReplay = data) }
    }

    // --- Guided group-000 capture wizard ---

    /** Read group 000 once and store its 10 raw values under [label]. */
    fun captureStep(label: String) {
        viewModelScope.launch {
            session.readGroupOnce(0)
            val raw = session.measurements.value.values
                .filter { it.group == 0 }
                .sortedBy { it.fieldIndex }
                .map { it.rawString ?: "?" }
            val snap = CaptureSnapshot(label, System.currentTimeMillis(), raw)
            _ui.update { it.copy(captureSnapshots = it.captureSnapshots + snap) }
        }
    }

    fun clearCaptures() = _ui.update { it.copy(captureSnapshots = emptyList()) }

    /** Write the captured snapshots to a shareable text file. */
    fun exportCaptures(onReady: (String) -> Unit) {
        val snaps = _ui.value.captureSnapshots
        if (snaps.isEmpty()) return
        val sb = StringBuilder()
        sb.appendLine("DigiDash group-000 guided capture")
        sb.appendLine("ecu=${session.identity.value?.partNumberRaw ?: "unknown"}")
        sb.appendLine("field:  1  2  3  4  5  6  7  8  9  10")
        snaps.forEach { s ->
            sb.appendLine("${s.label}: ${s.rawValues.joinToString("  ")}")
        }
        val file = container.logRepository.writeText("capture_g000_${compactTimestamp()}.txt", sb.toString())
        onReady(file.absolutePath)
    }

    fun cardFor(key: String) = _ui.value.cards.find { it.key == key }
    fun peakFor(key: String) = _ui.value.peaks[key]

    // --- Raw blocks on demand ---

    fun readGroup(group: Int) {
        viewModelScope.launch { session.readGroupOnce(group) }
    }

    /**
     * Read every model group once, then write a JSON debug capture (identity +
     * groups + DTCs). Returns the file path via [onReady] for sharing.
     */
    fun exportCapture(onReady: (String) -> Unit) {
        viewModelScope.launch {
            _ui.value.availableGroups.forEach { session.readGroupOnce(it) }
            val json = io.databang.digidash.core.logging.CaptureExporter.buildJson(
                identity = session.identity.value,
                modelName = session.model.value?.displayName,
                measurements = session.measurements.value.values,
                dtcs = session.dtcs.value,
                isoTimestamp = isoNow(),
            )
            val file = container.logRepository.writeCapture(compactTimestamp(), json)
            refreshLogs()
            onReady(file.absolutePath)
        }
    }

    private fun isoNow(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
            .format(java.util.Date())

    private fun compactTimestamp(): String =
        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())

    // --- Settings / dongle ---

    fun setScenario(scenario: FakeScenario) {
        container.fakeClient.scenario = scenario
        _ui.update { it.copy(scenario = scenario) }
        // Re-read DTCs so switching to "with dtcs" immediately shows faults
        // (and clears them when switching back) without a manual refresh.
        if (_ui.value.connected) {
            viewModelScope.launch { session.refreshDtcs() }
        }
    }

    fun setUseRealBackend(enabled: Boolean) {
        container.useRealBackend = enabled
        _ui.update { it.copy(useRealBackend = enabled) }
    }

    fun setCaptureRawTraffic(enabled: Boolean) {
        container.captureRawTraffic = enabled
        _ui.update { it.copy(captureRawTraffic = enabled) }
    }

    fun setReadOnlyMode(enabled: Boolean) {
        container.readOnlyMode = enabled
        _ui.update { it.copy(readOnlyMode = enabled) }
    }

    fun refreshDongles() {
        // Runs after the permission dialog; a good moment to (re)start GPS too.
        container.gpsSpeedProvider.start()
        val provider = container.dongleProvider
        _ui.update {
            // Keep any unpaired devices found by discovery; refresh the paired set.
            val discovered = it.dongles.filter { d -> !d.paired }
            val merged = (provider.pairedDevices() + discovered)
                .distinctBy { d -> d.address }
            it.copy(
                bluetoothPermissionNeeded = !provider.hasPermission(),
                dongles = merged,
            )
        }
    }

    fun bluetoothPermissions(): List<String> = container.dongleProvider.requiredPermissions()

    private var stopScan: (() -> Unit)? = null

    /** Discover unpaired dongles (Deep OBD-style) and merge them into the list. */
    fun scanDongles() {
        if (_ui.value.scanning) return
        stopScan?.invoke()
        // Include already-paired devices too (Android discovery does NOT re-report
        // bonded ones), then add discovered devices as they arrive.
        val paired = container.dongleProvider.pairedDevices()
        _ui.update { it.copy(scanning = true, dongles = mergeDongles(it.dongles, paired)) }
        stopScan = container.dongleProvider.startDiscovery(
            onFound = { dev -> _ui.update { st -> st.copy(dongles = mergeDongles(st.dongles, listOf(dev))) } },
            onDone = { _ui.update { it.copy(scanning = false) } },
        )
    }

    /** Merge device lists (dedup by address) and float likely OBD dongles to the top. */
    private fun mergeDongles(
        current: List<DongleDevice>,
        add: List<DongleDevice>,
    ): List<DongleDevice> =
        (current + add).distinctBy { it.address }
            .sortedWith(compareByDescending<DongleDevice> { isLikelyObd(it.name) }
                .thenBy { it.name.lowercase() })

    private fun isLikelyObd(name: String): Boolean {
        val n = name.lowercase()
        return listOf("obd", "elm", "vgate", "viecar", "konnwei", "vlink", "kkl",
            "can", "327", "obdii", "digi").any { it in n }
    }

    fun stopScanning() {
        stopScan?.invoke()
        stopScan = null
        _ui.update { it.copy(scanning = false) }
    }

    override fun onCleared() {
        stopScan?.invoke()
        super.onCleared()
    }

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

    fun dismissError() = _ui.update { it.copy(errorMessage = null) }

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
                        status = when {
                            count == 0 -> MeasurementStatus.NORMAL
                            state.dtcs.any { it.severity == DtcSeverity.CRITICAL } -> MeasurementStatus.CRITICAL
                            else -> MeasurementStatus.WARNING
                        },
                    )
                }
            )
            add(
                when {
                    state.gpsSpeedKmh != null -> DashboardCardState(
                        key = "gps_speed", title = "GPS speed",
                        valueText = Math.round(state.gpsSpeedKmh).toString(),
                        unit = "km/h", status = MeasurementStatus.NORMAL,
                    )
                    state.gpsFix == io.databang.digidash.core.location.GpsFix.SEARCHING ->
                        DashboardCardState(
                            key = "gps_speed", title = "GPS speed", valueText = "No fix",
                            unit = if (state.gpsSatellites > 0) "${state.gpsSatellites} sat"
                            else "searching…",
                            status = MeasurementStatus.NORMAL,
                        )
                    state.gpsFix == io.databang.digidash.core.location.GpsFix.DISABLED ->
                        DashboardCardState(
                            key = "gps_speed", title = "GPS speed", valueText = "GPS off",
                            unit = "", status = MeasurementStatus.NORMAL,
                        )
                    else -> DashboardCardState.unavailable("gps_speed", "GPS speed")
                }
            )
        }

        val orderedCards = applyCardOrder(cards).map { it.copy(size = sizeFor(it.key)) }

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

        _ui.update {
            it.copy(
                cards = orderedCards,
                techGroups = techGroups,
                ignition = deriveIgnition(measurements, state.dtcs, it.ignition.basicSettingsActive),
            )
        }
    }

    /** Record numeric history + peaks and fire an alert on a bad-status transition. */
    private fun recordAndAlert(measurements: Map<String, InterpretedMeasurement>) {
        var anyNewCritical = false
        var anyNewWarning = false
        for (m in measurements.values) {
            m.value?.let { history.record(m.key, m.timestampMillis, it) }
            val prev = lastAlertStatus.put(m.key, m.status)
            if (prev != m.status) {
                when (m.status) {
                    MeasurementStatus.CRITICAL -> if (prev != MeasurementStatus.CRITICAL) anyNewCritical = true
                    MeasurementStatus.WARNING -> if (prev == MeasurementStatus.NORMAL || prev == null) anyNewWarning = true
                    else -> {}
                }
            }
        }
        if (container.alertsEnabled && (anyNewCritical || anyNewWarning)) {
            container.alerter.alert(critical = anyNewCritical)
        }
        _ui.update { it.copy(peaks = peakSnapshot()) }
    }

    private fun peakSnapshot(): Map<String, io.databang.digidash.core.history.PeakHold> =
        _ui.value.cards.mapNotNull { c -> history.peak(c.key)?.let { c.key to it } }.toMap()

    fun setAlertsEnabled(enabled: Boolean) {
        container.alertsEnabled = enabled
        _ui.update { it.copy(alertsEnabled = enabled) }
    }

    fun resetPeaks() {
        history.reset()
        lastAlertStatus.clear()
        _ui.update { it.copy(peaks = emptyMap()) }
    }

    fun historyOf(key: String): List<io.databang.digidash.core.history.Sample> = history.history(key)

    /** Reorder cards by the saved custom order; unknown keys keep model order at the end. */
    private fun applyCardOrder(cards: List<DashboardCardState>): List<DashboardCardState> {
        val order = cardOrder ?: return cards
        val rank = order.withIndex().associate { (i, key) -> key to i }
        return cards.sortedBy { rank[it.key] ?: (order.size + cards.indexOf(it)) }
    }

    /** Persist a new dashboard order (called from drag-and-drop). */
    fun saveCardOrder(keys: List<String>) {
        cardOrder = keys
        container.prefs.edit().putString(AppContainer.PREF_CARD_ORDER, keys.joinToString(",")).apply()
        _ui.update { it.copy(cards = applyCardOrder(it.cards)) }
    }

    /** Enter/leave dashboard edit mode (long-press to enter); clears selection. */
    fun setDashboardEditMode(on: Boolean) =
        _ui.update { it.copy(dashboardEditMode = on, selectedCardKey = if (on) it.selectedCardKey else null) }

    /** Select a card in edit mode to reveal its size control (null = deselect). */
    fun selectCard(key: String?) = _ui.update { it.copy(selectedCardKey = key) }

    /** Set a card's size explicitly (from the segmented control) and persist. */
    fun setCardSize(key: String, size: io.databang.digidash.domain.model.CardSize) {
        cardSizes[key] = size
        container.prefs.edit().putString(
            AppContainer.PREF_CARD_SIZES,
            cardSizes.entries.joinToString(",") { "${it.key}:${it.value.name}" },
        ).apply()
        _ui.update { state ->
            state.copy(cards = state.cards.map { if (it.key == key) it.copy(size = size) else it })
        }
    }

    /** Reset the dashboard layout (order + sizes) to defaults. */
    fun resetDashboardLayout() {
        cardOrder = null
        cardSizes.clear()
        container.prefs.edit()
            .remove(AppContainer.PREF_CARD_ORDER)
            .remove(AppContainer.PREF_CARD_SIZES)
            .apply()
        _ui.update { state ->
            state.copy(cards = applyCardOrder(state.cards).map { it.copy(size = sizeFor(it.key)) })
        }
    }

    fun resetCardOrder() {
        cardOrder = null
        container.prefs.edit().remove(AppContainer.PREF_CARD_ORDER).apply()
        rebuildDerivedState(session.measurements.value)
    }

    private fun deriveIgnition(
        measurements: Map<String, InterpretedMeasurement>,
        dtcs: List<InterpretedDtc>,
        basicSettingsActive: Boolean,
    ): IgnitionState {
        fun value(vararg keys: String): Double? =
            keys.firstNotNullOfOrNull { measurements[it]?.value }

        val coolant = value("coolant_temp", "coolant_temp_000")
        val rpm = value("rpm", "rpm_000")
        val battery = value("battery_voltage")
        fun hasDtc(code: String) = dtcs.any { it.code == code }

        return IgnitionState(
            coolantOk = coolant != null && coolant >= 80.0,
            idleStable = rpm != null && rpm in 700.0..1000.0,
            batteryOk = battery != null && battery >= 12.5,
            noHallFault = !hasDtc("00515") && !hasDtc("00513"),
            noCoolantFault = !hasDtc("00522"),
            noThrottleFault = !hasDtc("00518") && !hasDtc("00516") && !hasDtc("00517"),
            // Demo (fake) backend supports Basic Settings; the real adapter path
            // is validated on the vehicle (ticket 14).
            basicSettingsSupported = !container.useRealBackend,
            basicSettingsActive = basicSettingsActive,
            basicRpm = value("rpm_g11", "rpm", "rpm_000"),
            basicAdvance = value("ignition_advance"),
        )
    }

    companion object {
        // One group per poll cycle on a slow K-line: with ~6 dashboard groups a
        // full refresh takes several seconds, so allow generous staleness.
        private const val STALE_AFTER_MILLIS = 12_000L

        fun factory(container: AppContainer, sessionHolder: SessionHolder): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(container, sessionHolder) as T
            }
    }
}
