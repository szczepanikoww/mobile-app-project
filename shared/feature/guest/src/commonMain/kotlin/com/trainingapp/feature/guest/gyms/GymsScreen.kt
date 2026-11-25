package com.trainingapp.feature.guest.gyms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.component.AppFilterChip
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.EmptyState
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.MultiChoiceSheet
import com.trainingapp.designsystem.component.SearchField
import com.trainingapp.designsystem.component.SheetOption
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.feature.guest.GuestCatalogUiState
import com.trainingapp.feature.guest.Load
import com.trainingapp.feature.guest.component.GymRow
import com.trainingapp.feature.guest.dataOrNull
import com.trainingapp.feature.guest.map.GymMap
import com.trainingapp.feature.guest.map.MapPin
import com.trainingapp.feature.guest.model.GymQuery
import com.trainingapp.feature.guest.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The gyms tab: search and district filter on top, a map of the gyms on the upper half of the rest, and the list of the
 * same gyms below it (the list scrolls, the map stays). Gyms without a position are only in the list.
 */
@Composable
fun GymsContent(
    state: GuestCatalogUiState,
    onQueryChange: (GymQuery) -> Unit,
    onOpenGym: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val query = state.gymQuery
    var districtSheet by rememberSaveable { mutableStateOf(false) }
    val gyms = state.gyms.dataOrNull.orEmpty()
    val pins = gyms.mapNotNull { g ->
        val lat = g.latitude
        val lng = g.longitude
        if (lat != null && lng != null) MapPin(g.id, g.name, g.hoursSummary, lat, lng) else null
    }
    val mapDescription = stringResource(Res.string.gyms_map_description)

    BoxWithConstraints(modifier.fillMaxSize()) {
    // A fixed share of the screen (not a weight), so the map never resizes itself while it is zoomed or panned.
    val mapHeight = (maxHeight * 0.4f).coerceIn(160.dp, 360.dp)
    Column(Modifier.fillMaxSize().testTag("guest-gyms")) {
        Column(
            Modifier.padding(horizontal = Spacing.gutter).padding(top = Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            SearchField(query.text, { onQueryChange(query.copy(text = it)) }, placeholder = stringResource(Res.string.search_gyms_placeholder))
            val title = stringResource(Res.string.filter_district)
            val value = query.districts.singleOrNull()
                ?: if (query.districts.size > 1) stringResource(Res.string.chip_with_count, title, query.districts.size) else null
            AppFilterChip(label = value ?: title, selected = value != null, onClick = { districtSheet = true }, showChevron = true)
        }
        // Always there, so the page does not jump while the list loads or a filter changes.
        GymMap(
            pins = pins,
            onOpen = onOpenGym,
            modifier = Modifier
                .padding(top = Spacing.s)
                .align(Alignment.CenterHorizontally)
                .fillMaxWidth(0.95f)
                .height(mapHeight)
                .clip(RoundedCornerShape(16.dp))
                .semantics { contentDescription = mapDescription },
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("guest-gyms-list"),
            state = listState,
            contentPadding = PaddingValues(Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            if (state.offline) item { InlineMessage(stringResource(Res.string.offline_banner), tone = MessageTone.Offline) }
            if (query.hasFilters) {
                item { AppTextButton(stringResource(Res.string.clear_filters), onClick = { onQueryChange(GymQuery()) }) }
            }
            val withoutPosition = gyms.size - pins.size
            if (withoutPosition > 0) {
                item {
                    Text(
                        stringResource(Res.string.gyms_map_no_position, withoutPosition),
                        style = AppTheme.type.compactBody,
                        color = AppTheme.colors.textSecondary,
                    )
                }
            }
            when (val load = state.gyms) {
                Load.Loading -> item { LoadingState(Modifier.height(160.dp)) }
                Load.Failed -> item { ErrorState(stringResource(Res.string.catalog_error), onRetry = onRetry) }
                is Load.Ready -> if (load.data.isEmpty()) {
                    item {
                        EmptyState(
                            stringResource(Res.string.gyms_empty_title),
                            stringResource(if (query.hasFilters) Res.string.no_results_body else Res.string.gyms_catalog_empty_body),
                            icon = Icons.Outlined.SearchOff,
                            actionLabel = if (query.hasFilters) stringResource(Res.string.clear_filters) else null,
                            onAction = if (query.hasFilters) ({ onQueryChange(GymQuery()) }) else null,
                        )
                    }
                } else {
                    items(load.data, key = { it.id }) { gym -> GymRow(gym, onClick = { onOpenGym(gym.id) }) }
                }
            }
            item { Spacer(Modifier.height(Spacing.l)) }
        }
    }
    }

    if (districtSheet) {
        MultiChoiceSheet(
            title = stringResource(Res.string.filter_district),
            options = state.filterOptions.districts.map { SheetOption(it, it) },
            selected = query.districts,
            onApply = { onQueryChange(query.copy(districts = it)); districtSheet = false },
            onDismiss = { districtSheet = false },
        )
    }
}
