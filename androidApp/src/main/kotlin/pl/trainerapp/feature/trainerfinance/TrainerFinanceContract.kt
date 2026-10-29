package pl.trainerapp.feature.trainerfinance

import pl.trainerapp.feature.trainerfinance.model.FinancePeriod
import pl.trainerapp.feature.trainerfinance.model.FinanceSnapshot
import pl.trainerapp.feature.trainerfinance.model.FinanceSummary
import pl.trainerapp.feature.trainerfinance.model.PaymentItem
import java.time.Instant

enum class FinanceLoadStatus { Loading, Content, Error }

data class TrainerFinanceUiState(
    val status: FinanceLoadStatus = FinanceLoadStatus.Loading,
    val period: FinancePeriod = FinancePeriod.Week,
    val summary: FinanceSummary? = null,
    val isOffline: Boolean = false,
    val isRefreshing: Boolean = false,
    val lastUpdated: Instant? = null,
)

sealed interface TrainerFinanceAction {
    data object Refresh : TrainerFinanceAction
    data class SelectPeriod(val period: FinancePeriod) : TrainerFinanceAction
    data class OpenPayment(val payment: PaymentItem) : TrainerFinanceAction
    data object OpenAllPayments : TrainerFinanceAction
    data object RequestPayout : TrainerFinanceAction
}

/** Destinations owned by other features. Until they exist the shell shows a "not available yet" message. */
sealed interface TrainerFinanceDestination {
    data class PaymentDetails(val paymentId: String) : TrainerFinanceDestination
    data object AllPayments : TrainerFinanceDestination
    data object Payout : TrainerFinanceDestination
}

sealed interface TrainerFinanceEvent {
    data class Navigate(val destination: TrainerFinanceDestination) : TrainerFinanceEvent
}

interface TrainerFinanceRepository {
    /** Returns fresh or cached data (flagged offline). Throws if there is neither. */
    suspend fun load(period: FinancePeriod): FinanceSnapshot
}
