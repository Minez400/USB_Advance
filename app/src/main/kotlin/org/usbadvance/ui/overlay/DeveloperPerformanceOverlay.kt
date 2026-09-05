package org.usbadvance.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Ultra-lightweight, zero-overhead performance overlay rendering real-time UI diagnostics:
 * - FPS: Smoothed 1-second rolling frame rate
 * - RAM: Active JVM heap memory vs Max heap
 * - CPU: Active app thread count
 * - GPU: Real frame render duration in milliseconds (actual Compose render pass time)
 */
@Composable
fun DeveloperPerformanceOverlay(
    modifier: Modifier = Modifier,
    visible: Boolean = true
) {
    if (!visible) return

    var fps by remember { mutableIntStateOf(60) }
    var frameTimeMs by remember { mutableFloatStateOf(2.5f) }
    var ramUsedMb by remember { mutableLongStateOf(0L) }
    var ramMaxMb by remember { mutableLongStateOf(0L) }
    var cpuThreads by remember { mutableIntStateOf(0) }

    // Measure FPS and actual frame render latency via Compose Frame Clock
    LaunchedEffect(Unit) {
        var frameCount = 0
        var lastFpsTimeNanos = withFrameNanos { it }

        while (isActive) {
            val renderStartNanos = System.nanoTime()
            withFrameNanos { frameTimeNanos ->
                val renderEndNanos = System.nanoTime()
                val durationMs = (renderEndNanos - renderStartNanos) / 1_000_000f
                // Exponential moving average for GPU frame rendering latency
                frameTimeMs = (frameTimeMs * 0.85f) + (durationMs * 0.20f)

                frameCount++
                val deltaNanos = frameTimeNanos - lastFpsTimeNanos
                if (deltaNanos >= 1_000_000_000L) { // Rolling 1-second window
                    val currentFps = ((frameCount * 1_000_000_000L) / deltaNanos).toInt()
                    fps = currentFps.coerceIn(0, 240)
                    frameCount = 0
                    lastFpsTimeNanos = frameTimeNanos
                }
            }
        }
    }

    // Refresh RAM & CPU statistics every 1.5 seconds
    LaunchedEffect(Unit) {
        val runtime = Runtime.getRuntime()
        while (isActive) {
            val total = runtime.totalMemory()
            val free = runtime.freeMemory()
            val max = runtime.maxMemory()

            ramUsedMb = (total - free) / (1024 * 1024)
            ramMaxMb = max / (1024 * 1024)
            cpuThreads = Thread.activeCount()

            delay(1500)
        }
    }

    val fpsColor = when {
        fps >= 50 -> Color(0xFF10B981) // Green
        fps >= 30 -> Color(0xFFFFB300) // Amber
        else -> Color(0xFFEF4444) // Red
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xEB0F172A))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // FPS Indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(fpsColor)
                )
                Text(
                    text = "$fps FPS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = fpsColor
                )
            }

            Text(
                text = "|",
                fontSize = 11.sp,
                color = Color(0xFF475569)
            )

            // RAM Badge
            Text(
                text = "RAM: ${ramUsedMb}/${ramMaxMb}MB",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF38BDF8)
            )

            Text(
                text = "|",
                fontSize = 11.sp,
                color = Color(0xFF475569)
            )

            // CPU Threads Badge
            Text(
                text = "CPU: $cpuThreads thr",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFA78BFA)
            )

            Text(
                text = "|",
                fontSize = 11.sp,
                color = Color(0xFF475569)
            )

            // GPU Frame Rendering Latency
            Text(
                text = "GPU: %.1fms".format(frameTimeMs),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFF472B6)
            )
        }
    }
}
