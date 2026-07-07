package io.databang.digidash

import android.content.Context
import android.content.SharedPreferences
import io.databang.digidash.core.diagnostics.AndroidDongleProvider
import io.databang.digidash.core.diagnostics.DiagnosticClient
import io.databang.digidash.core.diagnostics.DongleProvider
import android.bluetooth.BluetoothManager
import io.databang.digidash.core.deepobd.AndroidSppTransport
import io.databang.digidash.core.deepobd.DeepObdDiagnosticClient
import io.databang.digidash.core.deepobd.SppTransport
import io.databang.digidash.core.diagnostics.fake.FakeDiagnosticClient
import io.databang.digidash.core.ecumodel.AssetEcuModelSource
import io.databang.digidash.core.ecumodel.DefaultEcuModelRepository
import io.databang.digidash.core.ecumodel.EcuModelRepository
import io.databang.digidash.core.ecumodel.EcuModelSource
import io.databang.digidash.core.ecumodel.RemoteEcuModelSource
import io.databang.digidash.core.interpret.DefaultMeasurementInterpreter
import io.databang.digidash.core.interpret.MeasurementInterpreter
import io.databang.digidash.core.logging.LogRepository
import java.io.File

/** Hand-rolled dependency container; small enough that Hilt would be overkill. */
class AppContainer(private val appContext: Context) {

    val prefs: SharedPreferences =
        appContext.getSharedPreferences("digidash", Context.MODE_PRIVATE)

    val dongleProvider: DongleProvider = AndroidDongleProvider(appContext)

    val logRepository = LogRepository(appContext)

    val alerter = io.databang.digidash.core.alert.Alerter(appContext)

    var alertsEnabled: Boolean
        get() = prefs.getBoolean(PREF_ALERTS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(PREF_ALERTS_ENABLED, value).apply()

    val interpreter: MeasurementInterpreter = DefaultMeasurementInterpreter()

    val fakeClient = FakeDiagnosticClient(jitter = true, operationDelayMillis = 200)

    var captureRawTraffic: Boolean
        get() = prefs.getBoolean(PREF_CAPTURE_RAW, false)
        set(value) = prefs.edit().putBoolean(PREF_CAPTURE_RAW, value).apply()

    var readOnlyMode: Boolean
        get() = prefs.getBoolean(PREF_READ_ONLY, false)
        set(value) {
            prefs.edit().putBoolean(PREF_READ_ONLY, value).apply()
            deepObdClient.readOnly = value
        }

    /** Real adapter client; shares the DiagnosticClient interface with the fake. */
    val deepObdClient = DeepObdDiagnosticClient(
        transportFactory = { address -> buildSppTransport(address) },
        readOnly = prefs.getBoolean(PREF_READ_ONLY, false),
    )

    /** Whether the real Deep OBD backend is selected (else fake). */
    var useRealBackend: Boolean
        get() = prefs.getBoolean(PREF_USE_REAL_BACKEND, false)
        set(value) = prefs.edit().putBoolean(PREF_USE_REAL_BACKEND, value).apply()

    /** Active client, chosen by [useRealBackend]. */
    val diagnosticClient: DiagnosticClient
        get() = if (useRealBackend) deepObdClient else fakeClient

    private fun buildSppTransport(address: String): SppTransport {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            ?: error("No Bluetooth adapter")
        val base = AndroidSppTransport(adapter, address)
        if (!captureRawTraffic) return base
        // Tee every byte to logs/raw_<ts>.log for live framing debugging.
        val file = logRepository.newRawCaptureFile()
        val writer = file.bufferedWriter()
        return io.databang.digidash.core.deepobd.LoggingSppTransport(base, { line ->
            runCatching { writer.appendLine(line); writer.flush() }
        })
    }

    /**
     * Sources are ordered remote-first when a community git repo is configured,
     * with the bundled assets as offline fallback.
     */
    fun modelRepository(): EcuModelRepository {
        val sources = mutableListOf<EcuModelSource>()
        val remoteUrl = prefs.getString(PREF_REMOTE_REPO_URL, null)
        val remoteEnabled = prefs.getBoolean(PREF_REMOTE_REPO_ENABLED, false)
        if (remoteEnabled && !remoteUrl.isNullOrBlank()) {
            sources += RemoteEcuModelSource(
                baseUrl = remoteUrl,
                cacheDir = File(appContext.filesDir, "ecu_models_cache"),
            )
        }
        sources += AssetEcuModelSource(appContext)
        return DefaultEcuModelRepository(sources)
    }

    companion object {
        const val PREF_REMOTE_REPO_URL = "remote_repo_url"
        const val PREF_REMOTE_REPO_ENABLED = "remote_repo_enabled"
        const val PREF_DONGLE_ADDRESS = "dongle_address"
        const val PREF_DONGLE_NAME = "dongle_name"
        const val PREF_USE_REAL_BACKEND = "use_real_backend"
        const val PREF_CARD_ORDER = "dashboard_card_order"
        const val PREF_ALERTS_ENABLED = "alerts_enabled"
        const val PREF_CAPTURE_RAW = "capture_raw_traffic"
        const val PREF_READ_ONLY = "read_only_mode"

        const val DEFAULT_REMOTE_REPO_HINT =
            "https://raw.githubusercontent.com/<user>/<repo>/main/ecu_models"
    }
}
