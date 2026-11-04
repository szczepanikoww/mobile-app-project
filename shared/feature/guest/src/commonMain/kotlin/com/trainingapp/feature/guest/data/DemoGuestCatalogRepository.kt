package com.trainingapp.feature.guest.data

import com.trainingapp.domain.format.Money
import com.trainingapp.domain.media.MediaState
import com.trainingapp.feature.guest.model.Amenity
import com.trainingapp.feature.guest.model.Availability
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
import com.trainingapp.feature.guest.model.PolicyTier
import com.trainingapp.feature.guest.model.PublicCancellationPolicy
import com.trainingapp.feature.guest.model.PublicMedia
import com.trainingapp.feature.guest.model.Rating
import com.trainingapp.feature.guest.model.Seller
import com.trainingapp.feature.guest.model.TrainerGym
import com.trainingapp.feature.guest.model.TrainerProfile
import com.trainingapp.feature.guest.model.TrainerQuery
import com.trainingapp.feature.guest.model.TrainerSummary
import kotlinx.coroutines.delay
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

/** Demo scenarios for previews, tests and manual checks of each state. */
enum class CatalogScenario { Normal, Empty, Error }

/**
 * An explicit demo data source: the public catalogue backend does not exist yet. Every name, gym and address is
 * synthetic. Real gym brands must not appear here, because they would read as partners.
 */
class DemoGuestCatalogRepository(
    var scenario: CatalogScenario = CatalogScenario.Normal,
    private val latencyMs: Long = 400,
) : GuestCatalogRepository {

    private suspend fun <T> respond(block: () -> T): T {
        if (latencyMs > 0) delay(latencyMs)
        if (scenario == CatalogScenario.Error) throw IllegalStateException("demo catalogue error")
        return block()
    }

    private val trainers get() = if (scenario == CatalogScenario.Empty) emptyList() else DemoCatalog.trainers
    private val gyms get() = if (scenario == CatalogScenario.Empty) emptyList() else DemoCatalog.gyms

    override suspend fun home(): GuestHome = respond {
        GuestHome(
            // The featured order is the catalogue's decision, never computed in the UI.
            featuredTrainers = DemoCatalog.featuredIds.mapNotNull { id -> trainers.find { it.summary.id == id }?.summary },
            nearbyGyms = CatalogSearch.gyms(gyms.map { it.summary }, GymQuery()).take(3),
        )
    }

    override suspend fun trainers(query: TrainerQuery): List<TrainerSummary> = respond {
        CatalogSearch.trainers(trainers.map { it.summary }, query)
    }

    override suspend fun filterOptions(): FilterOptions = respond {
        FilterOptions(
            gyms = gyms.map { GymRef(it.summary.id, it.summary.name) }.sortedBy { it.name },
            districts = gyms.map { it.summary.district }.distinct().sorted(),
        )
    }

    override suspend fun trainer(id: String): TrainerProfile? = respond { trainers.find { it.summary.id == id } }

    override suspend fun gyms(query: GymQuery): List<GymSummary> = respond { CatalogSearch.gyms(gyms.map { it.summary }, query) }

    override suspend fun gym(id: String): GymDetail? = respond { gyms.find { it.summary.id == id } }
}

/** The synthetic catalogue. One source for every screen, so a trainer's price and rating never disagree. */
object DemoCatalog {
    private const val Zone = "Europe/Warsaw"

    private fun everyDay(open: LocalTime, close: LocalTime) = DayOfWeek.entries.map { OpeningPeriod(it, open, close) }
    private fun weekdays(open: LocalTime, close: LocalTime) =
        listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY).map { OpeningPeriod(it, open, close) }
    private fun weekend(open: LocalTime, close: LocalTime) = listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).map { OpeningPeriod(it, open, close) }

    private val gymSummaries = listOf(
        GymSummary("gym-centrum", "Studio Centrum", "Śródmieście", "Pon–Pt 06:00–22:00 · Sob–Nd 08:00–20:00", 1_200, Rating(4.8, 128)),
        GymSummary("gym-wola", "Forma Wola", "Wola", "Całą dobę", 2_400, Rating(4.6, 87)),
        GymSummary("gym-mokotow", "Ruch Mokotów", "Mokotów", "Pon–Pt 06:00–23:00 · Sob–Nd 08:00–21:00", 3_100, Rating(4.7, 64)),
        GymSummary("gym-praga", "Hala Praga", "Praga-Południe", "Pon–Nd 07:00–22:00", 4_800, Rating(4.4, 41)),
        GymSummary("gym-zoliborz", "Puls Żoliborz", "Żoliborz", "Pon–Pt 06:30–22:00 · Sob 09:00–18:00", 5_600, null),
        GymSummary("gym-ursynow", "Strefa Ursynów", "Ursynów", "Pon–Pt 06:00–22:00 · Sob–Nd 08:00–20:00", 9_300, Rating(4.5, 23)),
    )

    private fun gymSummary(id: String) = gymSummaries.first { it.id == id }
    private fun ref(id: String) = gymSummary(id).let { GymRef(it.id, it.name) }

    private fun session(id: String, zloty: Long, minutes: Int = 60, highlighted: Boolean = false) =
        Offer("$id-60", "Trening personalny", null, Money.pln(zloty), minutes, 1, null, highlighted, Seller.Trainer)

    private fun offers(id: String, single: Long): List<Offer> = listOf(
        session(id, single),
        Offer("$id-90", "Trening rozszerzony", "Dłuższa sesja z analizą techniki", Money.pln(single * 14 / 10), 90, 1, null, false, Seller.Trainer),
        Offer("$id-p8", "Pakiet 8 sesji", "Sesje po 60 min", Money.pln(single * 8 * 92 / 100 / 10 * 10), 60, 8, "Ważny 30 dni", true, Seller.Trainer),
        Offer("$id-p12", "Pakiet 12 sesji", "Sesje po 60 min", Money.pln(single * 12 * 85 / 100 / 10 * 10), 60, 12, "Ważny 45 dni", false, Seller.Trainer),
    )

    private fun policy(hours: Int, version: Int) = PublicCancellationPolicy(
        version = version,
        summary = "Bezpłatne odwołanie do $hours h przed sesją. Później trener może naliczyć opłatę.",
        tiers = listOf(
            PolicyTier("Ponad $hours h przed sesją: bez opłaty"),
            PolicyTier("Mniej niż $hours h przed sesją: 50% ceny sesji"),
            PolicyTier("Nieobecność bez odwołania: 100% ceny sesji"),
        ),
    )

    private fun media(id: String, first: String, second: String) = listOf(
        PublicMedia("$id-v1", first, "Technika", MediaState.READY, playbackUrl = null),
        PublicMedia("$id-v2", second, "Technika", MediaState.PROCESSING, playbackUrl = null),
    )

    private data class Seed(
        val id: String,
        val name: String,
        val headline: String,
        val gymIds: List<String>,
        val tags: List<String>,
        val goals: Set<Goal>,
        val availability: Set<Availability>,
        val price: Long?,
        val rating: Rating?,
        val verified: Boolean,
        val years: Int?,
        val clients: String?,
        val policyHours: Int,
        val bio: String,
    )

    private val seeds = listOf(
        Seed("tr-michal", "Michał Kowalczyk", "Trener personalny", listOf("gym-centrum", "gym-wola"), listOf("Siła", "Trójbój", "Hipertrofia"),
            setOf(Goal.Strength, Goal.Competition), setOf(Availability.Morning, Availability.Evening), 120, Rating(4.9, 42), true, 8, "140+", 24,
            "Specjalizuję się w treningu siłowym i przygotowaniu do zawodów trójbojowych. Pracuję na planach dopasowanych do Twojego dnia i regularnie sprawdzam technikę na nagraniach."),
        Seed("tr-anna", "Anna Wiśniewska", "Trenerka przygotowania motorycznego", listOf("gym-mokotow"), listOf("Mobilność", "Korekta postawy", "Kettlebell"),
            setOf(Goal.Mobility, Goal.Reduction), setOf(Availability.Afternoon, Availability.Weekend), 140, Rating(4.8, 31), true, 6, "90+", 12,
            "Pomagam wrócić do formy po przerwie i poprawić zakres ruchu. Na pierwszej sesji sprawdzam, jak się poruszasz, i ustalamy realny plan."),
        Seed("tr-piotr", "Piotr Zieliński", "Trener personalny", listOf("gym-wola"), listOf("Redukcja", "Kondycja"),
            setOf(Goal.Reduction), setOf(Availability.Morning, Availability.Afternoon), 100, Rating(4.6, 18), false, 3, null, 24,
            "Stawiam na regularność i proste nawyki. Treningi łączą siłę i kondycję."),
        Seed("tr-katarzyna", "Katarzyna Lewandowska-Majewska", "Trenerka personalna", listOf("gym-centrum"), listOf("Hipertrofia", "Siła", "Technika"),
            setOf(Goal.Strength, Goal.Reduction), setOf(Availability.Evening, Availability.Weekend), 160, Rating(5.0, 12), true, 10, "200+", 48,
            "Prowadzę treningi siłowe dla osób początkujących i zaawansowanych. Dużo uwagi poświęcam technice podstawowych ćwiczeń."),
        Seed("tr-tomasz", "Tomasz Wójcik", "Trener trójboju siłowego", listOf("gym-praga"), listOf("Trójbój", "Zawody"),
            setOf(Goal.Competition, Goal.Strength), setOf(Availability.Evening), 130, Rating(4.7, 27), true, 7, "60+", 24,
            "Przygotowuję zawodników do startów i osoby, które chcą bezpiecznie dźwigać więcej."),
        Seed("tr-magdalena", "Magdalena Kamińska", "Trenerka mobilności", listOf("gym-zoliborz"), listOf("Mobilność", "Stretching"),
            setOf(Goal.Mobility), setOf(Availability.Morning, Availability.Weekend), 110, Rating(4.9, 22), false, 5, "70+", 12,
            "Pracuję nad zakresem ruchu, oddechem i stabilizacją. Dobre uzupełnienie treningu siłowego."),
        Seed("tr-jakub", "Jakub Szymański", "Trener personalny", listOf("gym-ursynow", "gym-mokotow"), listOf("Redukcja", "Siła"),
            setOf(Goal.Reduction, Goal.Strength), setOf(Availability.Afternoon, Availability.Evening), 115, Rating(4.5, 15), false, 4, null, 24,
            "Łączę trening siłowy z pracą nad codzienną aktywnością."),
        Seed("tr-ewa", "Ewa Dąbrowska", "Trenerka personalna", listOf("gym-wola"), listOf("Redukcja", "Kettlebell", "Kondycja"),
            setOf(Goal.Reduction, Goal.Mobility), setOf(Availability.Morning), 95, Rating(4.6, 9), false, 2, null, 24,
            "Krótkie, intensywne treningi dla zapracowanych."),
        Seed("tr-lukasz", "Łukasz Kozłowski", "Trener przygotowania do zawodów", listOf("gym-centrum"), listOf("Zawody", "Hipertrofia"),
            setOf(Goal.Competition), setOf(Availability.Evening, Availability.Weekend), 180, Rating(4.8, 38), true, 12, "150+", 48,
            "Planuję okresy przygotowawcze i pomagam utrzymać formę w sezonie startowym."),
        Seed("tr-agnieszka", "Agnieszka Jankowska", "Trenerka personalna", listOf("gym-praga"), listOf("Siła", "Korekta postawy"),
            setOf(Goal.Strength, Goal.Mobility), setOf(Availability.Afternoon), 105, null, false, null, null, 24,
            "Zaczynam od podstaw: technika, oddech, stabilny tułów."),
        Seed("tr-marcin", "Marcin Mazur", "Trener personalny", listOf("gym-mokotow"), listOf("Redukcja", "Kondycja"),
            setOf(Goal.Reduction), setOf(Availability.Morning, Availability.Weekend), 125, Rating(4.4, 11), false, 5, null, 12,
            "Pomagam zbudować nawyk ruchu i trzymać się planu."),
        Seed("tr-zofia", "Zofia Krawczyk", "Trenerka personalna", listOf("gym-zoliborz", "gym-centrum"), listOf("Hipertrofia", "Mobilność"),
            setOf(Goal.Strength, Goal.Mobility), setOf(Availability.Evening), null, Rating(4.9, 7), true, 4, null, 24,
            "Cennik ustalam indywidualnie po pierwszej rozmowie."),
    )

    private fun initials(name: String) = name.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

    val trainers: List<TrainerProfile> = seeds.map { seed ->
        val offers = seed.price?.let { offers(seed.id, it) }.orEmpty()
        val summary = TrainerSummary(
            id = seed.id,
            name = seed.name,
            initials = initials(seed.name),
            headline = seed.headline,
            mainGym = ref(seed.gymIds.first()),
            tags = seed.tags,
            goals = seed.goals,
            districts = seed.gymIds.map { gymSummary(it).district }.toSet(),
            gymIds = seed.gymIds.toSet(),
            availability = seed.availability,
            startingPrice = offers.firstOrNull(),
            rating = seed.rating,
            verified = seed.verified,
        )
        TrainerProfile(
            summary = summary,
            bio = seed.bio,
            yearsOfExperience = seed.years,
            clientCountLabel = seed.clients,
            specialisations = seed.tags,
            gyms = seed.gymIds.mapIndexed { index, id -> TrainerGym(gymSummary(id), main = index == 0) },
            media = if (seed.verified) media(seed.id, "Technika przysiadu", "Biomechanika bioder") else emptyList(),
            offers = offers,
            cancellationPolicy = policy(seed.policyHours, version = 1),
        )
    }

    val featuredIds = listOf("tr-michal", "tr-anna", "tr-katarzyna")

    private val details = mapOf(
        "gym-centrum" to Triple("ul. Przykładowa 68, Warszawa", "Metro Centrum Testowe (150 m)",
            weekdays(LocalTime(6, 0), LocalTime(22, 0)) + weekend(LocalTime(8, 0), LocalTime(20, 0))),
        "gym-wola" to Triple("ul. Modelowa 12, Warszawa", "Tramwaj: przystanek Modelowa (50 m)", everyDay(LocalTime(0, 0), LocalTime(0, 0))),
        "gym-mokotow" to Triple("ul. Wzorcowa 5, Warszawa", null,
            weekdays(LocalTime(6, 0), LocalTime(23, 0)) + weekend(LocalTime(8, 0), LocalTime(21, 0))),
        "gym-praga" to Triple("ul. Próbna 30, Warszawa", "Autobus: przystanek Próbna (200 m)", everyDay(LocalTime(7, 0), LocalTime(22, 0))),
        "gym-zoliborz" to Triple("ul. Szkicowa 3, Warszawa", null,
            weekdays(LocalTime(6, 30), LocalTime(22, 0)) + OpeningPeriod(DayOfWeek.SATURDAY, LocalTime(9, 0), LocalTime(18, 0))),
        "gym-ursynow" to Triple("ul. Robocza 101, Warszawa", "Metro Ursynów Testowy (400 m)",
            weekdays(LocalTime(6, 0), LocalTime(22, 0)) + weekend(LocalTime(8, 0), LocalTime(20, 0))),
    )

    private val amenities = mapOf(
        "gym-centrum" to listOf(Amenity.FreeWeights, Amenity.Cardio, Amenity.Showers, Amenity.FunctionalZone),
        "gym-wola" to listOf(Amenity.FreeWeights, Amenity.Cardio, Amenity.Parking, Amenity.Showers),
        "gym-mokotow" to listOf(Amenity.FreeWeights, Amenity.Sauna, Amenity.Showers),
        "gym-praga" to listOf(Amenity.FreeWeights, Amenity.Parking),
        "gym-zoliborz" to listOf(Amenity.FunctionalZone, Amenity.Showers),
        "gym-ursynow" to listOf(Amenity.FreeWeights, Amenity.Cardio, Amenity.Parking, Amenity.Sauna, Amenity.Showers),
    )

    val gyms: List<GymDetail> = gymSummaries.map { summary ->
        val (address, transit, hours) = details.getValue(summary.id)
        GymDetail(
            summary = summary,
            address = address,
            transitNote = transit,
            weeklyHours = hours,
            timeZone = Zone,
            amenities = amenities.getValue(summary.id),
            trainers = trainers.map { it.summary }.filter { summary.id in it.gymIds },
        )
    }
}
