package com.trainingapp.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource

/** The trainer's top-level destinations (bottom navigation). Detail screens are pushed over it. */
@Serializable
data object TrainerHomeDestination

/**
 * The app entry point shared by Android and the desktop preview. Only the trainer role exists so far; client and
 * guest shells will switch on the active role here, with the same theme.
 */
@Composable
fun App() {
    AppTheme {
        val navController = rememberNavController()
        var showAssignInfo by rememberSaveable { mutableStateOf(false) }

        NavHost(navController = navController, startDestination = TrainerHomeDestination) {
            composable<TrainerHomeDestination> {
                TrainerShell(
                    onNewPlan = { navController.navigate(PlanEditorDestination()) },
                    onOpenPlans = { navController.navigate(PlanLibraryDestination) },
                    onOpenExerciseLibrary = { navController.navigate(ExerciseBrowserDestination()) },
                )
            }
            workoutBuilderGraph(
                navController = navController,
                // Integration point: the assignment flow belongs to the clients feature, which does not exist yet.
                onAssignToClient = { _, _ -> showAssignInfo = true },
            )
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
