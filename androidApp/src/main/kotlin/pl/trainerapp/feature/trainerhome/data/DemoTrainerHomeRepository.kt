package pl.trainerapp.feature.trainerhome.data

import kotlinx.coroutines.delay
import pl.trainerapp.feature.trainerhome.TrainerHomeRepository
import pl.trainerapp.feature.trainerhome.model.AttentionItem
import pl.trainerapp.feature.trainerhome.model.ClientRef
import pl.trainerapp.feature.trainerhome.model.HomeSnapshot
import pl.trainerapp.feature.trainerhome.model.SessionStatus
import pl.trainerapp.feature.trainerhome.model.SessionSummary
import pl.trainerapp.feature.trainerhome.model.TrainerHomeSummary
import pl.trainerapp.feature.trainerhome.model.WeekStats
import java.time.Instant
import java.time.ZoneId

/** Deterministic synthetic data. Used by previews, tests and the explicit demo data source. */
object TrainerHomeFixtures {
    val zone: ZoneId = ZoneId.of("Europe/Warsaw")

    /** 09:15 in Warsaw. */
    val now: Instant = Instant.parse("2026-10-28T08:15:00Z")

    private val kasia = ClientRef("c1", "Kasia Nowak")
    private val tomek = ClientRef("c2", "Tomek Wójcik")
    private val magda = ClientRef("c3", "Magda Brzeska")
    private val marek = ClientRef("c5", "Marek Lis")
    private val ania = ClientRef("c6", "Ania Kruk")
    private val piotr = ClientRef("c7", "Piotr Szymański")
    private val kamil = ClientRef("c8", "Kamil Nowicki")

    private fun at(time: String): Instant = Instant.parse("2026-10-28T${time}:00Z")

    val nextSession = SessionSummary(
        id = "s1",
        client = kasia,
        start = at("09:00"),
        end = at("10:00"),
        zone = zone,
        location = "Siłownia Centrum",
        planTitle = "Push A",
        status = SessionStatus.Confirmed,
        checkInOpen = true,
    )

    val todaySessions = listOf(
        SessionSummary("s2", tomek, at("11:30"), at("12:30"), zone, "Siłownia Centrum", "Trening nóg", SessionStatus.Confirmed, true),
        SessionSummary("s3", magda, at("13:00"), at("14:00"), zone, "Studio 2", "Full Body Workout", SessionStatus.Requested, false),
        SessionSummary("s4", kamil, at("15:30"), at("16:30"), zone, "Siłownia Centrum", "Plecy + Barki", SessionStatus.Confirmed, true),
    )

    val attention = listOf(
        AttentionItem.TechniqueVideo("m1", ania, "Martwy ciąg"),
        AttentionItem.UnreadMessages("u1", piotr, 2, "Pytanie o dietę"),
        AttentionItem.OverduePayment("o1", marek),
    )

    val summary = TrainerHomeSummary(
        generatedAt = now,
        nextSession = nextSession,
        todaySessions = todaySessions,
        attention = attention,
        trainerName = "Michał",
        unreadChats = 3,
        hasNotifications = true,
        week = WeekStats(sessionsDone = 12, sessionsPlanned = 15, newClients = 2, cancellations = 1),
    )

    val emptyDay = summary.copy(
        nextSession = null,
        todaySessions = emptyList(),
        upcomingAfterToday = nextSession.copy(
            start = Instant.parse("2026-10-29T09:00:00Z"),
            end = Instant.parse("2026-10-29T10:00:00Z"),
        ),
    )

    val newTrainer = TrainerHomeSummary(
        generatedAt = now,
        nextSession = null,
        todaySessions = emptyList(),
        attention = emptyList(),
        isNewTrainer = true,
        trainerName = "Michał",
    )
}

/** Demo data source: not connected to any backend. Wired in explicitly by the app entry point. */
class DemoTrainerHomeRepository(
    private val summary: TrainerHomeSummary = TrainerHomeFixtures.summary,
    private val latencyMillis: Long = 600,
) : TrainerHomeRepository {

    override suspend fun load(): HomeSnapshot {
        delay(latencyMillis)
        return HomeSnapshot(summary, isOffline = false, lastUpdated = summary.generatedAt)
    }
}
