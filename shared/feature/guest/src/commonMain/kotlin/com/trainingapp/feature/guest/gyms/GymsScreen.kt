package com.trainingapp.feature.guest.gyms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
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
import com.trainingapp.feature.guest.model.GymQuery
import com.trainingapp.feature.guest.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The gyms list behind the "Siłownie" tab (not in Stitch, D5): the same pattern as the Trainers tab. */
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
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("guest-gyms"),
        state = listState,
        contentPadding = PaddingValues(Spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        item { SearchField(query.text, { onQueryChange(query.copy(text = it)) }, placeholder = stringResource(Res.string.search_gyms_placeholder)) }
        item {
            val title = stringResource(Res.string.filter_district)
            val value = query.districts.singleOrNull()
                ?: if (query.districts.size > 1) stringResource(Res.string.chip_with_count, title, query.districts.size) else null
            AppFilterChip(label = value ?: title, selected = value != null, onClick = { districtSheet = true }, showChevron = true)
        }
        if (state.offline) item { InlineMessage(stringResource(Res.string.offline_banner), tone = MessageTone.Offline) }
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val count = state.gyms.dataOrNull?.size
                Text(
                    if (count != null) pluralStringResource(Res.plurals.results_count, count, count) else "",
                    style = AppTheme.type.compactBody,
                    color = AppTheme.colors.textSecondary,
                    modifier = Modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (query.hasFilters) AppTextButton(stringResource(Res.string.clear_filters), onClick = { onQueryChange(GymQuery()) })
            }
        }
        when (val gyms = state.gyms) {
            Load.Loading -> item { LoadingState(Modifier.height(240.dp)) }
            Load.Failed -> item { ErrorState(stringResource(Res.string.catalog_error), onRetry = onRetry) }
            is Load.Ready -> if (gyms.data.isEmpty()) {
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
                items(gyms.data, key = { it.id }) { gym -> GymRow(gym, onClick = { onOpenGym(gym.id) }) }
            }
        }
        item { Spacer(Modifier.height(Spacing.l)) }
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
