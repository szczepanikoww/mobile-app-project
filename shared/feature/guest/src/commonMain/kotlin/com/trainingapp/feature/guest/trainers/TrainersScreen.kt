package com.trainingapp.feature.guest.trainers

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.component.ActionRow
import com.trainingapp.designsystem.component.AppFilterChip
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.EmptyState
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.SearchField
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.feature.guest.GuestCatalogUiState
import com.trainingapp.feature.guest.Load
import com.trainingapp.feature.guest.TrainerFilter
import com.trainingapp.feature.guest.dataOrNull
import com.trainingapp.feature.guest.component.TrainerGridCard
import com.trainingapp.feature.guest.component.TrainerRow
import com.trainingapp.feature.guest.component.label
import com.trainingapp.feature.guest.component.priceRangeText
import com.trainingapp.feature.guest.model.TrainerQuery
import com.trainingapp.feature.guest.model.TrainerSort
import com.trainingapp.feature.guest.model.ViewMode
import com.trainingapp.feature.guest.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

data class TrainersActions(
    val onTextChange: (String) -> Unit,
    val onQueryChange: (TrainerQuery) -> Unit,
    val onClearFilters: () -> Unit,
    val onViewModeChange: (ViewMode) -> Unit,
    val onOpenTrainer: (String) -> Unit,
    val onRetry: () -> Unit,
    val onPendingSheetShown: () -> Unit,
)

/** Trenerzy – gość: search, narrow down and open a trainer. Same structure as the trainer's Clients screen. */
@Composable
fun TrainersContent(
    state: GuestCatalogUiState,
    actions: TrainersActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val query = state.trainerQuery
    var openSheet by rememberSaveable { mutableStateOf<TrainerFilter?>(null) }
    LaunchedEffect(state.pendingFilterSheet) {
        state.pendingFilterSheet?.let { openSheet = it; actions.onPendingSheetShown() }
    }
    // At large font scales the grid would clip names and prices, so the list is forced and the toggle hidden.
    val gridAllowed = LocalDensity.current.fontScale < 1.5f
    val mode = if (gridAllowed) state.viewMode else ViewMode.List
    val trainers = state.trainers

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("guest-trainers"),
        state = listState,
        contentPadding = PaddingValues(Spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        item {
            SearchField(query.text, actions.onTextChange, placeholder = stringResource(Res.string.search_trainers_placeholder))
        }
        item { FilterChips(query, state, onOpen = { openSheet = it }) }
        if (state.offline) item { InlineMessage(stringResource(Res.string.offline_banner), tone = MessageTone.Offline) }
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                val count = trainers.dataOrNull?.size
                Text(
                    if (count != null) pluralStringResource(Res.plurals.results_count, count, count) else "",
                    style = AppTheme.type.compactBody,
                    color = AppTheme.colors.textSecondary,
                    modifier = Modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (query.hasFilters) AppTextButton(stringResource(Res.string.clear_filters), onClick = actions.onClearFilters)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SortMenu(query.sort, onSort = { actions.onQueryChange(query.copy(sort = it)) }, modifier = Modifier.weight(1f))
                if (gridAllowed) {
                    IconToggleButton(checked = mode == ViewMode.List, onCheckedChange = { actions.onViewModeChange(ViewMode.List) }) {
                        Icon(Icons.AutoMirrored.Outlined.ViewList, stringResource(Res.string.view_list),
                            tint = if (mode == ViewMode.List) AppTheme.colors.brandStrong else AppTheme.colors.textSecondary)
                    }
                    IconToggleButton(checked = mode == ViewMode.Grid, onCheckedChange = { actions.onViewModeChange(ViewMode.Grid) }) {
                        Icon(Icons.Outlined.GridView, stringResource(Res.string.view_grid),
                            tint = if (mode == ViewMode.Grid) AppTheme.colors.brandStrong else AppTheme.colors.textSecondary)
                    }
                }
            }
        }
        when (trainers) {
            Load.Loading -> item { LoadingState(Modifier.height(240.dp)) }
            Load.Failed -> item { ErrorState(stringResource(Res.string.catalog_error), onRetry = actions.onRetry) }
            is Load.Ready -> {
                val list = trainers.data
                if (list.isEmpty()) {
                    item {
                        EmptyState(
                            stringResource(Res.string.no_results_title),
                            stringResource(Res.string.no_results_body),
                            icon = Icons.Outlined.SearchOff,
                            actionLabel = if (query.hasFilters) stringResource(Res.string.clear_filters) else null,
                            onAction = if (query.hasFilters) actions.onClearFilters else null,
                        )
                    }
                } else if (mode == ViewMode.List) {
                    items(list, key = { it.id }) { trainer -> TrainerRow(trainer, onClick = { actions.onOpenTrainer(trainer.id) }) }
                } else {
                    items(list.chunked(2), key = { row -> row.joinToString { it.id } }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                            row.forEach { trainer ->
                                TrainerGridCard(trainer, onClick = { actions.onOpenTrainer(trainer.id) }, modifier = Modifier.weight(1f))
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                // D4: no quiz screen exists, so the prompt opens the goal filter.
                item {
                    ActionRow(Icons.Outlined.Quiz, stringResource(Res.string.quiz_title), onClick = { openSheet = TrainerFilter.Goal },
                        subtitle = stringResource(Res.string.quiz_subtitle))
                }
            }
        }
        item { Spacer(Modifier.height(Spacing.l)) }
    }

    openSheet?.let { filter ->
        TrainerFilterSheet(
            filter = filter,
            query = query,
            options = state.filterOptions,
            onApply = { actions.onQueryChange(it); openSheet = null },
            onDismiss = { openSheet = null },
        )
    }
}

@Composable
private fun FilterChips(query: TrainerQuery, state: GuestCatalogUiState, onOpen: (TrainerFilter) -> Unit) {
    val gymName = state.filterOptions.gyms.find { it.id == query.gymId }?.name
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        TrainerFilter.entries.forEach { filter ->
            val title = stringResource(
                when (filter) {
                    TrainerFilter.Goal -> Res.string.filter_goal
                    TrainerFilter.Gym -> Res.string.filter_gym
                    TrainerFilter.Price -> Res.string.filter_price
                    TrainerFilter.District -> Res.string.filter_district
                    TrainerFilter.Availability -> Res.string.filter_availability
                },
            )
            // An active chip shows its value (or how many values) on the soft accent.
            val value: String? = when (filter) {
                TrainerFilter.Goal -> query.goals.singleOrNull()?.let { stringResource(it.label()) } ?: countLabel(title, query.goals.size)
                TrainerFilter.Gym -> gymName
                TrainerFilter.Price -> query.priceRange?.let { priceRangeText(it) }
                TrainerFilter.District -> query.districts.singleOrNull() ?: countLabel(title, query.districts.size)
                TrainerFilter.Availability -> query.availability.singleOrNull()?.let { stringResource(it.label()) } ?: countLabel(title, query.availability.size)
            }
            AppFilterChip(label = value ?: title, selected = value != null, onClick = { onOpen(filter) }, showChevron = true)
        }
    }
}

@Composable
private fun countLabel(title: String, count: Int): String? = if (count > 1) stringResource(Res.string.chip_with_count, title, count) else null

@Composable
private fun SortMenu(sort: TrainerSort, onSort: (TrainerSort) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        AppTextButton(stringResource(Res.string.sort_by, stringResource(sort.label())), onClick = { expanded = true },
            icon = Icons.AutoMirrored.Outlined.Sort)
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TrainerSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.label()), style = AppTheme.type.body) },
                    onClick = { onSort(option); expanded = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}
