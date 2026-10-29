package pl.trainer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class SharedNavItem(val label: String, val icon: ImageVector, val selected: Boolean, val onClick: () -> Unit)

/** Main's four-tab shell and central create action, adapted to the shared theme. */
@Composable
fun SharedBottomBar(items: List<SharedNavItem>, createLabel: String, onCreate: () -> Unit, createEnabled: Boolean = true) {
    val half = items.size / 2
    val expandedLabels = LocalDensity.current.fontScale >= 1.5f
    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = Layout.navigationOverhang),
            shape = Layout.navigation,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = Layout.small)) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = Layout.navigationHeight),
                    verticalAlignment = Alignment.CenterVertically) {
                    items.take(half).forEach { NavItem(it) }
                    if (!expandedLabels) {
                        Spacer(Modifier.width(Layout.navigationCreateGap))
                        items.drop(half).forEach { NavItem(it) }
                    }
                }
                if (expandedLabels) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = Layout.navigationHeight)) {
                    items.drop(half).forEach { NavItem(it) }
                }
            }
        }
        FloatingActionButton(
            onClick = { if (createEnabled) onCreate() },
            modifier = Modifier.align(Alignment.TopCenter).size(Layout.navigationCreateSize)
                .semantics { if (!createEnabled) disabled() },
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = Layout.floatingElevation, pressedElevation = Layout.activeFloatingElevation,
                focusedElevation = Layout.floatingElevation, hoveredElevation = Layout.activeFloatingElevation,
            ),
        ) {
            Icon(Icons.Outlined.Add, createLabel, Modifier.size(30.dp))
        }
    }
}

@Composable
private fun RowScope.NavItem(item: SharedNavItem) {
    val active = item.selected
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = Layout.target)
            .clickable(role = Role.Tab, onClick = item.onClick).semantics { this.selected = active }
            .padding(vertical = Layout.small),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Layout.tiny, Alignment.CenterVertically),
    ) {
        Icon(item.icon, null, Modifier.size(24.dp), tint = tint)
        Text(item.label, color = tint,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.Medium))
    }
}
