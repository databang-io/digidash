package io.databang.digidash.core.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.databang.digidash.DigiDashApplication
import io.databang.digidash.domain.model.InterpretedMeasurement
import io.databang.digidash.domain.model.MeasurementStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * A thin, always-on-top gauge bar drawn as a SYSTEM overlay over ANY app
 * (Waze, Maps, home screen…). It is a generic Android overlay window — the
 * app underneath needs no integration. Live values come from the same session
 * measurements the dashboard uses; only real values are shown (N/A otherwise).
 *
 * Runs as a foreground service so the process survives while Waze is in front.
 */
class GaugeOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private var barView: View? = null
    private var pill: TextView? = null
    private var collapsed = false
    private val cells = mutableMapOf<String, Pair<TextView, TextView>>()

    /** Which value-keys back a canonical gauge (first present wins). */
    private val fallbacks = mapOf(
        "rpm" to listOf("rpm", "rpm_000"),
        "coolant_temp" to listOf("coolant_temp", "coolant_temp_raw", "coolant_temp_000"),
    )
    private val shortLabels = mapOf(
        "rpm" to "RPM", "coolant_temp" to "TEMP", "battery_voltage" to "BATT",
        "lambda_signal" to "LAMBDA", "gps_speed" to "SPD", "injection_time" to "INJ",
        "throttle_angle" to "THR", "intake_air_temp" to "IAT", "engine_load" to "LOAD",
    )

    /** User-chosen gauges (key -> label + fallback keys), read from prefs. */
    private lateinit var layout: List<Pair<List<String>, String>>

    private fun loadLayout() {
        val prefs = (application as DigiDashApplication).container.prefs
        val keys = prefs.getString(PREF_OVERLAY_GAUGES, DEFAULT_GAUGES)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.ifEmpty { null } ?: DEFAULT_GAUGES.split(",")
        layout = keys.map { k ->
            (fallbacks[k] ?: listOf(k)) to (shortLabels[k] ?: k.uppercase())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIF_ID, notification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        loadLayout()
        addBar()
        observeMeasurements()
    }

    private fun addBar() {
        val dp = resources.displayMetrics.density
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(210, 12, 12, 14))
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        layout.forEach { (_, label) ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginStart = (6 * dp).toInt(); lp.marginEnd = (6 * dp).toInt()
                layoutParams = lp
            }
            val value = TextView(this).apply {
                text = "—"; setTextColor(Color.WHITE); textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            val name = TextView(this).apply {
                text = label; setTextColor(Color.argb(170, 255, 255, 255)); textSize = 9f
                gravity = Gravity.CENTER
            }
            cell.addView(value); cell.addView(name)
            bar.addView(cell)
            cells[label] = value to name
        }
        // A tiny pill shown when collapsed (tap to expand); full bar hidden.
        pill = TextView(this).apply {
            text = "▪ DIGI"; setTextColor(Color.WHITE); textSize = 12f
            setPadding((10 * dp).toInt(), (5 * dp).toInt(), (10 * dp).toInt(), (5 * dp).toInt())
            visibility = View.GONE
        }
        bar.addView(pill)
        // Tap = collapse/expand (keep Waze fully visible on demand);
        // long-press = flip top<->bottom edge.
        bar.setOnClickListener { toggleCollapsed() }
        bar.setOnLongClickListener { flipEdge(); true }
        barView = bar

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // Not focusable + not touch-modal: taps outside the bar reach Waze.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = topEdge.let { if (it) Gravity.TOP else Gravity.BOTTOM } }
        currentParams = params
        runCatching { windowManager.addView(bar, params) }
    }

    private fun flipEdge() {
        topEdge = !topEdge
        currentParams?.let { p ->
            p.gravity = (if (topEdge) Gravity.TOP else Gravity.BOTTOM) or
                (if (collapsed) Gravity.END else Gravity.CENTER_HORIZONTAL)
            runCatching { windowManager.updateViewLayout(barView, p) }
        }
    }

    private fun toggleCollapsed() {
        collapsed = !collapsed
        val bar = barView as? LinearLayout ?: return
        // Show only the pill when collapsed, only the gauge cells when expanded.
        for (i in 0 until bar.childCount) {
            val c = bar.getChildAt(i)
            c.visibility = if ((c === pill) == collapsed) View.VISIBLE else View.GONE
        }
        currentParams?.let { p ->
            p.width = if (collapsed) WindowManager.LayoutParams.WRAP_CONTENT
            else WindowManager.LayoutParams.MATCH_PARENT
            p.gravity = (if (topEdge) Gravity.TOP else Gravity.BOTTOM) or
                (if (collapsed) Gravity.END else Gravity.CENTER_HORIZONTAL)
            runCatching { windowManager.updateViewLayout(bar, p) }
        }
    }

    private fun observeMeasurements() {
        val session = (application as DigiDashApplication).sessionHolder.session
        scope.launch {
            session.measurements.collectLatest { map -> render(map) }
        }
    }

    private fun render(map: Map<String, InterpretedMeasurement>) {
        layout.forEach { (keys, label) ->
            val (valueTv, _) = cells[label] ?: return@forEach
            val m = keys.firstNotNullOfOrNull { map[it] }
            if (m == null) {
                valueTv.text = "N/A"; valueTv.setTextColor(Color.argb(140, 255, 255, 255))
            } else {
                valueTv.text = m.displayValue + if (m.unit.isNotBlank()) " ${m.unit}" else ""
                valueTv.setTextColor(colorFor(m.status))
            }
        }
    }

    private fun colorFor(status: MeasurementStatus): Int = when (status) {
        MeasurementStatus.CRITICAL -> Color.rgb(0xFF, 0x5A, 0x5A)
        MeasurementStatus.WARNING -> Color.rgb(0xFF, 0xC1, 0x07)
        MeasurementStatus.UNAVAILABLE, MeasurementStatus.UNKNOWN -> Color.argb(140, 255, 255, 255)
        else -> Color.WHITE
    }

    private fun notification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Gauge overlay", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("DigiDash gauge bar")
            .setContentText("Floating gauges over other apps — tap to open")
            .setContentIntent(pi).setOngoing(true).build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
        barView?.let { runCatching { windowManager.removeView(it) } }
        barView = null
    }

    companion object {
        @Volatile var isRunning = false
            private set
        private var topEdge = true
        private var currentParams: WindowManager.LayoutParams? = null
        private const val CHANNEL = "digidash_overlay"
        private const val NOTIF_ID = 43
        const val PREF_OVERLAY_GAUGES = "overlay_gauges"
        /** Default bar (no GPS speed — Waze already shows speed). */
        const val DEFAULT_GAUGES = "rpm,coolant_temp,battery_voltage,lambda_signal"

        /** Apply a config change: restart the bar if it is currently showing. */
        fun refresh(context: Context) { if (isRunning) { stop(context); start(context) } }

        fun start(context: Context) {
            val i = Intent(context, GaugeOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GaugeOverlayService::class.java))
        }
    }
}
