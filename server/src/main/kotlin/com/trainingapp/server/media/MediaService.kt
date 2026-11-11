package com.trainingapp.server.media

import com.trainingapp.contract.media.CreateUploadSessionRequest
import com.trainingapp.contract.media.MediaKind
import com.trainingapp.contract.media.MediaLimits
import com.trainingapp.contract.media.MediaStatusDto
import com.trainingapp.contract.media.RemoteMediaState
import com.trainingapp.contract.media.UploadSessionDto
import com.trainingapp.server.common.ApiException
import com.trainingapp.server.common.DeniedSubject
import com.trainingapp.server.common.ErrorCode
import com.trainingapp.server.common.conflict
import com.trainingapp.server.common.dbNow
import com.trainingapp.server.common.forbidden
import com.trainingapp.server.common.invalid
import com.trainingapp.server.common.notFound
import com.trainingapp.server.common.toOffset
import com.trainingapp.server.config.MediaConfig
import com.trainingapp.server.db.Db
import com.trainingapp.server.db.SqlState
import com.trainingapp.server.db.attempt
import com.trainingapp.server.identity.CurrentUser
import com.trainingapp.server.identity.IdentityService
import com.trainingapp.server.platform.JobQueue
import com.trainingapp.server.platform.JobQueue.schedule
import com.trainingapp.server.platform.Outbox.emit
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.io.InputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * The Media module (architecture §9.3, plan §8.5): upload authorisation, resumable upload, processing state and
 * short-lived signed playback links. Unfinished media is never presented as playable.
 */
class MediaService(
    private val db: Db,
    private val identity: IdentityService,
    private val storage: ObjectStorage,
    private val config: MediaConfig,
) {
    companion object {
        val TECHNIQUE_VIDEO_RETENTION = 90.days
        val STALE_UPLOAD = 24.hours
        val VIDEO_TYPES = setOf("video/mp4", "video/quicktime", "video/webm")
    }

    /** Idempotent by the phone's media id: a retry returns the same upload URL and the server's current offset. */
    suspend fun createUploadSession(user: CurrentUser, request: CreateUploadSessionRequest): UploadSessionDto {
        val id = runCatching { Uuid.parse(request.mediaId) }.getOrNull() ?: throw invalid("mediaId")
        if (request.contentType !in VIDEO_TYPES) throw invalid("contentType")
        if (request.sizeBytes <= 0 || request.sizeBytes > MediaLimits.maxSizeBytes(request.kind)) throw invalid("sizeBytes")
        val relationshipId = request.relationshipId?.let { runCatching { Uuid.parse(it) }.getOrNull() ?: throw invalid("relationshipId") }
        return db.tx {
            MediaAssets.selectAll().where { MediaAssets.id eq id }.singleOrNull()?.let { existing ->
                if (existing[MediaAssets.ownerUserId] != user.id) throw conflict(message = "media id taken")
                return@tx uploadSession(existing)
            }
            when (request.kind) {
                MediaKind.TECHNIQUE_VIDEO -> {
                    // Private kinds need a relationship of which the caller is the client (DB check as a backstop).
                    val rel = relationshipId?.let { with(identity) { relationship(it) } } ?: throw invalid("relationshipId")
                    if (rel.clientId != user.id) throw notFound("relationship", DeniedSubject("relationship", rel.id, rel.id))
                    if (!rel.isActive) throw conflict(message = "relationship is not active")
                }
                MediaKind.EXERCISE_DEMO -> if (!user.isTrainer) throw forbidden("trainer role required")
            }
            val now = dbNow()
            attempt(SqlState.UNIQUE_VIOLATION) {
                MediaAssets.insert {
                    it[MediaAssets.id] = id
                    it[ownerUserId] = user.id
                    it[MediaAssets.relationshipId] = if (request.kind == MediaKind.TECHNIQUE_VIDEO) relationshipId else null
                    it[kind] = request.kind.name
                    it[state] = MediaState.UPLOADING
                    it[contentType] = request.contentType
                    it[sizeBytes] = request.sizeBytes
                    it[storageKey] = "media/$id"
                    it[retainUntil] = if (request.kind == MediaKind.TECHNIQUE_VIDEO) (now + TECHNIQUE_VIDEO_RETENTION).toOffset() else null
                }
            }
            uploadSession(MediaAssets.selectAll().where { MediaAssets.id eq id }.single())
        }
    }

    /** tus `HEAD`: how many bytes the server has. */
    suspend fun uploadOffset(user: CurrentUser, id: Uuid): Pair<Long, Long> = db.tx {
        val row = owned(user, id)
        (storage.size(row[MediaAssets.storageKey]) ?: 0L) to row[MediaAssets.sizeBytes]
    }

    /**
     * tus `PATCH`: appends from [offset], which must equal the stored size (`409` otherwise, so the client asks for the
     * offset again). When the file is complete it moves to PROCESSING and the worker checks it.
     */
    suspend fun appendChunk(user: CurrentUser, id: Uuid, offset: Long, body: InputStream): Long {
        val row = db.tx { owned(user, id) }
        if (row[MediaAssets.state] != MediaState.UPLOADING) throw conflict(message = "upload already complete")
        val key = row[MediaAssets.storageKey]
        val size = row[MediaAssets.sizeBytes]
        val current = storage.size(key) ?: 0L
        if (offset != current) throw ApiException(409, ErrorCode.CONFLICT, "offset mismatch")
        val newSize = try {
            storage.append(key, offset, body, size)
        } catch (e: IllegalArgumentException) {
            throw invalid("sizeBytes")
        } catch (e: IllegalStateException) {
            throw ApiException(409, ErrorCode.CONFLICT, "offset mismatch")
        }
        if (newSize == size) {
            db.tx {
                val changed = MediaAssets.update({ (MediaAssets.id eq id) and (MediaAssets.state eq MediaState.UPLOADING) }) { it[state] = MediaState.PROCESSING }
                if (changed > 0) {
                    schedule(JobQueue.MEDIA_PROCESS, "media-process:$id", dbNow(), mapOf("mediaId" to id))
                    emit("media", id, "media.processing", mapOf("ownerId" to user.id, "relationshipId" to row[MediaAssets.relationshipId]))
                }
            }
        }
        return newSize
    }

    /** Owner or the other party of the relationship; demo videos for any signed-in user. READY → signed link. */
    suspend fun status(user: CurrentUser, id: Uuid): MediaStatusDto = db.tx {
        val row = MediaAssets.selectAll().where { (MediaAssets.id eq id) and MediaAssets.deletedAt.isNull() }.singleOrNull() ?: throw notFound("media")
        val allowed = row[MediaAssets.ownerUserId] == user.id || row[MediaAssets.kind] == "EXERCISE_DEMO" ||
            row[MediaAssets.relationshipId]?.let { relId -> with(identity) { relationship(relId) }?.let { it.clientId == user.id || it.trainerId == user.id } } == true
        if (!allowed) throw notFound("media", DeniedSubject("media", id, row[MediaAssets.relationshipId]))
        val state = RemoteMediaState.valueOf(row[MediaAssets.state])
        MediaStatusDto(id.toString(), state, playbackUrl = if (state == RemoteMediaState.READY) signedUrl(id, dbNow().epochSeconds + config.playbackTtlSeconds) else null)
    }

    /** Resolves a signed playback link (no token: the player fetches it directly). Null when invalid or expired. */
    suspend fun playbackFile(id: Uuid, expires: Long, signature: String): Pair<File, String>? {
        if (expires < System.currentTimeMillis() / 1000 || !java.security.MessageDigest.isEqual(sign(id, expires).encodeToByteArray(), signature.encodeToByteArray())) return null
        val row = db.tx { MediaAssets.selectAll().where { (MediaAssets.id eq id) and (MediaAssets.state eq MediaState.READY) and MediaAssets.deletedAt.isNull() }.singleOrNull() }
            ?: return null
        return storage.file(row[MediaAssets.storageKey])?.let { it to row[MediaAssets.contentType] }
    }

    // ----- Worker -----

    /** Checks a complete upload: object present with the declared size, video container, ≤ 60 s for MP4. */
    suspend fun process(id: Uuid) {
        val row = db.tx { MediaAssets.selectAll().where { MediaAssets.id eq id }.singleOrNull() } ?: return
        if (row[MediaAssets.state] != MediaState.PROCESSING) return
        val key = row[MediaAssets.storageKey]
        val file = storage.file(key)
        var duration: Long? = null
        val failure = when {
            file == null || file.length() != row[MediaAssets.sizeBytes] -> "MISSING_OBJECT"
            row[MediaAssets.contentType] !in VIDEO_TYPES -> "UNSUPPORTED_TYPE"
            row[MediaAssets.contentType] != "video/webm" -> {
                duration = runCatching { Mp4Duration.durationMs(file) }.getOrNull()
                when {
                    duration == null -> "UNSUPPORTED_TYPE"
                    duration > MediaLimits.maxDurationMs(MediaKind.valueOf(row[MediaAssets.kind])) -> "TOO_LONG"
                    else -> null
                }
            }
            else -> null
        }
        db.tx {
            val now = dbNow()
            MediaAssets.update({ (MediaAssets.id eq id) and (MediaAssets.state eq MediaState.PROCESSING) }) {
                if (failure == null) {
                    it[state] = MediaState.READY
                    it[readyAt] = now.toOffset()
                    it[durationMs] = duration
                } else {
                    it[state] = MediaState.FAILED
                    it[failureReason] = failure
                }
            }
            emit("media", id, if (failure == null) "media.ready" else "media.failed", mapOf("ownerId" to row[MediaAssets.ownerUserId], "relationshipId" to row[MediaAssets.relationshipId]))
        }
    }

    /** Uploads abandoned for 24 h fail; technique videos past `retain_until` are deleted from storage. */
    suspend fun maintenance() {
        val expired = db.tx {
            val now = dbNow()
            MediaAssets.update({ (MediaAssets.state eq MediaState.UPLOADING) and (MediaAssets.createdAt less (now - STALE_UPLOAD).toOffset()) }) {
                it[state] = MediaState.FAILED
                it[failureReason] = "MISSING_OBJECT"
            }
            MediaAssets.selectAll().where {
                MediaAssets.deletedAt.isNull() and MediaAssets.retainUntil.isNotNull() and (MediaAssets.retainUntil less now.toOffset())
            }.limit(100).map { it[MediaAssets.id] to it[MediaAssets.storageKey] }
        }
        for ((id, key) in expired) {
            storage.delete(key)
            db.tx { MediaAssets.update({ MediaAssets.id eq id }) { it[deletedAt] = dbNow().toOffset() } }
        }
    }

    // ----- Internals -----

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.owned(user: CurrentUser, id: Uuid): ResultRow {
        val row = MediaAssets.selectAll().where { MediaAssets.id eq id }.singleOrNull() ?: throw notFound("media")
        if (row[MediaAssets.ownerUserId] != user.id) throw notFound("media", DeniedSubject("media", id, row[MediaAssets.relationshipId]))
        return row
    }

    private fun uploadSession(row: ResultRow): UploadSessionDto {
        val id = row[MediaAssets.id]
        return UploadSessionDto(
            mediaId = id.toString(),
            uploadUrl = "${config.publicBaseUrl}/v1/media/uploads/$id",
            offset = storage.size(row[MediaAssets.storageKey]) ?: 0L,
            sizeBytes = row[MediaAssets.sizeBytes],
            state = RemoteMediaState.valueOf(row[MediaAssets.state]),
        )
    }

    private fun signedUrl(id: Uuid, expires: Long) = "${config.publicBaseUrl}/v1/media/$id/content?exp=$expires&sig=${sign(id, expires)}"

    private fun sign(id: Uuid, expires: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(config.signingKey.encodeToByteArray(), "HmacSHA256"))
        return mac.doFinal("$id:$expires".encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
