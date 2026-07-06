package io.databang.digidash.core.diagnostics

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** A candidate Bluetooth OBD dongle (paired device). */
data class DongleDevice(
    val name: String,
    val address: String,
)

/**
 * Lists paired Bluetooth devices so the user can pick their OBD dongle,
 * the same way Deep OBD does (bonded classic-Bluetooth SPP adapters).
 * The actual SPP connection (UUID 00001101-0000-1000-8000-00805F9B34FB)
 * belongs to the future Deep OBD adapter, not to this class.
 */
interface DongleProvider {
    /** True when listing paired devices is currently permitted. */
    fun hasPermission(): Boolean

    /** Runtime permissions the UI must request before [pairedDevices] works. */
    fun requiredPermissions(): List<String>

    fun pairedDevices(): List<DongleDevice>
}

class AndroidDongleProvider(private val context: Context) : DongleProvider {

    override fun hasPermission(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    override fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyList()
        }

    override fun pairedDevices(): List<DongleDevice> {
        if (!hasPermission()) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return try {
            adapter.bondedDevices.orEmpty().map {
                DongleDevice(name = it.name ?: it.address, address = it.address)
            }.sortedBy { it.name.lowercase() }
        } catch (e: SecurityException) {
            emptyList()
        }
    }
}
