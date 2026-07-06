package io.databang.digidash

import android.app.Application

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
    }
}
