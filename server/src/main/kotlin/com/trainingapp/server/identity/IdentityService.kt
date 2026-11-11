package com.trainingapp.server.identity

import com.trainingapp.contract.account.CreateAccountRequest
import com.trainingapp.contract.account.MeDto
import com.trainingapp.contract.account.PushPlatform
import com.trainingapp.contract.account.RoleDto
import com.trainingapp.contract.account.UpdateMeRequest
import com.trainingapp.contract.schedule.TrainerRefDto
import com.trainingapp.server.common.ApiException
import com.trainingapp.server.common.ErrorCode
import com.trainingapp.server.common.Ids
import com.trainingapp.server.common.dbNow
import com.trainingapp.server.common.initialsOf
import com.trainingapp.server.common.invalid
import com.trainingapp.server.common.toOffset
import com.trainingapp.server.db.Db
import com.trainingapp.server.db.SqlState
import com.trainingapp.server.db.attempt
import com.trainingapp.server.http.TokenIdentity
import com.trainingapp.server.platform.ConsentRecords
import com.trainingapp.server.platform.PushTokens
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.ZoneId
import kotlin.uuid.Uuid

/** The signed-in user as resolved from the token's subject. Roles are the profile rows that exist (G4). */
data class CurrentUser(
    val id: Uuid,
    val displayName: String,
    val locale: String,
    val timeZone: String,
    val isTrainer: Boolean,
    val isClient: Boolean,
) {
    fun toMe() = MeDto(
        userId = id.toString(),
        displayName = displayName,
        roles = buildList { if (isTrainer) add(RoleDto.TRAINER); if (isClient) add(RoleDto.CLIENT) },
        locale = locale,
        timeZone = timeZone,
    )
}

/** A trainer–client relationship as other modules need it. */
data class Relationship(val id: Uuid, val trainerId: Uuid, val clientId: Uuid, val status: String) {
    val isActive get() = status == RelationshipStatus.ACTIVE
    val isEnded get() = status == RelationshipStatus.ENDED
}

/**
 * The Identity & access module (architecture §6): accounts, roles, relationships. Other modules call this interface
 * and never write `identity.*` tables themselves.
 */
class IdentityService(private val db: Db) {

    suspend fun resolve(token: TokenIdentity): CurrentUser? = db.tx { resolveIn(token.subject) }

    fun JdbcTransaction.resolveIn(subject: String): CurrentUser? {
        val row = AppUsers.selectAll().where { AppUsers.authSubject eq subject }.singleOrNull() ?: return null
        if (row[AppUsers.deletedAt] != null) throw ApiException(401, ErrorCode.UNAUTHENTICATED, "account deleted")
        return currentUser(row)
    }

    private fun JdbcTransaction.currentUser(row: ResultRow): CurrentUser {
        val id = row[AppUsers.id]
        return CurrentUser(
            id = id,
            displayName = row[AppUsers.displayName],
            locale = row[AppUsers.locale],
            timeZone = row[AppUsers.timeZone],
            isTrainer = TrainerProfiles.selectAll().where { TrainerProfiles.userId eq id }.any(),
            isClient = ClientProfiles.selectAll().where { ClientProfiles.userId eq id }.any(),
        )
    }

    /**
     * `POST /v1/accounts`: user, starting role and the accepted terms/privacy versions in one transaction. Idempotent
     * by the IdP subject: a retry, or two concurrent requests, return one account.
     */
    suspend fun createAccount(token: TokenIdentity, request: CreateAccountRequest): CurrentUser {
        val name = request.name.trim()
        val bad = buildList {
            if (name.length !in 1..120) add("name")
            if (request.locale !in setOf("pl", "en")) add("locale")
            if (runCatching { ZoneId.of(request.timeZone) }.isFailure) add("timeZone")
            if (request.acceptedTermsVersion.isBlank() || request.acceptedTermsVersion.length > 64) add("acceptedTermsVersion")
            if (request.acceptedPrivacyVersion.isBlank() || request.acceptedPrivacyVersion.length > 64) add("acceptedPrivacyVersion")
        }
        if (bad.isNotEmpty()) throw invalid(*bad.toTypedArray())
        return db.tx {
            resolveIn(token.subject)?.let { return@tx it }
            val id = Ids.v7()
            val inserted = attempt(SqlState.UNIQUE_VIOLATION) {
                AppUsers.insert {
                    it[AppUsers.id] = id
                    it[authSubject] = token.subject
                    it[email] = token.email
                    it[displayName] = name
                    it[locale] = request.locale
                    it[timeZone] = request.timeZone
                    it[isOperator] = false
                }
            }
            // A concurrent registration with the same subject (or e-mail) won: return that account.
            if (inserted == null) return@tx resolveIn(token.subject) ?: throw ApiException(409, ErrorCode.CONFLICT, "account exists")
            when (request.startingRole) {
                RoleDto.CLIENT -> ClientProfiles.insert { it[userId] = id }
                RoleDto.TRAINER -> TrainerProfiles.insert { it[userId] = id; it[defaultCurrency] = "PLN"; it[sessionBufferMinutes] = 0 }
            }
            for ((purpose, version) in listOf("TERMS" to request.acceptedTermsVersion, "PRIVACY_POLICY" to request.acceptedPrivacyVersion)) {
                ConsentRecords.insert {
                    it[ConsentRecords.id] = Ids.v7()
                    it[userId] = id
                    it[ConsentRecords.purpose] = purpose
                    it[documentVersion] = version
                    it[action] = "GRANTED"
                }
            }
            resolveIn(token.subject)!!
        }
    }

    /** `POST /v1/me/roles/client`: adds the client role (idempotent by primary key). */
    suspend fun addClientRole(user: CurrentUser): CurrentUser = db.tx {
        ClientProfiles.insertIgnore { it[userId] = user.id }
        currentUser(AppUsers.selectAll().where { AppUsers.id eq user.id }.single())
    }

    suspend fun updateMe(user: CurrentUser, request: UpdateMeRequest): CurrentUser {
        if (request.locale != null && request.locale !in setOf("pl", "en")) throw invalid("locale")
        if (request.timeZone != null && runCatching { ZoneId.of(request.timeZone) }.isFailure) throw invalid("timeZone")
        return db.tx {
            AppUsers.update({ AppUsers.id eq user.id }) { row ->
                request.locale?.let { row[locale] = it }
                request.timeZone?.let { row[timeZone] = it }
            }
            currentUser(AppUsers.selectAll().where { AppUsers.id eq user.id }.single())
        }
    }

    /** The token is the key: a token moving to another account is re-assigned. */
    suspend fun putPushToken(user: CurrentUser, token: String, platform: PushPlatform) {
        if (token.isBlank() || token.length > 4096) throw invalid("token")
        db.tx {
            val now = dbNow().toOffset()
            PushTokens.upsert(PushTokens.token) {
                it[PushTokens.token] = token
                it[userId] = user.id
                it[PushTokens.platform] = platform.name
                it[updatedAt] = now
            }
        }
    }

    suspend fun deletePushToken(user: CurrentUser, token: String) = db.tx {
        PushTokens.deleteWhere { (PushTokens.token eq token) and (PushTokens.userId eq user.id) }
    }

    // ----- Used by other modules inside their transactions -----

    fun JdbcTransaction.relationship(id: Uuid): Relationship? =
        TrainerClients.selectAll().where { TrainerClients.id eq id }.singleOrNull()?.toRelationship()

    /** The open (not ENDED) relationship of a pair, if any (`trainer_client_open_pair`). */
    fun JdbcTransaction.openRelationship(trainerId: Uuid, clientId: Uuid): Relationship? =
        TrainerClients.selectAll()
            .where { (TrainerClients.trainerId eq trainerId) and (TrainerClients.clientId eq clientId) and (TrainerClients.status neq RelationshipStatus.ENDED) }
            .singleOrNull()?.toRelationship()

    /** Every relationship of a client, open or ended (ended ones give read-only history, R2). */
    fun JdbcTransaction.relationshipsOfClient(clientId: Uuid): List<Relationship> =
        TrainerClients.selectAll().where { TrainerClients.clientId eq clientId }.map { it.toRelationship() }

    fun JdbcTransaction.relationshipsOfTrainer(trainerId: Uuid): List<Relationship> =
        TrainerClients.selectAll().where { TrainerClients.trainerId eq trainerId }.map { it.toRelationship() }

    /**
     * R1: a client's first booking request with a trainer creates a PENDING relationship; the trainer confirming
     * activates it. Two concurrent first requests are safe: the partial unique index lets one insert win.
     */
    fun JdbcTransaction.openOrCreatePending(trainerId: Uuid, clientId: Uuid): Relationship {
        openRelationship(trainerId, clientId)?.let { return it }
        val id = Ids.v7()
        val inserted = attempt(SqlState.UNIQUE_VIOLATION) {
            TrainerClients.insert {
                it[TrainerClients.id] = id
                it[TrainerClients.trainerId] = trainerId
                it[TrainerClients.clientId] = clientId
                it[status] = RelationshipStatus.PENDING
                it[origin] = "CLIENT_REQUEST"
            }
        }
        if (inserted == null) return openRelationship(trainerId, clientId)!!
        return Relationship(id, trainerId, clientId, RelationshipStatus.PENDING)
    }

    /** PENDING → ACTIVE with the default access grants (trainer confirmation, plan §8.7). Returns true when it changed. */
    fun JdbcTransaction.activate(relationshipId: Uuid): Boolean {
        val changed = TrainerClients.update({ (TrainerClients.id eq relationshipId) and (TrainerClients.status eq RelationshipStatus.PENDING) }) {
            it[status] = RelationshipStatus.ACTIVE
            it[startedAt] = dbNow().toOffset()
        }
        if (changed == 0) return false
        for (scope in listOf("WORKOUT_HISTORY", "MEASUREMENTS", "TECHNIQUE_VIDEOS")) {
            AccessGrants.insertIgnore {
                it[id] = Ids.v7()
                it[AccessGrants.relationshipId] = relationshipId
                it[AccessGrants.scope] = scope
            }
        }
        return true
    }

    fun JdbcTransaction.isTrainer(userId: Uuid): Boolean = TrainerProfiles.selectAll().where { TrainerProfiles.userId eq userId }.any()

    fun JdbcTransaction.sessionBufferMinutes(trainerId: Uuid): Int =
        TrainerProfiles.selectAll().where { TrainerProfiles.userId eq trainerId }.single()[TrainerProfiles.sessionBufferMinutes]

    /** Lock order step 1 (plan §3 rule 3): the trainer row before any availability check. */
    fun JdbcTransaction.lockTrainer(trainerId: Uuid) {
        TrainerProfiles.selectAll().where { TrainerProfiles.userId eq trainerId }
            .forUpdate(org.jetbrains.exposed.v1.core.vendors.ForUpdateOption.ForUpdate).single()
    }

    fun JdbcTransaction.userTimeZone(userId: Uuid): String =
        AppUsers.selectAll().where { AppUsers.id eq userId }.single()[AppUsers.timeZone]

    /** Display read model for the client views: id, name, initials only. */
    fun JdbcTransaction.refs(userIds: Collection<Uuid>): Map<Uuid, TrainerRefDto> {
        if (userIds.isEmpty()) return emptyMap()
        return AppUsers.selectAll().where { AppUsers.id inList userIds.distinct() }.associate { row ->
            val name = if (row[AppUsers.deletedAt] != null) "—" else row[AppUsers.displayName]
            row[AppUsers.id] to TrainerRefDto(row[AppUsers.id].toString(), name, initialsOf(name))
        }
    }

    private fun ResultRow.toRelationship() =
        Relationship(this[TrainerClients.id], this[TrainerClients.trainerId], this[TrainerClients.clientId], this[TrainerClients.status])
}
