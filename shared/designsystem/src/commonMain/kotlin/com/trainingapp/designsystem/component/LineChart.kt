package com.trainingapp.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing

/** One point of a [LineChart]. [label] is shown under the first and last point ("12 paź"). */
data class ChartPoint(val value: Float, val label: String)

/**
 * A minimal line chart drawn on a Canvas: a brand-coloured line with point markers over three faint guide lines.
 * Charts are not readable by screen readers, so the caller passes [summary], a full sentence that replaces the
 * drawing for accessibility ("Najlepsza seria wzrosła z 80 kg × 8 do 85 kg × 5 w 4 treningach").
 */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    summary: String,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    minLabel: String? = null,
    maxLabel: String? = null,
) {
    val colors = AppTheme.colors
    Column(modifier.clearAndSetSemantics { contentDescription = summary }, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (maxLabel != null) Text(maxLabel, style = AppTheme.type.label, color = colors.textSecondary)
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val inset = 8.dp.toPx()
            val w = size.width - inset * 2
            val h = size.height - inset * 2
            repeat(3) { i ->
                val y = inset + h * i / 2f
                drawLine(colors.outline, Offset(inset, y), Offset(inset + w, y), strokeWidth = 1.dp.toPx())
            }
            if (points.isEmpty()) return@Canvas
            val min = points.minOf { it.value }
            val max = points.maxOf { it.value }
            val span = (max - min).takeIf { it > 0f } ?: 1f
            val offsets = points.mapIndexed { i, p ->
                val x = if (points.size == 1) inset + w / 2f else inset + w * i / (points.size - 1)
                // A flat series is drawn in the middle rather than on the bottom line.
                val y = if (max == min) inset + h / 2f else inset + h * (1f - (p.value - min) / span)
                Offset(x, y)
            }
            if (offsets.size > 1) {
                val path = Path().apply {
                    moveTo(offsets.first().x, offsets.first().y)
                    offsets.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(path, colors.brandAccent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            offsets.forEachIndexed { i, o ->
                val last = i == offsets.lastIndex
                drawCircle(colors.surface, radius = if (last) 7.dp.toPx() else 5.dp.toPx(), center = o)
                drawCircle(colors.brandStrong, radius = if (last) 5.dp.toPx() else 3.dp.toPx(), center = o)
            }
        }
        if (minLabel != null) Text(minLabel, style = AppTheme.type.label, color = colors.textSecondary)
        if (points.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(points.first().label, style = AppTheme.type.label, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (points.size > 1) Text(points.last().label, style = AppTheme.type.label, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
