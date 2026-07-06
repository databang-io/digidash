package io.databang.digidash.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import io.databang.digidash.DigiDashApplication
import io.databang.digidash.core.diagnostics.ConnectionConfig
import io.databang.digidash.core.diagnostics.ConnectionState
import io.databang.digidash.domain.model.DashboardCardState
import io.databang.digidash.domain.model.InterpretedMeasurement
import io.databang.digidash.domain.model.MeasurementStatus
import kotlinx.coroutines.launch

/**
 * Head-unit trip screen: shows the same live critical values as the phone
 * dashboard, rendered with Android Auto's PaneTemplate (templated rows — custom
 * canvas gauges are not allowed while projected, for driver-distraction safety).
 * Reads the shared [io.databang.digidash.SessionHolder] so both surfaces stay
 * in sync.
 */
class TripCarScreen(carContext: CarContext) : Screen(carContext) {

    private val app = carContext.applicationContext as DigiDashApplication
    private val session = app.sessionHolder.session

    private var connection: ConnectionState = ConnectionState.DISCONNECTED

    init {
        // Redraw whenever measurements, connection or DTC count change.
        lifecycleScope.launch { session.measurements.collect { invalidate() } }
        lifecycleScope.launch { session.connectionState.collect { connection = it; invalidate() } }
        lifecycleScope.launch { session.dtcCount.collect { invalidate() } }
    }

    override fun onGetTemplate(): Template {
        val model = session.model.value
        val measurements = session.measurements.value

        val pane = Pane.Builder()
        if (connection != ConnectionState.CONNECTED) {
            pane.addRow(
                Row.Builder()
                    .setTitle("Not connected")
                    .addText("Open DigiDash on the phone and connect (demo or dongle).")
                    .build()
            )
            pane.addAction(
                Action.Builder()
                    .setTitle("Connect (demo)")
                    .setOnClickListener {
                        app.container.useRealBackend = false
                        app.sessionHolder.scope.launch {
                            session.connect(ConnectionConfig(useFakeBackend = true))
                        }
                    }
                    .build()
            )
        } else {
            val rows = model?.tripCardFields()?.take(6).orEmpty()
            if (rows.isEmpty()) {
                pane.addRow(Row.Builder().setTitle("Waiting for data…").build())
            } else {
                rows.forEach { (_, field) ->
                    val m: InterpretedMeasurement? = measurements[field.key]
                    pane.addRow(row(field.name, m))
                }
            }
            session.dtcCount.value?.let { count ->
                pane.addRow(
                    Row.Builder()
                        .setTitle("Fault codes")
                        .addText(if (count == 0) "None" else "$count stored")
                        .build()
                )
            }
        }

        return PaneTemplate.Builder(pane.build())
            .setTitle("DigiDash — T3 2E Digifant")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun row(title: String, m: InterpretedMeasurement?): Row {
        val text = if (m == null) DashboardCardState.NOT_AVAILABLE
        else m.displayValue + if (m.unit.isNotBlank()) " ${m.unit}" else ""
        return Row.Builder()
            .setTitle(title)
            .addText(text + statusSuffix(m?.status))
            .build()
    }

    private fun statusSuffix(status: MeasurementStatus?): String = when (status) {
        MeasurementStatus.CRITICAL -> "  • CRITICAL"
        MeasurementStatus.WARNING -> "  • warning"
        else -> ""
    }
}
