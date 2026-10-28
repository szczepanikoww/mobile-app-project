package com.trainingapp.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing

data class NavItem(val label: String, val icon: ImageVector, val selected: Boolean, val onClick: () -> Unit)

/**
 * The trainer bottom bar of the calendar/clients branch: four tabs with the central 56 dp create control
 * overhanging the bar by 24 dp. The bar is flat with a 28 dp upper frame and the shared outline; at 150 % text
 * and above the tabs use two rows so the full labels stay readable.
 */
@Composable
fun AppBottomNavigation(
    items: List<NavItem>,
    createLabel: String,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val expandedLabels = LocalDensity.current.fontScale >= 1.5f
    val half = items.size / 2
    Box(modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = Sizes.navigationOverhang),
            shape = AppShapes.navigation,
            color = colors.surface.copy(alpha = 0.95f),
            border = BorderStroke(1.dp, colors.outline),
        ) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = Spacing.s)) {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = Sizes.navigationHeight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.take(half).forEach { NavBarItem(it) }
                    if (!expandedLabels) {
                        Spacer(Modifier.width(Sizes.navigationCreateGap))
                        items.drop(half).forEach { NavBarItem(it) }
                    }
                }
                if (expandedLabels) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = Sizes.navigationHeight)) {
                        items.drop(half).forEach { NavBarItem(it) }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = onCreate,
            modifier = Modifier.align(Alignment.TopCenter).size(Sizes.navigationCreateSize),
            shape = CircleShape,
            containerColor = colors.brandStrong,
            contentColor = colors.onBrandStrong,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = Sizes.floatingElevation,
                pressedElevation = Sizes.activeFloatingElevation,
                focusedElevation = Sizes.floatingElevation,
                hoveredElevation = Sizes.activeFloatingElevation,
            ),
        ) {
            Icon(Icons.Outlined.Add, createLabel, Modifier.size(30.dp))
        }
    }
}

@Composable
private fun RowScope.NavBarItem(item: NavItem) {
    val tint = if (item.selected) AppTheme.colors.brandStrong else AppTheme.colors.textSecondary
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .heightIn(min = Sizes.minTouchTarget)
            .clickable(role = Role.Tab, onClick = item.onClick)
            .semantics { selected = item.selected }
            .padding(vertical = Spacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterVertically),
    ) {
        Icon(item.icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
        Text(
            item.label,
            color = tint,
            textAlign = TextAlign.Center,
            style = AppTheme.type.label.copy(fontWeight = if (item.selected) FontWeight.Bold else FontWeight.Medium),
        )
    }
}
