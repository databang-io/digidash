package io.databang.digidash.core.deepobd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth classic SPP transport, following Deep OBD's connect strategy
 * (secure RFCOMM for bonded devices, insecure otherwise, retry once). See
 * docs/DeepOBD-Observed-API.md §2. Requires BLUETOOTH_CONNECT at runtime.
 */
@SuppressLint("MissingPermission")
class AndroidSppTransport(
    private val adapter: BluetoothAdapter,
    private val address: String,
) : SppTransport {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override val isConnected: Boolean
        get() = socket?.isConnected == true

    override fun connect() {
        val device: BluetoothDevice = adapter.getRemoteDevice(address)
        adapter.cancelDiscovery()
        val uuid = UUID.fromString(SPP_UUID)
        val bonded = device.bondState == BluetoothDevice.BOND_BONDED
        val s = if (bonded) {
            device.createRfcommSocketToServiceRecord(uuid)
        } else {
            device.createInsecureRfcommSocketToServiceRecord(uuid)
        }
        try {
            s.connect()
        } catch (first: Exception) {
            // "sometimes the second connect works" — Deep OBD retries once.
            try {
                s.connect()
            } catch (second: Exception) {
                runCatching { s.close() }
                throw second
            }
        }
        socket = s
        input = s.inputStream
        output = s.outputStream
    }

    override fun write(data: ByteArray) {
        val out = output ?: error("not connected")
        out.write(data)
        out.flush()
    }

    override fun read(max: Int, timeoutMillis: Long): ByteArray {
        val ins = input ?: error("not connected")
        val deadline = System.currentTimeMillis() + timeoutMillis
        val buffer = ByteArray(max)
        var offset = 0
        while (offset == 0 && System.currentTimeMillis() < deadline) {
            if (ins.available() > 0) {
                val n = ins.read(buffer, offset, max - offset)
                if (n > 0) offset += n
            } else {
                Thread.sleep(10)
            }
        }
        return buffer.copyOf(offset)
    }

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }
}
