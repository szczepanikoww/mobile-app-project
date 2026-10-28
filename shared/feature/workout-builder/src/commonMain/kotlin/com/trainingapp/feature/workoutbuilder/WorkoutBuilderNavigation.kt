package com.trainingapp.feature.workoutbuilder

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.trainingapp.feature.workoutbuilder.common.ExerciseBrowserDestination
import com.trainingapp.feature.workoutbuilder.common.ExerciseDetailDestination
import com.trainingapp.feature.workoutbuilder.common.ExerciseFormDestination
import com.trainingapp.feature.workoutbuilder.common.PlanEditorDestination
import com.trainingapp.feature.workoutbuilder.common.PlanLibraryDestination
import com.trainingapp.feature.workoutbuilder.plans.PlanLibraryScreen
import com.trainingapp.feature.workoutbuilder.editor.PlanEditorScreen
import com.trainingapp.feature.workoutbuilder.exercises.ExerciseBrowserScreen
import com.trainingapp.feature.workoutbuilder.exercises.ExerciseDetailScreen
import com.trainingapp.feature.workoutbuilder.exercises.ExerciseFormScreen

/**
 * The full-screen destinations of the plan creator and exercise library, all opened over the app shell.
 *
 * [onAssignToClient] is the integration point with the clients/assignment feature ("Przypisz klientowi").
 */
fun NavGraphBuilder.workoutBuilderGraph(
    navController: NavController,
    onAssignToClient: (templateId: String, versionNumber: Int) -> Unit,
) {
    composable<PlanLibraryDestination> {
        PlanLibraryScreen(
            onBack = { navController.popBackStack() },
            onNewPlan = { navController.navigate(PlanEditorDestination()) },
            onOpenPlan = { item ->
                navController.navigate(
                    // A published plan opens its unpublished draft, if any, or its latest version.
                    if (item.templateId != null) PlanEditorDestination(templateId = item.templateId) else PlanEditorDestination(draftId = item.draftId),
                )
            },
            onOpenExerciseLibrary = { navController.navigate(ExerciseBrowserDestination()) },
        )
    }
    composable<PlanEditorDestination> { entry ->
        PlanEditorScreen(
            destination = entry.toRoute(),
            onBack = { navController.popBackStack() },
            onOpenPicker = { key, single -> navController.navigate(ExerciseBrowserDestination(pickRequestKey = key, singleSelect = single)) },
            onOpenDraft = { draftId ->
                navController.navigate(PlanEditorDestination(draftId = draftId)) {
                    popUpTo<PlanEditorDestination> { inclusive = true }
                }
            },
            onAssignToClient = onAssignToClient,
        )
    }
    composable<ExerciseBrowserDestination> { entry ->
        val destination = entry.toRoute<ExerciseBrowserDestination>()
        ExerciseBrowserScreen(
            destination = destination,
            onBack = { navController.popBackStack() },
            onOpenExercise = { id -> navController.navigate(ExerciseDetailDestination(id, destination.pickRequestKey)) },
            onCreateExercise = { prefill -> navController.navigate(ExerciseFormDestination(prefillName = prefill, pickRequestKey = destination.pickRequestKey)) },
        )
    }
    composable<ExerciseDetailDestination> { entry ->
        ExerciseDetailScreen(
            destination = entry.toRoute(),
            onBack = { navController.popBackStack() },
            onEdit = { id -> navController.navigate(ExerciseFormDestination(exerciseId = id)) },
            // Added to the day: close the detail and the picker under it, back to the editor.
            onAddedToDay = { navController.popBackStack<ExerciseBrowserDestination>(inclusive = true) },
        )
    }
    composable<ExerciseFormDestination> { entry ->
        ExerciseFormScreen(
            destination = entry.toRoute(),
            onClose = { navController.popBackStack() },
            onSaved = { navController.popBackStack() },
            onOpenExercise = { id -> navController.navigate(ExerciseDetailDestination(id)) },
        )
    }
}
