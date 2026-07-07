package io.databang.digidash

import android.app.Application
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import io.databang.digidash.debug.DebugBridge

/** Holds the process-wide [AppContainer] and [SessionHolder]. */
class DigiDashApplication : Application() {

    lateinit var container: AppContainer
        private set

    lateinit var sessionHolder: SessionHolder
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
        sessionHolder = SessionHolder(container)

        // adb-drivable debug bridge for live vehicle framing work.
        // Exported so `adb shell am broadcast` can reach it. Debug builds only.
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(
                this,
                DebugBridge(container, sessionHolder),
                IntentFilter(DebugBridge.ACTION),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }
}
