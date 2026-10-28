package com.trainingapp.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.trainingapp.app.resources.Res
import com.trainingapp.app.resources.add
import com.trainingapp.app.resources.add_exercises
import com.trainingapp.app.resources.add_exercises_hint
import com.trainingapp.app.resources.add_menu_title
import com.trainingapp.app.resources.add_new_plan
import com.trainingapp.app.resources.add_new_plan_hint
import com.trainingapp.app.resources.add_plans
import com.trainingapp.app.resources.add_plans_hint
import com.trainingapp.app.resources.open_plans
import com.trainingapp.app.resources.screen_integration_pending
import com.trainingapp.app.resources.tab_calendar
import com.trainingapp.app.resources.tab_clients
import com.trainingapp.app.resources.tab_finance
import com.trainingapp.app.resources.tab_start
import com.trainingapp.app.resources.title_calendar
import com.trainingapp.app.resources.title_clients
import com.trainingapp.designsystem.component.ActionRow
import com.trainingapp.designsystem.component.AppBottomNavigation
import com.trainingapp.designsystem.component.EmptyState
import com.trainingapp.designsystem.component.NavItem
import com.trainingapp.designsystem.component.SectionHeader
import com.trainingapp.designsystem.component.TopLevelHeader
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The same four tabs as the trainer calendar/clients branch; the central "+" opens the shared add menu. */
private enum class TrainerTab(val label: StringResource, val title: StringResource, val icon: ImageVector) {
    Start(Res.string.tab_start, Res.string.tab_start, Icons.Outlined.Dashboard),
    Calendar(Res.string.tab_calendar, Res.string.title_calendar, Icons.Outlined.CalendarToday),
    Clients(Res.string.tab_clients, Res.string.title_clients, Icons.Outlined.Groups),
    Finance(Res.string.tab_finance, Res.string.tab_finance, Icons.Outlined.Payments),
}

/**
 * The shell owns the header, the bottom bar and the "Co chcesz dodać?" menu. Training plans live under the "+":
 * new plan, the plan list and the exercise library. The calendar branch adds its session and availability
 * actions to the same menu.
 */
@Composable
fun TrainerShell(
    onNewPlan: () -> Unit,
    onOpenPlans: () -> Unit,
    onOpenExerciseLibrary: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(TrainerTab.Start) }
    var addMenu by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = AppTheme.colors.background,
        topBar = { TopLevelHeader(stringResource(tab.title)) },
        bottomBar = {
            AppBottomNavigation(
                items = TrainerTab.entries.map { item ->
                    NavItem(stringResource(item.label), item.icon, selected = item == tab, onClick = { tab = item })
                },
                createLabel = stringResource(Res.string.add),
                onCreate = { addMenu = true },
            )
        },
    ) { padding ->
        // Start, Kalendarz, Podopieczni and Finanse are other features (the calendar branch implements two of them).
        Box(Modifier.fillMaxSize().padding(padding).padding(Spacing.gutter), contentAlignment = Alignment.Center) {
            EmptyState(
                title = stringResource(tab.label),
                message = stringResource(Res.string.screen_integration_pending),
                icon = tab.icon,
                actionLabel = stringResource(Res.string.open_plans),
                onAction = onOpenPlans,
            )
        }
    }

    if (addMenu) {
        ModalBottomSheet(onDismissRequest = { addMenu = false }, containerColor = AppTheme.colors.surface) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.gutter)
                    .padding(bottom = Spacing.xxxl),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                SectionHeader(stringResource(Res.string.add_menu_title))
                ActionRow(Icons.Outlined.FitnessCenter, stringResource(Res.string.add_new_plan), subtitle = stringResource(Res.string.add_new_plan_hint), onClick = {
                    addMenu = false
                    onNewPlan()
                })
                ActionRow(Icons.AutoMirrored.Outlined.Assignment, stringResource(Res.string.add_plans), subtitle = stringResource(Res.string.add_plans_hint), onClick = {
                    addMenu = false
                    onOpenPlans()
                })
                ActionRow(Icons.AutoMirrored.Outlined.LibraryBooks, stringResource(Res.string.add_exercises), subtitle = stringResource(Res.string.add_exercises_hint), onClick = {
                    addMenu = false
                    onOpenExerciseLibrary()
                })
            }
        }
    }
}
