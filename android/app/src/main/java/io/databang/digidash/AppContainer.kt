package io.databang.digidash

import android.content.Context
import android.content.SharedPreferences
import io.databang.digidash.core.diagnostics.AndroidDongleProvider
import io.databang.digidash.core.diagnostics.DiagnosticClient
import io.databang.digidash.core.diagnostics.DongleProvider
import io.databang.digidash.core.diagnostics.fake.FakeDiagnosticClient
import io.databang.digidash.core.ecumodel.AssetEcuModelSource
import io.databang.digidash.core.ecumodel.DefaultEcuModelRepository
import io.databang.digidash.core.ecumodel.EcuModelRepository
import io.databang.digidash.core.ecumodel.EcuModelSource
import io.databang.digidash.core.ecumodel.RemoteEcuModelSource
import io.databang.digidash.core.interpret.DefaultMeasurementInterpreter
import io.databang.digidash.core.interpret.MeasurementInterpreter
import java.io.File

/** Hand-rolled dependency container; small enough that Hilt would be overkill. */
class AppContainer(private val appContext: Context) {

    val prefs: SharedPreferences =
        appContext.getSharedPreferences("digidash", Context.MODE_PRIVATE)

    val dongleProvider: DongleProvider = AndroidDongleProvider(appContext)

    val interpreter: MeasurementInterpreter = DefaultMeasurementInterpreter()

    val fakeClient = FakeDiagnosticClient(jitter = true, operationDelayMillis = 200)

    /** Fake for now; the Deep OBD adapter will slot in behind the same interface. */
    val diagnosticClient: DiagnosticClient get() = fakeClient

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

        const val DEFAULT_REMOTE_REPO_HINT =
            "https://raw.githubusercontent.com/<user>/<repo>/main/ecu_models"
    }
}
