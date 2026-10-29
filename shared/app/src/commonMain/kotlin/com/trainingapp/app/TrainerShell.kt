package com.trainingapp.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.runtime.Composable
import com.trainingapp.app.resources.*
import org.jetbrains.compose.resources.stringResource
import pl.trainer.ui.ActionRow
import pl.trainer.ui.TrainerApp

data class WorkoutNavigationActions(val onNewPlan: () -> Unit, val onOpenPlans: () -> Unit, val onOpenExercises: () -> Unit)

/** Desktop uses the calendar/trainee shell; Android supplies main's host through App.host. */
@Composable
fun TrainerShell(actions: WorkoutNavigationActions) {
    TrainerApp(extraCreateActions = { dismiss -> WorkoutCreateActions(actions, dismiss) })
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
