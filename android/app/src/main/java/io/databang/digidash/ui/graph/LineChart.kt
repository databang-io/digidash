package io.databang.digidash.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** A point to plot: x is a monotonic time, y the value. */
data class ChartPoint(val x: Double, val y: Double)

/**
 * Minimal time-series line chart on a Canvas — no external chart lib (CSP-safe,
 * lightweight). Auto-scales Y to the data (with optional fixed [yMin]/[yMax]).
 */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    color: Color,
    modifier: Modifier = Modifier.fillMaxWidth().height(180.dp),
    yMin: Double? = null,
    yMax: Double? = null,
    gridColor: Color = Color.Gray.copy(alpha = 0.25f),
) {
    Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas
        val xs = points.map { it.x }
        val ys = points.map { it.y }
        val minX = xs.min(); val maxX = xs.max()
        val loY = yMin ?: ys.min()
        val hiY = yMax ?: ys.max()
        val spanX = (maxX - minX).takeIf { it > 0 } ?: 1.0
        val spanY = (hiY - loY).takeIf { it > 0 } ?: 1.0

        val pad = 8f
        val w = size.width - pad * 2
        val h = size.height - pad * 2

        // Horizontal grid lines (quarters).
        for (i in 0..4) {
            val gy = pad + h * i / 4f
            drawLine(gridColor, Offset(pad, gy), Offset(pad + w, gy), strokeWidth = 1f)
        }

        fun px(p: ChartPoint) = Offset(
            x = pad + ((p.x - minX) / spanX).toFloat() * w,
            y = pad + (1f - ((p.y - loY) / spanY).toFloat()) * h,
        )

        val path = Path()
        points.forEachIndexed { i, p ->
            val o = px(p)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, color = color, style = Stroke(width = 3f, cap = StrokeCap.Round))

        // Highlight the latest point.
        val last = px(points.last())
        drawCircle(color, radius = 4f, center = last)
    }
}
