package com.lifeos.ai.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lifeos.ai.core.theme.LocalLifeColors

/**
 * LifeOS custom line-icon set (24dp grid, 1.8dp rounded strokes). Each icon carries a small
 * "node" accent — the same circuit-node motif as the app icon — so the UI reads as one system.
 * Drawn in white; tint them with [androidx.compose.material3.Icon] or [GradientIcon].
 */
object LifeIcons {
    val Home: ImageVector by lazy {
        lineIcon("Home") {
            stroke("M3.5,11.2L12,4.2L20.5,11.2")
            stroke("M6,9.6V18.8A1.2,1.2 0 0,0 7.2,20H16.8A1.2,1.2 0 0,0 18,18.8V9.6")
            dot(12f, 14.6f, 1.6f)
        }
    }

    /** AI assistant: four-point spark with a companion sparkle. */
    val Spark: ImageVector by lazy {
        lineIcon("Spark") {
            stroke("M11,4.5C11.57,9.8 14.2,12.43 19.5,13C14.2,13.57 11.57,16.2 11,21.5C10.43,16.2 7.8,13.57 2.5,13C7.8,12.43 10.43,9.8 11,4.5Z", 1.7f)
            fill("M18.6,2.4C18.75,4.15 19.25,4.65 21,4.8C19.25,4.95 18.75,5.45 18.6,7.2C18.45,5.45 17.95,4.95 16.2,4.8C17.95,4.65 18.45,4.15 18.6,2.4Z")
        }
    }

    val Tools: ImageVector by lazy {
        lineIcon("Tools") {
            stroke("M5.6,3.8H8.4A1.8,1.8 0 0,1 10.2,5.6V8.4A1.8,1.8 0 0,1 8.4,10.2H5.6A1.8,1.8 0 0,1 3.8,8.4V5.6A1.8,1.8 0 0,1 5.6,3.8Z")
            stroke("M15.6,3.8H18.4A1.8,1.8 0 0,1 20.2,5.6V8.4A1.8,1.8 0 0,1 18.4,10.2H15.6A1.8,1.8 0 0,1 13.8,8.4V5.6A1.8,1.8 0 0,1 15.6,3.8Z")
            stroke("M5.6,13.8H8.4A1.8,1.8 0 0,1 10.2,15.6V18.4A1.8,1.8 0 0,1 8.4,20.2H5.6A1.8,1.8 0 0,1 3.8,18.4V15.6A1.8,1.8 0 0,1 5.6,13.8Z")
            stroke("M13.8,17A3.2,3.2 0 1,1 20.2,17A3.2,3.2 0 1,1 13.8,17Z")
            dot(17f, 17f, 1.1f)
        }
    }

    val Activity: ImageVector by lazy {
        lineIcon("Activity") {
            stroke("M2.8,12.5H6.6L9.1,6.6L14,17.6L16.4,12.5H18.6")
            dot(20.6f, 12.5f, 1.7f)
        }
    }

    val Profile: ImageVector by lazy {
        lineIcon("Profile") {
            stroke("M8.4,8.2A3.6,3.6 0 1,1 15.6,8.2A3.6,3.6 0 1,1 8.4,8.2Z")
            stroke("M4.8,19.8C5.6,16.2 8.4,14.4 12,14.4C15.6,14.4 18.4,16.2 19.2,19.8")
        }
    }

    /** Task: check mark inside the brand hexagon. */
    val Task: ImageVector by lazy {
        lineIcon("Task") {
            stroke("M12,3.2L19.62,7.6V16.4L12,20.8L4.38,16.4V7.6Z")
            stroke("M8.4,12.1L10.9,14.6L15.7,9.6")
        }
    }

    /** Reminder: bell with a live notification node. */
    val Reminder: ImageVector by lazy {
        lineIcon("Reminder") {
            stroke("M4.5,17.4H19.5L18,15.4V11A6,6 0 0,0 6,11V15.4Z")
            stroke("M10,20.2A2,2 0 0,0 14,20.2")
            dot(19f, 5f, 1.8f)
        }
    }

    /** Expense: wallet with a clasp node. */
    val Expense: ImageVector by lazy {
        lineIcon("Expense") {
            stroke("M4,8A2.5,2.5 0 0,1 6.5,5.5H17.5A2.5,2.5 0 0,1 20,8V16.5A2.5,2.5 0 0,1 17.5,19H6.5A2.5,2.5 0 0,1 4,16.5Z")
            stroke("M20,10.2H16.4A2,2 0 0,0 16.4,14.2H20")
            dot(16.6f, 12.2f, 1f)
        }
    }

    val Note: ImageVector by lazy {
        lineIcon("Note") {
            stroke("M6.5,3.5H14.2L19,8.3V18.5A2,2 0 0,1 17,20.5H6.5A2,2 0 0,1 4.5,18.5V5.5A2,2 0 0,1 6.5,3.5Z")
            stroke("M14.2,3.5V7A1.3,1.3 0 0,0 15.5,8.3H19")
            stroke("M8,12.4H15.5M8,16H12.8")
        }
    }

    val Mic: ImageVector by lazy {
        lineIcon("Mic") {
            stroke("M9,6.6A3,3 0 0,1 15,6.6V11.4A3,3 0 0,1 9,11.4Z")
            stroke("M5.6,11.2A6.4,6.4 0 0,0 18.4,11.2")
            stroke("M12,17.6V20.6M9.5,20.6H14.5")
        }
    }

    val MicOff: ImageVector by lazy {
        lineIcon("MicOff") {
            stroke("M9,6.6A3,3 0 0,1 15,6.6V11.4A3,3 0 0,1 9,11.4Z")
            stroke("M5.6,11.2A6.4,6.4 0 0,0 18.4,11.2")
            stroke("M12,17.6V20.6M9.5,20.6H14.5")
            stroke("M4.4,4.2L19.6,19.4")
        }
    }

    /** Chat bubble with a typing indicator. */
    val Chat: ImageVector by lazy {
        lineIcon("Chat") {
            stroke("M5.4,4.6H18.6A2,2 0 0,1 20.6,6.6V14.4A2,2 0 0,1 18.6,16.4H11.2L7,19.8V16.4H5.4A2,2 0 0,1 3.4,14.4V6.6A2,2 0 0,1 5.4,4.6Z")
            dot(8.4f, 10.5f, 1.15f)
            dot(12f, 10.5f, 1.15f)
            dot(15.6f, 10.5f, 1.15f)
        }
    }

    val Documents: ImageVector by lazy {
        lineIcon("Documents") {
            stroke("M3.5,7A2,2 0 0,1 5.5,5H9.4L11.4,7H18.5A2,2 0 0,1 20.5,9V17A2,2 0 0,1 18.5,19H5.5A2,2 0 0,1 3.5,17Z")
            stroke("M3.5,10H20.5")
            dot(17f, 14.6f, 1.3f)
        }
    }
}

private class LineIconScope(private val builder: ImageVector.Builder) {
    fun stroke(pathData: String, width: Float = 1.8f) {
        builder.addPath(
            pathData = addPathNodes(pathData),
            stroke = SolidColor(Color.White),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    fun fill(pathData: String) {
        builder.addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.White))
    }

    fun dot(cx: Float, cy: Float, r: Float) =
        fill("M${cx - r},${cy}A$r,$r 0 1,1 ${cx + r},${cy}A$r,$r 0 1,1 ${cx - r},${cy}Z")
}

private fun lineIcon(name: String, block: LineIconScope.() -> Unit): ImageVector {
    val builder = ImageVector.Builder(
        name = "LifeIcons.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    LineIconScope(builder).block()
    return builder.build()
}

/** An icon filled with a gradient instead of a flat tint. */
@Composable
fun GradientIcon(
    icon: ImageVector,
    brush: Brush,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = Color.White,
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(brush = brush, blendMode = BlendMode.SrcIn)
            },
    )
}

/**
 * Futuristic icon tile: tinted glass fill, a thin neon gradient border and a gradient-lit icon.
 * Kept glow-free so icons stay crisp; works in both dark and light themes.
 */
@Composable
fun NeonIcon(
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = size * 0.52f,
    contentDescription: String? = null,
) {
    val dark = LocalLifeColors.current.isDark
    val shape = RoundedCornerShape(size * 0.32f)
    val fill = Brush.linearGradient(
        if (dark) listOf(accent.copy(alpha = 0.26f), accent.copy(alpha = 0.06f))
        else listOf(accent.copy(alpha = 0.18f), accent.copy(alpha = 0.05f)),
    )
    val border = Brush.linearGradient(listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.12f)))
    val iconBrush = Brush.linearGradient(
        if (dark) listOf(lerp(accent, Color.White, 0.5f), accent)
        else listOf(accent, lerp(accent, Color.Black, 0.25f)),
    )
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape),
        contentAlignment = Alignment.Center,
    ) {
        GradientIcon(icon, iconBrush, contentDescription, Modifier.size(iconSize))
    }
}
