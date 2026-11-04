package pl.trainerapp.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import pl.trainer.resources.*
import pl.trainer.ui.AppColors as SharedColors
import pl.trainer.ui.EmptyContent
import pl.trainer.ui.InfoBanner
import pl.trainer.ui.Layout

/*
 * Compatibility API for the main-shell screens (home, finance, chats, notifications, profile). Every component
 * renders with the shared pl.trainer.ui tokens and components, so these screens match the calendar and clients views.
 */

/** Outlined pill button, as the secondary actions of the calendar and clients views. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = Layout.pill,
        modifier = modifier.heightIn(min = Layout.target),
    ) {
        Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Section title with an optional count badge on the left and an optional action on the right. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = Layout.target),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Layout.small),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (badge != null) {
            Surface(color = SharedColors.softAccent, contentColor = SharedColors.onSoftAccent, shape = Layout.pill) {
                Text(badge, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Layout.small, vertical = 2.dp))
            }
        }
        Box(Modifier.weight(1f))
        action?.invoke()
    }
}

/** Offline and similar notices: the shared info banner. */
@Composable
fun InlineBanner(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.CloudOff) {
    InfoBanner(text, icon, modifier = modifier)
}

/** Empty list or screen: the shared empty state with an icon, explanation and optional action. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    icon: ImageVector = Icons.Outlined.Inbox,
    onAction: () -> Unit = {},
) {
    Box(modifier) { EmptyContent(icon, title, body, actionLabel, onAction) }
}

/** Recoverable load failure: the shared empty state with a retry action. */
@Composable
fun ErrorState(title: String, retryLabel: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) { EmptyContent(Icons.Outlined.CloudOff, title, stringResource(Res.string.screen_error_body), retryLabel, onRetry) }
}

/** Static placeholder block; no shimmer, to keep motion subtle. */
@Composable
fun SkeletonBlock(height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(Layout.card)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** Flat white surface with the shared fine outline (the look of [OutlinedTile]); [alpha] is kept for source compatibility. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.frosted(shape: Shape, alpha: Float = 1f): Modifier = this
    .background(Color.White, shape)
    .border(1.dp, SurfaceOutline, shape)
