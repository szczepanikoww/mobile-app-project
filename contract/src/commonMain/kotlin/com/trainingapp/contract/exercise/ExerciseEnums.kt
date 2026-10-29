package com.trainingapp.contract.exercise

import kotlinx.serialization.Serializable

/** The 17 muscle values used by `free-exercise-db`. [datasetKey] is the dataset's spelling. */
@Serializable
enum class MuscleGroup(val datasetKey: String) {
    ABDOMINALS("abdominals"),
    ABDUCTORS("abductors"),
    ADDUCTORS("adductors"),
    BICEPS("biceps"),
    CALVES("calves"),
    CHEST("chest"),
    FOREARMS("forearms"),
    GLUTES("glutes"),
    HAMSTRINGS("hamstrings"),
    LATS("lats"),
    LOWER_BACK("lower back"),
    MIDDLE_BACK("middle back"),
    NECK("neck"),
    QUADRICEPS("quadriceps"),
    SHOULDERS("shoulders"),
    TRAPS("traps"),
    TRICEPS("triceps");

    companion object {
        fun fromDatasetKey(key: String): MuscleGroup? = entries.firstOrNull { it.datasetKey == key }
    }
}

/** Equipment values from `free-exercise-db`. A missing dataset value maps to [OTHER]. */
@Serializable
enum class Equipment(val datasetKey: String?) {
    BARBELL("barbell"),
    DUMBBELL("dumbbell"),
    CABLE("cable"),
    MACHINE("machine"),
    KETTLEBELL("kettlebells"),
    BANDS("bands"),
    BODY_ONLY("body only"),
    MEDICINE_BALL("medicine ball"),
    EXERCISE_BALL("exercise ball"),
    FOAM_ROLL("foam roll"),
    EZ_BAR("e-z curl bar"),
    OTHER("other");

    companion object {
        fun fromDatasetKey(key: String?): Equipment =
            if (key == null) OTHER else entries.firstOrNull { it.datasetKey == key } ?: OTHER
    }
}

/** How a set of the exercise is recorded. The planner and the workout logger both depend on it. */
@Serializable
enum class TrackingType { WEIGHT_REPS, REPS, TIME, DISTANCE_TIME }

@Serializable
enum class SetType { WARM_UP, WORKING, DROP }

@Serializable
enum class ExerciseStatus { ACTIVE, HIDDEN }
