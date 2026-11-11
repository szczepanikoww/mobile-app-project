package com.trainingapp.server.media

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// Exposed mapping of the `media` schema (V1).

object MediaAssets : Table("media.media_asset") {
    val id = uuid("id")
    val ownerUserId = uuid("owner_user_id")
    val relationshipId = uuid("relationship_id").nullable()
    val kind = text("kind")
    val state = text("state")
    val contentType = text("content_type")
    val sizeBytes = long("size_bytes")
    val durationMs = long("duration_ms").nullable()
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val storageKey = text("storage_key")
    val thumbnailKey = text("thumbnail_key").nullable()
    val failureReason = text("failure_reason").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val readyAt = timestampWithTimeZone("ready_at").nullable()
    val retainUntil = timestampWithTimeZone("retain_until").nullable()
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object MediaState {
    const val UPLOADING = "UPLOADING"
    const val PROCESSING = "PROCESSING"
    const val READY = "READY"
    const val FAILED = "FAILED"
}
