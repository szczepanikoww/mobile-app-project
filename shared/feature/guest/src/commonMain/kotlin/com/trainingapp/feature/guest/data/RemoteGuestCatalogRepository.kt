package com.trainingapp.feature.guest.data

import com.trainingapp.contract.api.MoneyDto
import com.trainingapp.contract.catalog.AvailabilityBucketDto
import com.trainingapp.contract.catalog.FilterOptionsDto
import com.trainingapp.contract.catalog.GoalDto
import com.trainingapp.contract.catalog.GuestHomeDto
import com.trainingapp.contract.catalog.GymDetailDto
import com.trainingapp.contract.catalog.GymSummaryDto
import com.trainingapp.contract.catalog.OfferDto
import com.trainingapp.contract.catalog.PriceRangeDto
import com.trainingapp.contract.catalog.PublicPolicyDto
import com.trainingapp.contract.catalog.SellerDto
import com.trainingapp.contract.catalog.TrainerProfileDto
import com.trainingapp.contract.catalog.TrainerSortDto
import com.trainingapp.contract.catalog.TrainerSummaryDto
import com.trainingapp.contract.money.Money
import com.trainingapp.data.remote.ApiClient
import com.trainingapp.data.remote.ApiOfflineException
import com.trainingapp.domain.media.MediaState
import com.trainingapp.feature.guest.model.Amenity
import com.trainingapp.feature.guest.model.Availability
import com.trainingapp.feature.guest.model.CatalogOfflineException
import com.trainingapp.feature.guest.model.FilterOptions
import com.trainingapp.feature.guest.model.Goal
import com.trainingapp.feature.guest.model.GuestCatalogRepository
import com.trainingapp.feature.guest.model.GuestHome
import com.trainingapp.feature.guest.model.GymDetail
import com.trainingapp.feature.guest.model.GymQuery
import com.trainingapp.feature.guest.model.GymRef
import com.trainingapp.feature.guest.model.GymSummary
import com.trainingapp.feature.guest.model.Offer
import com.trainingapp.feature.guest.model.OpeningPeriod
import com.trainingapp.feature.guest.model.PriceRange
import com.trainingapp.feature.guest.model.PublicCancellationPolicy
import com.trainingapp.feature.guest.model.PublicMedia
import com.trainingapp.feature.guest.model.Rating
import com.trainingapp.feature.guest.model.Seller
import com.trainingapp.feature.guest.model.TrainerGym
import com.trainingapp.feature.guest.model.TrainerProfile
import com.trainingapp.feature.guest.model.TrainerQuery
import com.trainingapp.feature.guest.model.TrainerSort
import com.trainingapp.feature.guest.model.TrainerSummary
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.serialization.builtins.ListSerializer
import com.trainingapp.feature.guest.model.PolicyTier as PublicPolicyTier

/**
 * The public catalogue from the backend (`/v1/public/...`, plan §5). Filtering and sorting happen on the server with
 * the same rules as [CatalogSearch]; this class only maps DTOs to the guest models. Without a connection it raises
 * [CatalogOfflineException], so the screens keep their last data, as with the demo source.
 */
class RemoteGuestCatalogRepository(private val api: ApiClient) : GuestCatalogRepository {

    override suspend fun home(): GuestHome = remote {
        api.get("/v1/public/home", GuestHomeDto.serializer()).let { dto ->
            GuestHome(dto.featuredTrainers.map { it.toModel() }, dto.nearbyGyms.map { it.toModel() })
        }
    }

    override suspend fun trainers(query: TrainerQuery): List<TrainerSummary> = remote {
        val params = buildList {
            if (query.text.isNotBlank()) add("text" to query.text)
            query.goals.forEach { add("goal" to it.toDto().name) }
            query.gymId?.let { add("gymId" to it) }
            query.priceRange?.let { add("price" to it.toDto().name) }
            query.districts.forEach { add("district" to it) }
            query.availability.forEach { add("availability" to it.toDto().name) }
            add("sort" to query.sort.toDto().name)
        }
        api.get("/v1/public/trainers", ListSerializer(TrainerSummaryDto.serializer()), params).map { it.toModel() }
    }

    override suspend fun filterOptions(): FilterOptions = remote {
        api.get("/v1/public/trainers/filters", FilterOptionsDto.serializer()).let { dto ->
            FilterOptions(dto.gyms.map { GymRef(it.id, it.name) }, dto.districts)
        }
    }

    override suspend fun trainer(id: String): TrainerProfile? = remote {
        api.getOrNull("/v1/public/trainers/$id", TrainerProfileDto.serializer())?.toModel()
    }

    override suspend fun gyms(query: GymQuery): List<GymSummary> = remote {
        val params = buildList {
            if (query.text.isNotBlank()) add("text" to query.text)
            query.districts.forEach { add("district" to it) }
        }
        api.get("/v1/public/gyms", ListSerializer(GymSummaryDto.serializer()), params).map { it.toModel() }
    }

    override suspend fun gym(id: String): GymDetail? = remote {
        api.getOrNull("/v1/public/gyms/$id", GymDetailDto.serializer())?.toModel()
    }

    private suspend fun <T> remote(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiOfflineException) {
        throw CatalogOfflineException()
    }
}

// ----- DTO → guest model -----

private fun MoneyDto.toMoney() = Money(minor, currency)

private fun GoalDto.toModel() = when (this) {
    GoalDto.REDUCTION -> Goal.Reduction
    GoalDto.STRENGTH -> Goal.Strength
    GoalDto.MOBILITY -> Goal.Mobility
    GoalDto.COMPETITION -> Goal.Competition
}

private fun Goal.toDto() = when (this) {
    Goal.Reduction -> GoalDto.REDUCTION
    Goal.Strength -> GoalDto.STRENGTH
    Goal.Mobility -> GoalDto.MOBILITY
    Goal.Competition -> GoalDto.COMPETITION
}

private fun AvailabilityBucketDto.toModel() = when (this) {
    AvailabilityBucketDto.MORNING -> Availability.Morning
    AvailabilityBucketDto.AFTERNOON -> Availability.Afternoon
    AvailabilityBucketDto.EVENING -> Availability.Evening
    AvailabilityBucketDto.WEEKEND -> Availability.Weekend
}

private fun Availability.toDto() = when (this) {
    Availability.Morning -> AvailabilityBucketDto.MORNING
    Availability.Afternoon -> AvailabilityBucketDto.AFTERNOON
    Availability.Evening -> AvailabilityBucketDto.EVENING
    Availability.Weekend -> AvailabilityBucketDto.WEEKEND
}

private fun PriceRange.toDto() = when (this) {
    PriceRange.UpTo100 -> PriceRangeDto.UP_TO_100
    PriceRange.From100To150 -> PriceRangeDto.FROM_100_TO_150
    PriceRange.From150 -> PriceRangeDto.FROM_150
}

private fun TrainerSort.toDto() = when (this) {
    TrainerSort.PriceAscending -> TrainerSortDto.PRICE_ASCENDING
    TrainerSort.PriceDescending -> TrainerSortDto.PRICE_DESCENDING
    TrainerSort.Rating -> TrainerSortDto.RATING
}

private fun OfferDto.toModel() = Offer(
    id = id,
    title = title,
    description = description,
    price = price.toMoney(),
    durationMinutes = durationMinutes,
    sessionCount = sessionCount,
    validityText = validityText,
    highlighted = highlighted,
    seller = if (seller == SellerDto.GYM) Seller.Gym else Seller.Trainer,
)

private fun TrainerSummaryDto.toModel() = TrainerSummary(
    id = id,
    name = name,
    initials = initials,
    headline = headline,
    mainGym = mainGym?.let { GymRef(it.id, it.name) },
    tags = tags,
    goals = goals.map { it.toModel() }.toSet(),
    districts = districts.toSet(),
    gymIds = gymIds.toSet(),
    availability = availability.map { it.toModel() }.toSet(),
    startingPrice = startingPrice?.toModel(),
    rating = rating?.let { Rating(it.average, it.count) },
    verified = verified,
)

private fun GymSummaryDto.toModel() = GymSummary(
    id = id,
    name = name,
    district = district,
    hoursSummary = hoursSummary,
    distanceMeters = distanceMeters,
    rating = rating?.let { Rating(it.average, it.count) },
)

private fun TrainerProfileDto.toModel() = TrainerProfile(
    summary = summary.toModel(),
    bio = bio,
    yearsOfExperience = yearsOfExperience,
    clientCountLabel = clientCountLabel,
    specialisations = specialisations,
    gyms = gyms.map { TrainerGym(it.gym.toModel(), it.main) },
    media = media.map { m ->
        val state = MediaState.entries.firstOrNull { it.name == m.state } ?: MediaState.PROCESSING
        PublicMedia(m.id, m.title, m.subtitle, state, m.playbackUrl.takeIf { state == MediaState.READY })
    },
    offers = offers.map { it.toModel() },
    cancellationPolicy = cancellationPolicy?.toModel(),
)

/**
 * The policy text in the same words as the demo catalogue. The server sends the rules (tiers, percents); the wording
 * stays in the app. Polish only, like the demo source; moving it to string resources is a follow-up for both.
 */
private fun PublicPolicyDto.toModel(): PublicCancellationPolicy {
    val free = freeCancellationHours
    val tiersText = buildList {
        if (free != null) add(PublicPolicyTier("Ponad $free h przed sesją: bez opłaty"))
        tiers.sortedByDescending { it.hoursBefore }.forEach { add(PublicPolicyTier("Mniej niż ${it.hoursBefore} h przed sesją: ${it.feePercent}% ceny sesji")) }
        add(PublicPolicyTier("Nieobecność bez odwołania: $noShowPercent% ceny sesji"))
    }
    val summary = if (free != null) "Bezpłatne odwołanie do $free h przed sesją. Później trener może naliczyć opłatę."
    else "Odwołanie jest zawsze bezpłatne."
    return PublicCancellationPolicy(version, summary, tiersText, free)
}

private fun GymDetailDto.toModel() = GymDetail(
    summary = summary.toModel(),
    address = address,
    transitNote = transitNote,
    weeklyHours = weeklyHours.map { OpeningPeriod(DayOfWeek(it.day), LocalTime.parse(it.open), LocalTime.parse(it.close)) },
    timeZone = timeZone,
    amenities = amenities.mapNotNull { a -> Amenity.entries.firstOrNull { it.name.equals(a.replace("_", ""), ignoreCase = true) } },
    trainers = trainers.map { it.toModel() },
)
