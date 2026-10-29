package pl.trainerapp.feature.trainerprofile

import pl.trainerapp.feature.trainerprofile.model.AvailabilityDay
import pl.trainerapp.feature.trainerprofile.model.PayoutAccount
import pl.trainerapp.feature.trainerprofile.model.ProfileDraft
import pl.trainerapp.feature.trainerprofile.model.ProfileSnapshot
import pl.trainerapp.feature.trainerprofile.model.SettingKey
import pl.trainerapp.feature.trainerprofile.model.TrainerProfile
import pl.trainerapp.feature.trainerprofile.model.TrainerSettings
import java.time.Instant

enum class ProfileLoadStatus { Loading, Content, Error }

enum class ProfileTab { Profile, Settings, Availability, Payments }

data class TrainerProfileUiState(
    val status: ProfileLoadStatus = ProfileLoadStatus.Loading,
    val tab: ProfileTab = ProfileTab.Profile,
    val profile: TrainerProfile? = null,
    val draft: ProfileDraft? = null,
    val settings: TrainerSettings? = null,
    val availability: List<AvailabilityDay> = emptyList(),
    val payout: PayoutAccount? = null,
    val isOffline: Boolean = false,
    val lastUpdated: Instant? = null,
    val isRefreshing: Boolean = false,
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
    val justSaved: Boolean = false,
) {
    /** True when the form differs from the stored profile. */
    val isDirty: Boolean get() = profile != null && draft != null && draft != ProfileDraft.of(profile)
    val canSave: Boolean get() = isDirty && draft?.isValid == true && !isSaving
}

enum class ProfileField { Name, Phone, Location, Bio, Rate }

sealed interface TrainerProfileAction {
    data object Refresh : TrainerProfileAction
    data class SelectTab(val tab: ProfileTab) : TrainerProfileAction
    data class EditField(val field: ProfileField, val value: String) : TrainerProfileAction
    data object SaveProfile : TrainerProfileAction
    data class SetSetting(val key: SettingKey, val enabled: Boolean) : TrainerProfileAction
    data class SetDayEnabled(val index: Int, val enabled: Boolean) : TrainerProfileAction
}

interface TrainerProfileRepository {
    /** Returns fresh or cached data (flagged offline). Throws if there is neither. */
    suspend fun load(): ProfileSnapshot

    /** Throws when the change could not be stored. */
    suspend fun saveProfile(profile: TrainerProfile)
    suspend fun saveSettings(settings: TrainerSettings)
    suspend fun saveAvailability(days: List<AvailabilityDay>)
}
