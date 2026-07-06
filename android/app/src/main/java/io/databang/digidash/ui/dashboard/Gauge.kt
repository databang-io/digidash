package io.databang.digidash.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Circular automotive-style gauge: 270° sweep arc with a needle,
 * value in the middle, label + unit below.
 */
@Composable
fun CircularGauge(
    label: String,
    valueText: String,
    unit: String,
    fraction: Float?, // 0f..1f position on the dial, null = unavailable
    color: Color,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = (fraction ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 450),
        label = "gauge",
    )
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val needleColor = MaterialTheme.colorScheme.onSurface

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.15f),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                val strokeWidth = size.minDimension * 0.09f
                val diameter = size.minDimension - strokeWidth
                val topLeft = Offset(
                    (size.width - diameter) / 2f,
                    (size.height - diameter) / 2f,
                )
                val arcSize = Size(diameter, diameter)
                val startAngle = 135f
                val sweep = 270f

                drawArc(
                    color = trackColor,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                if (fraction != null) {
                    drawArc(
                        color = color,
                        startAngle = startAngle,
                        sweepAngle = sweep * animated,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                    // Needle
                    val angleRad = Math.toRadians((startAngle + sweep * animated).toDouble())
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = diameter / 2f - strokeWidth
                    val tip = Offset(
                        center.x + radius * cos(angleRad).toFloat(),
                        center.y + radius * sin(angleRad).toFloat(),
                    )
                    drawLine(
                        color = needleColor,
                        start = center,
                        end = tip,
                        strokeWidth = strokeWidth * 0.35f,
                        cap = StrokeCap.Round,
                    )
                    drawCircle(
                        color = needleColor,
                        radius = strokeWidth * 0.5f,
                        center = center,
                    )
                }
            }
            Column(
                modifier = Modifier.align(Alignment.BottomCenter),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.headlineMedium,
                    color = color,
                    textAlign = TextAlign.Center,
                )
                if (unit.isNotBlank()) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
    }
}
