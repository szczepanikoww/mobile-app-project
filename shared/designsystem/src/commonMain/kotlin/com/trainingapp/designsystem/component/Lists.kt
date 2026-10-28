package com.trainingapp.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing

/** A section title in the section-title style (not a small uppercase label), with optional trailing content. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.s, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = AppTheme.type.sectionTitle,
            color = AppTheme.colors.text,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        trailing()
    }
}

/** One white surface that groups rows separated by dividers, instead of one card per row. */
@Composable
fun GroupedSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.card,
        color = AppTheme.colors.surface,
        border = BorderStroke(1.dp, AppTheme.colors.outline),
    ) {
        Column(content = content)
    }
}

@Composable
fun RowDivider(modifier: Modifier = Modifier, startIndent: Dp = Spacing.l) {
    HorizontalDivider(modifier.padding(start = startIndent), color = AppTheme.colors.outline)
}

/** A list row: optional leading content, title, supporting line, optional badges and trailing content. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    leading: (@Composable () -> Unit)? = null,
    badges: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AppTheme.type.itemTitle, color = AppTheme.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (supporting != null) {
                Text(supporting, style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (badges != null) {
                Row(
                    Modifier.padding(top = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    content = badges,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * Exercise media thumbnail. Until exercise images are copied to our storage (delivery slice 4) and a trainer
 * video exists, it shows a neutral placeholder; it is decorative, so screen readers skip it.
 */
@Composable
fun ExerciseThumbnail(
    modifier: Modifier = Modifier,
    size: Dp = Sizes.thumbnail,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(AppShapes.thumbnail)
            .background(AppTheme.colors.subtleSurface),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.FitnessCenter,
            contentDescription = null,
            tint = AppTheme.colors.textSecondary,
            modifier = Modifier.size(size * 0.43f),
        )
    }
}
