package pl.trainer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toLocalDateTime
import pl.trainer.domain.*
import pl.trainer.presentation.*
import pl.trainer.resources.*

/**
 * Podopieczni (docs/plans/trainer-redesign.md): current trainees and the archive, search and "Zaproś podopiecznego",
 * which opens the same invite sheet as the plus. No payment information here (every session is paid when booked).
 */
@Composable
fun ClientsScreen(state: TrainerUiState, onSearch: (String) -> Unit, onFilter: (ClientFilter) -> Unit, onTab: (ClientTab) -> Unit, onClient: (String) -> Unit, onInvite: () -> Unit) {
    val data = state.data ?: return
    LazyColumn(modifier = Modifier.testTag("clients-list"), contentPadding = PaddingValues(horizontal = Layout.gutter, vertical = Layout.padding),
        verticalArrangement = Arrangement.spacedBy(Layout.small)) {
        item {
            OutlinedTextField(state.search, onSearch, label = { Text(tr(Res.string.search_clients)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (state.search.isNotEmpty()) IconButton(onClick = { onSearch("") }) { Icon(Icons.Outlined.Close, tr(Res.string.clear_search)) } },
                singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = AppColors.surface, focusedContainerColor = AppColors.surface,
                    unfocusedBorderColor = AppColors.outline),
                shape = Layout.card, modifier = Modifier.fillMaxWidth())
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = Layout.small), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Layout.small)) {
                PillTabs(ClientTab.entries, state.clientTab, modifier = Modifier.weight(1f),
                    label = { tr(if (it == ClientTab.Current) Res.string.current_clients else Res.string.archive) }, onSelect = onTab)
            }
        }
        if (state.clientTab == ClientTab.Current) item {
            InviteRow(onInvite)
        }
        item {
            Text(tr(Res.string.results_count, state.visibleClients.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Layout.small))
        }
        if (state.clientTab == ClientTab.Archive && state.visibleClients.isEmpty() && state.search.isBlank()) item {
            EmptyContent(Icons.Outlined.Archive, tr(Res.string.no_archived_clients), tr(Res.string.no_archived_clients_body))
        } else if (data.clients.none { it.active } && state.clientTab == ClientTab.Current) item {
            EmptyContent(Icons.Outlined.PeopleOutline, tr(Res.string.no_clients), tr(Res.string.no_clients_body), tr(Res.string.invite), onInvite)
        } else if (state.visibleClients.isEmpty()) item {
            EmptyContent(Icons.Outlined.SearchOff, tr(Res.string.no_results), tr(Res.string.no_results_body), tr(Res.string.clear_filters), {
                onSearch(""); onFilter(ClientFilter.All)
            })
        }
        items(state.visibleClients, key = { it.id }) { client ->
            val next = data.sessions.filter { it.clientId == client.id && it.isActive() && (state.now?.let { now -> it.end > now } ?: (it.start.toLocalDateTime(TrainerZone).date >= state.today)) }
                .minByOrNull { it.start }
            ClientRow(client, next, onClick = { onClient(client.id) })
        }
        item { Spacer(Modifier.height(Layout.padding)) }
    }
}

/** "Zaproś podopiecznego": the brand tile, as a row above the list. */
@Composable
private fun InviteRow(onInvite: () -> Unit) {
    WhiteCard(onClick = onInvite) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
            IconTile(Icons.Outlined.PersonAdd, TileTone.Brand)
            Column(Modifier.weight(1f)) {
                Text(tr(Res.string.invite), style = MaterialTheme.typography.titleMedium)
                Text(tr(Res.string.invite_row_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.Share, null, tint = AppColors.brandStrong)
        }
    }
}

@Composable
private fun ClientRow(client: Client, next: Session?, onClick: () -> Unit) {
    val language = DateFormats.defaultLanguage()
    WhiteCard(onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(Layout.medium), verticalAlignment = Alignment.CenterVertically) {
            Portrait(client.initials, client.userId, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
                Text(client.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MetaLine(Icons.Outlined.CalendarToday, next?.let {
                    DateFormats.date(it.start.toLocalDateTime(TrainerZone).date, language, "EEE d MMM").replaceFirstChar { c -> c.titlecase() } + " · " +
                        DateFormats.time(it.start, it.timeZone, language)
                } ?: tr(Res.string.no_upcoming))
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
