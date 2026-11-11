package com.trainingapp.server.db

import com.trainingapp.server.platform.tstz
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

/** One pipeline for demo videos, technique videos, chat attachments and exercise images. */
object MediaAssets : Table("media.media_asset") {
    val id = javaUUID("id")
    val ownerUserId = javaUUID("owner_user_id")
    val relationshipId = javaUUID("relationship_id").nullable()
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
    val createdAt = tstz("created_at")
    val readyAt = tstz("ready_at").nullable()
    val retainUntil = tstz("retain_until").nullable()
    val deletedAt = tstz("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}
