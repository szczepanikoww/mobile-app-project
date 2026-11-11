package com.trainingapp.server.messaging

import com.trainingapp.contract.chat.AttachmentDto
import com.trainingapp.contract.chat.ConversationDto
import com.trainingapp.contract.chat.MessageDto
import com.trainingapp.contract.chat.MessagesDto
import com.trainingapp.contract.chat.SendMessageRequest
import com.trainingapp.contract.media.RemoteMediaState
import com.trainingapp.server.common.ApiException
import com.trainingapp.server.common.DeniedSubject
import com.trainingapp.server.common.ErrorCode
import com.trainingapp.server.common.Ids
import com.trainingapp.server.common.conflict
import com.trainingapp.server.common.invalid
import com.trainingapp.server.common.notFound
import com.trainingapp.server.common.toKotlin
import com.trainingapp.server.db.Db
import com.trainingapp.server.db.SqlState
import com.trainingapp.server.db.attempt
import com.trainingapp.server.identity.CurrentUser
import com.trainingapp.server.identity.IdentityService
import com.trainingapp.server.identity.Relationship
import com.trainingapp.server.media.MediaAssets
import com.trainingapp.server.platform.Outbox.emit
import com.trainingapp.server.training.TrainingService
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import kotlin.uuid.Uuid

/**
 * The Messaging module (plan §8.5): one conversation per relationship, messages ordered by a server sequence number.
 * Message text never reaches logs, outbox payloads or push text.
 */
class MessagingService(private val db: Db, private val identity: IdentityService, private val training: TrainingService) {

    /** Created when a relationship becomes ACTIVE. */
    fun JdbcTransaction.ensureConversation(relationshipId: Uuid) {
        Conversations.insertIgnore {
            it[id] = Ids.v7()
            it[Conversations.relationshipId] = relationshipId
            it[lastSeq] = 0
        }
    }

    suspend fun conversations(user: CurrentUser): List<ConversationDto> = db.tx {
        val relationships = with(identity) { relationshipsOfClient(user.id) + relationshipsOfTrainer(user.id) }
        if (relationships.isEmpty()) return@tx emptyList()
        val conversations = Conversations.selectAll().where { Conversations.relationshipId inList relationships.map { it.id } }.toList()
        val byRel = relationships.associateBy { it.id }
        val refs = with(identity) { refs(relationships.map { counterpart(it, user) }) }
        conversations.map { row ->
            val rel = byRel.getValue(row[Conversations.relationshipId])
            val conversationId = row[Conversations.id]
            val lastRead = lastRead(conversationId, user.id)
            ConversationDto(
                relationshipId = rel.id.toString(),
                counterpart = refs.getValue(counterpart(rel, user)),
                lastSeq = row[Conversations.lastSeq],
                unread = Messages.selectAll().where {
                    (Messages.conversationId eq conversationId) and (Messages.senderId neq user.id) and (Messages.seq greater lastRead)
                }.count().toInt(),
                canSend = rel.isActive,
            )
        }
    }

    suspend fun messages(user: CurrentUser, relationshipId: Uuid, afterSeq: Long, limit: Int): MessagesDto = db.tx {
        val (_, conversation) = partyConversation(user, relationshipId)
        val conversationId = conversation[Conversations.id]
        val rows = Messages.selectAll().where { (Messages.conversationId eq conversationId) and (Messages.seq greater afterSeq) }
            .orderBy(Messages.seq, SortOrder.ASC).limit(limit.coerceIn(1, 500)).toList()
        MessagesDto(toDtos(rows), conversation[Conversations.lastSeq], lastRead(conversationId, user.id))
    }

    /**
     * One transaction: next seq, the message (client UUID), its attachment and the technique-video link on the set,
     * then the outbox event (ids only). Resending the same id returns the stored message and seq.
     */
    suspend fun send(user: CurrentUser, relationshipId: Uuid, request: SendMessageRequest): MessageDto = db.tx {
        val messageId = runCatching { Uuid.parse(request.id) }.getOrNull() ?: throw invalid("id")
        val (relationship, conversation) = partyConversation(user, relationshipId)
        Messages.selectAll().where { Messages.id eq messageId }.singleOrNull()?.let { existing ->
            if (existing[Messages.senderId] != user.id || existing[Messages.conversationId] != conversation[Conversations.id]) {
                throw ApiException(422, ErrorCode.IDEMPOTENCY_MISMATCH, "message id reused")
            }
            return@tx toDtos(listOf(existing)).single()
        }
        if (!relationship.isActive) throw conflict(message = "the relationship is not active") // R2: ended → read-only
        val text = request.text?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null && text.length > 4000) throw invalid("text")
        val mediaId = request.mediaId?.let { runCatching { Uuid.parse(it) }.getOrNull() ?: throw invalid("mediaId") }
        if (text == null && mediaId == null) throw invalid("text")
        val workoutLogId = request.workoutLogId?.let { runCatching { Uuid.parse(it) }.getOrNull() ?: throw invalid("workoutLogId") }
        val setLogId = request.setLogId?.let { runCatching { Uuid.parse(it) }.getOrNull() ?: throw invalid("setLogId") }
        if (mediaId != null) {
            val media = MediaAssets.selectAll().where { MediaAssets.id eq mediaId }.singleOrNull()
                ?: throw ApiException(409, ErrorCode.DEPENDENCY_MISSING, "media not uploaded yet")
            if (media[MediaAssets.ownerUserId] != user.id || media[MediaAssets.relationshipId] != relationshipId) throw invalid("mediaId")
        }
        if (setLogId != null) {
            // The set must already be synced (FK); the weight/reps shown with a video are read from it, not copied.
            val ok = with(training) { setBelongsTo(setLogId, workoutLogId, relationship.clientId) }
            if (!ok) throw ApiException(409, ErrorCode.DEPENDENCY_MISSING, "set not synced yet")
        }
        val conversationId = conversation[Conversations.id]
        val created = attempt(SqlState.UNIQUE_VIOLATION) {
            val seq = Conversations.updateReturning(listOf(Conversations.lastSeq), where = { Conversations.id eq conversationId }) {
                it.update(Conversations.lastSeq, Conversations.lastSeq + 1L)
            }.single()[Conversations.lastSeq]
            Messages.insert {
                it[id] = messageId
                it[Messages.conversationId] = conversationId
                it[Messages.seq] = seq
                it[senderId] = user.id
                it[body] = text
                it[Messages.workoutLogId] = workoutLogId
                it[Messages.setLogId] = setLogId
            }
            if (mediaId != null) {
                MessageAttachments.insert {
                    it[MessageAttachments.messageId] = messageId
                    it[position] = 0
                    it[MessageAttachments.mediaId] = mediaId
                }
                if (setLogId != null) with(training) { attachTechniqueMedia(setLogId, mediaId) }
            }
            seq
        }
        // A concurrent retry of the same id committed first.
        val row = Messages.selectAll().where { Messages.id eq messageId }.single()
        if (created != null) {
            emit("message", messageId, "message.created", mapOf("relationshipId" to relationshipId, "recipientId" to counterpart(relationship, user)))
        }
        toDtos(listOf(row)).single()
    }

    suspend fun markRead(user: CurrentUser, relationshipId: Uuid, upToSeq: Long) = db.tx {
        val (_, conversation) = partyConversation(user, relationshipId)
        val conversationId = conversation[Conversations.id]
        val capped = upToSeq.coerceIn(0, conversation[Conversations.lastSeq])
        val current = ReadMarkers.selectAll().where { (ReadMarkers.conversationId eq conversationId) and (ReadMarkers.userId eq user.id) }.singleOrNull()
        if (current == null) {
            ReadMarkers.insertIgnore { it[ReadMarkers.conversationId] = conversationId; it[userId] = user.id; it[lastReadSeq] = capped }
        } else if (capped > current[ReadMarkers.lastReadSeq]) {
            ReadMarkers.update({ (ReadMarkers.conversationId eq conversationId) and (ReadMarkers.userId eq user.id) }) { it[lastReadSeq] = capped }
        }
    }

    // ----- Internals -----

    private fun counterpart(relationship: Relationship, user: CurrentUser) =
        if (relationship.clientId == user.id) relationship.trainerId else relationship.clientId

    /** The caller must be a party of the relationship; anything else is an audited 404. */
    private fun JdbcTransaction.partyConversation(user: CurrentUser, relationshipId: Uuid): Pair<Relationship, ResultRow> {
        val relationship = with(identity) { relationship(relationshipId) } ?: throw notFound("conversation")
        if (relationship.clientId != user.id && relationship.trainerId != user.id) {
            throw notFound("conversation", DeniedSubject("conversation", relationshipId, relationshipId))
        }
        val conversation = Conversations.selectAll().where { Conversations.relationshipId eq relationshipId }.singleOrNull()
            ?: throw notFound("conversation")
        return relationship to conversation
    }

    private fun JdbcTransaction.lastRead(conversationId: Uuid, userId: Uuid): Long =
        ReadMarkers.selectAll().where { (ReadMarkers.conversationId eq conversationId) and (ReadMarkers.userId eq userId) }
            .singleOrNull()?.get(ReadMarkers.lastReadSeq) ?: 0

    private fun JdbcTransaction.toDtos(rows: List<ResultRow>): List<MessageDto> {
        if (rows.isEmpty()) return emptyList()
        val attachments = (MessageAttachments innerJoin MediaAssets).selectAll()
            .where { MessageAttachments.messageId inList rows.map { it[Messages.id] } }
            .orderBy(MessageAttachments.position)
            .groupBy({ it[MessageAttachments.messageId] }) { AttachmentDto(it[MediaAssets.id].toString(), RemoteMediaState.valueOf(it[MediaAssets.state])) }
        return rows.map { row ->
            MessageDto(
                id = row[Messages.id].toString(),
                seq = row[Messages.seq],
                senderId = row[Messages.senderId].toString(),
                text = if (row[Messages.deletedAt] != null) null else row[Messages.body],
                createdAt = row[Messages.createdAt].toKotlin(),
                workoutLogId = row[Messages.workoutLogId]?.toString(),
                setLogId = row[Messages.setLogId]?.toString(),
                attachments = attachments[row[Messages.id]].orEmpty(),
            )
        }
    }
}
