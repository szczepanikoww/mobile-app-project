package pl.trainerapp.feature.trainerprofile.model

import pl.trainerapp.feature.trainerfinance.model.Pln
import kotlin.time.Instant

// Local stand-ins for the shared contract (:contract) until it exists. Keep them free of Android types.

data class TrainerProfile(
    val name: String,
    val email: String,
    val phone: String,
    val location: String,
    val bio: String,
    val specializations: List<String>,
    val hourlyRate: Pln,
)

/** Editable copy of the profile form. Kept as text so partially typed input is never rejected. */
data class ProfileDraft(
    val name: String,
    val phone: String,
    val location: String,
    val bio: String,
    val rateZl: String,
) {
    val rate: Long? get() = rateZl.trim().toLongOrNull()?.takeIf { it > 0 }

    /** Name and rate are required; the rest may be empty. */
    val isValid: Boolean get() = name.isNotBlank() && rate != null

    companion object {
        fun of(profile: TrainerProfile) = ProfileDraft(
            name = profile.name,
            phone = profile.phone,
            location = profile.location,
            bio = profile.bio,
            rateZl = (profile.hourlyRate.grosze / 100).toString(),
        )
    }
}

data class TrainerSettings(
    val notifyMessages: Boolean,
    val notifySessions: Boolean,
    val notifyPayments: Boolean,
    val emailSummary: Boolean,
    val autoConfirm: Boolean,
)

enum class SettingKey { NotifyMessages, NotifySessions, NotifyPayments, EmailSummary, AutoConfirm }

data class AvailabilityDay(
    /** Localized full day name, e.g. "Poniedziałek". */
    val dayName: String,
    val enabled: Boolean,
    /** E.g. "08:00 – 16:00". */
    val hours: String,
)

data class PayoutAccount(
    val connected: Boolean,
    /** E.g. "Stripe •••• 4821". */
    val accountLabel: String,
    val balance: Pln,
    val lastPayout: Pln,
)

/** A gym the trainer can pick for "Moje siłownie". */
data class GymOption(val id: String, val name: String, val address: String = "")

/** Every public gym and the ones the trainer works at. */
data class GymSelection(val all: List<GymOption>, val selected: Set<String>)

data class ProfileSnapshot(
    val profile: TrainerProfile,
    val settings: TrainerSettings,
    val availability: List<AvailabilityDay>,
    val payout: PayoutAccount,
    val isOffline: Boolean,
    val lastUpdated: Instant,
)
