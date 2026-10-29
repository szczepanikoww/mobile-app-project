package com.trainingapp.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.outlined.Check
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing

/**
 * A filter or choice chip as on the trainer calendar/clients branch: outlined when off, soft accent with dark
 * text and a check mark when selected. [showChevron] marks a chip that opens a sheet.
 */
@Composable
fun AppFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showChevron: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val colors = AppTheme.colors
    val icon = leadingIcon ?: if (selected && !showChevron) Icons.Outlined.Check else null
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = AppTheme.type.button, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier.defaultMinSize(minHeight = Sizes.minTouchTarget),
        shape = RoundedCornerShape(12.dp),
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)) } },
        trailingIcon = if (showChevron) {
            { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surface,
            labelColor = colors.textSecondary,
            iconColor = colors.textSecondary,
            selectedContainerColor = colors.softAccent,
            selectedLabelColor = colors.onSoftAccent,
            selectedLeadingIconColor = colors.onSoftAccent,
            selectedTrailingIconColor = colors.onSoftAccent,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = colors.outline,
            selectedBorderColor = colors.softAccent,
        ),
    )
}

/**
 * The inset pill switcher of the trainer calendar/clients branch ("Dzień | Tydzień", "Podopieczni | Archiwum"):
 * a rounded subtle track with an outline, the selected tab filled with the strong brand colour. It scrolls
 * horizontally instead of squeezing labels.
 */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Surface(modifier = modifier, shape = AppShapes.pill, color = colors.subtleSurface, border = BorderStroke(1.dp, colors.outline)) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(Spacing.xs)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .defaultMinSize(minHeight = Sizes.minTouchTarget)
                        .clip(AppShapes.pill)
                        .background(if (selected) colors.brandStrong else colors.subtleSurface)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
                        .padding(horizontal = Spacing.xl, vertical = Spacing.s),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option,
                        style = AppTheme.type.button,
                        color = if (selected) colors.onBrandStrong else colors.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

enum class StatusTone { Neutral, Accent, Success, Warning, Error }

/** A small status label. Status is always text (and optionally an icon), never colour alone. */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.Neutral,
    icon: ImageVector? = null,
) {
    val colors = AppTheme.colors
    val (container, content) = when (tone) {
        StatusTone.Neutral -> colors.subtleSurface to colors.textSecondary
        StatusTone.Accent -> colors.softAccent to colors.onSoftAccent
        StatusTone.Success -> colors.successContainer to colors.onSuccessContainer
        StatusTone.Warning -> colors.warningContainer to colors.onWarningContainer
        StatusTone.Error -> colors.errorContainer to colors.onErrorContainer
    }
    Surface(
        shape = AppShapes.pill,
        color = container,
        contentColor = content,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(text, style = AppTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
