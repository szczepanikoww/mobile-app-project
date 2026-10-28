package com.trainingapp.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.resources.Res
import com.trainingapp.designsystem.resources.ds_loading
import com.trainingapp.designsystem.resources.ds_retry
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import org.jetbrains.compose.resources.stringResource

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.ds_loading)
    Box(modifier.fillMaxSize().semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AppTheme.colors.brandStrong)
    }
}

/** A short explanation and the applicable next action. */
@Composable
fun EmptyState(
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    icon: ImageVector? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = AppTheme.colors.textSecondary, modifier = Modifier.size(32.dp))
        Text(title, style = AppTheme.type.itemTitle, color = AppTheme.colors.text, textAlign = TextAlign.Center)
        if (message != null) {
            Text(message, style = AppTheme.type.body, color = AppTheme.colors.textSecondary, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            SecondaryButton(actionLabel, onAction, modifier = Modifier.padding(top = Spacing.s))
        }
    }
}

/** What failed, in plain words, and a retry when retrying is safe. */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    EmptyState(
        title = message,
        message = null,
        modifier = modifier,
        actionLabel = onRetry?.let { stringResource(Res.string.ds_retry) },
        onAction = onRetry,
        icon = Icons.Outlined.ErrorOutline,
    )
}

enum class MessageTone { Info, Offline, Warning, Error, Success }

/** An inline banner (offline, not saved, conflicts). It is announced politely when it appears. */
@Composable
fun InlineMessage(
    text: String,
    modifier: Modifier = Modifier,
    tone: MessageTone = MessageTone.Info,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val (container, content, icon) = when (tone) {
        MessageTone.Info -> Triple(colors.subtleSurface, colors.text, Icons.Outlined.Info)
        MessageTone.Offline -> Triple(colors.subtleSurface, colors.text, Icons.Outlined.CloudOff)
        MessageTone.Warning -> Triple(colors.warningContainer, colors.onWarningContainer, Icons.Outlined.WarningAmber)
        MessageTone.Error -> Triple(colors.errorContainer, colors.onErrorContainer, Icons.Outlined.ErrorOutline)
        MessageTone.Success -> Triple(colors.successContainer, colors.onSuccessContainer, Icons.Outlined.Info)
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = AppShapes.card,
        color = container,
        contentColor = content,
    ) {
        Column(Modifier.padding(Spacing.m)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.Top) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(text, style = AppTheme.type.compactBody, modifier = Modifier.weight(1f))
            }
            if (action != null) Box(Modifier.align(Alignment.End)) { action() }
        }
    }
}
