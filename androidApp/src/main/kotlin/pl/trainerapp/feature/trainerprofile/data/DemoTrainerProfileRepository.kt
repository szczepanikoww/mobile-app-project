package pl.trainerapp.feature.trainerprofile.data

import kotlinx.coroutines.delay
import pl.trainerapp.feature.trainerfinance.model.Pln
import pl.trainerapp.feature.trainerprofile.TrainerProfileRepository
import pl.trainerapp.feature.trainerprofile.model.AvailabilityDay
import pl.trainerapp.feature.trainerprofile.model.PayoutAccount
import pl.trainerapp.feature.trainerprofile.model.ProfileSnapshot
import pl.trainerapp.feature.trainerprofile.model.TrainerProfile
import pl.trainerapp.feature.trainerprofile.model.TrainerSettings
import java.time.Instant

/** Deterministic synthetic data. Used by previews, tests and the explicit demo data source. */
object TrainerProfileFixtures {
    val now: Instant = Instant.parse("2026-10-28T08:15:00Z")

    val profile = TrainerProfile(
        name = "Michał Szczepański",
        email = "michal@trainerapp.pl",
        phone = "+48 600 123 456",
        location = "Kraków",
        bio = "Trener personalny z 6-letnim doświadczeniem. Pracuję z osobami początkującymi i zaawansowanymi: siła, masa, redukcja i technika.",
        specializations = listOf("Trening siłowy", "Redukcja", "Mobilność"),
        hourlyRate = Pln.zl(120),
    )

    val settings = TrainerSettings(
        notifyMessages = true,
        notifySessions = true,
        notifyPayments = true,
        emailSummary = false,
        autoConfirm = false,
    )

    val availability = listOf(
        AvailabilityDay("Poniedziałek", true, "08:00 – 18:00"),
        AvailabilityDay("Wtorek", true, "08:00 – 18:00"),
        AvailabilityDay("Środa", true, "09:00 – 17:00"),
        AvailabilityDay("Czwartek", true, "08:00 – 18:00"),
        AvailabilityDay("Piątek", true, "08:00 – 14:00"),
        AvailabilityDay("Sobota", false, "10:00 – 13:00"),
        AvailabilityDay("Niedziela", false, "Nieczynne"),
    )

    val payout = PayoutAccount(
        connected = true,
        accountLabel = "Stripe •••• 4821",
        balance = Pln.zl(530),
        lastPayout = Pln.zl(2950),
    )

    val snapshot = ProfileSnapshot(profile, settings, availability, payout, isOffline = false, lastUpdated = now)
}

/** Demo data source: not connected to any backend. Edits are kept in memory. */
class DemoTrainerProfileRepository(private val latencyMillis: Long = 500) : TrainerProfileRepository {

    private var snapshot = TrainerProfileFixtures.snapshot

    override suspend fun load(): ProfileSnapshot {
        delay(latencyMillis)
        return snapshot
    }

    override suspend fun saveProfile(profile: TrainerProfile) {
        delay(latencyMillis)
        snapshot = snapshot.copy(profile = profile)
    }

    override suspend fun saveSettings(settings: TrainerSettings) {
        snapshot = snapshot.copy(settings = settings)
    }

    override suspend fun saveAvailability(days: List<AvailabilityDay>) {
        snapshot = snapshot.copy(availability = days)
    }
}
