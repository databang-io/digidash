package io.databang.digidash.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto entry point. Projects the trip dashboard onto the head unit.
 * Personal (non-published) apps require Android Auto developer mode enabled on
 * the phone; the app itself is a valid car app regardless.
 */
class DigiDashCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        // Allow all hosts in debug so it works with the desktop head unit and
        // developer-mode phones. Tighten before any Play publication.
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = DigiDashSession()
}
