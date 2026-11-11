package com.trainingapp.server.messaging

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// Exposed mappings of the `messaging` schema (V1). Message bodies are private: never logged or put in push text.

object Conversations : Table("messaging.conversation") {
    val id = uuid("id")
    val relationshipId = uuid("relationship_id")
    val lastSeq = long("last_seq")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Messages : Table("messaging.message") {
    val id = uuid("id")
    val conversationId = uuid("conversation_id")
    val seq = long("seq")
    val senderId = uuid("sender_id")
    val body = text("body").nullable()
    val workoutLogId = uuid("workout_log_id").nullable()
    val setLogId = uuid("set_log_id").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object MessageAttachments : Table("messaging.message_attachment") {
    val messageId = uuid("message_id")
    val position = short("position")
    val mediaId = uuid("media_id").references(com.trainingapp.server.media.MediaAssets.id)
    override val primaryKey = PrimaryKey(messageId, position)
}

object ReadMarkers : Table("messaging.read_marker") {
    val conversationId = uuid("conversation_id")
    val userId = uuid("user_id")
    val lastReadSeq = long("last_read_seq")
    override val primaryKey = PrimaryKey(conversationId, userId)
}
