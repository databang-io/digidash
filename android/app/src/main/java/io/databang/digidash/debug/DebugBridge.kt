package io.databang.digidash.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.databang.digidash.AppContainer
import io.databang.digidash.SessionHolder
import io.databang.digidash.core.deepobd.DeepObdDiagnosticClient
import io.databang.digidash.core.diagnostics.ConnectionConfig
import io.databang.digidash.core.diagnostics.asDiagnosticError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * adb-drivable debug bridge for live KWP1281 framing work at the vehicle.
 * Everything is logged to logcat tag [TAG] so a laptop can watch with
 * `adb logcat -s DIGIDASH_DBG`.
 *
 * Send commands with `adb shell am broadcast`:
 *   -a io.databang.digidash.DEBUG --es cmd connect
 *   -a io.databang.digidash.DEBUG --es cmd identify
 *   -a io.databang.digidash.DEBUG --es cmd group --ei n 0
 *   -a io.databang.digidash.DEBUG --es cmd dtc
 *   -a io.databang.digidash.DEBUG --es cmd raw --es hex "82 F1 F1 FE FE 60"
 *   -a io.databang.digidash.DEBUG --es cmd disconnect
 *
 * The raw command sends bytes straight to the adapter and logs the response,
 * so telegrams can be probed interactively without rebuilding the app.
 */
class DebugBridge(
    private val container: AppContainer,
    private val sessionHolder: SessionHolder,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        Log.i(TAG, "cmd=$cmd")
        val client = container.deepObdClient
        sessionHolder.scope.launch {
            when (cmd) {
                "dumpgroups" -> {
                    // Capture REAL raw group frames (0x02 headers + 0xF4 bodies)
                    // to a pullable file for offline work. Connect first.
                    //   --es groups "0,1,2,3"  --ei seconds 30
                    //   adb pull <printed path>
                    if (!client.isTransportOpen()) {
                        Log.w(TAG, "dumpgroups: NOT connected — run connect first"); return@launch
                    }
                    val groups = intent.getStringExtra("groups")
                        ?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
                        ?: listOf(0, 1, 2, 3)
                    val seconds = intent.getIntExtra("seconds", 30)
                    val dir = java.io.File(context.getExternalFilesDir(null), "dumps").apply { mkdirs() }
                    val file = java.io.File(dir, "kwp-dump-${System.currentTimeMillis()}.txt")
                    val w = file.bufferedWriter()
                    val lock = Any()
                    fun line(s: String) { synchronized(lock) { w.appendLine(s); w.flush() }; Log.i(TAG, s) }
                    line("# DigiDash KWP1281 raw group dump")
                    line("# ecu=${client.idBlocks()}")
                    line("# groups=$groups seconds=$seconds")
                    line("# each line: group <g> T=<title> [<data hex>]  (02=header, F4=body, 0A=refused)")
                    client.setStreamRawTap { g, title, data ->
                        line("group %d T=%02X [%s]".format(g, title,
                            data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }))
                    }
                    client.configureMeasureStream(groups)
                    Log.i(TAG, "dumpgroups: capturing $groups for ${seconds}s -> ${file.absolutePath}")
                    kotlinx.coroutines.delay(seconds * 1000L)
                    client.configureMeasureStream(emptyList())
                    client.setStreamRawTap(null)
                    synchronized(lock) { w.close() }
                    Log.i(TAG, "dumpgroups DONE -> ${file.absolutePath}")
                    Log.i(TAG, "pull: adb pull ${file.absolutePath}")
                }
                "capture" -> {
                    val on = intent.getStringExtra("state") != "off"
                    container.captureRawTraffic = on
                    Log.i(TAG, "capture raw traffic = $on (applies on next connect)")
                }
                "resync" -> {
                    client.requestResync()
                    Log.i(TAG, "resync requested (0x00 + counter reset + ident pump)")
                }
                "demo" -> {
                    // Switch to the fake backend and connect (desk testing).
                    container.useRealBackend = false
                    val ok = sessionHolder.session.connect(ConnectionConfig(useFakeBackend = true))
                    Log.i(TAG, "demo connect -> $ok")
                }
                "connectbare" -> {
                    // h4 test precondition: client-direct connect — transport +
                    // probe + session (ident pump + keep-alive) with NO DTC read,
                    // NO group polling, NO 0x12, NO FC voltage telegrams.
                    container.useRealBackend = true
                    val cfg = ConnectionConfig(
                        useFakeBackend = false,
                        dongleAddress = container.prefs.getString(AppContainer.PREF_DONGLE_ADDRESS, null),
                        dongleName = container.prefs.getString(AppContainer.PREF_DONGLE_NAME, null),
                    )
                    val ok = client.connect(cfg)
                    Log.i(TAG, "connectbare -> ${ok.isSuccess}; id=${client.debugIdBlocks()}")
                }
                "disconnectbare" -> {
                    client.disconnect()
                    Log.i(TAG, "disconnectbare done")
                }
                "connect" -> {
                    container.useRealBackend = true
                    val cfg = ConnectionConfig(
                        useFakeBackend = false,
                        dongleAddress = container.prefs.getString(AppContainer.PREF_DONGLE_ADDRESS, null),
                        dongleName = container.prefs.getString(AppContainer.PREF_DONGLE_NAME, null),
                    )
                    val ok = sessionHolder.session.connect(cfg)
                    Log.i(TAG, "connect -> $ok; adapter=${client.adapterInfo()?.type}")
                }
                "identify" -> {
                    val id = client.identifyEcu()
                    Log.i(TAG, "identify -> " + id.map { "${it.partNumberRaw} / ${it.component}" }
                        .getOrElse { "ERR ${it.asDiagnosticError().userMessage()}" })
                }
                "groupsync" -> {
                    val n = intent.getIntExtra("n", 1)
                    Log.i(TAG, "groupsync $n -> " + client.debugGroupResync(n))
                }
                "sendblock" -> {
                    val title = intent.getIntExtra("title", 0x29)
                    val bytes = parseHex(intent.getStringExtra("data").orEmpty())
                    Log.i(TAG, "sendblock T=%02X data=[%s]".format(title, hex(bytes)))
                    Log.i(TAG, "sendblock -> " + client.debugBlock(title, bytes))
                }
                "group" -> {
                    val n = intent.getIntExtra("n", 0)
                    val r = client.readMeasuringBlock(n)
                    Log.i(TAG, "group $n -> " + r.map { b ->
                        b.fields.joinToString(" ") { "${it.index}=${it.raw}" }
                    }.getOrElse { "ERR ${it.asDiagnosticError().userMessage()}" })
                }
                "dtc" -> {
                    val r = client.readDtc()
                    Log.i(TAG, "dtc -> " + r.map { list ->
                        list.joinToString("; ") { "${it.code}/${it.statusRaw}" }.ifEmpty { "none" }
                    }.getOrElse { "ERR ${it.asDiagnosticError().userMessage()}" })
                }
                "cleardtc" -> {
                    // Real ECU write — only via an explicit operator command.
                    val r = client.clearDtc()
                    Log.i(TAG, "cleardtc -> " +
                        r.map { "OK (cleared)" }.getOrElse { "ERR ${it.asDiagnosticError().userMessage()}" })
                }
                "raw" -> {
                    val hex = intent.getStringExtra("hex").orEmpty()
                    val bytes = parseHex(hex)
                    if (bytes.isEmpty()) { Log.w(TAG, "raw: empty/invalid hex"); return@launch }
                    Log.i(TAG, "raw TX ${hex(bytes)}")
                    val resp = client.debugRaw(bytes)
                    Log.i(TAG, "raw RX ${resp.size}: ${hex(resp)}")
                }
                "scan" -> {
                    // Sweep measuring groups. Per the KW1281 spec (blafusel), an
                    // UNSUPPORTED group makes the ECU go silent and requires a
                    // re-init — so on any failure we reconnect and continue with
                    // the next group instead of leaving the session wedged. Best
                    // run with the ENGINE RUNNING (many groups are engine-live).
                    val from = intent.getIntExtra("from", 1)
                    val to = intent.getIntExtra("to", 12)
                    val cfg = ConnectionConfig(
                        useFakeBackend = false,
                        dongleAddress = container.prefs.getString(AppContainer.PREF_DONGLE_ADDRESS, null),
                        dongleName = container.prefs.getString(AppContainer.PREF_DONGLE_NAME, null),
                    )
                    val ok = ArrayList<Int>()
                    var reconnects = 0
                    val maxReconnects = (to - from) + 6
                    var g = from
                    while (g <= to) {
                        val r = client.readMeasuringBlock(g)
                        if (r.isSuccess) {
                            val b = r.getOrThrow()
                            ok.add(g)
                            Log.i(TAG, "scan $g -> OK " +
                                b.fields.joinToString(" ") { "${it.index}=${it.raw}" })
                            g++
                        } else {
                            Log.i(TAG, "scan $g -> ERR ${r.exceptionOrNull()?.let {
                                it.asDiagnosticError().userMessage() } } (unsupported? reconnecting)")
                            if (reconnects++ >= maxReconnects) {
                                Log.w(TAG, "scan: reconnect budget exhausted, stopping"); break
                            }
                            runCatching { sessionHolder.session.disconnect() }
                            kotlinx.coroutines.delay(600)
                            val re = sessionHolder.session.connect(cfg)
                            Log.i(TAG, "scan: reconnect after group $g -> $re")
                            if (!re) kotlinx.coroutines.delay(1500)
                            g++
                        }
                    }
                    Log.i(TAG, "scan done — supported groups: $ok (reconnects=$reconnects)")
                }
                "poll" -> {
                    val n = intent.getIntExtra("n", 0)
                    val count = intent.getIntExtra("count", 20)
                    val interval = intent.getLongExtra("interval", 500)
                    repeat(count) { i ->
                        val r = client.readMeasuringBlock(n)
                        Log.i(TAG, "poll $n #$i -> " + r.map { b ->
                            b.fields.joinToString(" ") { it.raw }
                        }.getOrElse { "ERR" })
                        kotlinx.coroutines.delay(interval)
                    }
                }
                "basic" -> {
                    val n = intent.getIntExtra("n", 0)
                    val r = client.enterBasicSettings(n)
                    Log.i(TAG, "enterBasicSettings $n -> " +
                        r.map { "OK" }.getOrElse { "ERR ${it.asDiagnosticError().userMessage()}" })
                }
                "basicexit" -> {
                    val r = client.exitBasicSettings()
                    Log.i(TAG, "exitBasicSettings -> " +
                        r.map { "OK" }.getOrElse { "ERR ${it.asDiagnosticError().userMessage()}" })
                }
                "dongles" -> {
                    val provider = container.dongleProvider
                    Log.i(TAG, "hasPermission=${provider.hasPermission()}")
                    provider.pairedDevices().forEach { Log.i(TAG, "paired: ${it.name} ${it.address}") }
                    Log.i(TAG, "starting discovery…")
                    provider.startDiscovery(
                        onFound = { Log.i(TAG, "found: ${it.name} ${it.address}") },
                        onDone = { Log.i(TAG, "discovery done") },
                    )
                }
                "setdongle" -> {
                    val mac = intent.getStringExtra("mac")
                    val name = intent.getStringExtra("name") ?: "OBDII"
                    if (mac.isNullOrBlank()) { Log.w(TAG, "setdongle: need --es mac XX:XX:..") ; return@launch }
                    container.prefs.edit()
                        .putString(AppContainer.PREF_DONGLE_ADDRESS, mac)
                        .putString(AppContainer.PREF_DONGLE_NAME, name)
                        .apply()
                    Log.i(TAG, "dongle set to $name $mac")
                }
                "blescan" -> runBleScan(context)
                "voltage" -> Log.i(TAG, "voltage -> ${client.adapterVoltage() ?: client.debugVoltage()} V")
                "id" -> Log.i(TAG, "id blocks -> ${client.debugIdBlocks()}")
                "adapter" -> Log.i(TAG, "adapter -> ${client.adapterInfo()}")
                "set" -> {
                    intent.getIntExtra("baud", -1).takeIf { it > 0 }?.let { client.kwpBaud = it }
                    intent.getIntExtra("addr", -1).takeIf { it >= 0 }?.let { client.ecuAddress = it }
                    val c = client.kwpConfig
                    client.kwpConfig = c.copy(
                        autoBaud = intent.getStringExtra("autobaud")?.let { it == "on" } ?: c.autoBaud,
                        initLine = intent.getStringExtra("initline") ?: c.initLine,
                        depair = intent.getStringExtra("depair") ?: c.depair,
                        buildFullBlock = intent.getStringExtra("block")
                            ?.let { it == "full" } ?: c.buildFullBlock,
                        sendAcks = intent.getStringExtra("acks")?.let { it == "on" } ?: c.sendAcks,
                    )
                    Log.i(TAG, "set -> baud=${client.kwpBaud} addr=${client.ecuAddress} " +
                        "cfg=${client.kwpConfig} (reconnect to apply)")
                }
                "disconnect" -> {
                    sessionHolder.session.disconnect()
                    Log.i(TAG, "disconnected")
                }
                else -> Log.w(TAG, "unknown cmd: $cmd")
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun runBleScan(context: Context) {
        val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) { Log.w(TAG, "no BLE scanner"); return }
        Log.i(TAG, "BLE scan starting…")
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(type: Int, result: android.bluetooth.le.ScanResult) {
                // BLE devices advertise their name in the scan record.
                val advName = result.scanRecord?.deviceName
                val devName = try { result.device.name } catch (e: SecurityException) { null }
                val name = advName ?: devName
                // Only log NAMED devices (cuts the noise of anonymous beacons).
                if (!name.isNullOrBlank()) {
                    Log.i(TAG, "BLE named: $name ${result.device.address} rssi=${result.rssi}")
                }
            }
        }
        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, cb)
            sessionHolder.scope.launch {
                kotlinx.coroutines.delay(15000)
                runCatching { scanner.stopScan(cb) }
                Log.i(TAG, "BLE scan done")
            }
        } catch (e: Exception) {
            Log.w(TAG, "BLE scan error: ${e.message}")
        }
    }

    private fun parseHex(s: String): ByteArray =
        s.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull(16)?.toByte() }
            .toByteArray()

    private fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    companion object {
        const val TAG = "DIGIDASH_DBG"
        const val ACTION = "io.databang.digidash.DEBUG"
    }
}
