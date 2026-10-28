package com.trainingapp.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing

data class NavItem(val label: String, val icon: ImageVector, val selected: Boolean, val onClick: () -> Unit)

/**
 * The role-aware bottom navigation of the Stitch references: a white bar with rounded top corners, uppercase
 * labels, the selected item in brand orange, and a raised orange circle in the middle ("+ PLAN" for trainers,
 * "LIVE" for clients). [center] is that circle; the other items are split evenly around it.
 */
@Composable
fun AppBottomNavigation(
    items: List<NavItem>,
    center: NavItem,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val half = items.size / 2
    val barShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .padding(top = 20.dp)
                .fillMaxWidth()
                .shadow(16.dp, barShape, ambientColor = Color(0x14000000), spotColor = Color(0x1F000000))
                .clip(barShape)
                .background(colors.surface)
                .navigationBarsPadding()
                .padding(horizontal = Spacing.s, vertical = Spacing.s)
                .selectableGroup(),
            verticalAlignment = Alignment.Bottom,
        ) {
            items.take(half).forEach { NavBarItem(it, Modifier.weight(1f)) }
            CenterLabel(center, Modifier.weight(1f))
            items.drop(half).forEach { NavBarItem(it, Modifier.weight(1f)) }
        }
        // The raised circle overlaps the top edge of the bar.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .size(60.dp)
                .shadow(12.dp, CircleShape, ambientColor = colors.brandAccent, spotColor = colors.brandAccent)
                .clip(CircleShape)
                .background(colors.brandAccent)
                .selectable(selected = center.selected, role = Role.Tab, onClick = center.onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(center.icon, contentDescription = center.label, tint = colors.onBrandStrong, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun NavBarItem(item: NavItem, modifier: Modifier) {
    val color = if (item.selected) AppTheme.colors.brandAccent else AppTheme.colors.textSecondary
    Column(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .selectable(selected = item.selected, role = Role.Tab, onClick = item.onClick)
            .padding(vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.Bottom),
    ) {
        Icon(item.icon, contentDescription = null, tint = color)
        Text(item.label.uppercase(), style = AppTheme.type.overline, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The label under the raised circle; the circle itself is the touch target. */
@Composable
private fun CenterLabel(item: NavItem, modifier: Modifier) {
    Box(modifier.heightIn(min = 56.dp).padding(bottom = Spacing.xs), contentAlignment = Alignment.BottomCenter) {
        Text(
            item.label.uppercase(),
            style = AppTheme.type.overline,
            color = if (item.selected) AppTheme.colors.brandAccent else AppTheme.colors.textSecondary,
            maxLines = 1,
        )
    }
}
