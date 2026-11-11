package com.trainingapp.server.db

import com.trainingapp.server.platform.citext
import com.trainingapp.server.platform.tstz
import org.jetbrains.exposed.sql.Table

/** Accounts: one row per person; roles are the profile rows below. */
object AppUsers : Table("identity.app_user") {
    val id = uuid("id")
    val authSubject = text("auth_subject")
    val email = citext("email").nullable()
    val displayName = text("display_name")
    val locale = text("locale")
    val timeZone = text("time_zone")
    val isOperator = bool("is_operator")
    val createdAt = tstz("created_at")
    val deletedAt = tstz("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Holding the trainer role = having this profile row. */
object TrainerProfiles : Table("identity.trainer_profile") {
    val userId = uuid("user_id")
    val defaultCurrency = char("default_currency", 3)
    val sessionBufferMinutes = integer("session_buffer_minutes")
    val paymentAccountRef = text("payment_account_ref").nullable()
    val createdAt = tstz("created_at")
    override val primaryKey = PrimaryKey(userId)
}

/** Holding the client role = having this profile row. */
object ClientProfiles : Table("identity.client_profile") {
    val userId = uuid("user_id")
    val createdAt = tstz("created_at")
    override val primaryKey = PrimaryKey(userId)
}

/** The trainer-client relationship: the unit of access, chat, billing and plans. */
object TrainerClients : Table("identity.trainer_client") {
    val id = uuid("id")
    val trainerId = uuid("trainer_id")
    val clientId = uuid("client_id")
    val status = text("status")
    val origin = text("origin")
    val createdAt = tstz("created_at")
    val startedAt = tstz("started_at").nullable()
    val endedAt = tstz("ended_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Scoped access a client gives the trainer; revoked when the relationship ends. */
object AccessGrants : Table("identity.access_grant") {
    val id = uuid("id")
    val relationshipId = uuid("relationship_id")
    val scope = text("scope")
    val grantedAt = tstz("granted_at")
    val revokedAt = tstz("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Trainer invite codes for connecting a client. */
object InviteCodes : Table("identity.invite_code") {
    val code = text("code")
    val trainerId = uuid("trainer_id")
    val expiresAt = tstz("expires_at")
    val maxUses = integer("max_uses")
    val usedCount = integer("used_count")
    val createdAt = tstz("created_at")
    override val primaryKey = PrimaryKey(code)
}
