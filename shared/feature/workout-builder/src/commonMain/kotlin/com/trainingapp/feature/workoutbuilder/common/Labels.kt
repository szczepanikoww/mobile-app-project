package com.trainingapp.feature.workoutbuilder.common

import androidx.compose.runtime.Composable
import com.trainingapp.contract.exercise.Equipment
import com.trainingapp.contract.exercise.MuscleGroup
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.feature.workoutbuilder.resources.Res
import com.trainingapp.feature.workoutbuilder.resources.equipment_bands
import com.trainingapp.feature.workoutbuilder.resources.equipment_barbell
import com.trainingapp.feature.workoutbuilder.resources.equipment_body_only
import com.trainingapp.feature.workoutbuilder.resources.equipment_cable
import com.trainingapp.feature.workoutbuilder.resources.equipment_dumbbell
import com.trainingapp.feature.workoutbuilder.resources.equipment_exercise_ball
import com.trainingapp.feature.workoutbuilder.resources.equipment_ez_bar
import com.trainingapp.feature.workoutbuilder.resources.equipment_foam_roll
import com.trainingapp.feature.workoutbuilder.resources.equipment_kettlebell
import com.trainingapp.feature.workoutbuilder.resources.equipment_machine
import com.trainingapp.feature.workoutbuilder.resources.equipment_medicine_ball
import com.trainingapp.feature.workoutbuilder.resources.equipment_other
import com.trainingapp.feature.workoutbuilder.resources.muscle_abdominals
import com.trainingapp.feature.workoutbuilder.resources.muscle_abductors
import com.trainingapp.feature.workoutbuilder.resources.muscle_adductors
import com.trainingapp.feature.workoutbuilder.resources.muscle_biceps
import com.trainingapp.feature.workoutbuilder.resources.muscle_calves
import com.trainingapp.feature.workoutbuilder.resources.muscle_chest
import com.trainingapp.feature.workoutbuilder.resources.muscle_forearms
import com.trainingapp.feature.workoutbuilder.resources.muscle_glutes
import com.trainingapp.feature.workoutbuilder.resources.muscle_hamstrings
import com.trainingapp.feature.workoutbuilder.resources.muscle_lats
import com.trainingapp.feature.workoutbuilder.resources.muscle_lower_back
import com.trainingapp.feature.workoutbuilder.resources.muscle_middle_back
import com.trainingapp.feature.workoutbuilder.resources.muscle_neck
import com.trainingapp.feature.workoutbuilder.resources.muscle_quadriceps
import com.trainingapp.feature.workoutbuilder.resources.muscle_shoulders
import com.trainingapp.feature.workoutbuilder.resources.muscle_traps
import com.trainingapp.feature.workoutbuilder.resources.muscle_triceps
import com.trainingapp.feature.workoutbuilder.resources.tracking_distance_time
import com.trainingapp.feature.workoutbuilder.resources.tracking_reps
import com.trainingapp.feature.workoutbuilder.resources.tracking_time
import com.trainingapp.feature.workoutbuilder.resources.tracking_weight_reps
import com.trainingapp.domain.exercise.Exercise
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

val MuscleGroup.labelRes: StringResource
    get() = when (this) {
        MuscleGroup.ABDOMINALS -> Res.string.muscle_abdominals
        MuscleGroup.ABDUCTORS -> Res.string.muscle_abductors
        MuscleGroup.ADDUCTORS -> Res.string.muscle_adductors
        MuscleGroup.BICEPS -> Res.string.muscle_biceps
        MuscleGroup.CALVES -> Res.string.muscle_calves
        MuscleGroup.CHEST -> Res.string.muscle_chest
        MuscleGroup.FOREARMS -> Res.string.muscle_forearms
        MuscleGroup.GLUTES -> Res.string.muscle_glutes
        MuscleGroup.HAMSTRINGS -> Res.string.muscle_hamstrings
        MuscleGroup.LATS -> Res.string.muscle_lats
        MuscleGroup.LOWER_BACK -> Res.string.muscle_lower_back
        MuscleGroup.MIDDLE_BACK -> Res.string.muscle_middle_back
        MuscleGroup.NECK -> Res.string.muscle_neck
        MuscleGroup.QUADRICEPS -> Res.string.muscle_quadriceps
        MuscleGroup.SHOULDERS -> Res.string.muscle_shoulders
        MuscleGroup.TRAPS -> Res.string.muscle_traps
        MuscleGroup.TRICEPS -> Res.string.muscle_triceps
    }

val Equipment.labelRes: StringResource
    get() = when (this) {
        Equipment.BARBELL -> Res.string.equipment_barbell
        Equipment.DUMBBELL -> Res.string.equipment_dumbbell
        Equipment.CABLE -> Res.string.equipment_cable
        Equipment.MACHINE -> Res.string.equipment_machine
        Equipment.KETTLEBELL -> Res.string.equipment_kettlebell
        Equipment.BANDS -> Res.string.equipment_bands
        Equipment.BODY_ONLY -> Res.string.equipment_body_only
        Equipment.MEDICINE_BALL -> Res.string.equipment_medicine_ball
        Equipment.EXERCISE_BALL -> Res.string.equipment_exercise_ball
        Equipment.FOAM_ROLL -> Res.string.equipment_foam_roll
        Equipment.EZ_BAR -> Res.string.equipment_ez_bar
        Equipment.OTHER -> Res.string.equipment_other
    }

val TrackingType.labelRes: StringResource
    get() = when (this) {
        TrackingType.WEIGHT_REPS -> Res.string.tracking_weight_reps
        TrackingType.REPS -> Res.string.tracking_reps
        TrackingType.TIME -> Res.string.tracking_time
        TrackingType.DISTANCE_TIME -> Res.string.tracking_distance_time
    }

/** "Klatka piersiowa · Sztanga". */
@Composable
fun Exercise.muscleAndEquipment(): String = listOfNotNull(
    primaryMuscle?.let { stringResource(it.labelRes) },
    stringResource(equipment.labelRes),
).joinToString(" · ")
