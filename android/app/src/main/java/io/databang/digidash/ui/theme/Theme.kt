package io.databang.digidash.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback palette (pre-Android 12): warm amber on deep asphalt, vintage-cockpit feel.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB951),
    onPrimary = Color(0xFF452B00),
    primaryContainer = Color(0xFF633F00),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFF6FDBB4),
    onSecondary = Color(0xFF003828),
    background = Color(0xFF17130E),
    onBackground = Color(0xFFECE1D4),
    surface = Color(0xFF17130E),
    onSurface = Color(0xFFECE1D4),
    surfaceVariant = Color(0xFF4F4539),
    onSurfaceVariant = Color(0xFFD3C4B4),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF825500),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDB0),
    onPrimaryContainer = Color(0xFF291800),
    secondary = Color(0xFF006C4F),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFF8F2),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFFF8F2),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFF0E0CF),
    onSurfaceVariant = Color(0xFF4F4539),
)

// Gauge/status colors shared by cards and gauges (not tied to dynamic scheme so
// status stays readable — never rely on color alone though: labels always shown).
object StatusColors {
    val normal = Color(0xFF34A853)
    val warning = Color(0xFFF9AB00)
    val critical = Color(0xFFEA4335)
    val unavailable = Color(0xFF9AA0A6)
}

@Composable
fun DigiDashTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
