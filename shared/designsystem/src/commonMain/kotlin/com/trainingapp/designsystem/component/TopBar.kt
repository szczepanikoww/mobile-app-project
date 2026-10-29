package com.trainingapp.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.trainingapp.designsystem.resources.Res
import com.trainingapp.designsystem.resources.ds_back
import com.trainingapp.designsystem.resources.ds_close
import com.trainingapp.designsystem.resources.ds_more_options
import com.trainingapp.designsystem.theme.AppTheme
import org.jetbrains.compose.resources.stringResource

enum class TopBarNavigation { Back, Close }

/**
 * The shared top app bar for detail screens: one navigation icon, the title once, and optional actions.
 * Global controls (chat, notifications, avatar) belong to the app shell, not here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onNavigate: (() -> Unit)?,
    modifier: Modifier = Modifier,
    navigation: TopBarNavigation = TopBarNavigation.Back,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(title, style = AppTheme.type.itemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = AppTheme.type.compactBody,
                        color = AppTheme.colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            if (onNavigate != null) {
                IconButton(onClick = onNavigate) {
                    when (navigation) {
                        TopBarNavigation.Back -> Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.ds_back))
                        TopBarNavigation.Close -> Icon(Icons.Outlined.Close, stringResource(Res.string.ds_close))
                    }
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = AppTheme.colors.background,
            scrolledContainerColor = AppTheme.colors.background,
            titleContentColor = AppTheme.colors.text,
            navigationIconContentColor = AppTheme.colors.text,
            actionIconContentColor = AppTheme.colors.text,
        ),
    )
}

data class MenuAction(
    val label: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** An overflow ("⋮") button with a menu. Used in top bars and on list rows. */
@Composable
fun OverflowMenu(
    actions: List<MenuAction>,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(Res.string.ds_more_options),
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action ->
                val color = if (action.destructive) AppTheme.colors.onErrorContainer else AppTheme.colors.text
                DropdownMenuItem(
                    text = { Text(action.label, style = AppTheme.type.body, color = color) },
                    leadingIcon = action.icon?.let { icon -> { Icon(icon, contentDescription = null, tint = color) } },
                    enabled = action.enabled,
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                )
            }
        }
    }
}
