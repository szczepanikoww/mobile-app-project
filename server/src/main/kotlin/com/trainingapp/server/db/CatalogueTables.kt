package com.trainingapp.server.db

import com.trainingapp.server.platform.jsonb
import com.trainingapp.server.platform.tstz
import org.jetbrains.exposed.sql.Table

/** The only trainer data guests may see (public listing). */
object TrainerListings : Table("catalogue.trainer_listing") {
    val trainerId = uuid("trainer_id")
    val headline = text("headline")
    val bio = text("bio").nullable()
    val specialties = array<String>("specialties")
    val isPublic = bool("is_public")
    val updatedAt = tstz("updated_at")
    override val primaryKey = PrimaryKey(trainerId)
}

/** Gyms where sessions can take place. */
object Gyms : Table("catalogue.gym") {
    val id = uuid("id")
    val name = text("name")
    val street = text("street")
    val city = text("city")
    val postalCode = text("postal_code").nullable()
    val latitude = decimal("latitude", 9, 6).nullable()
    val longitude = decimal("longitude", 9, 6).nullable()
    val timeZone = text("time_zone")
    val openingHours = jsonb("opening_hours").nullable()
    val isPublic = bool("is_public")
    val createdBy = uuid("created_by").nullable()
    val createdAt = tstz("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Which gyms a trainer works at. */
object TrainerGyms : Table("catalogue.trainer_gym") {
    val trainerId = uuid("trainer_id")
    val gymId = uuid("gym_id")
    override val primaryKey = PrimaryKey(trainerId, gymId)
}

/** Price list: service x duration; sessions snapshot the price. */
object Offers : Table("catalogue.offer") {
    val id = uuid("id")
    val trainerId = uuid("trainer_id")
    val kind = text("kind")
    val name = text("name")
    val durationMinutes = integer("duration_minutes")
    val priceMinor = long("price_minor")
    val currency = char("currency", 3)
    val sessionsPerPeriod = integer("sessions_per_period").nullable()
    val seller = text("seller")
    val purpose = text("purpose")
    val isPublic = bool("is_public")
    val createdAt = tstz("created_at")
    val archivedAt = tstz("archived_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Per-client price for an offer. */
object ClientPriceOverrides : Table("catalogue.client_price_override") {
    val relationshipId = uuid("relationship_id")
    val offerId = uuid("offer_id")
    val priceMinor = long("price_minor")
    val currency = char("currency", 3)
    override val primaryKey = PrimaryKey(relationshipId, offerId)
}

/** Immutable cancellation policy version; saving the editor inserts a new one. */
object PolicyVersions : Table("catalogue.policy_version") {
    val id = uuid("id")
    val trainerId = uuid("trainer_id")
    val version = integer("version")
    val noShowPercent = integer("no_show_percent")
    val graceMinutes = integer("grace_minutes")
    val freeLateCancellations = integer("free_late_cancellations")
    val lateRescheduleIsCancel = bool("late_reschedule_is_cancel")
    val cashFeeMode = text("cash_fee_mode")
    val packageFeeMode = text("package_fee_mode")
    val clientNote = text("client_note").nullable()
    val createdAt = tstz("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Immutable fee tier (hours before start -> fee percent) of a policy version. */
object PolicyTiers : Table("catalogue.policy_tier") {
    val policyVersionId = uuid("policy_version_id")
    val hoursBefore = integer("hours_before")
    val feePercent = integer("fee_percent")
    override val primaryKey = PrimaryKey(policyVersionId, hoursBefore)
}

/** Per-client override: exempt the client or apply another policy version. */
object ClientPolicyOverrides : Table("catalogue.client_policy_override") {
    val relationshipId = uuid("relationship_id")
    val mode = text("mode")
    val policyVersionId = uuid("policy_version_id").nullable()
    val updatedAt = tstz("updated_at")
    override val primaryKey = PrimaryKey(relationshipId)
}
