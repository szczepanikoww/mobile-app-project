package com.trainingapp.feature.workoutlogging

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.trainingapp.feature.workoutbuilder.common.ExerciseBrowserDestination
import com.trainingapp.feature.workoutlogging.logger.LiveWorkoutRoute
import com.trainingapp.feature.workoutlogging.progress.ProgressRoute
import com.trainingapp.feature.workoutlogging.summary.WorkoutSummaryRoute
import kotlinx.serialization.Serializable

/** A1 "Trening na żywo": today's plan, an empty workout of the client's own, or the unfinished one restored. */
@Serializable
data object LiveWorkoutDestination

/** A2. [readOnly] when opened from the history in A3. */
@Serializable
data class WorkoutSummaryDestination(val workoutLogId: String, val readOnly: Boolean = false)

/**
 * A3 "Postępy" as a pushed screen. In the client shell (Person B) it is a tab: the shell renders [ProgressRoute]
 * without `onBack` and owns the bottom navigation.
 */
@Serializable
data object ProgressDestination

/** The client's workout destinations, opened over the app shell. */
fun NavGraphBuilder.workoutLoggingGraph(navController: NavController) {
    composable<LiveWorkoutDestination> {
        LiveWorkoutRoute(
            onBack = { navController.popBackStack() },
            onOpenSummary = { id -> navController.navigate(WorkoutSummaryDestination(id)) },
            onExit = { navController.popBackStack() },
            // The library in pick mode, without the trainer's create/edit tools.
            onPickExercises = { key -> navController.navigate(ExerciseBrowserDestination(pickRequestKey = key, libraryEditable = false)) },
        )
    }
    composable<WorkoutSummaryDestination> { entry ->
        WorkoutSummaryRoute(
            destination = entry.toRoute(),
            onBack = { navController.popBackStack() },
            // Saved: close the summary and the live workout under it, back to where the workout was started.
            onSaved = {
                if (!navController.popBackStack<LiveWorkoutDestination>(inclusive = true)) navController.popBackStack()
            },
        )
    }
    composable<ProgressDestination> {
        ProgressRoute(
            onOpenWorkout = { id -> navController.navigate(WorkoutSummaryDestination(id, readOnly = true)) },
            onBack = { navController.popBackStack() },
        )
    }
}
