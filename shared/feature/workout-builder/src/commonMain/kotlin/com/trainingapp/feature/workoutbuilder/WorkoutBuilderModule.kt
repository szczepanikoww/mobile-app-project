package com.trainingapp.feature.workoutbuilder

import com.trainingapp.domain.exercise.GetExercise
import com.trainingapp.domain.exercise.SaveCustomExercise
import com.trainingapp.domain.exercise.SearchExercises
import com.trainingapp.domain.exercise.SetExerciseHidden
import com.trainingapp.domain.plan.GetPlanLibrary
import com.trainingapp.domain.plan.OpenPlanDraft
import com.trainingapp.domain.plan.PublishPlan
import com.trainingapp.domain.plan.SavePlanDraft
import com.trainingapp.feature.workoutbuilder.common.DefaultDayNames
import com.trainingapp.feature.workoutbuilder.common.ExerciseBrowserDestination
import com.trainingapp.feature.workoutbuilder.common.ExerciseDetailDestination
import com.trainingapp.feature.workoutbuilder.common.ExerciseFormDestination
import com.trainingapp.feature.workoutbuilder.common.ExercisePickerCoordinator
import com.trainingapp.feature.workoutbuilder.common.PlanEditorDestination
import com.trainingapp.feature.workoutbuilder.common.ResourceDefaultDayNames
import com.trainingapp.feature.workoutbuilder.editor.PlanEditorViewModel
import com.trainingapp.feature.workoutbuilder.exercises.ExerciseBrowserViewModel
import com.trainingapp.feature.workoutbuilder.exercises.ExerciseDetailViewModel
import com.trainingapp.feature.workoutbuilder.exercises.ExerciseFormViewModel
import com.trainingapp.feature.workoutbuilder.plans.PlanLibraryViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Use cases and view models of the plan creator. Repositories, [com.trainingapp.domain.common.NetworkMonitor],
 * [com.trainingapp.domain.common.CurrentUser], [com.trainingapp.domain.common.AppLanguage], ids and time are
 * provided by the app.
 */
val workoutBuilderModule = module {
    single { ExercisePickerCoordinator() }
    single<DefaultDayNames> { ResourceDefaultDayNames() }

    factory { GetPlanLibrary(get()) }
    factory { OpenPlanDraft(get(), get()) }
    factory { SavePlanDraft(get(), get()) }
    factory { PublishPlan(get(), get(), get(), get()) }
    factory { SearchExercises(get()) }
    factory { GetExercise(get()) }
    factory { SaveCustomExercise(get(), get(), get(), get(), get(), get()) }
    factory { SetExerciseHidden(get(), get()) }

    viewModel { PlanLibraryViewModel(get(), get(), get()) }
    viewModel { (destination: PlanEditorDestination) ->
        PlanEditorViewModel(
            destination = destination,
            openDraft = get(),
            saveDraft = get(),
            publishPlan = get(),
            plans = get(),
            exerciseRepository = get(),
            coordinator = get(),
            network = get(),
            language = get(),
            defaultDayNames = get(),
            ids = get(),
        )
    }
    viewModel { (destination: ExerciseBrowserDestination) -> ExerciseBrowserViewModel(destination, get(), get(), get()) }
    viewModel { (destination: ExerciseDetailDestination) -> ExerciseDetailViewModel(destination, get(), get(), get(), get(), get(), get()) }
    viewModel { (destination: ExerciseFormDestination) -> ExerciseFormViewModel(destination, get(), get(), get(), get(), get(), get(), get(), get()) }
}
