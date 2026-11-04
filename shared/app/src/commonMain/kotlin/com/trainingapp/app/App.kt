package com.trainingapp.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.trainingapp.app.resources.Res
import com.trainingapp.app.resources.assign_message
import com.trainingapp.app.resources.assign_title
import com.trainingapp.app.resources.understood
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.feature.workoutbuilder.common.ExerciseBrowserDestination
import com.trainingapp.feature.workoutbuilder.common.PlanEditorDestination
import com.trainingapp.feature.workoutbuilder.common.PlanLibraryDestination
import com.trainingapp.feature.workoutbuilder.workoutBuilderGraph
import com.trainingapp.feature.workoutlogging.LiveWorkoutDestination
import com.trainingapp.feature.workoutlogging.ProgressDestination
import com.trainingapp.feature.workoutlogging.workoutLoggingGraph
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource

/** The trainer's top-level destinations (bottom navigation). Detail screens are pushed over it. */
@Serializable
data object TrainerHomeDestination

/** Temporary entry to the client views until Person B's client shell exists. Remove it when that shell lands. */
@Serializable
data object ClientPreviewDestination

/** The client's chat (A6), hosted by the Android app. */
@Serializable
data object ClientChatDestination

/**
 * The app entry point shared by Android and the desktop preview. Only the trainer role exists so far; client and
 * guest shells will switch on the active role here, with the same theme.
 *
 * [clientChat] renders the client conversation (Android only; the chat is not a shared module yet).
 * Incrementing [openLiveWorkoutRequest] opens the live workout, e.g. after tapping the rest notification.
 */
@Composable
fun App(
    host: (@Composable (WorkoutNavigationActions) -> Unit)? = null,
    clientChat: (@Composable (onBack: () -> Unit) -> Unit)? = null,
    openLiveWorkoutRequest: Int = 0,
) {
    AppTheme {
        val navController = rememberNavController()
        var showAssignInfo by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(openLiveWorkoutRequest) {
            if (openLiveWorkoutRequest > 0) {
                navController.navigate(LiveWorkoutDestination) { launchSingleTop = true }
            }
        }

        NavHost(navController = navController, startDestination = TrainerHomeDestination) {
            composable<TrainerHomeDestination> {
                val actions = WorkoutNavigationActions(
                    onNewPlan = { navController.navigate(PlanEditorDestination()) },
                    onOpenPlans = { navController.navigate(PlanLibraryDestination) },
                    onOpenExercises = { navController.navigate(ExerciseBrowserDestination()) },
                    onOpenClientPreview = { navController.navigate(ClientPreviewDestination) },
                )
                if (host != null) host(actions) else TrainerShell(actions)
            }
            workoutBuilderGraph(
                navController = navController,
                // Integration point: the assignment flow belongs to the relationship-scoped assignment API, which is not connected yet.
                onAssignToClient = { _, _ -> showAssignInfo = true },
            )
            workoutLoggingGraph(navController)
            composable<ClientPreviewDestination> {
                ClientPreviewScreen(
                    onBack = { navController.popBackStack() },
                    onLiveWorkout = { navController.navigate(LiveWorkoutDestination) },
                    onProgress = { navController.navigate(ProgressDestination) },
                    onChat = if (clientChat != null) {
                        { navController.navigate(ClientChatDestination) }
                    } else {
                        null
                    },
                )
            }
            composable<ClientChatDestination> {
                clientChat?.invoke { navController.popBackStack() }
            }
        }

        if (showAssignInfo) {
            AlertDialog(
                onDismissRequest = { showAssignInfo = false },
                title = { Text(stringResource(Res.string.assign_title)) },
                text = { Text(stringResource(Res.string.assign_message), style = AppTheme.type.body) },
                confirmButton = { AppTextButton(stringResource(Res.string.understood), onClick = { showAssignInfo = false }) },
            )
        }
    }
}
