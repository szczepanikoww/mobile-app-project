package com.trainingapp.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.resources.Res
import com.trainingapp.designsystem.resources.ds_back
import com.trainingapp.designsystem.resources.ds_messages
import com.trainingapp.designsystem.resources.ds_notifications
import com.trainingapp.designsystem.resources.ds_profile
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/**
 * The header of a top-level destination, as in the Stitch Klienci and Start screens: the title on the left,
 * then messages, notifications and the profile avatar. Global controls live only here, never on detail screens.
 */
@Composable
fun TopLevelHeader(
    title: String,
    onMessages: () -> Unit,
    onNotifications: () -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.background)
            .statusBarsPadding()
            .padding(start = Spacing.gutter, end = Spacing.l, top = Spacing.s, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = AppTheme.type.screenTitle,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        IconButton(onClick = onMessages) { Icon(Icons.Outlined.ChatBubbleOutline, stringResource(Res.string.ds_messages), tint = colors.text) }
        IconButton(onClick = onNotifications) { Icon(Icons.Outlined.NotificationsNone, stringResource(Res.string.ds_notifications), tint = colors.text) }
        IconButton(
            onClick = onProfile,
            colors = IconButtonDefaults.iconButtonColors(containerColor = colors.brandStrong, contentColor = colors.onBrandStrong),
        ) { Icon(Icons.Outlined.PersonOutline, stringResource(Res.string.ds_profile)) }
    }
}

/** A white circular icon button with a soft shadow ("Profil trenera": back and share). */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.shadow(6.dp, CircleShape, ambientColor = Color(0x14000000), spotColor = Color(0x1F000000)).clip(CircleShape)) {
        IconButton(
            onClick = onClick,
            colors = IconButtonDefaults.iconButtonColors(containerColor = AppTheme.colors.surface, contentColor = AppTheme.colors.text),
        ) { Icon(icon, contentDescription) }
    }
}

/**
 * The header of a pushed detail screen ("Profil trenera"): a round back button, a centred uppercase title and
 * optional round actions on the right.
 */
@Composable
fun DetailHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(AppTheme.colors.background)
            .statusBarsPadding()
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        CircleIconButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.ds_back), onBack)
        Text(
            title.uppercase(),
            style = AppTheme.type.overline,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (actions != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), content = actions)
        } else {
            // Balances the back button so the title stays centred.
            Box(Modifier.size(48.dp))
        }
    }
}

/** "38 dostępnych" (accent) or "● 24 Podopiecznych" (white with a status dot). */
@Composable
fun CountPill(text: String, modifier: Modifier = Modifier, dotColor: Color? = null) {
    val colors = AppTheme.colors
    Row(
        modifier
            .clip(CircleShape)
            .background(if (dotColor == null) colors.softAccent else colors.surface)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (dotColor != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Text(text, style = AppTheme.type.bodyStrong, color = if (dotColor == null) colors.brandAccent else colors.text)
    }
}
