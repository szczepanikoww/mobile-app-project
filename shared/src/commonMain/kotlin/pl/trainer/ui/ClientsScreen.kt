package pl.trainer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import pl.trainer.domain.*
import pl.trainer.presentation.*
import pl.trainer.resources.*
import kotlinx.datetime.toLocalDateTime

@Composable
fun ClientsScreen(state: TrainerUiState, onSearch: (String) -> Unit, onFilter: (ClientFilter) -> Unit, onTab: (ClientTab) -> Unit, onClient: (String) -> Unit, onInvite: () -> Unit) {
    val data = state.data ?: return
    LazyColumn(modifier = Modifier.testTag("clients-list"), contentPadding = PaddingValues(Layout.gutter), verticalArrangement = Arrangement.spacedBy(Layout.small)) {
        item {
            OutlinedTextField(state.search, onSearch, label = { Text(tr(Res.string.search_clients)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (state.search.isNotEmpty()) IconButton(onClick = { onSearch("") }) { Icon(Icons.Outlined.Close, tr(Res.string.clear_search)) } },
                singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = Layout.card, modifier = Modifier.fillMaxWidth())
        }
        item {
            PillTabs(ClientTab.entries, state.clientTab,
                label = { tr(if (it == ClientTab.Current) Res.string.current_clients else Res.string.archive) }, onSelect = onTab)
        }
        if (state.clientTab == ClientTab.Current) item {
            FilterChip(selected = state.clientFilter == ClientFilter.Overdue,
                onClick = { onFilter(if (state.clientFilter == ClientFilter.Overdue) ClientFilter.All else ClientFilter.Overdue) },
                modifier = Modifier.heightIn(min = Layout.target), label = { Text(tr(Res.string.overdue_filter)) },
                leadingIcon = { if (state.clientFilter == ClientFilter.Overdue) Icon(Icons.Outlined.Check, null) })
        }
        item {
            FlowRow(Modifier.fillMaxWidth().padding(vertical = Layout.small), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(Layout.small)) {
                Text(tr(Res.string.results_count, state.visibleClients.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(min = Layout.target).wrapContentHeight(Alignment.CenterVertically))
                TextButton(onClick = onInvite, modifier = Modifier.heightIn(min = Layout.target)) {
                    Icon(Icons.Outlined.PersonAdd, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Layout.small))
                    Text(tr(Res.string.invite))
                }
            }
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
            ClientRow(client, data.sessions.filter { it.clientId == client.id && it.isActive() && it.start.toLocalDateTime(TrainerZone).date >= DemoDate }.minByOrNull { it.start }, onClick = { onClient(client.id) })
        }
        item { Spacer(Modifier.height(Layout.padding)) }
    }
}

@Composable
private fun ClientRow(client: Client, next: Session?, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = Layout.tile, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(Layout.padding), horizontalArrangement = Arrangement.spacedBy(Layout.medium), verticalAlignment = Alignment.Top) {
            Avatar(client)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Layout.small)) {
                Text(client.name, style = MaterialTheme.typography.titleMedium)
                MetaLine(Icons.Outlined.CalendarToday, next?.let {
                    DateFormats.date(it.start.toLocalDateTime(TrainerZone).date, DateFormats.defaultLanguage(), "d MMM") + " · " + DateFormats.time(it.start, it.timeZone, DateFormats.defaultLanguage())
                } ?: tr(Res.string.no_upcoming))
                if (client.hasOverduePayment) MetaLine(Icons.Outlined.ErrorOutline, tr(Res.string.overdue_label))
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
