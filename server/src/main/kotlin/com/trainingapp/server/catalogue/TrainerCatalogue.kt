package com.trainingapp.server.catalogue

import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import com.trainingapp.contract.api.GymDto
import com.trainingapp.contract.api.OfferDto
import com.trainingapp.contract.api.OfferKind
import com.trainingapp.contract.api.PolicyDto
import com.trainingapp.contract.api.PolicyEditorDto
import com.trainingapp.contract.api.SaveOfferRequest
import com.trainingapp.contract.api.SavePolicyRequest
import com.trainingapp.contract.api.SaveTrainerProfileRequest
import com.trainingapp.contract.api.TrainerProfileDto
import com.trainingapp.contract.money.Money
import com.trainingapp.contract.policy.PolicyRules
import com.trainingapp.contract.policy.PolicyTier
import com.trainingapp.contract.policy.PolicyValidator
import com.trainingapp.server.db.AppUsers
import com.trainingapp.server.db.Gyms
import com.trainingapp.server.db.Offers
import com.trainingapp.server.db.TrainerGyms
import com.trainingapp.server.db.TrainerListings
import com.trainingapp.server.db.TrainerProfiles
import com.trainingapp.server.platform.Db
import com.trainingapp.server.platform.ServerClock
import com.trainingapp.server.platform.invalid
import com.trainingapp.server.platform.newId
import com.trainingapp.server.platform.notFound
import com.trainingapp.server.platform.toOffset
import com.trainingapp.server.platform.toUuid
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/** Trainer-side catalogue: cancellation policy editor, price list and the trainer's own profile. */
class TrainerCatalogue(private val db: Db, private val clock: ServerClock) {

    // ---------------------------------------------------------------- policy

    suspend fun policyEditor(trainerId: UUID): PolicyEditorDto = db.tx {
        val current = Policies.current(trainerId)
        val warnings = current?.let {
            val rules = PolicyRules(it.tiers.map { t -> PolicyTier(t.hoursBefore, t.feePercent) }, it.noShowPercent,
                it.graceMinutes, it.freeLateCancellationsPerMonth)
            PolicyValidator.warnings(rules).map { w -> w.name }
        }.orEmpty()
        val price = Offers.selectAll()
            .where { (Offers.trainerId eq trainerId) and Offers.archivedAt.isNull() and (Offers.kind eq OfferKind.SINGLE_SESSION.name) }
            .orderBy(Offers.createdAt, SortOrder.ASC).limit(1).singleOrNull()
            ?.let { Money(it[Offers.priceMinor], it[Offers.currency]) }
        PolicyEditorDto(current, price, warnings)
    }

    suspend fun policyVersion(trainerId: UUID, versionId: UUID): PolicyDto = db.tx {
        Policies.byId(versionId).takeIf { it.trainerId == trainerId.toString() } ?: notFound("Policy not found")
    }

    suspend fun savePolicy(trainerId: UUID, request: SavePolicyRequest): PolicyDto = db.tx {
        Policies.save(trainerId, request, clock.now())
    }

    // ---------------------------------------------------------------- offers

    suspend fun offers(trainerId: UUID): List<OfferDto> = db.tx {
        Offers.selectAll().where { (Offers.trainerId eq trainerId) and Offers.archivedAt.isNull() }
            .orderBy(Offers.createdAt, SortOrder.ASC).map { it.toOffer() }
    }

    /**
     * Offers are never edited in place because sessions snapshot the price: "editing" an offer is archiving it
     * ([archiveOffer]) and creating a new one.
     */
    suspend fun createOffer(trainerId: UUID, request: SaveOfferRequest): OfferDto = db.tx {
        if (request.name.trim().length !in 1..80) invalid("Invalid name")
        if (request.durationMinutes !in 15..240) invalid("Invalid duration")
        if (request.price.minor < 0) invalid("Invalid price")
        if (request.price.currency.length != 3) invalid("Invalid currency")
        when (request.kind) {
            OfferKind.PACKAGE -> if ((request.sessionsPerPeriod ?: 0) <= 0) invalid("Package needs a session count")
            OfferKind.SINGLE_SESSION -> if (request.sessionsPerPeriod != null) invalid("Single session has no session count")
        }
        val id = newId()
        Offers.insert {
            it[Offers.id] = id
            it[Offers.trainerId] = trainerId
            it[kind] = request.kind.name
            it[name] = request.name.trim()
            it[durationMinutes] = request.durationMinutes
            it[priceMinor] = request.price.minor
            it[currency] = request.price.currency.uppercase()
            it[sessionsPerPeriod] = request.sessionsPerPeriod
            it[isPublic] = request.isPublic
            it[createdAt] = clock.now().toOffset()
        }
        Offers.selectAll().where { Offers.id eq id }.single().toOffer()
    }

    suspend fun archiveOffer(trainerId: UUID, offerId: UUID): Unit = db.tx {
        val updated = Offers.update({ (Offers.id eq offerId) and (Offers.trainerId eq trainerId) and Offers.archivedAt.isNull() }) {
            it[archivedAt] = clock.now().toOffset()
        }
        if (updated == 0) notFound("Offer not found")
    }

    private fun ResultRow.toOffer() = OfferDto(
        id = this[Offers.id].toString(),
        kind = OfferKind.valueOf(this[Offers.kind]),
        name = this[Offers.name],
        durationMinutes = this[Offers.durationMinutes],
        price = Money(this[Offers.priceMinor], this[Offers.currency]),
        sessionsPerPeriod = this[Offers.sessionsPerPeriod],
        isPublic = this[Offers.isPublic],
    )

    // ---------------------------------------------------------------- profile

    suspend fun profile(trainerId: UUID): TrainerProfileDto = db.tx { loadProfile(trainerId) }

    private fun loadProfile(trainerId: UUID): TrainerProfileDto {
        val user = AppUsers.selectAll().where { AppUsers.id eq trainerId }.singleOrNull() ?: notFound("Trainer not found")
        val trainer = TrainerProfiles.selectAll().where { TrainerProfiles.userId eq trainerId }.singleOrNull() ?: notFound("Trainer not found")
        val listing = TrainerListings.selectAll().where { TrainerListings.trainerId eq trainerId }.singleOrNull()
        val gyms = TrainerGyms.selectAll().where { TrainerGyms.trainerId eq trainerId }.map { it[TrainerGyms.gymId].toString() }.sorted()
        return TrainerProfileDto(
            userId = trainerId.toString(),
            displayName = user[AppUsers.displayName],
            headline = listing?.get(TrainerListings.headline).orEmpty(),
            bio = listing?.get(TrainerListings.bio),
            specialties = listing?.get(TrainerListings.specialties).orEmpty(),
            isPublic = listing?.get(TrainerListings.isPublic) ?: false,
            sessionBufferMinutes = trainer[TrainerProfiles.sessionBufferMinutes],
            defaultCurrency = trainer[TrainerProfiles.defaultCurrency],
            gymIds = gyms,
            payoutAccountConnected = trainer[TrainerProfiles.paymentAccountRef] != null,
        )
    }

    suspend fun saveProfile(trainerId: UUID, request: SaveTrainerProfileRequest): TrainerProfileDto = db.tx {
        val name = request.displayName.trim()
        if (name.length !in 1..80) invalid("Invalid name")
        if (request.headline.length > 120) invalid("Headline too long")
        if ((request.bio?.length ?: 0) > 2000) invalid("Bio too long")
        if (request.specialties.size > 10 || request.specialties.any { it.isBlank() || it.length > 40 }) invalid("Invalid specialties")
        if (request.sessionBufferMinutes !in 0..120) invalid("Invalid buffer")
        val gymIds = request.gymIds.map { it.toUuid("gymId") }.distinct()
        if (gymIds.isNotEmpty()) {
            val found = Gyms.selectAll().where { Gyms.id inList gymIds }.count()
            if (found != gymIds.size.toLong()) invalid("Unknown gym")
        }
        val now = clock.now().toOffset()
        val bio = request.bio?.takeIf { it.isNotBlank() }
        val specialties = request.specialties.map { it.trim() }
        val headline = request.headline.trim()
        val exists = TrainerListings.selectAll().where { TrainerListings.trainerId eq trainerId }.any()
        if (exists) {
            TrainerListings.update({ TrainerListings.trainerId eq trainerId }) {
                it[TrainerListings.headline] = headline
                it[TrainerListings.bio] = bio
                it[TrainerListings.specialties] = specialties
                it[isPublic] = request.isPublic
                it[updatedAt] = now
            }
        } else {
            TrainerListings.insert {
                it[TrainerListings.trainerId] = trainerId
                it[TrainerListings.headline] = headline
                it[TrainerListings.bio] = bio
                it[TrainerListings.specialties] = specialties
                it[isPublic] = request.isPublic
                it[updatedAt] = now
            }
        }
        AppUsers.update({ AppUsers.id eq trainerId }) { it[displayName] = name }
        TrainerProfiles.update({ TrainerProfiles.userId eq trainerId }) { it[sessionBufferMinutes] = request.sessionBufferMinutes }
        TrainerGyms.deleteWhere { TrainerGyms.trainerId eq trainerId }
        gymIds.forEach { gym ->
            TrainerGyms.insert {
                it[TrainerGyms.trainerId] = trainerId
                it[gymId] = gym
            }
        }
        loadProfile(trainerId)
    }

    /** Public gyms the profile editor can pick from. */
    suspend fun gyms(): List<GymDto> = db.tx {
        Gyms.selectAll().where { Gyms.isPublic eq true }.orderBy(Gyms.city to SortOrder.ASC, Gyms.name to SortOrder.ASC)
            .map { GymDto(it[Gyms.id].toString(), it[Gyms.name], it[Gyms.city], it[Gyms.street]) }
    }
}
