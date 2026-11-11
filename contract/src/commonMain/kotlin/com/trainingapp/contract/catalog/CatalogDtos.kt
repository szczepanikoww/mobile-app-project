package com.trainingapp.contract.catalog

import com.trainingapp.contract.api.MoneyDto
import kotlinx.serialization.Serializable

/*
 * The public catalogue (`/v1/public/...`, no token). Public data only: no client names, notes, health data, chat or
 * relationship data. Fields without a column yet (ratings, verification, offer descriptions, amenities, media) are
 * null / false / empty until the team decides gaps G-6 … G-8 and guest D1/D2.
 */

@Serializable
enum class GoalDto { REDUCTION, STRENGTH, MOBILITY, COMPETITION }

@Serializable
enum class AvailabilityBucketDto { MORNING, AFTERNOON, EVENING, WEEKEND }

@Serializable
enum class PriceRangeDto { UP_TO_100, FROM_100_TO_150, FROM_150 }

@Serializable
enum class TrainerSortDto { PRICE_ASCENDING, PRICE_DESCENDING, RATING }

@Serializable
enum class SellerDto { TRAINER, GYM }

@Serializable
data class GymRefDto(val id: String, val name: String)

@Serializable
data class OfferDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val price: MoneyDto,
    val durationMinutes: Int?,
    val sessionCount: Int?,
    val validityText: String? = null,
    val highlighted: Boolean = false,
    val seller: SellerDto = SellerDto.TRAINER,
)

@Serializable
data class RatingDto(val average: Double, val count: Int)

@Serializable
data class TrainerSummaryDto(
    val id: String,
    val name: String,
    val initials: String,
    val headline: String,
    val mainGym: GymRefDto?,
    val tags: List<String>,
    val goals: List<GoalDto>,
    val districts: List<String>,
    val gymIds: List<String>,
    val availability: List<AvailabilityBucketDto>,
    val startingPrice: OfferDto?,
    val rating: RatingDto? = null,
    val verified: Boolean = false,
)

/** One fee step of the trainer's published policy; the app renders the text with `PolicyRules`. */
@Serializable
data class PolicyTierDto(val hoursBefore: Int, val feePercent: Int)

@Serializable
data class PublicPolicyDto(
    val versionId: String,
    val version: Int,
    val tiers: List<PolicyTierDto>,
    val noShowPercent: Int,
    val graceMinutes: Int,
    val freeLateCancellationsPerMonth: Int,
    /** Cancelling at least this many hours before is free; null when every cancellation is free. */
    val freeCancellationHours: Int?,
    val clientNote: String? = null,
)

@Serializable
data class PublicMediaDto(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    /** `READY` media carry a [playbackUrl]; anything else is not playable. */
    val state: String,
    val playbackUrl: String? = null,
)

@Serializable
data class TrainerGymDto(val gym: GymSummaryDto, val main: Boolean)

@Serializable
data class TrainerProfileDto(
    val summary: TrainerSummaryDto,
    val bio: String,
    val yearsOfExperience: Int? = null,
    val clientCountLabel: String? = null,
    val specialisations: List<String>,
    val gyms: List<TrainerGymDto>,
    val media: List<PublicMediaDto> = emptyList(),
    val offers: List<OfferDto>,
    val cancellationPolicy: PublicPolicyDto?,
)

@Serializable
data class GymSummaryDto(
    val id: String,
    val name: String,
    val district: String,
    /** Formatted on the server in the request's locale, e.g. `Pon–Pt 06:00–22:00 · Sob–Nd 08:00–20:00`. */
    val hoursSummary: String,
    val distanceMeters: Int? = null,
    val rating: RatingDto? = null,
)

/** `day` is ISO (1 = Monday). `close` ≤ `open` means the window runs past midnight. Times are `HH:mm`. */
@Serializable
data class OpeningPeriodDto(val day: Int, val open: String, val close: String)

@Serializable
data class GymDetailDto(
    val summary: GymSummaryDto,
    val address: String,
    val transitNote: String? = null,
    val weeklyHours: List<OpeningPeriodDto>,
    val timeZone: String,
    val amenities: List<String> = emptyList(),
    val trainers: List<TrainerSummaryDto>,
)

@Serializable
data class GuestHomeDto(val featuredTrainers: List<TrainerSummaryDto>, val nearbyGyms: List<GymSummaryDto>)

@Serializable
data class FilterOptionsDto(val gyms: List<GymRefDto>, val districts: List<String>)
