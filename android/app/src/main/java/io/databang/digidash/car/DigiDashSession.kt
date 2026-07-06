package io.databang.digidash.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class DigiDashSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = TripCarScreen(carContext)
}
