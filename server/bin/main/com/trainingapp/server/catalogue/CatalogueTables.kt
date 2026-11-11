package com.trainingapp.server.catalogue

import com.trainingapp.server.common.ApiJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

// Exposed mappings of the `catalogue` schema (V1 + V2 gaps G-1…G-5). Only public data lives here.

object TrainerListings : Table("catalogue.trainer_listing") {
    val trainerId = uuid("trainer_id")
    val headline = text("headline")
    val bio = text("bio").nullable()
    val specialties = array<String>("specialties")
    val isPublic = bool("is_public")
    val updatedAt = timestampWithTimeZone("updated_at")
    val displayName = text("display_name") // G-1
    val searchText = text("search_text") // G-1
    val goals = array<String>("goals") // G-2
    override val primaryKey = PrimaryKey(trainerId)
}

/** `[{weekday, opens, closes}]` in the gym's time zone; weekday is ISO (1 = Monday). */
@Serializable
data class OpeningHours(val weekday: Int, val opens: String, val closes: String)

object Gyms : Table("catalogue.gym") {
    val id = uuid("id")
    val name = text("name")
    val street = text("street")
    val city = text("city")
    val postalCode = text("postal_code").nullable()
    val latitude = decimal("latitude", 9, 6).nullable()
    val longitude = decimal("longitude", 9, 6).nullable()
    val timeZone = text("time_zone")
    val openingHours = jsonb("opening_hours", ApiJson, ListSerializer(OpeningHours.serializer())).nullable()
    val isPublic = bool("is_public")
    val createdBy = uuid("created_by").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val district = text("district").nullable() // G-4
    val searchText = text("search_text") // G-4
    override val primaryKey = PrimaryKey(id)
}

object TrainerGyms : Table("catalogue.trainer_gym") {
    val trainerId = uuid("trainer_id").references(TrainerListings.trainerId)
    val gymId = uuid("gym_id").references(Gyms.id)
    val isMain = bool("is_main") // G-5
    override val primaryKey = PrimaryKey(trainerId, gymId)
}

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
    val createdAt = timestampWithTimeZone("created_at")
    val archivedAt = timestampWithTimeZone("archived_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object ClientPriceOverrides : Table("catalogue.client_price_override") {
    val relationshipId = uuid("relationship_id")
    val offerId = uuid("offer_id")
    val priceMinor = long("price_minor")
    val currency = char("currency", 3)
    override val primaryKey = PrimaryKey(relationshipId, offerId)
}

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
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object PolicyTiers : Table("catalogue.policy_tier") {
    val policyVersionId = uuid("policy_version_id")
    val hoursBefore = integer("hours_before")
    val feePercent = integer("fee_percent")
    override val primaryKey = PrimaryKey(policyVersionId, hoursBefore)
}

object ClientPolicyOverrides : Table("catalogue.client_policy_override") {
    val relationshipId = uuid("relationship_id")
    val mode = text("mode")
    val policyVersionId = uuid("policy_version_id").nullable()
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(relationshipId)
}

/** View `catalogue.public_trainer_availability` (gap G-3): buckets only, owned by Scheduling. */
object PublicTrainerAvailability : Table("catalogue.public_trainer_availability") {
    val trainerId = uuid("trainer_id")
    val bucket = text("bucket")
}
