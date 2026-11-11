package com.trainingapp.server.identity

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// Exposed mappings of the `identity` schema (V1). The schema is owned by Flyway; SchemaUtils.create is never called.

object AppUsers : Table("identity.app_user") {
    val id = uuid("id")
    val authSubject = text("auth_subject")
    val email = text("email").nullable()
    val displayName = text("display_name")
    val locale = text("locale")
    val timeZone = text("time_zone")
    val isOperator = bool("is_operator")
    val createdAt = timestampWithTimeZone("created_at")
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object TrainerProfiles : Table("identity.trainer_profile") {
    val userId = uuid("user_id")
    val defaultCurrency = char("default_currency", 3)
    val sessionBufferMinutes = integer("session_buffer_minutes")
    val paymentAccountRef = text("payment_account_ref").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(userId)
}

object ClientProfiles : Table("identity.client_profile") {
    val userId = uuid("user_id")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(userId)
}

object TrainerClients : Table("identity.trainer_client") {
    val id = uuid("id")
    val trainerId = uuid("trainer_id")
    val clientId = uuid("client_id")
    val status = text("status")
    val origin = text("origin")
    val createdAt = timestampWithTimeZone("created_at")
    val startedAt = timestampWithTimeZone("started_at").nullable()
    val endedAt = timestampWithTimeZone("ended_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object AccessGrants : Table("identity.access_grant") {
    val id = uuid("id")
    val relationshipId = uuid("relationship_id")
    val scope = text("scope")
    val grantedAt = timestampWithTimeZone("granted_at")
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object RelationshipStatus {
    const val PENDING = "PENDING"
    const val ACTIVE = "ACTIVE"
    const val ENDED = "ENDED"
}
