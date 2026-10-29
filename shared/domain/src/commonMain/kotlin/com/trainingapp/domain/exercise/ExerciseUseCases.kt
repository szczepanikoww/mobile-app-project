package com.trainingapp.domain.exercise

import com.trainingapp.contract.exercise.Equipment
import com.trainingapp.contract.exercise.ExerciseIssue
import com.trainingapp.contract.exercise.ExerciseSources
import com.trainingapp.contract.exercise.ExerciseStatus
import com.trainingapp.contract.exercise.ExerciseValidator
import com.trainingapp.contract.exercise.MuscleGroup
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.media.MediaKind
import com.trainingapp.domain.media.MediaRepository
import com.trainingapp.domain.media.PickedVideo
import com.trainingapp.domain.media.VideoProblem
import com.trainingapp.domain.media.VideoValidator
import com.trainingapp.domain.common.CurrentUser
import com.trainingapp.domain.common.IdGenerator
import com.trainingapp.domain.common.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SearchExercises(private val repository: ExerciseRepository) {
    operator fun invoke(filter: ExerciseFilter, languageTag: String): Flow<List<Exercise>> =
        repository.observeExercises().map { ExerciseSearch.search(it, filter, languageTag) }
}

class GetExercise(private val repository: ExerciseRepository) {
    operator fun invoke(id: String): Flow<Exercise?> = repository.observeExercise(id)
}

/** What the trainer entered in the exercise form. */
data class ExerciseInput(
    val name: String,
    val primaryMuscle: MuscleGroup?,
    val equipment: Equipment?,
    val trackingType: TrackingType?,
    val secondaryMuscles: List<MuscleGroup> = emptyList(),
    val instructions: String = "",
    val demoVideo: DemoVideoChange = DemoVideoChange.Keep,
)

/** What happens to the exercise's demo video on save. */
sealed interface DemoVideoChange {
    /** Keep the current video, or none. */
    data object Keep : DemoVideoChange
    data object Remove : DemoVideoChange
    data class Replace(val video: PickedVideo) : DemoVideoChange
}

sealed interface SaveExerciseResult {
    data class Saved(val exercise: Exercise) : SaveExerciseResult
    data class Invalid(val issues: List<ExerciseIssue>) : SaveExerciseResult

    /** The tracking type cannot change once a plan version or workout log uses the exercise. */
    data object TrackingTypeLocked : SaveExerciseResult
    data object NotOwner : SaveExerciseResult
    data object NotFound : SaveExerciseResult

    /** The picked video is too long, too large or empty. Nothing was saved. */
    data class VideoInvalid(val problem: VideoProblem) : SaveExerciseResult
}

/**
 * Creates or edits the trainer's own exercise, local-first with a client UUID.
 * The name is stored in the trainer's current language; other languages fall back to it.
 */
class SaveCustomExercise(
    private val repository: ExerciseRepository,
    private val media: MediaRepository,
    private val usage: ExerciseUsage,
    private val currentUser: CurrentUser,
    private val ids: IdGenerator,
    private val time: TimeSource,
) {
    suspend operator fun invoke(existingId: String?, input: ExerciseInput, languageTag: String): SaveExerciseResult {
        val issues = ExerciseValidator.validate(
            name = input.name,
            primaryMuscle = input.primaryMuscle,
            equipment = input.equipment,
            trackingType = input.trackingType,
            secondaryMuscles = input.secondaryMuscles,
            instructions = input.instructions,
        )
        if (issues.isNotEmpty()) return SaveExerciseResult.Invalid(issues)

        val existing = existingId?.let { repository.getExercise(it) ?: return SaveExerciseResult.NotFound }
        if (existing != null) {
            if (existing.ownerTrainerId != currentUser.trainerId) return SaveExerciseResult.NotOwner
            if (existing.trackingType != input.trackingType && usage.isInUse(existing.id)) {
                return SaveExerciseResult.TrackingTypeLocked
            }
        }
        val demoVideo = input.demoVideo
        if (demoVideo is DemoVideoChange.Replace) {
            VideoValidator.validate(demoVideo.video)?.let { return SaveExerciseResult.VideoInvalid(it) }
        }
        val demoMediaId = when (demoVideo) {
            DemoVideoChange.Keep -> existing?.demoMediaId
            DemoVideoChange.Remove -> null
            // The upload starts in the background; the exercise refers to the video at once.
            is DemoVideoChange.Replace -> media.import(demoVideo.video, MediaKind.EXERCISE_DEMO).id
        }
        val steps = input.instructions.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val exercise = Exercise(
            id = existing?.id ?: ids.newId(),
            ownerTrainerId = currentUser.trainerId,
            source = ExerciseSources.TRAINER,
            sourceId = null,
            names = mapOf(languageTag to input.name.trim()),
            aliases = emptyList(),
            primaryMuscles = listOf(input.primaryMuscle!!),
            secondaryMuscles = input.secondaryMuscles,
            equipment = input.equipment!!,
            trackingType = input.trackingType!!,
            instructions = if (steps.isEmpty()) emptyMap() else mapOf(languageTag to steps),
            images = existing?.images.orEmpty(),
            demoMediaId = demoMediaId,
            featured = false,
            status = existing?.status ?: ExerciseStatus.ACTIVE,
            syncState = SyncState.PENDING,
            updatedAtEpochMs = time.nowEpochMs(),
        )
        repository.saveCustomExercise(exercise)
        return SaveExerciseResult.Saved(exercise)
    }
}

/** Hiding is reversible and never deletes: plans and logs keep resolving the exercise. Only the owner may hide. */
class SetExerciseHidden(
    private val repository: ExerciseRepository,
    private val currentUser: CurrentUser,
) {
    suspend operator fun invoke(id: String, hidden: Boolean): Boolean {
        val exercise = repository.getExercise(id) ?: return false
        if (exercise.ownerTrainerId != currentUser.trainerId) return false
        repository.setHidden(id, hidden)
        return true
    }
}
