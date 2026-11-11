package com.trainingapp.server.identity

import com.trainingapp.contract.api.AccessScope
import com.trainingapp.contract.api.AssignedPlanDto
import com.trainingapp.contract.api.ClientDetailDto
import com.trainingapp.contract.api.ClientInformationDto
import com.trainingapp.contract.api.ClientPolicyMode
import com.trainingapp.contract.api.ClientPolicyOverrideDto
import com.trainingapp.contract.api.ClientSummaryDto
import com.trainingapp.contract.api.CreateInviteRequest
import com.trainingapp.contract.api.InviteDto
import com.trainingapp.contract.api.NoteDto
import com.trainingapp.contract.api.SessionStatus
import com.trainingapp.server.auth.Access
import com.trainingapp.server.auth.AuditLog
import com.trainingapp.server.catalogue.Policies
import com.trainingapp.server.db.AccessGrants
import com.trainingapp.server.db.AssignedPlans
import com.trainingapp.server.db.ClientPolicyOverrides
import com.trainingapp.server.db.CoachingNotes
import com.trainingapp.server.db.InviteCodes
import com.trainingapp.server.db.PlanVersions
import com.trainingapp.server.db.Sessions
import com.trainingapp.server.db.TrainerClients
import com.trainingapp.server.platform.ApiJson
import com.trainingapp.server.platform.DataCipher
import com.trainingapp.server.platform.Db
import com.trainingapp.server.platform.ServerClock
import com.trainingapp.server.platform.conflict
import com.trainingapp.server.platform.denied
import com.trainingapp.server.platform.invalid
import com.trainingapp.server.platform.newId
import com.trainingapp.server.platform.notFound
import com.trainingapp.server.platform.tstz
import com.trainingapp.server.platform.toInstantK
import com.trainingapp.server.platform.toOffset
import com.trainingapp.server.platform.toUuid
import com.trainingapp.server.scheduling.SessionReads
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import org.jetbrains.exposed.sql.deleteWhere
import java.security.SecureRandom
import java.util.UUID
import kotlin.time.Duration.Companion.days

/** `coaching.client_information` (V2): one encrypted JSON document per client. */
private object ClientInformationTable : Table("coaching.client_information") {
    val clientId = uuid("client_id")
    val payloadEncrypted = binary("payload_encrypted")
    val keyId = text("key_id")
    val updatedAt = tstz("updated_at")
    override val primaryKey = PrimaryKey(clientId)
}

/**
 * The trainer's view of their clients: list and detail, private notes, client-entered information, invite codes,
 * ending a relationship and the per-client policy override. Every method checks the relationship belongs to the trainer.
 */
class TrainerClientsService(
    private val db: Db,
    private val clock: ServerClock,
    private val audit: AuditLog,
    private val cipher: DataCipher,
) {
    private val random = SecureRandom()

    // ---------------------------------------------------------------- list and detail

    suspend fun clients(trainerId: UUID): List<ClientSummaryDto> = db.tx { SessionReads.clients(trainerId) }

    suspend fun detail(trainerId: UUID, relationshipId: UUID): ClientDetailDto = db.tx {
        val rel = Access.trainerRelationship(trainerId, relationshipId)
        val now = clock.now().toOffset()
        val plan = AssignedPlans.join(PlanVersions, JoinType.INNER, AssignedPlans.planVersionId, PlanVersions.id)
            .selectAll()
            .where { (AssignedPlans.relationshipId eq relationshipId) and (AssignedPlans.status eq "ACTIVE") }
            .orderBy(AssignedPlans.assignedAt, SortOrder.DESC).limit(1).singleOrNull()?.toPlan()
        val upcoming = Sessions.selectAll()
            .where {
                (Sessions.relationshipId eq relationshipId) and (Sessions.startsAt greaterEq now) and
                    (Sessions.status inList listOf(SessionStatus.REQUESTED.name, SessionStatus.CONFIRMED.name))
            }
            .orderBy(Sessions.startsAt, SortOrder.ASC).limit(20).map { SessionReads.toDto(it) }
        ClientDetailDto(
            client = SessionReads.client(relationshipId),
            startedAt = rel[TrainerClients.startedAt]?.toInstantK(),
            grants = Access.activeScopes(relationshipId),
            activePlan = plan,
            upcomingSessions = upcoming,
            policyOverride = Policies.override(relationshipId),
        )
    }

    private fun ResultRow.toPlan() = AssignedPlanDto(
        id = this[AssignedPlans.id].toString(),
        clientId = this[AssignedPlans.relationshipId].toString(),
        planVersionId = this[AssignedPlans.planVersionId].toString(),
        templateId = this[PlanVersions.templateId].toString(),
        versionNumber = this[PlanVersions.number],
        name = this[PlanVersions.name],
        status = this[AssignedPlans.status],
        startsOn = this[AssignedPlans.startsOn]?.toString(),
        endsOn = this[AssignedPlans.endsOn]?.toString(),
        assignedAt = this[AssignedPlans.assignedAt].toInstantK(),
    )

    // ---------------------------------------------------------------- coaching notes (author only)

    suspend fun notes(trainerId: UUID, relationshipId: UUID): List<NoteDto> = db.tx {
        Access.trainerRelationship(trainerId, relationshipId)
        CoachingNotes.selectAll().where { ownNotes(trainerId, relationshipId) }
            .orderBy(CoachingNotes.createdAt, SortOrder.DESC).map { it.toNote() }
    }

    suspend fun addNote(trainerId: UUID, relationshipId: UUID, body: String): NoteDto = db.tx {
        Access.trainerRelationship(trainerId, relationshipId)
        insertNote(trainerId, relationshipId, checkBody(body))
    }

    suspend fun updateNote(trainerId: UUID, relationshipId: UUID, noteId: UUID, body: String): NoteDto = db.tx {
        Access.trainerRelationship(trainerId, relationshipId)
        val text = checkBody(body)
        val updated = CoachingNotes.update({ ownNotes(trainerId, relationshipId) and (CoachingNotes.id eq noteId) }) {
            it[CoachingNotes.body] = text
            it[updatedAt] = clock.now().toOffset()
        }
        if (updated == 0) notFound("Note not found")
        CoachingNotes.selectAll().where { CoachingNotes.id eq noteId }.single().toNote()
    }

    suspend fun deleteNote(trainerId: UUID, relationshipId: UUID, noteId: UUID): Unit = db.tx {
        Access.trainerRelationship(trainerId, relationshipId)
        val updated = CoachingNotes.update({ ownNotes(trainerId, relationshipId) and (CoachingNotes.id eq noteId) }) {
            it[deletedAt] = clock.now().toOffset()
        }
        if (updated == 0) notFound("Note not found")
    }

    /**
     * The app's single-note editor: updates the newest note or creates one; a blank body deletes it.
     * Returns null when the note was deleted (or there was nothing to delete).
     */
    suspend fun saveSingleNote(trainerId: UUID, relationshipId: UUID, body: String): NoteDto? = db.tx {
        Access.trainerRelationship(trainerId, relationshipId)
        val newest = CoachingNotes.selectAll().where { ownNotes(trainerId, relationshipId) }
            .orderBy(CoachingNotes.updatedAt, SortOrder.DESC).limit(1).singleOrNull()
        val now = clock.now().toOffset()
        if (body.isBlank()) {
            if (newest != null) {
                CoachingNotes.update({ CoachingNotes.id eq newest[CoachingNotes.id] }) { it[deletedAt] = now }
            }
            return@tx null
        }
        val text = checkBody(body)
        if (newest == null) {
            insertNote(trainerId, relationshipId, text)
        } else {
            val id = newest[CoachingNotes.id]
            CoachingNotes.update({ CoachingNotes.id eq id }) {
                it[CoachingNotes.body] = text
                it[updatedAt] = now
            }
            CoachingNotes.selectAll().where { CoachingNotes.id eq id }.single().toNote()
        }
    }

    private fun ownNotes(trainerId: UUID, relationshipId: UUID) =
        (CoachingNotes.relationshipId eq relationshipId) and (CoachingNotes.authorId eq trainerId) and CoachingNotes.deletedAt.isNull()

    private fun checkBody(body: String): String {
        if (body.isBlank() || body.length > 5000) invalid("Invalid note")
        return body
    }

    private fun insertNote(trainerId: UUID, relationshipId: UUID, body: String): NoteDto {
        val id = newId()
        val now = clock.now().toOffset()
        CoachingNotes.insert {
            it[CoachingNotes.id] = id
            it[CoachingNotes.relationshipId] = relationshipId
            it[authorId] = trainerId
            it[CoachingNotes.body] = body
            it[createdAt] = now
            it[updatedAt] = now
        }
        return CoachingNotes.selectAll().where { CoachingNotes.id eq id }.single().toNote()
    }

    private fun ResultRow.toNote() = NoteDto(
        id = this[CoachingNotes.id].toString(),
        body = this[CoachingNotes.body],
        createdAt = this[CoachingNotes.createdAt].toInstantK(),
        updatedAt = this[CoachingNotes.updatedAt].toInstantK(),
    )

    // ---------------------------------------------------------------- client information (client-entered, encrypted)

    /**
     * Needs an ACTIVE relationship and the client's HEALTH_QUESTIONNAIRE grant. Every attempt is audited (ids only);
     * the content is never logged.
     */
    suspend fun information(trainerId: UUID, relationshipId: UUID): ClientInformationDto = db.tx {
        val rel = Access.trainerRelationship(trainerId, relationshipId)
        val clientId = rel[TrainerClients.clientId]
        val allowed = rel[TrainerClients.status] == "ACTIVE" && Access.hasScope(relationshipId, AccessScope.HEALTH_QUESTIONNAIRE)
        audit.record(trainerId, "CLIENT_INFORMATION_READ", "client_information", clientId, relationshipId, allowed)
        if (!allowed) denied()
        val row = ClientInformationTable.selectAll().where { ClientInformationTable.clientId eq clientId }.singleOrNull()
            ?: notFound("No information")
        val json = cipher.decrypt(row[ClientInformationTable.payloadEncrypted]).decodeToString()
        ApiJson.decodeFromString(ClientInformationDto.serializer(), json)
            .copy(updatedAt = row[ClientInformationTable.updatedAt].toInstantK())
    }

    /** Encrypts and stores the client's own information (called by the client backend and tests). */
    suspend fun saveForClient(clientUserId: UUID, dto: ClientInformationDto): Unit = db.tx {
        if (dto.trainingGoal.length > 1000 || dto.injuries.length > 2000 || dto.movementLimitations.length > 2000) invalid("Text too long")
        dto.weightKg?.let { if (it !in 20.0..400.0) invalid("Invalid weight") }
        dto.heightCm?.let { if (it !in 50.0..260.0) invalid("Invalid height") }
        dto.birthDate?.let { runCatching { java.time.LocalDate.parse(it) }.getOrElse { invalid("Invalid birth date") } }
        val payload = ApiJson.encodeToString(ClientInformationDto.serializer(), dto.copy(updatedAt = null))
        val encrypted = cipher.encrypt(payload.encodeToByteArray())
        ClientInformationTable.upsert {
            it[clientId] = clientUserId
            it[payloadEncrypted] = encrypted
            it[keyId] = cipher.keyId
            it[updatedAt] = clock.now().toOffset()
        }
    }

    // ---------------------------------------------------------------- invites

    suspend fun invites(trainerId: UUID): List<InviteDto> = db.tx {
        val now = clock.now().toOffset()
        InviteCodes.selectAll().where { (InviteCodes.trainerId eq trainerId) and (InviteCodes.expiresAt greater now) }
            .orderBy(InviteCodes.createdAt, SortOrder.DESC)
            .filter { it[InviteCodes.usedCount] < it[InviteCodes.maxUses] }
            .map { it.toInvite() }
    }

    suspend fun createInvite(trainerId: UUID, request: CreateInviteRequest): InviteDto = db.tx {
        if (request.validDays !in 1..30) invalid("Invalid validity")
        if (request.maxUses !in 1..50) invalid("Invalid use count")
        val now = clock.now()
        var code = randomCode()
        var attempts = 0
        while (InviteCodes.selectAll().where { InviteCodes.code eq code }.any()) {
            if (++attempts > 10) conflict("Try again")
            code = randomCode()
        }
        InviteCodes.insert {
            it[InviteCodes.code] = code
            it[InviteCodes.trainerId] = trainerId
            it[expiresAt] = (now + request.validDays.days).toOffset()
            it[maxUses] = request.maxUses
            it[usedCount] = 0
            it[createdAt] = now.toOffset()
        }
        InviteCodes.selectAll().where { InviteCodes.code eq code }.single().toInvite()
    }

    suspend fun revokeInvite(trainerId: UUID, code: String): Unit = db.tx {
        val updated = InviteCodes.update({ (InviteCodes.code eq code.uppercase()) and (InviteCodes.trainerId eq trainerId) }) {
            it[expiresAt] = clock.now().toOffset()
        }
        if (updated == 0) notFound("Invite not found")
    }

    private fun randomCode() = buildString { repeat(CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) } }

    private fun ResultRow.toInvite() = InviteDto(
        code = this[InviteCodes.code],
        expiresAt = this[InviteCodes.expiresAt].toInstantK(),
        maxUses = this[InviteCodes.maxUses],
        usedCount = this[InviteCodes.usedCount],
    )

    // ---------------------------------------------------------------- end relationship and policy override

    /** Ends the relationship: revokes every access grant and closes the active plan. Already ended is CONFLICT. */
    suspend fun endRelationship(trainerId: UUID, relationshipId: UUID): Unit = db.tx {
        val rel = Access.trainerRelationship(trainerId, relationshipId)
        if (rel[TrainerClients.status] == "ENDED") conflict("Relationship already ended")
        val now = clock.now().toOffset()
        TrainerClients.update({ TrainerClients.id eq relationshipId }) {
            it[status] = "ENDED"
            it[endedAt] = now
        }
        AccessGrants.update({ (AccessGrants.relationshipId eq relationshipId) and AccessGrants.revokedAt.isNull() }) {
            it[revokedAt] = now
        }
        AssignedPlans.update({ (AssignedPlans.relationshipId eq relationshipId) and (AssignedPlans.status eq "ACTIVE") }) {
            it[status] = "ENDED"
        }
    }

    suspend fun setPolicyOverride(trainerId: UUID, relationshipId: UUID, dto: ClientPolicyOverrideDto): Unit = db.tx {
        Access.activeRelationship(trainerId, relationshipId)
        val versionId = when (dto.mode) {
            ClientPolicyMode.EXEMPT -> {
                if (dto.policyVersionId != null) invalid("Exempt has no policy")
                null
            }
            ClientPolicyMode.OTHER_POLICY -> {
                val id = (dto.policyVersionId ?: invalid("Policy required")).toUuid("policyVersionId")
                if (Policies.byId(id).trainerId != trainerId.toString()) notFound("Policy not found")
                id
            }
        }
        ClientPolicyOverrides.upsert {
            it[ClientPolicyOverrides.relationshipId] = relationshipId
            it[mode] = dto.mode.name
            it[policyVersionId] = versionId
            it[updatedAt] = clock.now().toOffset()
        }
    }

    suspend fun clearPolicyOverride(trainerId: UUID, relationshipId: UUID): Unit = db.tx {
        Access.trainerRelationship(trainerId, relationshipId)
        ClientPolicyOverrides.deleteWhere { ClientPolicyOverrides.relationshipId eq relationshipId }
    }

    private companion object {
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val CODE_LENGTH = 8
    }
}
