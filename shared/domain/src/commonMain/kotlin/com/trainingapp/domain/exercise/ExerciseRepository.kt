package com.trainingapp.domain.exercise

import kotlinx.coroutines.flow.Flow

/**
 * The exercise library on this device: the global library plus the trainer's own exercises, hidden ones included.
 * Own exercises are written locally first and synced through an operation queue.
 */
interface ExerciseRepository {
    fun observeExercises(): Flow<List<Exercise>>

    fun observeExercise(id: String): Flow<Exercise?>

    suspend fun getExercise(id: String): Exercise?

    /** Saves locally with [SyncState.PENDING] and schedules a sync. Saving twice with the same id updates one record. */
    suspend fun saveCustomExercise(exercise: Exercise)

    suspend fun setHidden(id: String, hidden: Boolean)

    /** Sends one pending own exercise now. Returns true when it is synced. */
    suspend fun syncExercise(id: String): Boolean

    /** Sends every pending own exercise. Returns the ids that are still not synced. */
    suspend fun syncPending(): List<String>

    /** Most recently used first. */
    fun observeRecentlyUsedIds(): Flow<List<String>>

    suspend fun markUsed(ids: List<String>)
}

/** Whether a published plan version or a workout log references an exercise. It locks the tracking type. */
fun interface ExerciseUsage {
    suspend fun isInUse(exerciseId: String): Boolean
}
