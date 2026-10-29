package pl.trainer.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import pl.trainer.presentation.Destination
import pl.trainer.resources.*

@Composable
fun TrainerBottomBar(selected: Destination, onSelect: (Destination) -> Unit, onCreate: () -> Unit, createEnabled: Boolean) {
    SharedBottomBar(Destination.entries.map { destination ->
        val icon = when (destination) {
            Destination.Start -> Icons.Outlined.Dashboard
            Destination.Calendar -> Icons.Outlined.CalendarToday
            Destination.Clients -> Icons.Outlined.Groups
            Destination.Finance -> Icons.Outlined.Payments
        }
        SharedNavItem(tr(destination.label()), icon, destination == selected) { onSelect(destination) }
    }, tr(Res.string.add), onCreate, createEnabled)
}

internal fun Destination.label() = when (this) {
    Destination.Start -> Res.string.nav_start
    Destination.Calendar -> Res.string.calendar
    Destination.Clients -> Res.string.trainees
    Destination.Finance -> Res.string.nav_finance
}
