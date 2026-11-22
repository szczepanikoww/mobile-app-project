package com.trainingapp.app.photos

import androidx.compose.ui.graphics.ImageBitmap
import com.trainingapp.data.remote.ApiClient
import com.trainingapp.data.remote.ApiHttpException
import com.trainingapp.designsystem.component.ExercisePhotoSource
import com.trainingapp.domain.exercise.ExerciseRepository
import com.trainingapp.feature.workoutbuilder.common.loadExerciseImage
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.decodeToImageBitmap
import pl.trainer.ui.MyPhoto
import pl.trainer.ui.ProfilePhotoSource

/**
 * Profile photos from the server (`GET /v1/me/photo`, `GET /v1/users/{id}/photo`) and the own upload
 * (`PUT /v1/me/photo`). Loaded photos and "no photo" answers are kept in memory for the app's lifetime; a failed
 * load (offline) is not kept, so it is tried again next time. An upload refreshes the own photo everywhere.
 */
class RemoteProfilePhotos(private val api: ApiClient) : ProfilePhotoSource {
    private val cache = mutableMapOf<String, ImageBitmap?>()
    private val lock = Mutex()
    private val mutableRevision = MutableStateFlow(0)
    override val revision: StateFlow<Int> = mutableRevision.asStateFlow()

    override suspend fun load(key: String): ImageBitmap? {
        lock.withLock { if (key in cache) return cache[key] }
        val path = if (key == MyPhoto) "v1/me/photo" else "v1/users/$key/photo"
        val photo = try {
            val response = api.raw(HttpMethod.Get, path)
            if (response.status.isSuccess()) response.readRawBytes().decodeToImageBitmap() else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiHttpException) {
            // 404: no photo (or not visible). Remember it, so lists do not ask again.
            if (e.status == 404) lock.withLock { cache[key] = null }
            return null
        } catch (_: Exception) {
            return null
        }
        lock.withLock { cache[key] = photo }
        return photo
    }

    /** Forgets every loaded photo (another account signed in). */
    suspend fun clear() {
        lock.withLock { cache.clear() }
        mutableRevision.update { it + 1 }
    }

    /** Uploads [jpeg] as the signed-in user's photo. Returns true when the server stored it. */
    suspend fun upload(jpeg: ByteArray): Boolean {
        val stored = try {
            api.raw(HttpMethod.Put, "v1/me/photo") {
                contentType(ContentType.Image.JPEG)
                setBody(jpeg)
            }.status.isSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (stored) {
            // Other users' copies of this photo are refreshed on their next app start.
            lock.withLock { cache.remove(MyPhoto) }
            mutableRevision.update { it + 1 }
        }
        return stored
    }
}

/**
 * Exercise photos bundled with the app (free-exercise-db start positions, `:feature:workout-builder` resources).
 * A plan's exercise id is resolved to its library source id through the local exercise library, then through
 * [remoteSourceId] (the server's exercises: a server plan uses the server's exercise ids).
 */
class BundledExercisePhotos(
    private val exercises: ExerciseRepository,
    private val remoteSourceId: (suspend (exerciseId: String) -> String?)? = null,
) : ExercisePhotoSource {
    private val cache = mutableMapOf<String, ImageBitmap?>()
    private val sourceIds = mutableMapOf<String, String?>()
    private val lock = Mutex()

    override suspend fun byExerciseId(exerciseId: String): ImageBitmap? {
        val sourceId = lock.withLock { sourceIds[exerciseId] } ?: run {
            val local = runCatching { exercises.getExercise(exerciseId) }.getOrNull()?.sourceId
            val resolved = local ?: remoteSourceId?.let { runCatching { it(exerciseId) }.getOrNull() }
            if (resolved != null) lock.withLock { sourceIds[exerciseId] = resolved }
            resolved
        } ?: return null
        return bySourceId(sourceId)
    }

    override suspend fun bySourceId(sourceId: String): ImageBitmap? {
        lock.withLock { if (sourceId in cache) return cache[sourceId] }
        val image = loadExerciseImage(sourceId) ?: SourceIdAliases[sourceId]?.let { loadExerciseImage(it) }
        lock.withLock { cache[sourceId] = image }
        return image
    }

    private companion object {
        /** Source ids used by the server's demo seed whose bundled photo has a longer dataset name. */
        val SourceIdAliases = mapOf("Barbell_Bench_Press" to "Barbell_Bench_Press_-_Medium_Grip")
    }
}

/** The library source id of a server exercise (`GET /v1/exercises?ids=`), or null. */
suspend fun ApiClient.exerciseSourceId(exerciseId: String): String? =
    get(
        "v1/exercises",
        kotlinx.serialization.builtins.ListSerializer(com.trainingapp.contract.exercise.ExerciseDto.serializer()),
        query = listOf("ids" to exerciseId),
    ).firstOrNull()?.sourceId
