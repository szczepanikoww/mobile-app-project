package com.trainingapp.feature.guest.trainers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.trainingapp.contract.text.SearchText
import com.trainingapp.designsystem.component.AppBottomSheet
import com.trainingapp.designsystem.component.MultiChoiceSheet
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.SearchField
import com.trainingapp.designsystem.component.SheetOption
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.feature.guest.TrainerFilter
import com.trainingapp.feature.guest.component.label
import com.trainingapp.feature.guest.component.priceRangeText
import com.trainingapp.feature.guest.model.Availability
import com.trainingapp.feature.guest.model.FilterOptions
import com.trainingapp.feature.guest.model.Goal
import com.trainingapp.feature.guest.model.PriceRange
import com.trainingapp.feature.guest.model.TrainerQuery
import com.trainingapp.feature.guest.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The sheet behind one filter chip. Multi-select sheets keep the choice until it is confirmed; single-choice
 * sheets keep it until "Zastosuj".
 */
@Composable
fun TrainerFilterSheet(
    filter: TrainerFilter,
    query: TrainerQuery,
    options: FilterOptions,
    onApply: (TrainerQuery) -> Unit,
    onDismiss: () -> Unit,
) {
    when (filter) {
        TrainerFilter.Goal -> MultiChoiceSheet(
            title = stringResource(Res.string.filter_goal),
            options = Goal.entries.map { SheetOption(it, stringResource(it.label())) },
            selected = query.goals,
            onApply = { onApply(query.copy(goals = it)) },
            onDismiss = onDismiss,
        )
        TrainerFilter.District -> MultiChoiceSheet(
            title = stringResource(Res.string.filter_district),
            options = options.districts.map { SheetOption(it, it) },
            selected = query.districts,
            onApply = { onApply(query.copy(districts = it)) },
            onDismiss = onDismiss,
        )
        TrainerFilter.Availability -> MultiChoiceSheet(
            title = stringResource(Res.string.filter_availability),
            options = Availability.entries.map { SheetOption(it, stringResource(it.label())) },
            selected = query.availability,
            onApply = { onApply(query.copy(availability = it)) },
            onDismiss = onDismiss,
            supportingText = stringResource(Res.string.availability_hint),
        )
        TrainerFilter.Price -> {
            val labels = PriceRange.entries.associateWith { priceRangeText(it) }
            RadioSheet(
                title = stringResource(Res.string.filter_price),
                options = listOf<Pair<PriceRange?, String>>(null to stringResource(Res.string.any_price)) + PriceRange.entries.map { it to labels.getValue(it) },
                selected = query.priceRange,
                searchable = false,
                onApply = { onApply(query.copy(priceRange = it)) },
                onDismiss = onDismiss,
            )
        }
        TrainerFilter.Gym -> RadioSheet(
            title = stringResource(Res.string.filter_gym),
            options = listOf<Pair<String?, String>>(null to stringResource(Res.string.any_gym)) + options.gyms.map { it.id to it.name },
            selected = query.gymId,
            searchable = true,
            onApply = { onApply(query.copy(gymId = it)) },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun <T> RadioSheet(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    searchable: Boolean,
    onApply: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember(selected) { mutableStateOf(selected) }
    var search by remember { mutableStateOf("") }
    AppBottomSheet(onDismiss = onDismiss, title = title) {
        if (searchable) {
            SearchField(search, { search = it }, placeholder = stringResource(Res.string.search_gyms_placeholder))
        }
        val tokens = SearchText.tokens(search)
        val visible = options.filter { (value, label) -> value == null || tokens.all { it in SearchText.normalize(label) } }
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            visible.forEach { (value, label) ->
                val isSelected = value == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.minTouchTarget)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { current = value }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = isSelected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = AppTheme.colors.brandStrong))
                    Text(label, style = AppTheme.type.body, modifier = Modifier.padding(start = Spacing.s))
                }
            }
        }
        PrimaryButton(stringResource(Res.string.apply_filters), onClick = { onApply(current) }, modifier = Modifier.fillMaxWidth())
    }
}
