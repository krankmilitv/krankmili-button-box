package com.krankmili.buttonbox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

/**
 * Icons are plain 24x24 SVG stroke paths drawn on a Canvas. This avoids the large
 * material-icons-extended dependency and keeps the icons identical to the mockup.
 * The server picks an icon by name via the "icon" field of a button.
 */
private val ICON_PATHS = mapOf(
    "arrow_up" to "M12 19V5M5 12l7-7 7 7",
    "arrow_down" to "M12 5v14M19 12l-7 7-7-7",
    "power" to "M12 3v9M6.3 6.6a8 8 0 1 0 11.4 0",
    "bus" to "M5 17V6a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v11M5 17h14M5 11h14M8 20v-3M16 20v-3",
    "link" to "M10 14a4 4 0 0 1 0-6l2-2a4 4 0 0 1 6 6l-1 1M14 10a4 4 0 0 1 0 6l-2 2a4 4 0 0 1-6-6l1-1",
    "unlink" to "M10 14a4 4 0 0 1 0-6l2-2a4 4 0 0 1 6 6l-1 1M14 10a4 4 0 0 1 0 6l-2 2a4 4 0 0 1-6-6l1-1M4 4l16 16",
    "axle_down" to "M12 2v7M8 6l4 4 4-4M12 21a5 5 0 1 0 0-10 5 5 0 0 0 0 10z",
    "axle_up" to "M12 9V2M8 5l4-4 4 4M12 22a5 5 0 1 0 0-10 5 5 0 0 0 0 10z",
    "flash" to "M13 2 4 14h7l-1 8 9-12h-7l1-8z",
    "headlight" to "M10 6a6 6 0 0 0 0 12h1V6h-1zM15 8h6M15 12h6M15 16h6",
    "left" to "M19 12H5M12 5l-7 7 7 7",
    "right" to "M5 12h14M12 5l7 7-7 7",
    "parking" to "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM10 16V8h3a2.5 2.5 0 0 1 0 5h-3",
    "susp_up" to "M5 20h14M12 16V4M7 9l5-5 5 5",
    "susp_down" to "M5 4h14M12 8v12M7 15l5 5 5-5",
    "check" to "M5 13l4 4L19 7",
    "close" to "M6 6l12 12M18 6L6 18",
    "service" to "M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.4 2.4-2.6-2.6 2.4-2.4z",
    "reset" to "M3 12a9 9 0 1 0 3-6.7M3 4v5h5",
    "wiper" to "M3 17a9 9 0 0 1 18 0M12 17l6-9",
    "bulb" to "M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5c.7.7 1 1.5 1 2.5h6c0-1 .3-1.8 1-2.5A6 6 0 0 0 12 3z",
    "roof" to "M3 15h18M5 15V10a3 3 0 0 1 3-3h8a3 3 0 0 1 3 3v5M8 19v-4M16 19v-4M12 2v2M6 3.5l1 1.5M18 3.5l-1 1.5",
    "beacon" to "M12 3v2M5 6l1.5 1.5M19 6l-1.5 1.5M7 17v-3a5 5 0 0 1 10 0v3M5 17h14M6 21h12",
    "eye" to "M1 12s4-7 11-7 11 7 11 7-4 7-11 7S1 12 1 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6",
    "eye_off" to "M17.94 17.94A10.07 10.07 0 0 1 12 19c-7 0-11-7-11-7a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 7 11 7a18.5 18.5 0 0 1-2.16 3.19M1 1l22 22",
    "mic" to "M12 14a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v5a3 3 0 0 0 3 3zM19 11a7 7 0 0 1-14 0M12 18v3",
    "mic_off" to "M12 14a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v5a3 3 0 0 0 3 3zM19 11a7 7 0 0 1-14 0M12 18v3M1 1l22 22",
    "hazard" to "M12 3L2 21h20L12 3zM12 10v5M12 18v.01",
)

@Composable
fun SvgIcon(name: String, size: Dp, color: Color, modifier: Modifier = Modifier) {
    val data = ICON_PATHS[name] ?: return
    val path = remember(data) { PathParser().parsePathString(data).toPath() }
    Canvas(modifier.size(size)) {
        val factor = this.size.minDimension / 24f
        // Stroke width is in the scaled 24-unit space, so 2f = the mockup's 2px stroke.
        scale(factor, pivot = Offset.Zero) {
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}
