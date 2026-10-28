package com.trainingapp.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing

/**
 * One value pill of a set row. [prefix] and [suffix] are drawn inside the pill next to the value
 * ("80 kg", "8 powt.", "@8", "120s"). [accent] uses the brand text colour, as RPE does in the reference.
 */
data class SetCell(
    val value: String,
    val onValueChange: (String) -> Unit,
    val contentDescription: String,
    val placeholder: String = "",
    val prefix: String? = null,
    val suffix: String? = null,
    val accent: Boolean = false,
    val keyboardType: KeyboardType = KeyboardType.Decimal,
    val isError: Boolean = false,
    /** Large bold value, as weight and reps in the reference. */
    val large: Boolean = false,
    /** Relative width of the cell; value columns are wider than RPE and rest. */
    val weight: Float = 1f,
)

/** The column headers above set rows ("Seria · Ciężar · Powt. · RPE · Przerwa"). */
@Composable
fun SetTableHeader(
    setColumnLabel: String,
    valueColumnLabels: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
    labelWidth: Dp = SetLabelWidth,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = Spacing.s).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderLabel(setColumnLabel, Modifier.width(labelWidth))
        valueColumnLabels.forEach { (label, weight) -> HeaderLabel(label, Modifier.weight(weight)) }
    }
}

@Composable
private fun HeaderLabel(text: String, modifier: Modifier) {
    Text(
        text,
        style = AppTheme.type.label,
        color = AppTheme.colors.textSecondary,
        modifier = modifier,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}

private val SetLabelWidth = 40.dp

/**
 * A workout set row shared by the plan creator (planned values) and the workout logger (actual values):
 * a grey rounded row with the set marker in a white circle and white value pills.
 * Tapping the marker calls [onSetLabelClick] when given (the planner cycles the set type).
 */
@Composable
fun SetRow(
    setLabel: String,
    setLabelDescription: String,
    cells: List<SetCell>,
    modifier: Modifier = Modifier,
    highlightLabel: Boolean = false,
    onSetLabelClick: (() -> Unit)? = null,
    onSetLabelClickLabel: String? = null,
) {
    val colors = AppTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.subtleSurface)
            .padding(horizontal = Spacing.s, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(SetLabelWidth)
                .clip(CircleShape)
                .background(if (highlightLabel) colors.softAccent else colors.surface)
                .then(
                    if (onSetLabelClick != null) {
                        Modifier.clickable(role = Role.Button, onClickLabel = onSetLabelClickLabel, onClick = onSetLabelClick)
                    } else {
                        Modifier
                    },
                )
                .semantics { contentDescription = setLabelDescription },
            contentAlignment = Alignment.Center,
        ) {
            Text(setLabel, style = AppTheme.type.bodyStrong, color = if (highlightLabel) colors.onSoftAccent else colors.text)
        }
        cells.forEach { cell -> SetValuePill(cell, Modifier.weight(cell.weight)) }
    }
}

@Composable
private fun SetValuePill(cell: SetCell, modifier: Modifier) {
    val colors = AppTheme.colors
    val valueColor = if (cell.accent) colors.brandStrong else colors.text
    val valueStyle = if (cell.large) AppTheme.type.statValue.copy(fontSize = 20.sp, lineHeight = 24.sp) else AppTheme.type.bodyStrong
    BasicTextField(
        value = cell.value,
        onValueChange = cell.onValueChange,
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = cell.contentDescription },
        singleLine = true,
        textStyle = valueStyle.copy(color = valueColor, textAlign = TextAlign.End),
        cursorBrush = SolidColor(colors.brandStrong),
        keyboardOptions = KeyboardOptions(keyboardType = cell.keyboardType, imeAction = ImeAction.Next),
        decorationBox = { inner ->
            Row(
                Modifier
                    .clip(AppShapes.pill)
                    .background(colors.surface)
                    .border(BorderStroke(if (cell.isError) 2.dp else 0.dp, if (cell.isError) colors.onErrorContainer else Color.Transparent), AppShapes.pill)
                    .padding(horizontal = Spacing.s, vertical = Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (cell.prefix != null) Text(cell.prefix, style = valueStyle, color = valueColor)
                Box(Modifier.weight(1f, fill = false), contentAlignment = Alignment.CenterEnd) {
                    if (cell.value.isEmpty() && cell.placeholder.isNotEmpty()) {
                        Text(cell.placeholder, style = valueStyle, color = colors.textSecondary, maxLines = 1)
                    }
                    inner()
                }
                if (cell.suffix != null) {
                    Text(
                        cell.suffix,
                        style = AppTheme.type.overline.copy(letterSpacing = 0.sp),
                        color = colors.textSecondary,
                        modifier = Modifier.padding(start = 2.dp),
                        maxLines = 1,
                    )
                }
            }
        },
    )
}
