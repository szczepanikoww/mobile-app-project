package com.trainingapp.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trainingapp.app.resources.*
import org.jetbrains.compose.resources.stringResource
import pl.trainer.data.DemoTrainerRepository
import pl.trainer.presentation.Destination
import pl.trainer.presentation.TrainerViewModel
import pl.trainer.ui.ActionRow
import pl.trainer.ui.TrainerApp
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.feature.trainerchats.data.DemoTrainerChatsRepository
import pl.trainerapp.feature.trainerfinance.data.DemoTrainerFinanceRepository
import pl.trainerapp.feature.trainerhome.data.DemoTrainerHomeRepository
import pl.trainerapp.feature.trainernotifications.data.DemoTrainerNotificationsRepository
import pl.trainerapp.feature.trainerprofile.data.DemoTrainerProfileRepository
import pl.trainerapp.shell.TrainerDestination
import pl.trainerapp.shell.TrainerRepositories
import pl.trainerapp.shell.TrainerShell as MainTrainerShell

/** Actions the trainer shell needs from the app. [onLogOut] returns to the guest views (trainer profile screen). */
data class WorkoutNavigationActions(
    val onNewPlan: () -> Unit,
    val onOpenPlans: () -> Unit,
    val onOpenExercises: () -> Unit,
    val onLogOut: () -> Unit = {},
)

/**
 * The one trainer shell used by Android and the desktop preview: home, finance, chats, notifications and profile
 * from the main shell, with the calendar/clients feature and the workout-plan create actions inside it.
 */
@Composable
fun TrainerShell(actions: WorkoutNavigationActions) {
    // Explicit demo data sources: no backend exists yet.
    val repositories = remember {
        TrainerRepositories(
            home = DemoTrainerHomeRepository(),
            finance = DemoTrainerFinanceRepository(),
            chats = DemoTrainerChatsRepository(),
            notifications = DemoTrainerNotificationsRepository(),
            profile = DemoTrainerProfileRepository(),
        )
    }
    AppTheme {
        val trainer: TrainerViewModel = viewModel { TrainerViewModel(DemoTrainerRepository()) }
        MainTrainerShell(repositories, trainerFeature = { destination, createRequest, onMain ->
            LaunchedEffect(destination) {
                trainer.destination(if (destination == TrainerDestination.Calendar) Destination.Calendar else Destination.Clients)
            }
            TrainerApp(
                trainer,
                showNavigation = false,
                createRequest = createRequest,
                onMainRouteChanged = onMain,
                extraCreateActions = { dismiss -> WorkoutCreateActions(actions, dismiss) },
            )
        }, onLogOut = actions.onLogOut)
    }
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
