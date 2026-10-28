package com.trainingapp.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.trainingapp.app.resources.Res
import com.trainingapp.app.resources.global_not_available
import com.trainingapp.app.resources.ok
import com.trainingapp.app.resources.placeholder_message
import com.trainingapp.app.resources.placeholder_title
import com.trainingapp.app.resources.plans_title
import com.trainingapp.app.resources.tab_calendar
import com.trainingapp.app.resources.tab_clients
import com.trainingapp.app.resources.tab_finance
import com.trainingapp.app.resources.tab_plan
import com.trainingapp.app.resources.tab_start
import com.trainingapp.designsystem.component.AppBottomNavigation
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.EmptyState
import com.trainingapp.designsystem.component.NavItem
import com.trainingapp.designsystem.component.TopLevelHeader
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.domain.plan.PlanLibraryItem
import com.trainingapp.feature.workoutbuilder.plans.PlanLibraryRoute
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The trainer navigation of the Stitch references: START · KALENDARZ · (+ PLAN) · KLIENCI · FINANSE. */
private enum class TrainerTab(val label: StringResource, val icon: ImageVector) {
    Start(Res.string.tab_start, Icons.Outlined.GridView),
    Calendar(Res.string.tab_calendar, Icons.Outlined.CalendarToday),
    Plan(Res.string.tab_plan, Icons.Outlined.Add),
    Clients(Res.string.tab_clients, Icons.Outlined.Groups),
    Finance(Res.string.tab_finance, Icons.Outlined.AccountBalanceWallet),
}

/** The shell owns the header controls and the bottom navigation; feature screens never render their own. */
@Composable
fun TrainerShell(
    onNewPlan: () -> Unit,
    onOpenPlan: (PlanLibraryItem) -> Unit,
    onOpenExerciseLibrary: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(TrainerTab.Plan) }
    var showNotAvailable by rememberSaveable { mutableStateOf(false) }
    val notAvailable = { showNotAvailable = true }

    Scaffold(
        containerColor = AppTheme.colors.background,
        topBar = {
            TopLevelHeader(
                title = if (tab == TrainerTab.Plan) stringResource(Res.string.plans_title) else stringResource(tab.label),
                onMessages = notAvailable,
                onNotifications = notAvailable,
                onProfile = notAvailable,
            )
        },
        bottomBar = {
            AppBottomNavigation(
                items = TrainerTab.entries.filter { it != TrainerTab.Plan }.map { item ->
                    NavItem(stringResource(item.label), item.icon, selected = item == tab, onClick = { tab = item })
                },
                center = NavItem(
                    stringResource(TrainerTab.Plan.label),
                    TrainerTab.Plan.icon,
                    selected = tab == TrainerTab.Plan,
                    onClick = { tab = TrainerTab.Plan },
                ),
            )
        },
    ) { padding ->
        when (tab) {
            TrainerTab.Plan -> PlanLibraryRoute(
                onNewPlan = onNewPlan,
                onOpenPlan = onOpenPlan,
                onOpenExerciseLibrary = onOpenExerciseLibrary,
                contentPadding = padding,
            )
            else -> TopLevelPlaceholder(padding)
        }
    }

    if (showNotAvailable) {
        AlertDialog(
            onDismissRequest = { showNotAvailable = false },
            text = { Text(stringResource(Res.string.global_not_available), style = AppTheme.type.body) },
            confirmButton = { AppTextButton(stringResource(Res.string.ok), onClick = { showNotAvailable = false }) },
        )
    }
}

/** Start, Kalendarz, Klienci and Finanse are other features. */
@Composable
private fun TopLevelPlaceholder(padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding)) {
        EmptyState(
            title = stringResource(Res.string.placeholder_title),
            message = stringResource(Res.string.placeholder_message),
            icon = Icons.Outlined.Construction,
        )
    }
}
