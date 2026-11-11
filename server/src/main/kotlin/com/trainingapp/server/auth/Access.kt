package com.trainingapp.server.auth

import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import com.trainingapp.contract.api.AccessScope
import com.trainingapp.server.db.AccessGrants
import com.trainingapp.server.db.TrainerClients
import com.trainingapp.server.platform.ApiException
import com.trainingapp.server.platform.ErrorCode
import com.trainingapp.server.platform.notFound
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource

/**
 * Relationship-scoped authorization (architecture §4, database-design "Privacy and access"). UI hiding is not
 * authorization: every private read or command checks here, inside the service transaction.
 */
object Access {
    /** The relationship [relationshipId] owned by [trainerId]. Someone else's relationship is reported as not found. */
    fun trainerRelationship(trainerId: UUID, relationshipId: UUID): ResultRow =
        TrainerClients.selectAll()
            .where { (TrainerClients.id eq relationshipId) and (TrainerClients.trainerId eq trainerId) }
            .singleOrNull() ?: notFound("Client not found")

    /** Like [trainerRelationship] but the relationship must be ACTIVE (commands, private reads). */
    fun activeRelationship(trainerId: UUID, relationshipId: UUID): ResultRow =
        trainerRelationship(trainerId, relationshipId).also {
            if (it[TrainerClients.status] != "ACTIVE") throw ApiException(ErrorCode.DENIED, "Relationship is not active")
        }

    fun activeScopes(relationshipId: UUID): Set<AccessScope> =
        AccessGrants.selectAll()
            .where { (AccessGrants.relationshipId eq relationshipId) and AccessGrants.revokedAt.isNull() }
            .mapNotNull { row -> AccessScope.entries.firstOrNull { it.name == row[AccessGrants.scope] } }
            .toSet()

    fun hasScope(relationshipId: UUID, scope: AccessScope) = scope in activeScopes(relationshipId)
}

/**
 * Append-only access audit (`platform.audit_record`): ids only, never content. Written on its own connection so a
 * denial is recorded even though the request's transaction rolls back.
 */
class AuditLog(private val dataSource: DataSource) {
    private val log = LoggerFactory.getLogger(AuditLog::class.java)

    fun record(actorId: UUID, action: String, subjectType: String, subjectId: UUID?, relationshipId: UUID?, allowed: Boolean) {
        runCatching {
            dataSource.connection.use { c ->
                c.autoCommit = true
                c.prepareStatement(
                    "INSERT INTO platform.audit_record (actor_id, action, subject_type, subject_id, relationship_id, outcome, occurred_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                ).use { st ->
                    st.setObject(1, actorId)
                    st.setString(2, action)
                    st.setString(3, subjectType)
                    st.setObject(4, subjectId)
                    st.setObject(5, relationshipId)
                    st.setString(6, if (allowed) "ALLOWED" else "DENIED")
                    st.setTimestamp(7, Timestamp(System.currentTimeMillis()))
                    st.executeUpdate()
                }
            }
        }.onFailure { log.warn("Audit write failed for action {}", action) }
    }
}
