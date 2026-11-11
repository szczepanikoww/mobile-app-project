package com.trainingapp.server.messaging

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.between
import com.trainingapp.contract.api.ConversationDto
import com.trainingapp.contract.api.MarkReadRequest
import com.trainingapp.contract.api.MessageDto
import com.trainingapp.contract.api.SendMessageRequest
import com.trainingapp.contract.api.ThreadDto
import com.trainingapp.server.auth.Access
import com.trainingapp.server.db.AppUsers
import com.trainingapp.server.db.Conversations
import com.trainingapp.server.db.MediaAssets
import com.trainingapp.server.db.MessageAttachments
import com.trainingapp.server.db.Messages
import com.trainingapp.server.db.ReadMarkers
import com.trainingapp.server.db.SetLogs
import com.trainingapp.server.db.TrainerClients
import com.trainingapp.server.db.WorkoutLogs
import com.trainingapp.server.platform.Db
import com.trainingapp.server.platform.TrainerOutbox
import com.trainingapp.server.platform.ServerClock
import com.trainingapp.server.platform.conflict
import com.trainingapp.server.platform.invalid
import com.trainingapp.server.platform.toInstantK
import com.trainingapp.server.platform.toOffset
import com.trainingapp.server.platform.toUuid
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import org.jetbrains.exposed.v1.core.plus

/**
 * One-to-one chat between the trainer and each client. Ids named `clientId` are relationship ids. Message text is
 * private: it is never logged and never placed in outbox payloads.
 */
class TrainerChat(private val db: Db, private val clock: ServerClock) {

    suspend fun conversations(trainerId: UUID): List<ConversationDto> = db.tx {
        TrainerClients.join(AppUsers, JoinType.INNER, TrainerClients.clientId, AppUsers.id)
            .selectAll().where { (TrainerClients.trainerId eq trainerId) and (TrainerClients.status eq "ACTIVE") }
            .map { rel ->
                val conversation = conversation(rel[TrainerClients.id])
                val lastRead = lastRead(conversation[Conversations.id], trainerId)
                val last = Messages.selectAll()
                    .where { (Messages.conversationId eq conversation[Conversations.id]) and Messages.deletedAt.isNull() }
                    .orderBy(Messages.seq, SortOrder.DESC).limit(1).singleOrNull()
                val unread = Messages.selectAll()
                    .where {
                        (Messages.conversationId eq conversation[Conversations.id]) and (Messages.seq greater lastRead) and
                            (Messages.senderId neq trainerId) and Messages.deletedAt.isNull()
                    }.count().toInt()
                ConversationDto(
                    clientId = rel[TrainerClients.id].toString(),
                    conversationId = conversation[Conversations.id].toString(),
                    clientName = if (rel[AppUsers.deletedAt] != null) "—" else rel[AppUsers.displayName],
                    lastMessage = last?.let { toDto(listOf(it), trainerId).first() },
                    unread = unread,
                )
            }
            .sortedWith(compareByDescending<ConversationDto> { it.lastMessage?.createdAt }.thenBy { it.clientName.lowercase() })
    }

    /** Messages with seq > [afterSeq], ascending. Reading does not mark the thread read. */
    suspend fun thread(trainerId: UUID, clientId: String, afterSeq: Long, limit: Int): ThreadDto = db.tx {
        val relationshipId = clientId.toUuid("clientId")
        Access.activeRelationship(trainerId, relationshipId)
        val conversation = conversation(relationshipId)
        val rows = Messages.selectAll()
            .where { (Messages.conversationId eq conversation[Conversations.id]) and (Messages.seq greater afterSeq.coerceAtLeast(0)) and Messages.deletedAt.isNull() }
            .orderBy(Messages.seq, SortOrder.ASC)
            .limit(limit.coerceIn(1, 200))
            .toList()
        ThreadDto(
            conversationId = conversation[Conversations.id].toString(),
            clientId = clientId,
            messages = toDto(rows, trainerId),
            lastReadSeq = lastRead(conversation[Conversations.id], trainerId),
        )
    }

    /** Idempotent by the client-generated message id: a resend returns the stored message. */
    suspend fun send(trainerId: UUID, clientId: String, request: SendMessageRequest): MessageDto = db.tx {
        val relationshipId = clientId.toUuid("clientId")
        val messageId = request.id.toUuid("id")
        val relationship = Access.activeRelationship(trainerId, relationshipId)
        val conversation = conversation(relationshipId)
        val conversationId = conversation[Conversations.id]

        val existing = Messages.selectAll().where { Messages.id eq messageId }.singleOrNull()
        if (existing != null) {
            if (existing[Messages.conversationId] != conversationId) conflict("Message id already used")
            return@tx toDto(listOf(existing), trainerId).first()
        }

        if (request.body.length > 4000) invalid("Message is too long")
        val attachmentIds = request.attachmentMediaIds.map { it.toUuid("attachmentMediaId") }
        if (attachmentIds.size != attachmentIds.toSet().size || attachmentIds.size > 10) invalid("Invalid attachments")
        if (request.body.isBlank() && attachmentIds.isEmpty()) invalid("Message is empty")
        if (attachmentIds.isNotEmpty()) {
            val usable = MediaAssets.selectAll()
                .where {
                    (MediaAssets.id inList attachmentIds) and MediaAssets.deletedAt.isNull() and (MediaAssets.state eq "READY") and
                        ((MediaAssets.ownerUserId eq trainerId) or (MediaAssets.relationshipId eq relationshipId))
                }.count()
            if (usable != attachmentIds.size.toLong()) invalid("Attachment not available")
        }
        val clientUser = relationship[TrainerClients.clientId]
        val workoutLogId = request.workoutLogId?.toUuid("workoutLogId")
        if (workoutLogId != null &&
            WorkoutLogs.selectAll().where { (WorkoutLogs.id eq workoutLogId) and (WorkoutLogs.clientId eq clientUser) }.empty()
        ) invalid("Unknown workout")
        val setLogId = request.setLogId?.toUuid("setLogId")
        if (setLogId != null) {
            val set = SetLogs.join(WorkoutLogs, JoinType.INNER, SetLogs.workoutLogId, WorkoutLogs.id)
                .selectAll().where { (SetLogs.id eq setLogId) and (WorkoutLogs.clientId eq clientUser) }.empty()
            if (set) invalid("Unknown set")
        }

        val now = clock.now()
        // The row lock serialises concurrent senders so seq values are gap-free and unique.
        Conversations.selectAll().where { Conversations.id eq conversationId }.forUpdate().single()
        Conversations.update({ Conversations.id eq conversationId }) { it[lastSeq] = lastSeq + 1L }
        val seq = Conversations.selectAll().where { Conversations.id eq conversationId }.single()[Conversations.lastSeq]
        Messages.insert {
            it[id] = messageId
            it[Messages.conversationId] = conversationId
            it[Messages.seq] = seq
            it[senderId] = trainerId
            it[body] = request.body.takeIf { b -> b.isNotEmpty() }
            it[Messages.workoutLogId] = workoutLogId
            it[Messages.setLogId] = setLogId
            it[createdAt] = now.toOffset()
        }
        attachmentIds.forEachIndexed { index, media ->
            MessageAttachments.insert {
                it[MessageAttachments.messageId] = messageId
                it[position] = index.toShort()
                it[mediaId] = media
            }
        }
        TrainerOutbox.emit("message.sent", "message", messageId, mapOf("relationshipId" to relationshipId), now)
        toDto(Messages.selectAll().where { Messages.id eq messageId }.toList(), trainerId).first()
    }

    /** Moves the trainer's read marker forward to [MarkReadRequest.seq] (never back). */
    suspend fun markRead(trainerId: UUID, clientId: String, request: MarkReadRequest): Unit = db.tx {
        val relationshipId = clientId.toUuid("clientId")
        Access.activeRelationship(trainerId, relationshipId)
        val conversation = Conversations.selectAll().where { Conversations.id eq conversation(relationshipId)[Conversations.id] }
            .forUpdate().single()
        val conversationId = conversation[Conversations.id]
        if (request.seq < 0 || request.seq > conversation[Conversations.lastSeq]) invalid("Invalid sequence number")
        val marker = ReadMarkers.selectAll().where { (ReadMarkers.conversationId eq conversationId) and (ReadMarkers.userId eq trainerId) }.singleOrNull()
        if (marker == null) {
            ReadMarkers.insert {
                it[ReadMarkers.conversationId] = conversationId
                it[userId] = trainerId
                it[lastReadSeq] = request.seq
            }
        } else if (request.seq > marker[ReadMarkers.lastReadSeq]) {
            ReadMarkers.update({ (ReadMarkers.conversationId eq conversationId) and (ReadMarkers.userId eq trainerId) }) {
                it[lastReadSeq] = request.seq
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The relationship's conversation, created on first use. */
    private fun conversation(relationshipId: UUID): ResultRow {
        Conversations.insertIgnore {
            it[id] = UUID.randomUUID()
            it[Conversations.relationshipId] = relationshipId
            it[lastSeq] = 0L
            it[createdAt] = clock.now().toOffset()
        }
        return Conversations.selectAll().where { Conversations.relationshipId eq relationshipId }.single()
    }

    private fun lastRead(conversationId: UUID, userId: UUID): Long =
        ReadMarkers.selectAll().where { (ReadMarkers.conversationId eq conversationId) and (ReadMarkers.userId eq userId) }
            .singleOrNull()?.get(ReadMarkers.lastReadSeq) ?: 0L

    private fun toDto(rows: List<ResultRow>, viewer: UUID): List<MessageDto> {
        if (rows.isEmpty()) return emptyList()
        val attachments = MessageAttachments.selectAll()
            .where { MessageAttachments.messageId inList rows.map { it[Messages.id] } }
            .orderBy(MessageAttachments.position, SortOrder.ASC)
            .groupBy({ it[MessageAttachments.messageId] }, { it[MessageAttachments.mediaId].toString() })
        return rows.map {
            MessageDto(
                id = it[Messages.id].toString(),
                seq = it[Messages.seq],
                senderId = it[Messages.senderId].toString(),
                mine = it[Messages.senderId] == viewer,
                body = it[Messages.body].orEmpty(),
                createdAt = it[Messages.createdAt].toInstantK(),
                workoutLogId = it[Messages.workoutLogId]?.toString(),
                setLogId = it[Messages.setLogId]?.toString(),
                attachmentMediaIds = attachments[it[Messages.id]].orEmpty(),
            )
        }
    }
}
