package com.lifeos.ai.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lifeos.ai.core.theme.LifeColors
import com.lifeos.ai.core.theme.LocalLifeColors

data class ChartSlice(val label: String, val value: Long, val color: Color)

/** Donut chart for category breakdown. Values are real totals; the legend shows exact amounts. */
@Composable
fun DonutChart(
    slices: List<ChartSlice>,
    centerTitle: String,
    centerValue: String,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
) {
    val total = slices.sumOf { it.value }.coerceAtLeast(1)
    val progress = remember(slices) { Animatable(0f) }
    LaunchedEffect(slices) { progress.animateTo(1f, tween(700)) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(size).semantics { contentDescription = "$centerTitle $centerValue" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = this.size.minDimension * 0.14f
            val r = this.size.minDimension / 2 - stroke / 2
            val topLeft = Offset(center.x - r, center.y - r)
            val arcSize = Size(r * 2, r * 2)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            var start = -90f
            slices.forEach { s ->
                val sweep = 360f * s.value / total * progress.value
                if (sweep > 0.5f) {
                    drawArc(s.color, start, (sweep - 1.5f).coerceAtLeast(0.5f), false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                }
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerTitle, style = MaterialTheme.typography.labelMedium, color = LocalLifeColors.current.textSecondary)
            Text(centerValue, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun ChartLegend(slices: List<ChartSlice>, format: (Long) -> String, modifier: Modifier = Modifier) {
    val total = slices.sumOf { it.value }.coerceAtLeast(1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        slices.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(s.color))
                Spacer(Modifier.width(8.dp))
                Text(s.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${(s.value * 100 / total)}%", style = MaterialTheme.typography.labelMedium, color = LocalLifeColors.current.textSecondary)
                Spacer(Modifier.width(10.dp))
                Text(format(s.value), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

data class BarGroup(val label: String, val values: List<Long>)

/** Grouped vertical bars (e.g. income vs expense per month). */
@Composable
fun GroupedBarChart(
    groups: List<BarGroup>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
) {
    val max = groups.flatMap { it.values }.maxOrNull()?.coerceAtLeast(1) ?: 1
    val progress = remember(groups) { Animatable(0f) }
    LaunchedEffect(groups) { progress.animateTo(1f, tween(700)) }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(height),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            groups.forEach { g ->
                Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    g.values.forEachIndexed { i, v ->
                        val fraction = (v.toFloat() / max * progress.value).coerceIn(0f, 1f)
                        Box(
                            Modifier.width(if (g.values.size > 1) 10.dp else 18.dp)
                                .fillMaxHeight(fraction.coerceAtLeast(0.01f))
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(colors[i % colors.size]),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            groups.forEach { g ->
                Text(
                    g.label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                    color = LocalLifeColors.current.textSecondary, modifier = Modifier.width(40.dp),
                )
            }
        }
    }
}

/** Horizontal progress bar with a gradient fill (budget usage, productivity). */
@Composable
fun GradientProgressBar(fraction: Float, modifier: Modifier = Modifier, overColor: Color = LifeColors.Error) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val f = fraction.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(track)) {
        Box(
            Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                .background(if (fraction > 1f) androidx.compose.ui.graphics.Brush.linearGradient(listOf(overColor, overColor)) else LocalLifeColors.current.brandGradient),
        )
    }
}
