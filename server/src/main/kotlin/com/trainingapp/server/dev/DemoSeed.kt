package com.trainingapp.server.dev

import com.trainingapp.contract.exercise.Equipment
import com.trainingapp.contract.exercise.MuscleGroup
import com.trainingapp.contract.exercise.SetType
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.plan.PlanBodyDto
import com.trainingapp.contract.plan.PlanDayDto
import com.trainingapp.contract.plan.PlannedExerciseDto
import com.trainingapp.contract.plan.PlannedSetDto
import com.trainingapp.contract.policy.PolicyPreset
import com.trainingapp.contract.policy.PolicyPresets
import com.trainingapp.contract.schedule.BookingRequestDto
import com.trainingapp.server.Services
import com.trainingapp.server.catalogue.Gyms
import com.trainingapp.server.catalogue.Offers
import com.trainingapp.server.catalogue.OpeningHours
import com.trainingapp.server.catalogue.PolicyTiers
import com.trainingapp.server.catalogue.PolicyVersions
import com.trainingapp.server.catalogue.TrainerGyms
import com.trainingapp.server.catalogue.TrainerListings
import com.trainingapp.server.common.Ids
import com.trainingapp.server.common.dbNow
import com.trainingapp.server.http.TokenIdentity
import com.trainingapp.server.identity.AppUsers
import com.trainingapp.server.identity.ClientProfiles
import com.trainingapp.server.identity.TrainerProfiles
import com.trainingapp.server.scheduling.AvailabilityRules
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

/**
 * Synthetic demo data for the local profile, matching the app's demo catalogue (DemoCatalog) and demo accounts
 * (DemoAccounts): every person, gym and address is invented. Base rows are inserted directly; every transition
 * (relationship, booking, confirmation, plan assignment, package) runs through the service code.
 *
 * Ids are derived from the demo ids, so they are stable across resets. Dev tokens: `dev.demo-client`,
 * `dev.demo-trainer` (trainer + client), `dev.demo-trainer-only`, `dev.tr-anna` and the other `tr-…` trainers.
 */
class DemoSeed(private val s: Services) {
    private val log = LoggerFactory.getLogger("trainer.seed")
    private val zone = "Europe/Warsaw"

    companion object {
        fun id(demoId: String): Uuid = Ids.derived(Uuid.NIL, "demo:$demoId")
        const val CLIENT_SUBJECT = "demo-client"
        const val TRAINER_SUBJECT = "demo-trainer"
    }

    private data class GymSeed(val id: String, val name: String, val district: String, val street: String, val hours: List<OpeningHours>)

    private data class TrainerSeed(
        val id: String, val name: String, val headline: String, val gyms: List<String>, val tags: List<String>,
        val goals: List<String>, val availability: List<String>, val price: Long?, val policyHours: Int, val bio: String,
    )

    private fun weekdays(open: String, close: String) = (1..5).map { OpeningHours(it, open, close) }
    private fun weekend(open: String, close: String) = (6..7).map { OpeningHours(it, open, close) }
    private fun everyDay(open: String, close: String) = (1..7).map { OpeningHours(it, open, close) }

    private val gyms = listOf(
        GymSeed("gym-centrum", "Studio Centrum", "Śródmieście", "ul. Przykładowa 68", weekdays("06:00", "22:00") + weekend("08:00", "20:00")),
        GymSeed("gym-wola", "Forma Wola", "Wola", "ul. Modelowa 12", everyDay("00:00", "00:00")),
        GymSeed("gym-mokotow", "Ruch Mokotów", "Mokotów", "ul. Wzorcowa 5", weekdays("06:00", "23:00") + weekend("08:00", "21:00")),
        GymSeed("gym-praga", "Hala Praga", "Praga-Południe", "ul. Próbna 30", everyDay("07:00", "22:00")),
        GymSeed("gym-zoliborz", "Puls Żoliborz", "Żoliborz", "ul. Szkicowa 3", weekdays("06:30", "22:00") + OpeningHours(6, "09:00", "18:00")),
        GymSeed("gym-ursynow", "Strefa Ursynów", "Ursynów", "ul. Robocza 101", weekdays("06:00", "22:00") + weekend("08:00", "20:00")),
    )

    private val trainers = listOf(
        TrainerSeed("tr-michal", "Michał Kowalczyk", "Trener personalny", listOf("gym-centrum", "gym-wola"), listOf("Siła", "Trójbój", "Hipertrofia"),
            listOf("STRENGTH", "COMPETITION"), listOf("DEMO"), 120, 24,
            "Specjalizuję się w treningu siłowym i przygotowaniu do zawodów trójbojowych. Pracuję na planach dopasowanych do Twojego dnia i regularnie sprawdzam technikę na nagraniach."),
        TrainerSeed("tr-anna", "Anna Wiśniewska", "Trenerka przygotowania motorycznego", listOf("gym-mokotow"), listOf("Mobilność", "Korekta postawy", "Kettlebell"),
            listOf("MOBILITY", "REDUCTION"), listOf("AFTERNOON", "WEEKEND"), 140, 12,
            "Pomagam wrócić do formy po przerwie i poprawić zakres ruchu. Na pierwszej sesji sprawdzam, jak się poruszasz, i ustalamy realny plan."),
        TrainerSeed("tr-piotr", "Piotr Zieliński", "Trener personalny", listOf("gym-wola"), listOf("Redukcja", "Kondycja"),
            listOf("REDUCTION"), listOf("MORNING", "AFTERNOON"), 100, 24, "Stawiam na regularność i proste nawyki. Treningi łączą siłę i kondycję."),
        TrainerSeed("tr-katarzyna", "Katarzyna Lewandowska-Majewska", "Trenerka personalna", listOf("gym-centrum"), listOf("Hipertrofia", "Siła", "Technika"),
            listOf("STRENGTH", "REDUCTION"), listOf("EVENING", "WEEKEND"), 160, 48,
            "Prowadzę treningi siłowe dla osób początkujących i zaawansowanych. Dużo uwagi poświęcam technice podstawowych ćwiczeń."),
        TrainerSeed("tr-tomasz", "Tomasz Wójcik", "Trener trójboju siłowego", listOf("gym-praga"), listOf("Trójbój", "Zawody"),
            listOf("COMPETITION", "STRENGTH"), listOf("EVENING"), 130, 24, "Przygotowuję zawodników do startów i osoby, które chcą bezpiecznie dźwigać więcej."),
        TrainerSeed("tr-magdalena", "Magdalena Kamińska", "Trenerka mobilności", listOf("gym-zoliborz"), listOf("Mobilność", "Stretching"),
            listOf("MOBILITY"), listOf("MORNING", "WEEKEND"), 110, 12, "Pracuję nad zakresem ruchu, oddechem i stabilizacją. Dobre uzupełnienie treningu siłowego."),
        TrainerSeed("tr-jakub", "Jakub Szymański", "Trener personalny", listOf("gym-ursynow", "gym-mokotow"), listOf("Redukcja", "Siła"),
            listOf("REDUCTION", "STRENGTH"), listOf("AFTERNOON", "EVENING"), 115, 24, "Łączę trening siłowy z pracą nad codzienną aktywnością."),
        TrainerSeed("tr-ewa", "Ewa Dąbrowska", "Trenerka personalna", listOf("gym-wola"), listOf("Redukcja", "Kettlebell", "Kondycja"),
            listOf("REDUCTION", "MOBILITY"), listOf("MORNING"), 95, 24, "Krótkie, intensywne treningi dla zapracowanych."),
        TrainerSeed("tr-lukasz", "Łukasz Kozłowski", "Trener przygotowania do zawodów", listOf("gym-centrum"), listOf("Zawody", "Hipertrofia"),
            listOf("COMPETITION"), listOf("EVENING", "WEEKEND"), 180, 48, "Planuję okresy przygotowawcze i pomagam utrzymać formę w sezonie startowym."),
        TrainerSeed("tr-agnieszka", "Agnieszka Jankowska", "Trenerka personalna", listOf("gym-praga"), listOf("Siła", "Korekta postawy"),
            listOf("STRENGTH", "MOBILITY"), listOf("AFTERNOON"), 105, 24, "Zaczynam od podstaw: technika, oddech, stabilny tułów."),
        TrainerSeed("tr-marcin", "Marcin Mazur", "Trener personalny", listOf("gym-mokotow"), listOf("Redukcja", "Kondycja"),
            listOf("REDUCTION"), listOf("MORNING", "WEEKEND"), 125, 12, "Pomagam zbudować nawyk ruchu i trzymać się planu."),
        TrainerSeed("tr-zofia", "Zofia Krawczyk", "Trenerka personalna", listOf("gym-zoliborz", "gym-centrum"), listOf("Hipertrofia", "Mobilność"),
            listOf("STRENGTH", "MOBILITY"), listOf("EVENING"), null, 24, "Cennik ustalam indywidualnie po pierwszej rozmowie."),
    )

    /** Seeds once: does nothing when any user exists. */
    suspend fun seedIfEmpty() {
        val empty = s.db.tx { AppUsers.selectAll().limit(1).none() }
        if (!empty) return
        seed()
        log.info("demo data seeded")
    }

    suspend fun seed() {
        s.db.tx {
            gyms.forEach { insertGym(it) }
            trainers.forEach { insertTrainer(it) }
            insertUser(CLIENT_SUBJECT, "Katarzyna Nowak", trainer = false, client = true)
            // The demo trainer also holds the client role (DemoAccounts.bothRoles).
            ClientProfiles.insert { it[userId] = id("tr-michal") }
            insertUser("demo-trainer-only", "Trener demo", trainer = true, client = false)
            with(s.training) {
                insertGlobalExercise(id("ex-squat"), "Barbell_Squat", "Przysiad ze sztangą", "Barbell squat", TrackingType.WEIGHT_REPS, Equipment.BARBELL, MuscleGroup.QUADRICEPS)
                insertGlobalExercise(id("ex-bench"), "Barbell_Bench_Press", "Wyciskanie sztangi leżąc", "Barbell bench press", TrackingType.WEIGHT_REPS, Equipment.BARBELL, MuscleGroup.CHEST)
                insertGlobalExercise(id("ex-row"), "Bent_Over_Barbell_Row", "Wiosłowanie sztangą", "Bent-over barbell row", TrackingType.WEIGHT_REPS, Equipment.BARBELL, MuscleGroup.MIDDLE_BACK)
                insertGlobalExercise(id("ex-plank"), "Plank", "Deska", "Plank", TrackingType.TIME, Equipment.BODY_ONLY, MuscleGroup.ABDOMINALS)
            }
        }
        // Transitions through the services: a booking request, its confirmation (activates the relationship and
        // opens the conversation), a package and an assigned plan.
        val client = s.identity.resolve(TokenIdentity(CLIENT_SUBJECT, null))!!
        val trainerId = id("tr-michal")
        val options = s.scheduling.bookingOptions(client, trainerId)
        val slot = nextSlots(client, trainerId).first()
        val session = s.scheduling.requestBooking(
            client,
            BookingRequestDto(trainerId.toString(), slot.start, slot.end, options.places.first(), options.policy.versionId),
            id("seed-booking-1"),
        )
        s.scheduling.confirm(trainerId, Uuid.parse(session.id))
        val relationshipId = s.db.tx { with(s.identity) { openRelationship(trainerId, client.id) }!!.id }
        s.db.tx { with(s.billing) { grantPackage(relationshipId, id("tr-michal-p8"), id("seed-package"), dbNow()) } }
        val versionId = s.db.tx { with(s.training) { publishPlan(trainerId, id("plan-fbw"), demoPlan(), id("seed-plan-v1")) } }
        s.training.assignPlan(trainerId, relationshipId, versionId, null, id("seed-assign"))
        // A second, still unanswered request, so "Czeka na trenera" has data.
        nextSlots(client, trainerId).getOrNull(3)?.let { second ->
            s.scheduling.requestBooking(client, BookingRequestDto(trainerId.toString(), second.start, second.end, options.places.first(), options.policy.versionId), id("seed-booking-2"))
        }
    }

    private suspend fun nextSlots(client: com.trainingapp.server.identity.CurrentUser, trainerId: Uuid): List<com.trainingapp.contract.schedule.SlotDto> {
        val slots = mutableListOf<com.trainingapp.contract.schedule.SlotDto>()
        for (day in 1L..14L) {
            slots += s.scheduling.availableSlots(client, trainerId, LocalDate.now(ZoneId.of(zone)).plusDays(day), null)
            if (slots.size >= 6) break
        }
        return slots
    }

    private fun demoPlan() = PlanBodyDto(
        name = "FBW — 2 dni",
        tags = listOf("Siła"),
        days = listOf(
            PlanDayDto("day-a", "Dzień A", listOf(
                PlannedExerciseDto("pe-a1", id("ex-squat").toString(), restSeconds = 150, sets = listOf(
                    PlannedSetDto("ps-a1-w", SetType.WARM_UP, 8, 8, 40_000),
                    PlannedSetDto("ps-a1-1", SetType.WORKING, 5, 5, 80_000),
                    PlannedSetDto("ps-a1-2", SetType.WORKING, 5, 5, 80_000),
                )),
                PlannedExerciseDto("pe-a2", id("ex-bench").toString(), restSeconds = 120, sets = listOf(
                    PlannedSetDto("ps-a2-1", SetType.WORKING, 6, 8, 60_000),
                    PlannedSetDto("ps-a2-2", SetType.WORKING, 6, 8, 60_000),
                )),
            )),
            PlanDayDto("day-b", "Dzień B", listOf(
                PlannedExerciseDto("pe-b1", id("ex-row").toString(), restSeconds = 120, sets = listOf(
                    PlannedSetDto("ps-b1-1", SetType.WORKING, 8, 10, 50_000),
                    PlannedSetDto("ps-b1-2", SetType.WORKING, 8, 10, 50_000),
                )),
                PlannedExerciseDto("pe-b2", id("ex-plank").toString(), accessory = true, sets = listOf(
                    PlannedSetDto("ps-b2-1", SetType.WORKING, durationSeconds = 45),
                )),
            )),
        ),
    )

    private fun JdbcTransaction.insertUser(subject: String, name: String, trainer: Boolean, client: Boolean, userId: Uuid = id(subject)): Uuid {
        AppUsers.insert {
            it[id] = userId
            it[authSubject] = subject
            it[email] = null
            it[displayName] = name
            it[locale] = "pl"
            it[timeZone] = zone
            it[isOperator] = false
        }
        if (trainer) TrainerProfiles.insert { it[TrainerProfiles.userId] = userId; it[defaultCurrency] = "PLN"; it[sessionBufferMinutes] = 15 }
        if (client) ClientProfiles.insert { it[ClientProfiles.userId] = userId }
        return userId
    }

    private fun JdbcTransaction.insertGym(g: GymSeed) {
        Gyms.insert {
            it[id] = id(g.id)
            it[name] = g.name
            it[street] = g.street
            it[city] = "Warszawa"
            it[timeZone] = zone
            it[openingHours] = g.hours
            it[isPublic] = true
            it[district] = g.district
            it[searchText] = ""
        }
        with(s.catalogue) { refreshGymSearchText(id(g.id)) }
    }

    private fun JdbcTransaction.insertTrainer(t: TrainerSeed) {
        val trainerId = id(t.id)
        // tr-michal is the demo trainer account (auth subject `demo-trainer`).
        insertUser(if (t.id == "tr-michal") TRAINER_SUBJECT else t.id, t.name, trainer = true, client = false, userId = trainerId)
        TrainerListings.insert {
            it[TrainerListings.trainerId] = trainerId
            it[headline] = t.headline
            it[bio] = t.bio
            it[specialties] = t.tags
            it[isPublic] = true
            it[displayName] = t.name
            it[searchText] = ""
            it[goals] = t.goals
        }
        t.gyms.forEachIndexed { index, gym ->
            TrainerGyms.insert { it[TrainerGyms.trainerId] = trainerId; it[gymId] = id(gym); it[isMain] = index == 0 }
        }
        t.price?.let { single ->
            fun offer(suffix: String, kind: String, name: String, minutes: Int, zloty: Long, sessions: Int?) = Offers.insert {
                it[id] = id("${t.id}-$suffix")
                it[Offers.trainerId] = trainerId
                it[Offers.kind] = kind
                it[Offers.name] = name
                it[durationMinutes] = minutes
                it[priceMinor] = zloty * 100
                it[currency] = "PLN"
                it[sessionsPerPeriod] = sessions
                it[seller] = "TRAINER"
                it[purpose] = "PHYSICAL"
                it[isPublic] = true
            }
            offer("60", "SINGLE_SESSION", "Trening personalny", 60, single, null)
            offer("90", "SINGLE_SESSION", "Trening rozszerzony", 90, single * 14 / 10, null)
            offer("p8", "PACKAGE", "Pakiet 8 sesji", 60, single * 8 * 92 / 100 / 10 * 10, 8)
            offer("p12", "PACKAGE", "Pakiet 12 sesji", 60, single * 12 * 85 / 100 / 10 * 10, 12)
        }
        val preset = when (t.policyHours) { 12 -> PolicyPreset.Flexible; 48 -> PolicyPreset.Strict; else -> PolicyPreset.Standard }
        val rules = PolicyPresets.rules(preset)
        val policyId = id("${t.id}-policy-1")
        PolicyVersions.insert {
            it[id] = policyId
            it[PolicyVersions.trainerId] = trainerId
            it[version] = 1
            it[noShowPercent] = rules.noShowPercent
            it[graceMinutes] = rules.graceMinutes
            it[freeLateCancellations] = rules.freeLateCancellationsPerMonth
            it[lateRescheduleIsCancel] = true
            it[cashFeeMode] = "ADD_TO_NEXT_SESSION"
            it[packageFeeMode] = "USE_CREDIT"
        }
        rules.tiers.forEach { tier -> PolicyTiers.insert { it[policyVersionId] = policyId; it[hoursBefore] = tier.hoursBefore; it[feePercent] = tier.feePercent } }
        val windows = buildList {
            for (bucket in t.availability) when (bucket) {
                // The demo trainer works Mon–Fri 7:00–20:00 (D9).
                "DEMO" -> (1..5).forEach { add(Triple(it, LocalTime.of(7, 0), LocalTime.of(20, 0))) }
                "MORNING" -> (1..5).forEach { add(Triple(it, LocalTime.of(7, 0), LocalTime.of(11, 0))) }
                "AFTERNOON" -> (1..5).forEach { add(Triple(it, LocalTime.of(12, 0), LocalTime.of(16, 0))) }
                "EVENING" -> (1..5).forEach { add(Triple(it, LocalTime.of(17, 0), LocalTime.of(21, 0))) }
                "WEEKEND" -> add(Triple(6, LocalTime.of(9, 0), LocalTime.of(13, 0)))
            }
        }
        val today = dbNow().toJavaInstant().atZone(ZoneId.of(zone)).toLocalDate().minusDays(7)
        windows.forEach { (day, from, to) ->
            AvailabilityRules.insert {
                it[id] = Ids.v7()
                it[AvailabilityRules.trainerId] = trainerId
                it[weekday] = day.toShort()
                it[startTime] = from
                it[endTime] = to
                it[timeZone] = zone
                it[gymId] = id(t.gyms.first())
                it[validFrom] = today
            }
        }
        with(s.catalogue) { refreshTrainerSearchText(trainerId) }
    }
}
