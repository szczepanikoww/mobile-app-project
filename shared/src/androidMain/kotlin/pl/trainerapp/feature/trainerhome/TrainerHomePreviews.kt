package pl.trainerapp.feature.trainerhome

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.feature.trainerhome.data.TrainerHomeFixtures as Fx

private val populated = TrainerHomeUiState(
    status = LoadStatus.Content,
    summary = Fx.summary,
    lastUpdated = Fx.now,
)

@Composable
private fun Frame(state: TrainerHomeUiState) {
    AppTheme { TrainerHomeScreen(state = state, onAction = {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Z danymi")
@Composable
private fun PopulatedPreview() = Frame(populated)

@Preview(showBackground = true, widthDp = 360, heightDp = 780, fontScale = 1.5f, name = "Czcionka 1.5x")
@Composable
private fun LargeFontPreview() = Frame(populated)

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Ładowanie")
@Composable
private fun LoadingPreview() = Frame(TrainerHomeUiState())

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Błąd")
@Composable
private fun ErrorPreview() = Frame(TrainerHomeUiState(status = LoadStatus.Error))

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Offline")
@Composable
private fun OfflinePreview() = Frame(populated.copy(isOffline = true))

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Brak sesji")
@Composable
private fun EmptyDayPreview() = Frame(populated.copy(summary = Fx.emptyDay))

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Nowy trener")
@Composable
private fun NewTrainerPreview() = Frame(populated.copy(summary = Fx.newTrainer))
