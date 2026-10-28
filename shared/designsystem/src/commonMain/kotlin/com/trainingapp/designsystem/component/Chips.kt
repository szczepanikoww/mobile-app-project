package com.trainingapp.designsystem.component

import androidx.compose.foundation.BorderStroke
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
 * A filter or choice chip as in the Stitch references: a white pill, filled brand orange with white text when
 * selected ("Cena ▾", "Wszyscy (24)"). [showChevron] marks a chip that opens a sheet.
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
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = AppTheme.type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier.defaultMinSize(minHeight = Sizes.minTouchTarget),
        shape = AppShapes.pill,
        leadingIcon = leadingIcon?.let { icon -> { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) } },
        trailingIcon = if (showChevron) {
            { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surface,
            labelColor = colors.text,
            iconColor = colors.text,
            selectedContainerColor = colors.brandAccent,
            selectedLabelColor = colors.onBrandStrong,
            selectedLeadingIconColor = colors.onBrandStrong,
            selectedTrailingIconColor = colors.onBrandStrong,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = colors.outline,
            selectedBorderColor = colors.brandAccent,
        ),
    )
}

/**
 * Pill tabs as in the Stitch Postępy screen ("Ćwiczenia | Pomiary | …"): the selected tab is filled brand orange
 * with white text, the others are white pills. Each option is a selectable tab for screen readers.
 */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Row(
        modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = Sizes.minTouchTarget)
                    .background(if (selected) colors.brandAccent else colors.surface, AppShapes.pill)
                    .clip(AppShapes.pill)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = Spacing.m, vertical = Spacing.s),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = AppTheme.type.bodyStrong,
                    color = if (selected) colors.onBrandStrong else colors.text,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
        border = if (tone == StatusTone.Neutral) BorderStroke(1.dp, colors.outline) else null,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(text, style = AppTheme.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
