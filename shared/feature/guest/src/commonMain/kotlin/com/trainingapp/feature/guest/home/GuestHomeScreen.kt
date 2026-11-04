package com.trainingapp.feature.guest.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.component.ActionRow
import com.trainingapp.designsystem.component.EmptyState
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.SearchField
import com.trainingapp.designsystem.component.SectionHeader
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.feature.guest.GuestCatalogUiState
import com.trainingapp.feature.guest.Load
import com.trainingapp.feature.guest.component.BenefitRow
import com.trainingapp.feature.guest.component.GoalTile
import com.trainingapp.feature.guest.component.GymRow
import com.trainingapp.feature.guest.component.TrainerRow
import com.trainingapp.feature.guest.component.hint
import com.trainingapp.feature.guest.component.label
import com.trainingapp.feature.guest.model.Goal
import com.trainingapp.feature.guest.resources.*
import org.jetbrains.compose.resources.stringResource

data class GuestHomeActions(
    val onSearch: (String) -> Unit,
    val onOpenFilters: () -> Unit,
    val onBrowseTrainers: () -> Unit,
    val onOpenTrainer: (String) -> Unit,
    val onGoal: (Goal) -> Unit,
    val onBrowseGyms: () -> Unit,
    val onOpenGym: (String) -> Unit,
    val onRegister: () -> Unit,
    val onRetry: () -> Unit,
)

private fun Goal.icon() = when (this) {
    Goal.Reduction -> Icons.Outlined.LocalFireDepartment
    Goal.Strength -> Icons.Outlined.FitnessCenter
    Goal.Mobility -> Icons.Outlined.SelfImprovement
    Goal.Competition -> Icons.Outlined.EmojiEvents
}

/** Start – gość: gets a guest to a trainer quickly. "Znajdź swojego trenera" is the heading of this screen. */
@Composable
fun GuestHomeContent(
    state: GuestCatalogUiState,
    actions: GuestHomeActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    var search by rememberSaveable { mutableStateOf("") }
    val home = state.home
    val largeText = LocalDensity.current.fontScale >= 1.5f
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("guest-home"),
        state = listState,
        contentPadding = PaddingValues(Spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        item {
            Text(stringResource(Res.string.home_heading), style = AppTheme.type.screenTitle, color = AppTheme.colors.text,
                modifier = Modifier.semantics { heading() })
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                SearchField(
                    query = search,
                    onQueryChange = { search = it },
                    placeholder = stringResource(Res.string.search_home_placeholder),
                    modifier = Modifier.weight(1f),
                    onSearch = { actions.onSearch(search) },
                )
                IconButton(onClick = actions.onOpenFilters, modifier = Modifier.padding(top = Spacing.s)) {
                    Icon(Icons.Outlined.Tune, stringResource(Res.string.filters), tint = AppTheme.colors.brandStrong)
                }
            }
        }
        if (state.offline) item { InlineMessage(stringResource(Res.string.offline_banner), tone = MessageTone.Offline) }
        when (home) {
            Load.Loading -> item { LoadingState(Modifier.height(240.dp)) }
            Load.Failed -> item { ErrorState(stringResource(Res.string.catalog_error), onRetry = actions.onRetry) }
            is Load.Ready -> {
                val data = home.data
                item {
                    ActionRow(Icons.Outlined.PersonSearch, stringResource(Res.string.pros_title), actions.onBrowseTrainers,
                        subtitle = stringResource(Res.string.pros_subtitle))
                }
                if (data.featuredTrainers.isEmpty() && data.nearbyGyms.isEmpty()) {
                    item {
                        EmptyState(stringResource(Res.string.catalog_empty_title), stringResource(Res.string.catalog_empty_body), icon = Icons.Outlined.Groups)
                    }
                }
                if (data.featuredTrainers.isNotEmpty()) {
                    item {
                        SectionHeader(stringResource(Res.string.featured_trainers), Modifier.padding(top = Spacing.m),
                            actionLabel = stringResource(Res.string.see_all), onAction = actions.onBrowseTrainers)
                    }
                    items(data.featuredTrainers, key = { "trainer-${it.id}" }) { trainer ->
                        TrainerRow(trainer, onClick = { actions.onOpenTrainer(trainer.id) }, compact = true)
                    }
                }
                val catalogEmpty = data.featuredTrainers.isEmpty() && data.nearbyGyms.isEmpty()
                if (!catalogEmpty) item { SectionHeader(stringResource(Res.string.goals_title), Modifier.padding(top = Spacing.m)) }
                // One column at large text so the two-line labels never clip.
                val goalRows = when {
                    catalogEmpty -> emptyList()
                    largeText -> Goal.entries.map { listOf(it) }
                    else -> Goal.entries.chunked(2)
                }
                items(goalRows, key = { row -> "goals-" + row.joinToString { it.name } }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                        row.forEach { goal ->
                            GoalTile(goal.icon(), stringResource(goal.label()), stringResource(goal.hint()), onClick = { actions.onGoal(goal) },
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (data.nearbyGyms.isNotEmpty()) {
                    val withDistance = data.nearbyGyms.any { it.distanceMeters != null }
                    item {
                        // Without a known distance the section is not "nearby" (D6).
                        SectionHeader(stringResource(if (withDistance) Res.string.nearby_gyms else Res.string.gyms_title), Modifier.padding(top = Spacing.m),
                            actionLabel = stringResource(Res.string.see_all_gyms), onAction = actions.onBrowseGyms)
                    }
                    items(data.nearbyGyms, key = { "gym-${it.id}" }) { gym -> GymRow(gym, onClick = { actions.onOpenGym(gym.id) }) }
                }
                item { JoinPanel(actions.onRegister, Modifier.padding(top = Spacing.m)) }
            }
        }
        item { Spacer(Modifier.height(Spacing.l)) }
    }
}

/** One panel, one CTA (D3). */
@Composable
private fun JoinPanel(onRegister: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = AppShapes.frame, color = AppTheme.colors.surface, border = BorderStroke(1.dp, AppTheme.colors.outline)) {
        Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Text(stringResource(Res.string.join_title), style = AppTheme.type.sectionTitle, color = AppTheme.colors.text,
                modifier = Modifier.semantics { heading() })
            BenefitRow(stringResource(Res.string.benefit_payments))
            BenefitRow(stringResource(Res.string.benefit_chat))
            BenefitRow(stringResource(Res.string.benefit_progress))
            PrimaryButton(stringResource(Res.string.create_account), onClick = onRegister, modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
                icon = Icons.AutoMirrored.Outlined.ArrowForward)
        }
    }
}
