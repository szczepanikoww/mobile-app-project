package com.trainingapp.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trainingapp.app.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import pl.trainer.domain.TrainerRepository
import pl.trainer.presentation.TrainerViewModel
import pl.trainer.ui.ActionRow
import pl.trainer.ui.TrainerApp

/** Actions the trainer shell needs from the app. [onLogOut] returns to the guest views (Android profile screen). */
data class WorkoutNavigationActions(
    val onNewPlan: () -> Unit,
    val onOpenPlans: () -> Unit,
    val onOpenExercises: () -> Unit,
    val onLogOut: () -> Unit = {},
)

/**
 * Desktop uses the calendar/trainee shell; Android supplies main's host through App.host. The calendar reads the
 * shared demo store, so the client's requests appear here.
 */
@Composable
fun TrainerShell(actions: WorkoutNavigationActions) {
    val repository: TrainerRepository = koinInject()
    TrainerApp(model = viewModel { TrainerViewModel(repository) }, extraCreateActions = { dismiss -> WorkoutCreateActions(actions, dismiss) })
}

/** The same create sheet extends the session/availability actions on both platforms. */
@Composable
fun WorkoutCreateActions(actions: WorkoutNavigationActions, dismiss: () -> Unit) {
    ActionRow(Icons.Outlined.FitnessCenter, stringResource(Res.string.add_new_plan), stringResource(Res.string.add_new_plan_hint)) {
        dismiss(); actions.onNewPlan()
    }
    ActionRow(Icons.AutoMirrored.Outlined.Assignment, stringResource(Res.string.add_plans), stringResource(Res.string.add_plans_hint)) {
        dismiss(); actions.onOpenPlans()
    }
    ActionRow(Icons.AutoMirrored.Outlined.LibraryBooks, stringResource(Res.string.add_exercises), stringResource(Res.string.add_exercises_hint)) {
        dismiss(); actions.onOpenExercises()
    }
}
