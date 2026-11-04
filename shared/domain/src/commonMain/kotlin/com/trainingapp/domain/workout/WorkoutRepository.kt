package com.trainingapp.domain.workout

import com.trainingapp.contract.plan.PlanVersionDto
import kotlinx.coroutines.flow.Flow

/**
 * Workout logs on this device. Every change is saved at once, and saving the same log or set again updates one
 * record. Sync with the server is a later slice; until then every log stays [WorkoutSyncState.LOCAL].
 */
interface WorkoutRepository {
    /** The unfinished workout of [clientId], if the app was closed during one. */
    fun observeActive(clientId: String): Flow<WorkoutLog?>

    suspend fun getActive(clientId: String): WorkoutLog?

    fun observe(id: String): Flow<WorkoutLog?>

    suspend fun get(id: String): WorkoutLog?

    /** Writes the log and all its sets; sets no longer in the log are deleted. */
    suspend fun save(log: WorkoutLog)

    suspend fun delete(id: String)

    /** Finished workouts, newest first. */
    fun observeHistory(clientId: String): Flow<List<WorkoutLog>>

    suspend fun history(clientId: String): List<WorkoutLog>
}

interface MeasurementRepository {
    /** Newest first. */
    fun observe(clientId: String): Flow<List<BodyMeasurement>>

    suspend fun add(measurement: BodyMeasurement)
}

/** The plan day the client should do today, from their assigned (immutable) plan version. */
data class TodayWorkout(
    val planVersion: PlanVersionDto,
    val dayId: String,
    /** The booked session this workout belongs to, if any. */
    val sessionId: String? = null,
    val place: String? = null,
) {
    val day get() = planVersion.body.days.first { it.id == dayId }
}

/**
 * Integration point with the plan assignment flow, which does not exist yet. The app provides a demo fixture;
 * the real assignment repository replaces only this source.
 */
fun interface TodayWorkoutSource {
    suspend fun today(clientId: String): TodayWorkout?
}

/** The signed-in client. Sign-in and the client shell are not implemented yet; the app wires a synthetic client. */
interface CurrentClient {
    val clientId: String
    val firstName: String
    /** The trainer–client relationship whose conversation receives technique videos. */
    val trainerRelationshipId: String
}

/** What a technique video shows: the exercise and the set it was recorded for. */
data class TechniqueVideoContext(
    val workoutLogId: String,
    val exerciseId: String,
    val exerciseName: String,
    val setNumber: Int,
    val setCount: Int,
    val weightGrams: Int?,
    val reps: Int?,
)

/**
 * Sends a technique video to the trainer's conversation. The chat lives in the Android app; a platform without chat
 * gets [None], and the video stays linked to its set.
 */
fun interface TechniqueVideoOutbox {
    suspend fun send(relationshipId: String, mediaId: String, context: TechniqueVideoContext): Boolean

    companion object {
        val None = TechniqueVideoOutbox { _, _, _ -> false }
    }
}
