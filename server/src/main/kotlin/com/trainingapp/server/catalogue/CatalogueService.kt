package com.trainingapp.server.catalogue

import com.trainingapp.contract.api.MoneyDto
import com.trainingapp.contract.catalog.AvailabilityBucketDto
import com.trainingapp.contract.catalog.FilterOptionsDto
import com.trainingapp.contract.catalog.GoalDto
import com.trainingapp.contract.catalog.GuestHomeDto
import com.trainingapp.contract.catalog.GymDetailDto
import com.trainingapp.contract.catalog.GymRefDto
import com.trainingapp.contract.catalog.GymSummaryDto
import com.trainingapp.contract.catalog.OfferDto
import com.trainingapp.contract.catalog.OpeningPeriodDto
import com.trainingapp.contract.catalog.PolicyTierDto
import com.trainingapp.contract.catalog.PriceRangeDto
import com.trainingapp.contract.catalog.PublicPolicyDto
import com.trainingapp.contract.catalog.SellerDto
import com.trainingapp.contract.catalog.TrainerGymDto
import com.trainingapp.contract.catalog.TrainerProfileDto
import com.trainingapp.contract.catalog.TrainerSortDto
import com.trainingapp.contract.catalog.TrainerSummaryDto
import com.trainingapp.contract.policy.CancellationPolicy
import com.trainingapp.contract.policy.PolicyTier
import com.trainingapp.contract.schedule.PolicyVersionDto
import com.trainingapp.contract.text.SearchText
import com.trainingapp.server.common.initialsOf
import com.trainingapp.server.common.toKotlin
import com.trainingapp.server.db.Db
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

data class TrainerQuery(
    val text: String = "",
    val goals: Set<GoalDto> = emptySet(),
    val gymId: Uuid? = null,
    val priceRange: PriceRangeDto? = null,
    val districts: Set<String> = emptySet(),
    val availability: Set<AvailabilityBucketDto> = emptySet(),
    val sort: TrainerSortDto = TrainerSortDto.PRICE_ASCENDING,
)

/** A trainer's current policy as rules: the shared `CancellationPolicy` plus the DB-only fields. */
data class PolicyRecord(
    val policy: CancellationPolicy,
    val lateRescheduleIsCancel: Boolean,
    val cashFeeMode: String,
    val packageFeeMode: String,
    val clientNote: String?,
) {
    val id: Uuid get() = Uuid.parse(policy.versionId)

    fun toDto() = PolicyVersionDto(
        versionId = policy.versionId,
        trainerId = policy.trainerId,
        version = policy.version,
        createdAt = policy.createdAt,
        tiers = policy.sortedTiers.map { PolicyTierDto(it.hoursBefore, it.feePercent) },
        noShowPercent = policy.noShowPercent,
        graceMinutes = policy.graceMinutes,
        freeLateCancellationsPerMonth = policy.freeLateCancellationsPerMonth,
        lateRescheduleIsCancel = lateRescheduleIsCancel,
        clientNote = clientNote,
    )
}

/**
 * The Catalogue module. Public reads (plan §5) use only `catalogue.*` rows marked public and the availability view;
 * they never touch identity, scheduling, training, messaging or billing tables.
 */
class CatalogueService(private val db: Db) {

    // ----- Public catalogue -----

    suspend fun home(locale: String): GuestHomeDto = db.tx {
        val trainers = publicTrainers()
        // Featured order is the catalogue's decision: cheapest public offers first, a stable, explainable rule until
        // the team defines featuring.
        GuestHomeDto(
            featuredTrainers = sortTrainers(trainers, TrainerSortDto.PRICE_ASCENDING).take(4),
            nearbyGyms = publicGyms(locale).sortedBy { SearchText.normalize(it.name) }.take(3),
        )
    }

    suspend fun trainers(query: TrainerQuery): List<TrainerSummaryDto> = db.tx {
        val all = publicTrainersWithSearch()
        val tokens = SearchText.tokens(query.text)
        val filtered = all.filter { (trainer, haystack) ->
            val price = trainer.startingPrice?.price?.minor
            tokens.all { it in haystack } &&
                (query.goals.isEmpty() || trainer.goals.any { it in query.goals }) &&
                (query.gymId == null || query.gymId.toString() in trainer.gymIds) &&
                (query.districts.isEmpty() || trainer.districts.any { it in query.districts }) &&
                (query.availability.isEmpty() || trainer.availability.any { it in query.availability }) &&
                (query.priceRange == null || (price != null && price in query.priceRange.bounds()))
        }.map { it.first }
        sortTrainers(filtered, query.sort)
    }

    suspend fun filterOptions(): FilterOptionsDto = db.tx {
        val gyms = Gyms.selectAll().where { Gyms.isPublic eq true }.toList()
        FilterOptionsDto(
            gyms = gyms.map { GymRefDto(it[Gyms.id].toString(), it[Gyms.name]) }.sortedBy { SearchText.normalize(it.name) },
            districts = gyms.mapNotNull { it[Gyms.district] }.distinct().sortedBy { SearchText.normalize(it) },
        )
    }

    /** Null for a trainer that is not public: the route answers 404, never 403. */
    suspend fun trainer(id: Uuid, locale: String): TrainerProfileDto? = db.tx {
        val listing = TrainerListings.selectAll().where { (TrainerListings.trainerId eq id) and (TrainerListings.isPublic eq true) }
            .singleOrNull() ?: return@tx null
        val summary = publicTrainers(listOf(id)).single()
        val gymRows = (TrainerGyms innerJoin Gyms).selectAll()
            .where { (TrainerGyms.trainerId eq id) and (Gyms.isPublic eq true) }.toList()
        TrainerProfileDto(
            summary = summary,
            bio = listing[TrainerListings.bio].orEmpty(),
            specialisations = listing[TrainerListings.specialties],
            gyms = gymRows.map { TrainerGymDto(gymSummary(it, locale), it[TrainerGyms.isMain]) }.sortedByDescending { it.main },
            media = emptyList(), // gap G-8: no link between a listing and its public media yet
            offers = publicOffers(listOf(id))[id].orEmpty().map { it.toDto() },
            cancellationPolicy = currentPolicy(id)?.let { record ->
                val p = record.policy
                PublicPolicyDto(
                    versionId = p.versionId,
                    version = p.version,
                    tiers = p.sortedTiers.map { PolicyTierDto(it.hoursBefore, it.feePercent) },
                    noShowPercent = p.noShowPercent,
                    graceMinutes = p.graceMinutes,
                    freeLateCancellationsPerMonth = p.freeLateCancellationsPerMonth,
                    freeCancellationHours = p.freeUntilHours.takeIf { it > 0 },
                    clientNote = record.clientNote,
                )
            },
        )
    }

    suspend fun gyms(text: String, districts: Set<String>, locale: String): List<GymSummaryDto> = db.tx {
        val tokens = SearchText.tokens(text)
        Gyms.selectAll().where { Gyms.isPublic eq true }.toList()
            .filter { row -> tokens.all { it in row[Gyms.searchText] } && (districts.isEmpty() || row[Gyms.district] in districts) }
            .map { gymSummary(it, locale) }
            .sortedBy { SearchText.normalize(it.name) } // distance is unknown until location is decided (D6)
    }

    suspend fun gym(id: Uuid, locale: String): GymDetailDto? = db.tx {
        val row = Gyms.selectAll().where { (Gyms.id eq id) and (Gyms.isPublic eq true) }.singleOrNull() ?: return@tx null
        val trainerIds = (TrainerGyms innerJoin TrainerListings).selectAll()
            .where { (TrainerGyms.gymId eq id) and (TrainerListings.isPublic eq true) }
            .map { it[TrainerGyms.trainerId] }
        GymDetailDto(
            summary = gymSummary(row, locale),
            address = listOfNotNull(row[Gyms.street], listOfNotNull(row[Gyms.postalCode], row[Gyms.city]).joinToString(" ")).joinToString(", "),
            weeklyHours = row[Gyms.openingHours].orEmpty().map { OpeningPeriodDto(it.weekday, it.opens, it.closes) },
            timeZone = row[Gyms.timeZone],
            trainers = sortTrainers(publicTrainers(trainerIds), TrainerSortDto.PRICE_ASCENDING),
        )
    }

    // ----- Policies and prices, for Scheduling and Billing -----

    fun JdbcTransaction.policy(versionId: Uuid): PolicyRecord? {
        val row = PolicyVersions.selectAll().where { PolicyVersions.id eq versionId }.singleOrNull() ?: return null
        return policyRecord(row)
    }

    /** The trainer's highest version: the one a new booking must accept. */
    fun JdbcTransaction.currentPolicy(trainerId: Uuid): PolicyRecord? =
        PolicyVersions.selectAll().where { PolicyVersions.trainerId eq trainerId }
            .orderBy(PolicyVersions.version, SortOrder.DESC).limit(1).singleOrNull()?.let { policyRecord(it) }

    /** A client-specific override (architecture §3.1): `EXEMPT` or `OTHER_POLICY` with a version id. */
    fun JdbcTransaction.policyOverride(relationshipId: Uuid): Pair<String, Uuid?>? =
        ClientPolicyOverrides.selectAll().where { ClientPolicyOverrides.relationshipId eq relationshipId }.singleOrNull()
            ?.let { it[ClientPolicyOverrides.mode] to it[ClientPolicyOverrides.policyVersionId] }

    fun JdbcTransaction.isPublicTrainer(trainerId: Uuid): Boolean =
        TrainerListings.selectAll().where { (TrainerListings.trainerId eq trainerId) and (TrainerListings.isPublic eq true) }.any()

    /** The cheapest public single-session offer: the booking price unless the relationship has an override. */
    fun JdbcTransaction.defaultSessionOffer(trainerId: Uuid): ResultRow? =
        Offers.selectAll().where {
            (Offers.trainerId eq trainerId) and (Offers.kind eq "SINGLE_SESSION") and (Offers.isPublic eq true) and Offers.archivedAt.isNull()
        }.orderBy(Offers.priceMinor, SortOrder.ASC).limit(1).singleOrNull()

    fun JdbcTransaction.priceOverride(relationshipId: Uuid, offerId: Uuid): MoneyDto? =
        ClientPriceOverrides.selectAll().where { (ClientPriceOverrides.relationshipId eq relationshipId) and (ClientPriceOverrides.offerId eq offerId) }
            .singleOrNull()?.let { MoneyDto(it[ClientPriceOverrides.priceMinor], it[ClientPriceOverrides.currency]) }

    /** Names of the trainer's gyms, offered as places in the booking sheet, with their ids. */
    fun JdbcTransaction.trainerGyms(trainerId: Uuid): List<Pair<Uuid, String>> =
        (TrainerGyms innerJoin Gyms).selectAll().where { TrainerGyms.trainerId eq trainerId }
            .orderBy(TrainerGyms.isMain, SortOrder.DESC)
            .map { it[Gyms.id] to it[Gyms.name] }

    fun JdbcTransaction.gymNames(ids: Collection<Uuid>): Map<Uuid, String> =
        if (ids.isEmpty()) emptyMap() else Gyms.selectAll().where { Gyms.id inList ids.distinct() }.associate { it[Gyms.id] to it[Gyms.name] }

    fun JdbcTransaction.offerName(id: Uuid): String? = Offers.selectAll().where { Offers.id eq id }.singleOrNull()?.get(Offers.name)

    // ----- Writes owned by the catalogue (search text, G-1/G-4) -----

    /** Recomputes the folded search text of a listing from its name, headline, specialties, gyms and districts. */
    fun JdbcTransaction.refreshTrainerSearchText(trainerId: Uuid) {
        val listing = TrainerListings.selectAll().where { TrainerListings.trainerId eq trainerId }.singleOrNull() ?: return
        val gyms = (TrainerGyms innerJoin Gyms).selectAll().where { TrainerGyms.trainerId eq trainerId }
            .flatMap { listOfNotNull(it[Gyms.name], it[Gyms.district]) }
        val text = SearchText.normalize(
            (listOf(listing[TrainerListings.displayName], listing[TrainerListings.headline]) + listing[TrainerListings.specialties] + gyms).joinToString(" "),
        )
        TrainerListings.update({ TrainerListings.trainerId eq trainerId }) { it[searchText] = text }
    }

    fun JdbcTransaction.refreshGymSearchText(gymId: Uuid) {
        val row = Gyms.selectAll().where { Gyms.id eq gymId }.single()
        val text = SearchText.normalize(listOfNotNull(row[Gyms.name], row[Gyms.district], row[Gyms.city]).joinToString(" "))
        Gyms.update({ Gyms.id eq gymId }) { it[searchText] = text }
    }

    // ----- Internals -----

    private fun JdbcTransaction.policyRecord(row: ResultRow): PolicyRecord {
        val id = row[PolicyVersions.id]
        val tiers = PolicyTiers.selectAll().where { PolicyTiers.policyVersionId eq id }
            .map { PolicyTier(it[PolicyTiers.hoursBefore], it[PolicyTiers.feePercent]) }
        return PolicyRecord(
            policy = CancellationPolicy(
                versionId = id.toString(),
                trainerId = row[PolicyVersions.trainerId].toString(),
                version = row[PolicyVersions.version],
                createdAt = row[PolicyVersions.createdAt].toKotlin(),
                tiers = tiers.sortedByDescending { it.hoursBefore },
                noShowPercent = row[PolicyVersions.noShowPercent],
                graceMinutes = row[PolicyVersions.graceMinutes],
                freeLateCancellationsPerMonth = row[PolicyVersions.freeLateCancellations],
            ),
            lateRescheduleIsCancel = row[PolicyVersions.lateRescheduleIsCancel],
            cashFeeMode = row[PolicyVersions.cashFeeMode],
            packageFeeMode = row[PolicyVersions.packageFeeMode],
            clientNote = row[PolicyVersions.clientNote],
        )
    }

    private data class OfferRow(val id: Uuid, val kind: String, val name: String, val minutes: Int, val price: MoneyDto, val sessions: Int?) {
        fun toDto() = OfferDto(
            id = id.toString(),
            title = name,
            price = price,
            durationMinutes = minutes,
            sessionCount = sessions ?: 1,
            seller = SellerDto.TRAINER, // the DB only allows TRAINER (G12); Seller.Gym is never produced
        )
    }

    private fun JdbcTransaction.publicOffers(trainerIds: List<Uuid>): Map<Uuid, List<OfferRow>> =
        if (trainerIds.isEmpty()) emptyMap() else Offers.selectAll().where {
            (Offers.trainerId inList trainerIds) and (Offers.isPublic eq true) and Offers.archivedAt.isNull()
        }.orderBy(Offers.priceMinor, SortOrder.ASC).groupBy({ it[Offers.trainerId] }) {
            OfferRow(it[Offers.id], it[Offers.kind], it[Offers.name], it[Offers.durationMinutes], MoneyDto(it[Offers.priceMinor], it[Offers.currency]), it[Offers.sessionsPerPeriod])
        }

    private fun JdbcTransaction.publicTrainersWithSearch(): List<Pair<TrainerSummaryDto, String>> {
        val listings = TrainerListings.selectAll().where { TrainerListings.isPublic eq true }.associateBy { it[TrainerListings.trainerId] }
        return publicTrainers(listings.keys.toList()).map { summary ->
            val listing = listings.getValue(Uuid.parse(summary.id))
            val goalText = summary.goals.joinToString(" ") { goalLabels.getValue(it) }
            summary to (listing[TrainerListings.searchText] + " " + SearchText.normalize(goalText))
        }
    }

    private fun JdbcTransaction.publicTrainers(only: List<Uuid>? = null): List<TrainerSummaryDto> {
        val listings = TrainerListings.selectAll().where {
            if (only == null) TrainerListings.isPublic eq true else (TrainerListings.isPublic eq true) and (TrainerListings.trainerId inList only)
        }.toList()
        val ids = listings.map { it[TrainerListings.trainerId] }
        if (ids.isEmpty()) return emptyList()
        val gyms = (TrainerGyms innerJoin Gyms).selectAll().where { (TrainerGyms.trainerId inList ids) and (Gyms.isPublic eq true) }
            .groupBy { it[TrainerGyms.trainerId] }
        val buckets = PublicTrainerAvailability.selectAll().where { PublicTrainerAvailability.trainerId inList ids }
            .groupBy({ it[PublicTrainerAvailability.trainerId] }) { AvailabilityBucketDto.valueOf(it[PublicTrainerAvailability.bucket]) }
        val offers = publicOffers(ids)
        return listings.map { row ->
            val id = row[TrainerListings.trainerId]
            val trainerGyms = gyms[id].orEmpty()
            val main = trainerGyms.firstOrNull { it[TrainerGyms.isMain] } ?: trainerGyms.firstOrNull()
            val name = row[TrainerListings.displayName]
            TrainerSummaryDto(
                id = id.toString(),
                name = name,
                initials = initialsOf(name),
                headline = row[TrainerListings.headline],
                mainGym = main?.let { GymRefDto(it[Gyms.id].toString(), it[Gyms.name]) },
                tags = row[TrainerListings.specialties],
                goals = row[TrainerListings.goals].mapNotNull { g -> GoalDto.entries.firstOrNull { it.name == g } },
                districts = trainerGyms.mapNotNull { it[Gyms.district] }.distinct(),
                gymIds = trainerGyms.map { it[Gyms.id].toString() },
                availability = buckets[id].orEmpty().distinct().sortedBy { it.ordinal },
                startingPrice = offers[id].orEmpty().filter { it.kind == "SINGLE_SESSION" }.minByOrNull { it.price.minor }?.toDto(),
            )
        }
    }

    private fun JdbcTransaction.publicGyms(locale: String): List<GymSummaryDto> =
        Gyms.selectAll().where { Gyms.isPublic eq true }.map { gymSummary(it, locale) }

    private fun gymSummary(row: ResultRow, locale: String) = GymSummaryDto(
        id = row[Gyms.id].toString(),
        name = row[Gyms.name],
        district = row[Gyms.district] ?: row[Gyms.city],
        hoursSummary = HoursFormatter.summary(row[Gyms.openingHours].orEmpty(), locale),
    )

    companion object {
        /** Visible goal names in both locales, so text search also finds "siła" or "strength" (as CatalogSearch). */
        val goalLabels = mapOf(
            GoalDto.REDUCTION to "Redukcja Weight loss",
            GoalDto.STRENGTH to "Siła Strength",
            GoalDto.MOBILITY to "Mobilność Mobility",
            GoalDto.COMPETITION to "Zawody Competition",
        )

        /** Mirrors `CatalogSearch`: trainers without a public price go last in both price orders. */
        fun sortTrainers(list: List<TrainerSummaryDto>, sort: TrainerSortDto): List<TrainerSummaryDto> {
            val byName = compareBy<TrainerSummaryDto> { SearchText.normalize(it.name) }
            return when (sort) {
                // No ratings exist yet (guest D1), so the rating order falls back to price.
                TrainerSortDto.PRICE_ASCENDING, TrainerSortDto.RATING -> list.sortedWith(
                    compareBy<TrainerSummaryDto> { it.startingPrice == null }.thenBy { it.startingPrice?.price?.minor }.then(byName),
                )
                TrainerSortDto.PRICE_DESCENDING -> list.sortedWith(
                    compareBy<TrainerSummaryDto> { it.startingPrice == null }.thenByDescending { it.startingPrice?.price?.minor }.then(byName),
                )
            }
        }

        /** The app's fixed `PriceRange` buckets, in minor units. */
        fun PriceRangeDto.bounds(): LongRange = when (this) {
            PriceRangeDto.UP_TO_100 -> Long.MIN_VALUE..100_00L
            PriceRangeDto.FROM_100_TO_150 -> 100_00L..150_00L
            PriceRangeDto.FROM_150 -> 150_00L..Long.MAX_VALUE
        }
    }
}
