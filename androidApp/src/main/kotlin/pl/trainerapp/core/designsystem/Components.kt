package pl.trainerapp.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val MinTarget = 48.dp

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
        shape = AppShapes.pill,
        modifier = modifier.heightIn(min = MinTarget),
        border = BorderStroke(1.dp, AppTheme.colors.border),
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
    val colors = AppTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.brand,
                    modifier = Modifier
                        .clip(AppShapes.pill)
                        .background(colors.softBrand)
                        .border(1.dp, colors.brandBorder, AppShapes.pill)
                        .padding(horizontal = Spacing.sm, vertical = 2.dp),
                )
            }
        }
        action?.invoke()
    }
}

@Composable
fun InlineBanner(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.CloudOff) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShapes.card)
            .background(colors.softBrand)
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(icon, contentDescription = null, tint = colors.warning)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.warning)
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null) {
            Spacer(Modifier.height(Spacing.sm))
            SecondaryButton(actionLabel, onAction)
        }
    }
}

@Composable
fun ErrorState(title: String, retryLabel: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        SecondaryButton(retryLabel, onRetry)
    }
}

/** Static skeleton block; no shimmer, to keep motion subtle. */
@Composable
fun SkeletonBlock(height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(AppShapes.card)
            .background(AppTheme.colors.border),
    )
}

enum class Corner(val alignment: Alignment) {
    TopStart(Alignment.TopStart),
    TopEnd(Alignment.TopEnd),
    BottomStart(Alignment.BottomStart),
    BottomEnd(Alignment.BottomEnd),
}

/**
 * Signature translucent orange quarter circle that overlaps a media corner. The arc bulges toward the
 * media centre, so a quarter circle in [corner] is rounded on the opposite-facing corner.
 */
@Composable
fun BoxScope.CornerArc(corner: Corner, size: Dp) {
    val inward = when (corner) {
        Corner.TopStart -> RoundedCornerShape(bottomEndPercent = 100)
        Corner.TopEnd -> RoundedCornerShape(bottomStartPercent = 100)
        Corner.BottomStart -> RoundedCornerShape(topEndPercent = 100)
        Corner.BottomEnd -> RoundedCornerShape(topStartPercent = 100)
    }
    Box(
        modifier = Modifier
            .align(corner.alignment)
            .size(size)
            .background(AppTheme.colors.brand.copy(alpha = 0.3f), inward)
            .clearAndSetSemantics { },
    )
}

/** Semi-transparent white pill/panel surface used over photos (no backdrop blur on Android Compose). */
fun Modifier.frosted(shape: androidx.compose.ui.graphics.Shape, alpha: Float = 0.82f): Modifier = this
    .shadow(4.dp, shape)
    .background(Color.White.copy(alpha = alpha), shape)
    .border(1.dp, Color.White.copy(alpha = 0.9f), shape)

/** Frosted white panel docked to the bottom of a photo card. */
@Composable
fun BoxScope.GlassPanel(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(10.dp)
            .fillMaxWidth()
            .frosted(AppShapes.panel, alpha = 0.78f)
            .padding(Spacing.md),
    ) { content() }
}
