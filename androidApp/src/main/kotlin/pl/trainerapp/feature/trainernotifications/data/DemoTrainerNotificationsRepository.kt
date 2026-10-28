package pl.trainerapp.feature.trainernotifications.data

import kotlinx.coroutines.delay
import pl.trainerapp.feature.trainernotifications.TrainerNotificationsRepository
import pl.trainerapp.feature.trainernotifications.model.NotificationDetail
import pl.trainerapp.feature.trainernotifications.model.NotificationTarget
import pl.trainerapp.feature.trainernotifications.model.NotificationType
import pl.trainerapp.feature.trainernotifications.model.NotificationsSnapshot
import pl.trainerapp.feature.trainernotifications.model.TrainerNotification
import java.time.Instant
import java.time.ZoneId

/** Deterministic synthetic data. Used by previews, tests and the explicit demo data source. */
object TrainerNotificationsFixtures {
    val zone: ZoneId = ZoneId.of("Europe/Warsaw")

    /** 09:15 in Warsaw. */
    val now: Instant = Instant.parse("2026-10-28T08:15:00Z")

    private fun n(
        id: String,
        type: NotificationType,
        title: String,
        body: String,
        detail: String,
        at: String,
        read: Boolean,
        target: NotificationTarget,
    ) = TrainerNotification(id, type, title, body, detail, Instant.parse(at), zone, read, target)

    val items = listOf(
        n(
            "n1", NotificationType.Payment, "Płatność od Katarzyny Nowak", "Pakiet 8 sesji · +960 zł",
            "Katarzyna Nowak opłaciła pakiet 8 sesji przez Blik. Kwota 960 zł trafi do najbliższej wypłaty ze Stripe.",
            "2026-10-28T06:30:00Z", false, NotificationTarget.Finance,
        ),
        n(
            "n2", NotificationType.Message, "Nowa wiadomość od Piotra Szymańskiego", "Dzień dobry, mam pytanie o dietę.",
            "Piotr Szymański napisał: „Dzień dobry, mam pytanie o dietę. Czy białko po treningu musi być w ciągu godziny?”",
            "2026-10-28T07:22:00Z", false, NotificationTarget.Chat("c7", "Piotr Szymański"),
        ),
        n(
            "n3", NotificationType.Video, "Wideo techniki od Ani Kruk", "Martwy ciąg · czeka na ocenę",
            "Ania Kruk wysłała nagranie z ćwiczenia „Martwy ciąg”. Obejrzyj je i odpowiedz w czacie z uwagami do techniki.",
            "2026-10-28T06:50:00Z", false, NotificationTarget.Chat("c6", "Ania Kruk"),
        ),
        n(
            "n4", NotificationType.Session, "Prośba o sesję od Magdy Brzeskiej", "Dziś, 13:00 – 14:00 · Studio 2",
            "Magda Brzeska prosi o potwierdzenie sesji Full Body Workout dziś w godzinach 13:00 – 14:00 w Studio 2.",
            "2026-10-27T12:00:00Z", true, NotificationTarget.Session("s3"),
        ),
        n(
            "n5", NotificationType.Payment, "Opłata za późne odwołanie", "Piotr Szymański · +60 zł",
            "Piotr Szymański odwołał sesję w oknie 12–24 h przed terminem. Zgodnie z polityką naliczono opłatę 50% (60 zł).",
            "2026-10-20T10:00:00Z", true, NotificationTarget.Finance,
        ),
        n(
            "n6", NotificationType.Payment, "Zaległa płatność", "Marek Lis · 120 zł",
            "Płatność Marka Lisa za sesję pojedynczą jest zaległa. Możesz przypomnieć o niej w czacie.",
            "2026-10-25T09:00:00Z", true, NotificationTarget.Chat("c5", "Marek Lis"),
        ),
        n(
            "n7", NotificationType.System, "Zaktualizowany regulamin", "Zmiany obowiązują od 1 listopada",
            "Zaktualizowaliśmy regulamin platformy. Zmiany dotyczą zasad wypłat i rozliczania późnych odwołań. Pełny tekst znajdziesz w ustawieniach konta.",
            "2026-10-22T08:00:00Z", true, NotificationTarget.None,
        ),
    )
}

/** Demo data source: not connected to any backend. Read state is kept in memory. */
class DemoTrainerNotificationsRepository(private val latencyMillis: Long = 400) : TrainerNotificationsRepository {

    private var items = TrainerNotificationsFixtures.items

    private fun snapshot() = NotificationsSnapshot(
        items = items.sortedByDescending { it.at },
        generatedAt = TrainerNotificationsFixtures.now,
        isOffline = false,
        lastUpdated = TrainerNotificationsFixtures.now,
    )

    override suspend fun notifications(): NotificationsSnapshot {
        delay(latencyMillis)
        return snapshot()
    }

    override suspend fun open(id: String): NotificationDetail {
        delay(latencyMillis)
        val found = items.firstOrNull { it.id == id } ?: error("Unknown notification $id")
        items = items.map { if (it.id == id) it.copy(read = true) else it }
        return NotificationDetail(found.copy(read = true), TrainerNotificationsFixtures.now)
    }

    override suspend fun markAllRead() {
        delay(latencyMillis)
        items = items.map { it.copy(read = true) }
    }
}
