package io.databang.digidash.core.diagnostics

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** A candidate Bluetooth OBD dongle. [paired] false = found by discovery. */
data class DongleDevice(
    val name: String,
    val address: String,
    val paired: Boolean = true,
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

    /** Runtime permissions the UI must request before scan/connect works. */
    fun requiredPermissions(): List<String>

    fun pairedDevices(): List<DongleDevice>

    /**
     * Start classic Bluetooth discovery (Deep OBD does NOT bond — it discovers
     * unpaired dongles and connects insecure). [onFound] fires per device;
     * [onDone] when discovery finishes. Returns a stop() lambda.
     */
    fun startDiscovery(onFound: (DongleDevice) -> Unit, onDone: () -> Unit): () -> Unit
}

class AndroidDongleProvider(private val context: Context) : DongleProvider {

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    override fun hasPermission(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    override fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // Android <=11 (e.g. Galaxy Tab S3 on Android 9): discovering the
            // UNPAIRED dongle requires a runtime location grant. (BLUETOOTH /
            // BLUETOOTH_ADMIN are install-time and already granted.)
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    override fun pairedDevices(): List<DongleDevice> {
        if (!hasPermission()) return emptyList()
        val a = adapter ?: return emptyList()
        if (!a.isEnabled) return emptyList()
        return try {
            a.bondedDevices.orEmpty().map {
                DongleDevice(name = it.name ?: it.address, address = it.address, paired = true)
            }.sortedBy { it.name.lowercase() }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    override fun startDiscovery(onFound: (DongleDevice) -> Unit, onDone: () -> Unit): () -> Unit {
        val a = adapter
        if (!hasPermission() || a == null || !a.isEnabled) { onDone(); return {} }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @Suppress("DEPRECATION")
                        val device: BluetoothDevice? =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        device?.let {
                            val name = try { it.name } catch (e: SecurityException) { null }
                            onFound(DongleDevice(name = name ?: it.address, address = it.address, paired = false))
                        }
                    }
                    android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onDone()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // Bluetooth discovery broadcasts are system/protected; some OEMs
        // (Samsung) only deliver them to an EXPORTED context receiver.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        try {
            if (a.isDiscovering) a.cancelDiscovery()
            val started = a.startDiscovery()
            android.util.Log.i("DIGIDASH_DBG", "startDiscovery() returned $started")
        } catch (e: SecurityException) {
            onDone()
        }
        return {
            runCatching { a.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
