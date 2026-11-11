package com.trainingapp.server.media

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import com.trainingapp.contract.api.AccessScope
import com.trainingapp.contract.api.UploadSessionDto
import com.trainingapp.contract.media.CreateUploadSessionRequest
import com.trainingapp.contract.media.MediaKind
import com.trainingapp.contract.media.MediaLimits
import com.trainingapp.contract.media.MediaStatusDto
import com.trainingapp.contract.media.RemoteMediaState
import com.trainingapp.server.auth.Access
import com.trainingapp.server.auth.AuditLog
import com.trainingapp.server.db.MediaAssets
import com.trainingapp.server.db.TrainerClients
import com.trainingapp.server.platform.AppConfig
import com.trainingapp.server.platform.Db
import com.trainingapp.server.platform.ServerClock
import com.trainingapp.server.platform.conflict
import com.trainingapp.server.platform.denied
import com.trainingapp.server.platform.invalid
import com.trainingapp.server.platform.notFound
import com.trainingapp.server.platform.toOffset
import com.trainingapp.server.platform.toUuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** What the processing step learned about an uploaded file. */
sealed interface ProcessingResult {
    data class Ready(val durationMs: Long? = null, val width: Int? = null, val height: Int? = null) : ProcessingResult
    data class Failed(val reason: String) : ProcessingResult
}

/** Checks an uploaded file (type, duration, thumbnail). A real implementation replaces [LocalProcessor]. */
fun interface MediaProcessor {
    fun process(storageKey: String, contentType: String, sizeBytes: Long): ProcessingResult
}

/** Local development: accepts every file; duration and size of the picture are unknown. */
val LocalProcessor = MediaProcessor { _, _, _ -> ProcessingResult.Ready() }

class MediaContent(val contentType: String, val sizeBytes: Long, val stream: InputStream)

/**
 * The one media pipeline: UPLOADING, PROCESSING, READY or FAILED. Bytes go through short-lived signed links, so the
 * storage stays private; a file is never playable before READY.
 */
class TrainerMediaService(
    private val db: Db,
    private val clock: ServerClock,
    private val config: AppConfig,
    private val audit: AuditLog,
    private val storage: MediaStorage = LocalMediaStorage(config.mediaDir),
    private val processor: MediaProcessor = LocalProcessor,
) {
    private val upload = "upload"
    private val play = "play"

    // ------------------------------------------------------------------ signed links

    private fun sign(mediaId: UUID, purpose: String, expires: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(config.auth.jwtSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val sig = mac.doFinal("$mediaId:$purpose:$expires".toByteArray(Charsets.UTF_8))
        return "$expires.${Base64.getUrlEncoder().withoutPadding().encodeToString(sig)}"
    }

    private fun url(mediaId: UUID, purpose: String, validFor: kotlin.time.Duration): String {
        val expires = (clock.now() + validFor).epochSeconds
        return "${config.publicBaseUrl}/media/$mediaId/content?token=${sign(mediaId, purpose, expires)}"
    }

    private fun verify(mediaId: UUID, purpose: String, token: String?) {
        val parts = token?.split('.') ?: denied("Invalid link")
        val expires = parts.takeIf { it.size == 2 }?.get(0)?.toLongOrNull() ?: denied("Invalid link")
        val expected = sign(mediaId, purpose, expires)
        if (!MessageDigest.isEqual(expected.toByteArray(), token.toByteArray())) denied("Invalid link")
        if (expires <= clock.now().epochSeconds) denied("Link expired")
    }

    // ------------------------------------------------------------------ trainer API

    /** Idempotent by the client-generated media id. Trainers upload exercise demos only. */
    suspend fun createUploadSession(trainerId: UUID, request: CreateUploadSessionRequest): UploadSessionDto {
        val mediaId = request.mediaId.toUuid("mediaId")
        if (request.kind == MediaKind.TECHNIQUE_VIDEO) denied("Technique videos are uploaded by clients")
        if (request.sizeBytes <= 0 || request.sizeBytes > MediaLimits.maxSizeBytes(request.kind)) invalid("File size not allowed")
        if (!request.contentType.startsWith("video/")) invalid("Unsupported content type")
        val validFor = 1.hours
        return db.tx {
            val existing = MediaAssets.selectAll().where { MediaAssets.id eq mediaId }.singleOrNull()
            if (existing != null && existing[MediaAssets.ownerUserId] != trainerId) conflict("Media id already used")
            if (existing == null) {
                MediaAssets.insert {
                    it[id] = mediaId
                    it[ownerUserId] = trainerId
                    it[kind] = request.kind.name
                    it[state] = RemoteMediaState.UPLOADING.name
                    it[contentType] = request.contentType
                    it[sizeBytes] = request.sizeBytes
                    it[storageKey] = "media/$mediaId"
                    it[createdAt] = clock.now().toOffset()
                }
            }
            UploadSessionDto(mediaId.toString(), url(mediaId, upload, validFor), clock.now() + validFor)
        }
    }

    private sealed interface Lookup {
        data class Allowed(val row: ResultRow) : Lookup
        data class Denied(val relationshipId: UUID) : Lookup
        data object Missing : Lookup
    }

    /** Owner, or the trainer of the media's relationship with the technique-video grant (denials are audited). */
    suspend fun status(trainerId: UUID, mediaIdText: String): MediaStatusDto {
        val mediaId = mediaIdText.toUuid("mediaId")
        val found = db.tx {
            val row = MediaAssets.selectAll().where { (MediaAssets.id eq mediaId) and MediaAssets.deletedAt.isNull() }.singleOrNull()
                ?: return@tx Lookup.Missing
            val relationshipId = row[MediaAssets.relationshipId]
            when {
                row[MediaAssets.ownerUserId] == trainerId -> Lookup.Allowed(row)
                relationshipId == null -> Lookup.Missing
                TrainerClients.selectAll().where { (TrainerClients.id eq relationshipId) and (TrainerClients.trainerId eq trainerId) }.empty() -> Lookup.Missing
                Access.hasScope(relationshipId, AccessScope.TECHNIQUE_VIDEOS) -> Lookup.Allowed(row)
                else -> Lookup.Denied(relationshipId)
            }
        }
        when (found) {
            Lookup.Missing -> notFound("Media not found")
            is Lookup.Denied -> {
                audit.record(trainerId, "media.read", "media", mediaId, found.relationshipId, allowed = false)
                denied("No access to this media")
            }
            is Lookup.Allowed -> {
                val row = found.row
                if (row[MediaAssets.ownerUserId] != trainerId) {
                    audit.record(trainerId, "media.read", "media", mediaId, row[MediaAssets.relationshipId], allowed = true)
                }
                val state = RemoteMediaState.valueOf(row[MediaAssets.state])
                return MediaStatusDto(
                    mediaId = mediaId.toString(),
                    state = state,
                    playbackUrl = if (state == RemoteMediaState.READY) url(mediaId, play, 15.minutes) else null,
                )
            }
        }
    }

    // ------------------------------------------------------------------ signed content endpoints

    /**
     * Stores the uploaded bytes (outside any transaction), then runs the processing step. Too large: FAILED with
     * TOO_LARGE. The declared size is the limit, so a client cannot exceed what it announced.
     */
    suspend fun receiveUpload(mediaIdText: String, token: String?, input: InputStream) {
        val mediaId = mediaIdText.toUuid("mediaId")
        verify(mediaId, upload, token)
        val row = db.tx { MediaAssets.selectAll().where { MediaAssets.id eq mediaId }.singleOrNull() } ?: notFound("Media not found")
        if (row[MediaAssets.state] != RemoteMediaState.UPLOADING.name) conflict("Upload already received")
        val key = row[MediaAssets.storageKey]
        val written = try {
            withContext(Dispatchers.IO) { storage.write(key, input, row[MediaAssets.sizeBytes]) }
        } catch (e: TooLargeException) {
            fail(mediaId, "TOO_LARGE")
            invalid("File too large")
        }
        if (written == 0L) {
            fail(mediaId, "MISSING_OBJECT")
            invalid("Empty upload")
        }
        val claimed = db.tx {
            MediaAssets.update({ (MediaAssets.id eq mediaId) and (MediaAssets.state eq RemoteMediaState.UPLOADING.name) }) {
                it[state] = RemoteMediaState.PROCESSING.name
                it[sizeBytes] = written
            }
        }
        if (claimed == 0) conflict("Upload already received")
        val result = withContext(Dispatchers.IO) { processor.process(key, row[MediaAssets.contentType], written) }
        when (result) {
            is ProcessingResult.Failed -> fail(mediaId, result.reason)
            is ProcessingResult.Ready -> db.tx {
                MediaAssets.update({ (MediaAssets.id eq mediaId) and (MediaAssets.state eq RemoteMediaState.PROCESSING.name) }) {
                    it[state] = RemoteMediaState.READY.name
                    it[readyAt] = clock.now().toOffset()
                    it[durationMs] = result.durationMs
                    it[width] = result.width
                    it[height] = result.height
                }
            }
        }
    }

    private suspend fun fail(mediaId: UUID, reason: String) = db.tx {
        MediaAssets.update({ (MediaAssets.id eq mediaId) and (MediaAssets.state inList listOf("UPLOADING", "PROCESSING")) }) {
            it[state] = RemoteMediaState.FAILED.name
            it[failureReason] = reason
        }
    }

    /** The bytes of a READY file for a valid "play" link. The caller closes the stream. */
    suspend fun openContent(mediaIdText: String, token: String?): MediaContent {
        val mediaId = mediaIdText.toUuid("mediaId")
        verify(mediaId, play, token)
        val row = db.tx {
            MediaAssets.selectAll()
                .where { (MediaAssets.id eq mediaId) and (MediaAssets.state eq RemoteMediaState.READY.name) and MediaAssets.deletedAt.isNull() }
                .singleOrNull()
        } ?: notFound("Media not found")
        val stream = withContext(Dispatchers.IO) { storage.read(row[MediaAssets.storageKey]) } ?: notFound("Media not found")
        return MediaContent(row[MediaAssets.contentType], row[MediaAssets.sizeBytes], stream)
    }
}
