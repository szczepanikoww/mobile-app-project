package pl.trainerapp.feature.trainerfinance

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.feature.trainerfinance.data.TrainerFinanceFixtures as Fx
import pl.trainerapp.feature.trainerfinance.model.FinancePeriod

private val populated = TrainerFinanceUiState(
    status = FinanceLoadStatus.Content,
    summary = Fx.week,
    lastUpdated = Fx.now,
)

@Composable
private fun Frame(state: TrainerFinanceUiState) {
    AppTheme { TrainerFinanceScreen(state = state, onAction = {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 1200, name = "Tydzień")
@Composable
private fun WeekPreview() = Frame(populated)

@Preview(showBackground = true, widthDp = 360, heightDp = 1200, name = "Miesiąc")
@Composable
private fun MonthPreview() = Frame(populated.copy(period = FinancePeriod.Month, summary = Fx.month))

@Preview(showBackground = true, widthDp = 360, heightDp = 1200, name = "Rok")
@Composable
private fun YearPreview() = Frame(populated.copy(period = FinancePeriod.Year, summary = Fx.year))

@Preview(showBackground = true, widthDp = 360, heightDp = 1200, fontScale = 1.5f, name = "Czcionka 1.5x")
@Composable
private fun LargeFontPreview() = Frame(populated)

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Ładowanie")
@Composable
private fun LoadingPreview() = Frame(TrainerFinanceUiState())

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Błąd")
@Composable
private fun ErrorPreview() = Frame(TrainerFinanceUiState(status = FinanceLoadStatus.Error))

@Preview(showBackground = true, widthDp = 360, heightDp = 1200, name = "Offline")
@Composable
private fun OfflinePreview() = Frame(populated.copy(isOffline = true))

@Preview(showBackground = true, widthDp = 360, heightDp = 1200, name = "Brak płatności")
@Composable
private fun EmptyPreview() = Frame(populated.copy(summary = Fx.empty))
