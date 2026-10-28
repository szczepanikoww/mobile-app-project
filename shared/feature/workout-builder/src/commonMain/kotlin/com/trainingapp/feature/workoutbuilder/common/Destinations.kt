package com.trainingapp.feature.workoutbuilder.common

import kotlinx.serialization.Serializable

/** S2. Opens a stored draft, the draft or latest version of a template, or (both null) a new plan. */
@Serializable
data class PlanEditorDestination(val draftId: String? = null, val templateId: String? = null)

/** S3. Browse mode without [pickRequestKey]; pick mode (from the editor) with it. */
@Serializable
data class ExerciseBrowserDestination(val pickRequestKey: String? = null, val singleSelect: Boolean = false)

/** S4. With [pickRequestKey], "Dodaj do dnia" adds the exercise to the day the picker was opened for. */
@Serializable
data class ExerciseDetailDestination(val exerciseId: String, val pickRequestKey: String? = null)

/** S5. Creates an exercise ([exerciseId] null) or edits an own one. In pick mode the new exercise is selected. */
@Serializable
data class ExerciseFormDestination(
    val exerciseId: String? = null,
    val prefillName: String? = null,
    val pickRequestKey: String? = null,
)
