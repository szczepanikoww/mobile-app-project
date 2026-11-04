package com.trainingapp.feature.guest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.trainingapp.designsystem.component.AppBottomNavigation
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.NavItem
import com.trainingapp.designsystem.component.TopLevelHeader
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.feature.guest.gyms.GymsContent
import com.trainingapp.feature.guest.home.GuestHomeActions
import com.trainingapp.feature.guest.home.GuestHomeContent
import com.trainingapp.feature.guest.resources.*
import com.trainingapp.feature.guest.trainers.TrainersActions
import com.trainingapp.feature.guest.trainers.TrainersContent
import org.jetbrains.compose.resources.stringResource

data class GuestShellActions(
    val onOpenTrainer: (String) -> Unit,
    val onOpenGym: (String) -> Unit,
    val onLogIn: () -> Unit,
    val onRegister: () -> Unit,
)

/**
 * The guest's top-level shell: the calendar's white header and the shared bottom bar with four items and no create
 * button. "Zaloguj się" pushes the Auth screen; it is never a selected tab. Each tab keeps its scroll position,
 * search and filters while switching.
 */
@Composable
fun GuestShell(viewModel: GuestCatalogViewModel, state: GuestCatalogUiState, actions: GuestShellActions) {
    val tabs = rememberSaveableStateHolder()
    val title = stringResource(
        when (state.tab) {
            GuestTab.Start -> Res.string.tab_start
            GuestTab.Trainers -> Res.string.tab_trainers
            GuestTab.Gyms -> Res.string.tab_gyms
        },
    )
    Scaffold(
        containerColor = AppTheme.colors.background,
        topBar = {
            TopLevelHeader(title) {
                if (state.tab == GuestTab.Start) AppTextButton(stringResource(Res.string.log_in), onClick = actions.onLogIn)
            }
        },
        bottomBar = {
            AppBottomNavigation(
                listOf(
                    NavItem(stringResource(Res.string.tab_start), Icons.Outlined.Explore, state.tab == GuestTab.Start) { viewModel.selectTab(GuestTab.Start) },
                    NavItem(stringResource(Res.string.tab_trainers), Icons.Outlined.FitnessCenter, state.tab == GuestTab.Trainers) { viewModel.selectTab(GuestTab.Trainers) },
                    NavItem(stringResource(Res.string.tab_gyms), Icons.Outlined.LocationOn, state.tab == GuestTab.Gyms) { viewModel.selectTab(GuestTab.Gyms) },
                    NavItem(stringResource(Res.string.log_in), Icons.Outlined.AccountCircle, selected = false, onClick = actions.onLogIn),
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = Sizes.contentWidth).fillMaxWidth()) {
                tabs.SaveableStateProvider(state.tab.name) {
                    when (state.tab) {
                        GuestTab.Start -> GuestHomeContent(
                            state = state,
                            actions = GuestHomeActions(
                                onSearch = viewModel::searchFromStart,
                                onOpenFilters = { viewModel.openTrainerFilter(TrainerFilter.Goal) },
                                onBrowseTrainers = { viewModel.selectTab(GuestTab.Trainers) },
                                onOpenTrainer = actions.onOpenTrainer,
                                onGoal = viewModel::browseGoal,
                                onBrowseGyms = { viewModel.selectTab(GuestTab.Gyms) },
                                onOpenGym = actions.onOpenGym,
                                onRegister = actions.onRegister,
                                onRetry = viewModel::retryHome,
                            ),
                        )
                        GuestTab.Trainers -> TrainersContent(
                            state = state,
                            actions = TrainersActions(
                                onTextChange = viewModel::onTrainerTextChange,
                                onQueryChange = viewModel::onTrainerQueryChange,
                                onClearFilters = viewModel::clearTrainerFilters,
                                onViewModeChange = viewModel::onViewModeChange,
                                onOpenTrainer = actions.onOpenTrainer,
                                onRetry = viewModel::retryTrainers,
                                onPendingSheetShown = viewModel::consumePendingFilterSheet,
                            ),
                        )
                        GuestTab.Gyms -> GymsContent(
                            state = state,
                            onQueryChange = viewModel::onGymQueryChange,
                            onOpenGym = actions.onOpenGym,
                            onRetry = viewModel::retryGyms,
                        )
                    }
                }
            }
        }
    }
}
