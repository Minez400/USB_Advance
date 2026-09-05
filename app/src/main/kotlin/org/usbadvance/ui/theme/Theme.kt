package org.usbadvance.ui.theme

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

// USB Advance Modern Color Palette (High-Tech & Expressive Theme)
val TechCyan = Color(0xFF00E5FF)
val TechBlue = Color(0xFF00B0FF)
val NeonGreen = Color(0xFF00E676)
val CyberAmber = Color(0xFFFFB300)
val DarkBg = Color(0xFF0B0F19)
val DarkSurface = Color(0xFF131A29)
val DarkSurfaceVariant = Color(0xFF1A2438)
val DarkOutline = Color(0xFF2E3D5B)
val CrimsonError = Color(0xFFFF3D57)

private val DarkColorScheme = darkColorScheme(
    primary = TechCyan,
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF80F2FF),
    secondary = NeonGreen,
    onSecondary = Color(0xFF003915),
    secondaryContainer = Color(0xFF005322),
    onSecondaryContainer = Color(0xFF6CFF90),
    tertiary = CyberAmber,
    background = DarkBg,
    onBackground = Color(0xFFE2E8F0),
    surface = DarkSurface,
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = DarkOutline,
    error = CrimsonError,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006876),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA1EFFF),
    onPrimaryContainer = Color(0xFF001F25),
    secondary = Color(0xFF006D30),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF7BFA9B),
    onSecondaryContainer = Color(0xFF00210A),
    tertiary = Color(0xFF825500),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

@Composable
fun UsbAdvanceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Defaults to false to maintain consistent cyber cyan technological aesthetic
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> DarkColorScheme // Utility block storage tools excel in dark mode aesthetics
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
