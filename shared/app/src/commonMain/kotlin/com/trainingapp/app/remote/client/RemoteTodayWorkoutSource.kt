package com.trainingapp.app.remote.client

import com.trainingapp.contract.sync.TodayWorkoutDto
import com.trainingapp.data.remote.ApiClient
import com.trainingapp.data.workout.LocalWorkoutSyncStore
import com.trainingapp.domain.workout.TodayWorkout
import com.trainingapp.domain.workout.TodayWorkoutSource
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException

/**
 * Today's plan day from `GET /v1/client/today` (204 = nothing to do). The last answer is kept on the device per
 * client, so the plan is still there offline or while the server is unreachable. A day that is not in the returned
 * plan version is treated as "nothing today" instead of crashing [TodayWorkout.day].
 */
class RemoteTodayWorkoutSource(
    private val api: ApiClient,
    private val store: LocalWorkoutSyncStore,
) : TodayWorkoutSource {

    override suspend fun today(clientId: String): TodayWorkout? {
        val dto = try {
            val response = api.raw(HttpMethod.Get, "v1/client/today")
            val fresh = if (response.status == HttpStatusCode.NoContent) {
                null
            } else {
                api.json.decodeFromString(TodayWorkoutDto.serializer(), response.bodyAsText())
            }
            store.putKv(clientId, CACHE_KEY, if (fresh == null) NONE else api.json.encodeToString(TodayWorkoutDto.serializer(), fresh))
            fresh
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            readCache(clientId)
        }
        return dto?.toDomain()
    }

    private suspend fun readCache(clientId: String): TodayWorkoutDto? {
        val text = store.kv(clientId, CACHE_KEY)?.takeIf { it != NONE } ?: return null
        return runCatching { api.json.decodeFromString(TodayWorkoutDto.serializer(), text) }.getOrNull()
    }

    private fun TodayWorkoutDto.toDomain(): TodayWorkout? {
        if (planVersion.body.days.none { it.id == dayId }) return null
        return TodayWorkout(planVersion = planVersion, dayId = dayId, sessionId = sessionId, place = place)
    }

    private companion object {
        const val CACHE_KEY = "today"
        const val NONE = "none"
    }
}
