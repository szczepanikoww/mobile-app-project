package com.trainingapp.server.db

import com.trainingapp.server.platform.tstz
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

/** One 1:1 chat conversation per trainer-client relationship. */
object Conversations : Table("messaging.conversation") {
    val id = javaUUID("id")
    val relationshipId = javaUUID("relationship_id")
    val lastSeq = long("last_seq")
    val createdAt = tstz("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Chat message with a client-generated id and a server-assigned seq. */
object Messages : Table("messaging.message") {
    val id = javaUUID("id")
    val conversationId = javaUUID("conversation_id")
    val seq = long("seq")
    val senderId = javaUUID("sender_id")
    val body = text("body").nullable()
    val workoutLogId = javaUUID("workout_log_id").nullable()
    val setLogId = javaUUID("set_log_id").nullable()
    val createdAt = tstz("created_at")
    val deletedAt = tstz("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Media attached to a message. */
object MessageAttachments : Table("messaging.message_attachment") {
    val messageId = javaUUID("message_id")
    val position = short("position")
    val mediaId = javaUUID("media_id")
    override val primaryKey = PrimaryKey(messageId, position)
}

/** Last-read sequence number per user and conversation. */
object ReadMarkers : Table("messaging.read_marker") {
    val conversationId = javaUUID("conversation_id")
    val userId = javaUUID("user_id")
    val lastReadSeq = long("last_read_seq")
    override val primaryKey = PrimaryKey(conversationId, userId)
}
