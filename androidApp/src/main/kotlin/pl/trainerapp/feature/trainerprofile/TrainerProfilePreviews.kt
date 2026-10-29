package pl.trainerapp.feature.trainerprofile

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.feature.trainerprofile.data.TrainerProfileFixtures as Fx
import pl.trainerapp.feature.trainerprofile.model.ProfileDraft

private val populated = TrainerProfileUiState(
    status = ProfileLoadStatus.Content,
    profile = Fx.profile,
    draft = ProfileDraft.of(Fx.profile),
    settings = Fx.settings,
    availability = Fx.availability,
    payout = Fx.payout,
    lastUpdated = Fx.now,
)

@Composable
private fun Frame(state: TrainerProfileUiState) {
    AppTheme { TrainerProfileScreen(state, onAction = {}, onBack = {}, onUnavailable = {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 1100, name = "Profil")
@Composable
private fun ProfilePreview() = Frame(populated)

@Preview(showBackground = true, widthDp = 360, heightDp = 1100, name = "Profil: zmiany")
@Composable
private fun ProfileDirtyPreview() = Frame(
    populated.copy(draft = ProfileDraft.of(Fx.profile).copy(location = "Warszawa")),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 1100, fontScale = 1.5f, name = "Profil 1.5x")
@Composable
private fun ProfileLargeFontPreview() = Frame(populated)

@Preview(showBackground = true, widthDp = 360, heightDp = 1400, name = "Ustawienia")
@Composable
private fun SettingsPreview() = Frame(populated.copy(tab = ProfileTab.Settings))

@Preview(showBackground = true, widthDp = 360, heightDp = 1000, name = "Dostępność")
@Composable
private fun AvailabilityPreview() = Frame(populated.copy(tab = ProfileTab.Availability))

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Płatności")
@Composable
private fun PaymentsPreview() = Frame(populated.copy(tab = ProfileTab.Payments))

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Ładowanie")
@Composable
private fun LoadingPreview() = Frame(TrainerProfileUiState())

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Błąd")
@Composable
private fun ErrorPreview() = Frame(TrainerProfileUiState(status = ProfileLoadStatus.Error))
