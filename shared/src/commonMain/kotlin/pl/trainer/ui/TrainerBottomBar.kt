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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.trainer.presentation.Destination
import pl.trainer.resources.*

/** Main's four-tab shell and central create action, adapted to the shared theme. */
@Composable
fun TrainerBottomBar(selected: Destination, onSelect: (Destination) -> Unit, onCreate: () -> Unit, createEnabled: Boolean) {
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
                    NavItem(Destination.Start, selected, onSelect)
                    NavItem(Destination.Calendar, selected, onSelect)
                    if (!expandedLabels) {
                        Spacer(Modifier.width(Layout.navigationCreateGap))
                        NavItem(Destination.Clients, selected, onSelect)
                        NavItem(Destination.Finance, selected, onSelect)
                    }
                }
                if (expandedLabels) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = Layout.navigationHeight)) {
                    NavItem(Destination.Clients, selected, onSelect)
                    NavItem(Destination.Finance, selected, onSelect)
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
            Icon(Icons.Outlined.Add, tr(Res.string.add), Modifier.size(30.dp))
        }
    }
}

@Composable
private fun RowScope.NavItem(destination: Destination, selected: Destination, onSelect: (Destination) -> Unit) {
    val active = destination == selected
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val icon = when (destination) {
        Destination.Start -> Icons.Outlined.Dashboard
        Destination.Calendar -> Icons.Outlined.CalendarToday
        Destination.Clients -> Icons.Outlined.Groups
        Destination.Finance -> Icons.Outlined.Payments
    }
    Column(
        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = Layout.target)
            .clickable(role = Role.Tab) { onSelect(destination) }.semantics { this.selected = active }
            .padding(vertical = Layout.small),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Layout.tiny, Alignment.CenterVertically),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Text(tr(destination.label()), color = tint,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.Medium))
    }
}

internal fun Destination.label() = when (this) {
    Destination.Start -> Res.string.nav_start
    Destination.Calendar -> Res.string.calendar
    Destination.Clients -> Res.string.trainees
    Destination.Finance -> Res.string.nav_finance
}
