package pl.trainerapp.feature.trainerprofile.data

import com.trainingapp.contract.api.AvailabilityDto
import com.trainingapp.contract.api.AvailabilityRuleDto
import com.trainingapp.contract.api.OfferDto
import com.trainingapp.contract.api.OfferKind
import com.trainingapp.contract.api.SaveOfferRequest
import com.trainingapp.contract.api.SaveTrainerProfileRequest
import com.trainingapp.contract.api.TrainerProfileDto
import com.trainingapp.contract.money.Money
import io.ktor.http.HttpMethod
import pl.trainer.data.remote.TrainerApiClient
import pl.trainer.domain.Failure
import pl.trainer.domain.TrainerException
import pl.trainerapp.feature.trainerfinance.model.Pln
import pl.trainerapp.feature.trainerprofile.TrainerProfileRepository
import pl.trainerapp.feature.trainerprofile.model.AvailabilityDay
import pl.trainerapp.feature.trainerprofile.model.PayoutAccount
import pl.trainerapp.feature.trainerprofile.model.ProfileSnapshot
import pl.trainerapp.feature.trainerprofile.model.TrainerProfile
import pl.trainerapp.feature.trainerprofile.model.TrainerSettings
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The trainer's profile, weekly hours and hourly rate from `trainer/profile`, `trainer/availability` and
 * `trainer/offers`.
 *
 * Server-backed: name, bio, specialties (profile); weekly hours (availability, ISO weekday 1 = Monday); hourly rate
 * (the price of the first single-session offer; changing it creates a new offer and archives the old one, because
 * offers are never edited in place); "payout account connected" (flag only).
 *
 * Kept in memory inside this repository only, because the server has no endpoint for them (lost on restart):
 * [TrainerSettings] (starting with the demo defaults), the trainer's phone and location, and the payout figures
 * (balance and last payout stay 0, the account label stays empty). The e-mail address is not in the DTO and is "".
 * Fields of the server profile the screen does not edit (headline, visibility, buffer, gyms) are re-read and sent back
 * unchanged on save. Availability rules keep their gym and validity dates when their hours were not edited.
 *
 * [now] only stamps [ProfileSnapshot.lastUpdated] (the DTOs carry no server time). The last successful snapshot is
 * cached and returned flagged offline when there is no connection.
 */
class RemoteTrainerProfileRepository(
    private val api: TrainerApiClient,
    private val now: () -> Instant = { Clock.System.now() },
) : TrainerProfileRepository {
    private var settings = TrainerSettings(
        notifyMessages = true, notifySessions = true, notifyPayments = true, emailSummary = false, autoConfirm = false,
    )
    private var phone = ""
    private var location = ""
    private var payout = PayoutAccount(connected = false, accountLabel = "", balance = Pln(0), lastPayout = Pln(0))
    private var rules: AvailabilityDto? = null
    private var last: ProfileSnapshot? = null

    override suspend fun load(): ProfileSnapshot {
        try {
            val profile = api.get<TrainerProfileDto>("trainer/profile")
            val availability = api.get<AvailabilityDto>("trainer/availability")
            val offers = api.get<List<OfferDto>>("trainer/offers")
            rules = availability
            payout = payout.copy(connected = profile.payoutAccountConnected)
            val rate = offers.firstOrNull { it.kind == OfferKind.SINGLE_SESSION }?.price?.minor ?: 0L
            return ProfileSnapshot(
                profile = TrainerProfile(
                    name = profile.displayName,
                    email = "",
                    phone = phone,
                    location = location,
                    bio = profile.bio.orEmpty(),
                    specializations = profile.specialties,
                    hourlyRate = Pln(rate),
                ),
                settings = settings,
                availability = availability.toDays(),
                payout = payout,
                isOffline = false,
                lastUpdated = now(),
            ).also { last = it }
        } catch (e: TrainerException) {
            val cached = last
            if (e.failure == Failure.Offline && cached != null) return cached.copy(isOffline = true)
            throw e
        }
    }

    override suspend fun saveProfile(profile: TrainerProfile) {
        val current = api.get<TrainerProfileDto>("trainer/profile")
        val body = SaveTrainerProfileRequest(
            displayName = profile.name.trim(),
            headline = current.headline,
            bio = profile.bio.trim().ifEmpty { null },
            specialties = profile.specializations,
            isPublic = current.isPublic,
            sessionBufferMinutes = current.sessionBufferMinutes,
            gymIds = current.gymIds,
        )
        api.send<SaveTrainerProfileRequest, TrainerProfileDto>(HttpMethod.Put, "trainer/profile", body)
        saveRate(profile.hourlyRate.grosze, current.defaultCurrency)
        phone = profile.phone
        location = profile.location
        last = last?.copy(profile = profile.copy(email = last?.profile?.email.orEmpty()))
    }

    /** Offers are never edited in place (sessions snapshot the price): create the new one first, then archive the old. */
    private suspend fun saveRate(grosze: Long, currency: String) {
        val offers = api.get<List<OfferDto>>("trainer/offers")
        val old = offers.firstOrNull { it.kind == OfferKind.SINGLE_SESSION }
        if (old?.price?.minor == grosze || (old == null && grosze <= 0)) return
        val request = SaveOfferRequest(
            kind = OfferKind.SINGLE_SESSION,
            name = old?.name ?: "Sesja 60 min",
            durationMinutes = old?.durationMinutes ?: 60,
            price = Money(grosze, old?.price?.currency ?: currency),
            isPublic = old?.isPublic ?: true,
        )
        api.send<SaveOfferRequest, OfferDto>(HttpMethod.Post, "trainer/offers", request)
        if (old != null) api.command(HttpMethod.Post, "trainer/offers/${old.id}/archive")
    }

    override suspend fun saveSettings(settings: TrainerSettings) {
        this.settings = settings
        last = last?.copy(settings = settings)
    }

    override suspend fun saveAvailability(days: List<AvailabilityDay>) {
        val base = rules ?: api.get<AvailabilityDto>("trainer/availability")
        val kept = base.rules.groupBy { it.weekday }
        val out = days.take(7).flatMapIndexed { index, day ->
            val weekday = index + 1
            val existing = kept[weekday].orEmpty()
            when {
                !day.enabled -> emptyList()
                existing.isNotEmpty() && existing.hoursText() == day.hours -> existing
                else -> parseHours(day.hours).map { (start, end) -> AvailabilityRuleDto(weekday, start, end) }
            }
        }
        val saved = api.send<AvailabilityDto, AvailabilityDto>(HttpMethod.Put, "trainer/availability", AvailabilityDto(base.timeZone, out))
        rules = saved
        last = last?.copy(availability = saved.toDays())
    }
}

private val DayNames = listOf("Poniedziałek", "Wtorek", "Środa", "Czwartek", "Piątek", "Sobota", "Niedziela")

private fun List<AvailabilityRuleDto>.hoursText() =
    sortedBy { it.startTime }.joinToString(", ") { "${it.startTime.take(5)} – ${it.endTime.take(5)}" }

private fun AvailabilityDto.toDays(): List<AvailabilityDay> = DayNames.mapIndexed { index, name ->
    val day = rules.filter { it.weekday == index + 1 }
    if (day.isEmpty()) AvailabilityDay(name, enabled = false, hours = "Nieczynne") else AvailabilityDay(name, true, day.hoursText())
}

/** "08:00 – 12:00, 14:00 – 18:00" to time pairs; anything else is invalid input. */
private fun parseHours(text: String): List<Pair<String, String>> {
    val time = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    val ranges = text.split(",").map { part ->
        val bounds = part.split("–", "-").map { it.trim() }
        if (bounds.size != 2 || !time.matches(bounds[0]) || !time.matches(bounds[1])) throw TrainerException(Failure.InvalidInput)
        bounds[0] to bounds[1]
    }
    return ranges
}
