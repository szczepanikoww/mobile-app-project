package com.trainingapp.data.exercise

import com.trainingapp.contract.exercise.UpsertExerciseRequest
import com.trainingapp.domain.common.NetworkMonitor
import kotlinx.coroutines.delay

/** `PUT /exercises/{id}` and `POST /exercises/{id}/hide`. Both are idempotent. */
interface ExerciseRemoteDataSource {
    suspend fun upsert(request: UpsertExerciseRequest): Boolean

    suspend fun setHidden(id: String, hidden: Boolean): Boolean
}

/** Stands in for the backend until it exists: succeeds whenever the device is online. */
class FakeExerciseRemoteDataSource(
    private val network: NetworkMonitor,
    private val latencyMs: Long = 0,
) : ExerciseRemoteDataSource {
    override suspend fun upsert(request: UpsertExerciseRequest): Boolean = respond()

    override suspend fun setHidden(id: String, hidden: Boolean): Boolean = respond()

    private suspend fun respond(): Boolean {
        if (latencyMs > 0) delay(latencyMs)
        return network.isOnline.value
    }
}
