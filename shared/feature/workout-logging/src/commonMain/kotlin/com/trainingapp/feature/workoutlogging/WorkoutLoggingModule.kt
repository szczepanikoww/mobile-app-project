package com.trainingapp.feature.workoutlogging

import com.trainingapp.feature.workoutlogging.logger.LiveWorkoutViewModel
import com.trainingapp.feature.workoutlogging.progress.ProgressViewModel
import com.trainingapp.feature.workoutlogging.summary.WorkoutSummaryViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * View models of the client workout views. The app provides the repositories, [com.trainingapp.domain.workout.CurrentClient],
 * [com.trainingapp.domain.workout.TodayWorkoutSource], [com.trainingapp.domain.workout.TechniqueVideoOutbox] and
 * [com.trainingapp.feature.workoutlogging.rest.RestTimerNotifier].
 */
val workoutLoggingModule = module {
    viewModel {
        LiveWorkoutViewModel(
            workouts = get(),
            todaySource = get(),
            exerciseRepository = get(),
            media = get(),
            outbox = get(),
            client = get(),
            notifier = get(),
            time = get(),
            ids = get(),
            language = get(),
        )
    }
    viewModel { (destination: WorkoutSummaryDestination) ->
        WorkoutSummaryViewModel(destination, get(), get(), get(), get(), get())
    }
    viewModel { ProgressViewModel(get(), get(), get(), get(), get(), get(), get()) }
}
