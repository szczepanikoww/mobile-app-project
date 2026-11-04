package com.trainingapp.feature.guest.model

import com.trainingapp.domain.format.Money
import com.trainingapp.domain.media.MediaState
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

/*
 * The public catalogue. These models are a separate public view: they carry no client names, notes, health data,
 * chat or relationship data. Only aggregate public numbers are allowed. The server must enforce the same rule;
 * hiding fields in the UI is not authorisation.
 */

/** Training goals a guest can browse by ("Czego szukasz?"). Labels live in resources. */
enum class Goal { Reduction, Strength, Mobility, Competition }

/** When a trainer says they usually work. Declared by the trainer; not live free slots. */
enum class Availability { Morning, Afternoon, Evening, Weekend }

/** Fixed starting-price ranges for the "Cena" filter, in minor units of PLN. */
enum class PriceRange(val minMinor: Long?, val maxMinor: Long?) {
    UpTo100(null, 100_00),
    From100To150(100_00, 150_00),
    From150(150_00, null),
}

enum class TrainerSort { PriceAscending, PriceDescending, Rating }

enum class ViewMode { List, Grid }

enum class Amenity { FreeWeights, Cardio, Parking, Showers, Sauna, FunctionalZone }

data class Rating(val average: Double, val count: Int)

data class GymRef(val id: String, val name: String)

/** Who sells an offer: the trainer personally or the gym (G7, G12). */
enum class Seller { Trainer, Gym }

/**
 * A priced service or package. [validityText] is the trainer's own offer text ("Ważny 30 dni"); the app has no
 * expiry or renewal logic (G13).
 */
data class Offer(
    val id: String,
    val title: String,
    val description: String?,
    val price: Money,
    val durationMinutes: Int?,
    val sessionCount: Int?,
    val validityText: String?,
    val highlighted: Boolean,
    val seller: Seller,
)

data class PolicyTier(val description: String)

/** The trainer's published cancellation policy version. Never a universal, hard-coded rule. */
data class PublicCancellationPolicy(val version: Int, val summary: String, val tiers: List<PolicyTier>)

/** Public media. Only [MediaState.READY] with a [playbackUrl] is playable. */
data class PublicMedia(
    val id: String,
    val title: String,
    val subtitle: String?,
    val state: MediaState,
    val playbackUrl: String?,
) {
    val playable: Boolean get() = state == MediaState.READY && playbackUrl != null
}

data class TrainerSummary(
    val id: String,
    val name: String,
    val initials: String,
    val headline: String,
    val mainGym: GymRef?,
    val tags: List<String>,
    val goals: Set<Goal>,
    val districts: Set<String>,
    val gymIds: Set<String>,
    val availability: Set<Availability>,
    val startingPrice: Offer?,
    val rating: Rating?,
    val verified: Boolean,
)

data class TrainerGym(val gym: GymSummary, val main: Boolean)

data class TrainerProfile(
    val summary: TrainerSummary,
    val bio: String,
    val yearsOfExperience: Int?,
    /** An aggregate public label such as "140+"; never a list of clients. */
    val clientCountLabel: String?,
    val specialisations: List<String>,
    val gyms: List<TrainerGym>,
    val media: List<PublicMedia>,
    val offers: List<Offer>,
    val cancellationPolicy: PublicCancellationPolicy?,
)

data class GymSummary(
    val id: String,
    val name: String,
    val district: String,
    val hoursSummary: String,
    /** Fixture distance in the demo; null when location is unknown (D6). */
    val distanceMeters: Int?,
    val rating: Rating?,
)

/** One opening window. [close] earlier than or equal to [open] means the window runs past midnight. */
data class OpeningPeriod(val day: DayOfWeek, val open: LocalTime, val close: LocalTime)

data class GymDetail(
    val summary: GymSummary,
    val address: String,
    val transitNote: String?,
    val weeklyHours: List<OpeningPeriod>,
    /** IANA time zone of the gym, used for "Otwarte teraz". */
    val timeZone: String,
    val amenities: List<Amenity>,
    val trainers: List<TrainerSummary>,
)

data class GuestHome(
    val featuredTrainers: List<TrainerSummary>,
    val nearbyGyms: List<GymSummary>,
)

data class TrainerQuery(
    val text: String = "",
    val goals: Set<Goal> = emptySet(),
    val gymId: String? = null,
    val priceRange: PriceRange? = null,
    val districts: Set<String> = emptySet(),
    val availability: Set<Availability> = emptySet(),
    val sort: TrainerSort = TrainerSort.PriceAscending,
) {
    val hasFilters: Boolean
        get() = text.isNotBlank() || goals.isNotEmpty() || gymId != null || priceRange != null ||
            districts.isNotEmpty() || availability.isNotEmpty()

    /** Clears the search text and filters; the chosen sort order stays. */
    fun cleared() = TrainerQuery(sort = sort)
}

data class GymQuery(val text: String = "", val districts: Set<String> = emptySet()) {
    val hasFilters: Boolean get() = text.isNotBlank() || districts.isNotEmpty()
}

data class FilterOptions(val gyms: List<GymRef>, val districts: List<String>)
