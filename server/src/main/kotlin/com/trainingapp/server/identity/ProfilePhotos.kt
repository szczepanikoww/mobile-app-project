package com.trainingapp.server.identity

import com.trainingapp.server.common.dbNow
import com.trainingapp.server.common.invalid
import com.trainingapp.server.common.notFound
import com.trainingapp.server.common.toOffset
import com.trainingapp.server.db.Db
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/** `identity.profile_photo` (V4). */
object ProfilePhotosTable : Table("identity.profile_photo") {
    val userId = uuid("user_id")
    val contentType = text("content_type")
    val bytes = binary("bytes")
    val photoSource = text("source")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

data class ProfilePhoto(val contentType: String, val bytes: ByteArray, val version: Long)

/** Downloads a Google account picture. Returns null on any failure; tests replace it. */
fun interface PictureDownloader {
    fun download(url: String): Pair<String, ByteArray>?

    companion object {
        val Http = PictureDownloader { url ->
            runCatching {
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
                val response = client.send(
                    HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray(),
                )
                if (response.statusCode() != 200) return@runCatching null
                val bytes = response.body()
                imageType(bytes)?.let { it to bytes }
            }.getOrNull()
        }
    }
}

/**
 * Profile photos of trainers and clients: an uploaded photo, or a one-time copy of the Google account picture. A
 * photo is shown to the person themself, for any trainer (their public face), and between a trainer and a client
 * who have (or had) a relationship. Anyone else gets 404, as for a missing photo.
 */
class ProfilePhotoService(private val db: Db, private val downloader: PictureDownloader = PictureDownloader.Http) {
    private val log = LoggerFactory.getLogger(ProfilePhotoService::class.java)

    /** Subjects whose Google picture was already tried in this process, so a failing URL is not fetched on every `/me`. */
    private val googleAttempted = ConcurrentHashMap.newKeySet<Uuid>()

    suspend fun photo(viewer: Uuid, target: Uuid): ProfilePhoto = db.tx {
        val visible = viewer == target ||
            TrainerProfiles.selectAll().where { TrainerProfiles.userId eq target }.any() ||
            TrainerClients.selectAll().where {
                ((TrainerClients.trainerId eq viewer) and (TrainerClients.clientId eq target)) or
                    ((TrainerClients.trainerId eq target) and (TrainerClients.clientId eq viewer))
            }.any()
        val row = if (visible) ProfilePhotosTable.selectAll().where { ProfilePhotosTable.userId eq target }.singleOrNull() else null
        row ?: throw notFound("photo")
        ProfilePhoto(row[ProfilePhotosTable.contentType], row[ProfilePhotosTable.bytes], row[ProfilePhotosTable.updatedAt].toInstant().toEpochMilli())
    }

    /** `PUT /v1/me/photo`: replaces the caller's photo. The type is taken from the bytes, not the header. */
    suspend fun upload(user: Uuid, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw invalid("photo")
        val type = imageType(bytes) ?: throw invalid("photo")
        db.tx {
            ProfilePhotosTable.upsert(ProfilePhotosTable.userId) {
                it[userId] = user
                it[contentType] = type
                it[ProfilePhotosTable.bytes] = bytes
                it[photoSource] = "UPLOAD"
                it[updatedAt] = dbNow().toOffset()
            }
        }
    }

    /**
     * Copies the Google account picture once, when the user has no photo yet. Never replaces an existing photo
     * (an upload always wins). Only https URLs on Google's picture host are fetched.
     */
    suspend fun copyGooglePicture(user: Uuid, pictureUrl: String?) {
        val url = pictureUrl?.let(::googlePictureUrl) ?: return
        if (!googleAttempted.add(user)) return
        val hasPhoto = db.tx { ProfilePhotosTable.selectAll().where { ProfilePhotosTable.userId eq user }.any() }
        if (hasPhoto) return
        val (type, bytes) = downloader.download(url) ?: run {
            log.info("Google picture not copied")
            return
        }
        if (bytes.size > MAX_BYTES) return
        db.tx {
            ProfilePhotosTable.insertIgnore {
                it[userId] = user
                it[contentType] = type
                it[ProfilePhotosTable.bytes] = bytes
                it[photoSource] = "GOOGLE"
                it[updatedAt] = dbNow().toOffset()
            }
        }
    }

    companion object {
        const val MAX_BYTES = 2 * 1024 * 1024

        /** Google's picture URL at 256 px, or null for anything that is not an https Google picture. */
        fun googlePictureUrl(raw: String): String? {
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (uri.scheme != "https" || !(host == "googleusercontent.com" || host.endsWith(".googleusercontent.com"))) return null
            // "…=s96-c" is the size suffix Google adds; ask for a sharper copy.
            return raw.replace(Regex("=s\\d+(-c)?$"), "=s256-c")
        }
    }
}

/** The image type by its magic bytes: JPEG, PNG or WebP. */
fun imageType(bytes: ByteArray): String? {
    fun at(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xFF)
    return when {
        at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
        at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
        bytes.size > 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" && bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
        else -> null
    }
}
