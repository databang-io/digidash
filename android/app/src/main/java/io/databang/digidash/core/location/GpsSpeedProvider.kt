package io.databang.digidash.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** GPS acquisition state, so the UI can show "No fix" (searching) vs N/A. */
enum class GpsFix { NO_PERMISSION, DISABLED, SEARCHING, FIX }

/**
 * GPS ground speed in km/h from the framework [LocationManager] (no Play Services
 * dependency). Exposes a [fix] state and satellite counts so the dashboard can
 * show "No fix (n sat)" indoors instead of a bare N/A. Speed is 0 when stopped
 * with a fix, null only when there is no fix at all.
 */
class GpsSpeedProvider(private val context: Context) {

    private val _speedKmh = MutableStateFlow<Double?>(null)
    val speedKmh: StateFlow<Double?> = _speedKmh

    private val _fix = MutableStateFlow(GpsFix.NO_PERMISSION)
    val fix: StateFlow<GpsFix> = _fix

    /** Satellites in view / used in the current fix. */
    private val _satellitesInView = MutableStateFlow(0)
    val satellitesInView: StateFlow<Int> = _satellitesInView
    private val _satellitesUsed = MutableStateFlow(0)
    val satellitesUsed: StateFlow<Int> = _satellitesUsed

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var listening = false

    private val listener = LocationListener { loc: Location ->
        _speedKmh.value = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        _fix.value = GpsFix.FIX
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            _satellitesInView.value = status.satelliteCount
            _satellitesUsed.value = used
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Begin GPS updates if permitted; a no-op otherwise. */
    @SuppressLint("MissingPermission")
    fun start() {
        val manager = lm ?: return
        if (!hasPermission()) { _fix.value = GpsFix.NO_PERMISSION; return }
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            _fix.value = GpsFix.DISABLED
        }
        if (listening) return
        listening = true
        if (_fix.value != GpsFix.FIX) _fix.value = GpsFix.SEARCHING
        val handler = Handler(Looper.getMainLooper())
        runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper(),
            )
            manager.registerGnssStatusCallback(gnssCallback, handler)
        }.onFailure { listening = false }
    }

    fun stop() {
        val manager = lm ?: return
        if (!listening) return
        listening = false
        runCatching { manager.removeUpdates(listener) }
        runCatching { manager.unregisterGnssStatusCallback(gnssCallback) }
    }
}
