package io.databang.digidash.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * GPS ground speed in km/h from the framework [LocationManager] (no Play Services
 * dependency). Emits null when there is no location permission or no fix yet, so
 * the dashboard shows N/A rather than a fake 0. Useful on a T3 whose mechanical
 * speedo is unreliable, or to sanity-check the ECU's own speed signal.
 */
class GpsSpeedProvider(private val context: Context) {

    private val _speedKmh = MutableStateFlow<Double?>(null)
    val speedKmh: StateFlow<Double?> = _speedKmh

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var listening = false

    private val listener = LocationListener { loc: Location ->
        if (loc.hasSpeed()) _speedKmh.value = (loc.speed * 3.6)
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Begin GPS updates if permitted; a no-op otherwise (speed stays N/A). */
    @SuppressLint("MissingPermission")
    fun start() {
        val manager = lm ?: return
        if (listening || !hasPermission()) return
        listening = true
        runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper(),
            )
        }.onFailure { listening = false }
    }

    fun stop() {
        val manager = lm ?: return
        if (!listening) return
        listening = false
        runCatching { manager.removeUpdates(listener) }
    }
}
