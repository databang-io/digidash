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
                "raw" -> {
                    val hex = intent.getStringExtra("hex").orEmpty()
                    val bytes = parseHex(hex)
                    if (bytes.isEmpty()) { Log.w(TAG, "raw: empty/invalid hex"); return@launch }
                    Log.i(TAG, "raw TX ${hex(bytes)}")
                    val resp = client.debugRaw(bytes)
                    Log.i(TAG, "raw RX ${resp.size}: ${hex(resp)}")
                }
                "scan" -> {
                    val from = intent.getIntExtra("from", 0)
                    val to = intent.getIntExtra("to", 15)
                    for (g in from..to) {
                        val r = client.readMeasuringBlock(g)
                        Log.i(TAG, "scan $g -> " + r.map { b ->
                            b.fields.joinToString(" ") { "${it.index}=${it.raw}" }
                        }.getOrElse { "ERR ${it.asDiagnosticError().userMessage()}" })
                    }
                    Log.i(TAG, "scan done")
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
                "voltage" -> Log.i(TAG, "voltage -> ${client.debugVoltage()} V")
                "id" -> Log.i(TAG, "id blocks -> ${client.debugIdBlocks()}")
                "adapter" -> Log.i(TAG, "adapter -> ${client.adapterInfo()}")
                "set" -> {
                    intent.getIntExtra("baud", -1).takeIf { it > 0 }?.let { client.kwpBaud = it }
                    intent.getIntExtra("addr", -1).takeIf { it >= 0 }?.let { client.ecuAddress = it }
                    val c = client.kwpConfig
                    client.kwpConfig = c.copy(
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
