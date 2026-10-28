package com.trainingapp.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.trainingapp.app.resources.Res
import com.trainingapp.app.resources.placeholder_message
import com.trainingapp.app.resources.placeholder_title
import com.trainingapp.app.resources.segment_clients
import com.trainingapp.app.resources.segment_plans
import com.trainingapp.app.resources.tab_calendar
import com.trainingapp.app.resources.tab_clients
import com.trainingapp.app.resources.tab_finance
import com.trainingapp.app.resources.tab_start
import com.trainingapp.designsystem.component.EmptyState
import com.trainingapp.designsystem.component.SegmentedTabs
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.domain.plan.PlanLibraryItem
import com.trainingapp.feature.workoutbuilder.plans.PlanLibraryRoute
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Reference grouping for the trainer (CLAUDE.md section 5); not finalized routes. */
private enum class TrainerTab(val label: StringResource, val icon: ImageVector) {
    Start(Res.string.tab_start, Icons.Outlined.Home),
    Calendar(Res.string.tab_calendar, Icons.Outlined.CalendarMonth),
    Clients(Res.string.tab_clients, Icons.Outlined.Groups),
    Finance(Res.string.tab_finance, Icons.Outlined.AccountBalanceWallet),
}

/** The shell owns the bottom navigation; feature screens never render their own. */
@Composable
fun TrainerShell(
    onNewPlan: () -> Unit,
    onOpenPlan: (PlanLibraryItem) -> Unit,
    onOpenExerciseLibrary: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(TrainerTab.Clients) }
    val colors = AppTheme.colors
    Scaffold(
        containerColor = colors.background,
        bottomBar = {
            NavigationBar(containerColor = colors.surface) {
                TrainerTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == tab,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.label), style = AppTheme.type.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.onSoftAccent,
                            selectedTextColor = colors.brandStrong,
                            indicatorColor = colors.softAccent,
                            unselectedIconColor = colors.textSecondary,
                            unselectedTextColor = colors.textSecondary,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            TrainerTab.Clients -> ClientsTab(padding, onNewPlan, onOpenPlan, onOpenExerciseLibrary)
            else -> TopLevelPlaceholder(stringResource(tab.label), padding)
        }
    }
}

/** Decision D1: the plan library is the "Plany" segment of the Klienci tab. */
@Composable
private fun ClientsTab(
    padding: PaddingValues,
    onNewPlan: () -> Unit,
    onOpenPlan: (PlanLibraryItem) -> Unit,
    onOpenExerciseLibrary: () -> Unit,
) {
    var segment by rememberSaveable { mutableStateOf(1) }
    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        Column(
            Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Text(stringResource(Res.string.tab_clients), style = AppTheme.type.screenTitle, modifier = Modifier.semantics { heading() })
            SegmentedTabs(
                options = listOf(stringResource(Res.string.segment_clients), stringResource(Res.string.segment_plans)),
                selectedIndex = segment,
                onSelect = { segment = it },
            )
        }
        if (segment == 1) {
            PlanLibraryRoute(
                onNewPlan = onNewPlan,
                onOpenPlan = onOpenPlan,
                onOpenExerciseLibrary = onOpenExerciseLibrary,
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
            )
        } else {
            // The client list is a separate feature.
            EmptyState(
                title = stringResource(Res.string.placeholder_title),
                message = stringResource(Res.string.placeholder_message),
                icon = Icons.Outlined.Construction,
            )
        }
    }
}

@Composable
private fun TopLevelPlaceholder(title: String, padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.gutter, vertical = Spacing.l)) {
        Text(title, style = AppTheme.type.screenTitle, modifier = Modifier.semantics { heading() })
        EmptyState(
            title = stringResource(Res.string.placeholder_title),
            message = stringResource(Res.string.placeholder_message),
            icon = Icons.Outlined.Construction,
        )
    }
}
