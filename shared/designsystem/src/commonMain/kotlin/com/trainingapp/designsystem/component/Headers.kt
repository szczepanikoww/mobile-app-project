package com.trainingapp.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.trainingapp.designsystem.resources.Res
import com.trainingapp.designsystem.resources.ds_back
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/**
 * The header of a top-level destination, as on the trainer calendar/clients branch: a white bar of at least
 * 64 dp with the title in the bold item-title style. It grows with large text instead of clipping it.
 */
@Composable
fun TopLevelHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    HeaderBar(title, onBack = null, modifier, actions)
}

/** The header of a pushed screen: back arrow and title on the white bar, optional actions on the right. */
@Composable
fun DetailHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backEnabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    HeaderBar(title, onBack, modifier, actions, subtitle, backEnabled)
}

@Composable
private fun HeaderBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier,
    actions: @Composable RowScope.() -> Unit,
    subtitle: String? = null,
    backEnabled: Boolean = true,
) {
    Surface(modifier, color = AppTheme.colors.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(min = Sizes.headerHeight)
                .padding(horizontal = if (onBack == null) Spacing.gutter else Spacing.s, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, enabled = backEnabled) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.ds_back), tint = AppTheme.colors.text)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(title, style = AppTheme.type.itemTitle, color = AppTheme.colors.text, modifier = Modifier.semantics { heading() })
                if (subtitle != null) {
                    Text(subtitle, style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}
