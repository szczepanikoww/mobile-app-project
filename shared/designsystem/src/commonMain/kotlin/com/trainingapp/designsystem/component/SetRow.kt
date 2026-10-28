package com.trainingapp.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing

/** One value cell of a set row. The unit lives in the column header, not inside the box. */
data class SetCell(
    val value: String,
    val onValueChange: (String) -> Unit,
    val contentDescription: String,
    val placeholder: String = "",
    val keyboardType: KeyboardType = KeyboardType.Decimal,
    val isError: Boolean = false,
)

/** The column headers above set rows ("SERIA · KG · POWT. · RPE"). */
@Composable
fun SetTableHeader(
    setColumnLabel: String,
    valueColumnLabels: List<String>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            setColumnLabel,
            style = AppTheme.type.label,
            color = AppTheme.colors.textSecondary,
            modifier = Modifier.width(Sizes.minTouchTarget),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        valueColumnLabels.forEach {
            Text(
                it,
                style = AppTheme.type.label,
                color = AppTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A workout set row shared by the plan editor (planned values) and the workout logger (actual values).
 * [setLabel] is the set number or type marker ("1", "W", "D"); tapping it calls [onSetLabelClick] when given.
 * [trailing] holds the row's menu in the planner, or the completion checkbox in the logger.
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
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.colors
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(Sizes.minTouchTarget)
                .clip(CircleShape)
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
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (highlightLabel) colors.softAccent else colors.subtleSurface),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    setLabel,
                    style = AppTheme.type.bodyStrong,
                    color = if (highlightLabel) colors.onSoftAccent else colors.text,
                )
            }
        }
        cells.forEach { cell ->
            SetValueField(
                value = cell.value,
                onValueChange = cell.onValueChange,
                contentDescription = cell.contentDescription,
                placeholder = cell.placeholder,
                isError = cell.isError,
                keyboardType = cell.keyboardType,
                modifier = Modifier.weight(1f),
            )
        }
        trailing?.invoke()
    }
}
