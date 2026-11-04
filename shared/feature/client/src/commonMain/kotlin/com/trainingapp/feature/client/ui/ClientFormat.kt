package com.trainingapp.feature.client.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material.icons.outlined.Update
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.trainingapp.contract.money.Money
import com.trainingapp.designsystem.component.StatusChip
import com.trainingapp.designsystem.component.StatusTone
import com.trainingapp.feature.client.resources.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pl.trainer.domain.ClientSession
import pl.trainer.domain.ClientSessionState
import pl.trainer.domain.Failure
import pl.trainer.domain.PaymentKind
import pl.trainer.domain.SessionPayment
import pl.trainer.ui.DateFormats
import kotlin.time.Instant

/* Locale-aware formatting shared by the client views. Times are shown in the session's own time zone. */

internal fun language(): String = DateFormats.defaultLanguage()

internal fun money(amount: Money): String = pl.trainer.ui.formatMoney(amount)

internal fun Instant.dateIn(zone: String): LocalDate = toLocalDateTime(TimeZone.of(zone)).date

/** "Środa, 29 października". */
internal fun dayTitle(date: LocalDate): String = DateFormats.date(date, language(), "EEEE, d MMMM").replaceFirstChar { it.titlecase() }

/** "29 paź · 07:00". */
internal fun shortDateTime(instant: Instant, zone: String): String =
    DateFormats.date(instant.dateIn(zone), language(), "d MMM") + " · " + DateFormats.time(instant, zone, language())

/** "07:00 – 08:00". */
internal fun hours(start: Instant, end: Instant, zone: String): String = pl.trainer.ui.timeRange(start, end, zone)

internal val ClientSession.zone: String get() = session.timeZone

private data class StateStyle(val label: StringResource, val tone: StatusTone, val icon: ImageVector)

private fun ClientSessionState.style(): StateStyle = when (this) {
    ClientSessionState.AwaitingTrainer -> StateStyle(Res.string.state_awaiting_trainer, StatusTone.Warning, Icons.Outlined.HourglassEmpty)
    ClientSessionState.Confirmed -> StateStyle(Res.string.state_confirmed, StatusTone.Success, Icons.Outlined.EventAvailable)
    ClientSessionState.ProposalFromTrainer -> StateStyle(Res.string.state_proposal, StatusTone.Accent, Icons.Outlined.Update)
    ClientSessionState.RescheduleRequested -> StateStyle(Res.string.state_reschedule_requested, StatusTone.Warning, Icons.Outlined.Update)
    ClientSessionState.Rejected -> StateStyle(Res.string.state_rejected, StatusTone.Error, Icons.Outlined.EventBusy)
    ClientSessionState.Expired -> StateStyle(Res.string.state_expired, StatusTone.Neutral, Icons.Outlined.TimerOff)
    ClientSessionState.Cancelled -> StateStyle(Res.string.state_cancelled, StatusTone.Neutral, Icons.Outlined.EventBusy)
    ClientSessionState.Completed -> StateStyle(Res.string.state_completed, StatusTone.Neutral, Icons.Outlined.DoneAll)
    ClientSessionState.NoShow -> StateStyle(Res.string.state_no_show, StatusTone.Neutral, Icons.Outlined.PersonOff)
}

/** The session's status as text and an icon, never colour alone. */
@Composable
internal fun SessionStateChip(state: ClientSessionState, modifier: Modifier = Modifier) {
    val style = state.style()
    StatusChip(stringResource(style.label), modifier, tone = style.tone, icon = style.icon)
}

@Composable
internal fun paymentText(payment: SessionPayment?): String = when (payment?.kind) {
    PaymentKind.Package -> {
        val used = payment?.packageUsed
        val total = payment?.packageTotal
        if (used != null && total != null) stringResource(Res.string.payment_package_count, used, total) else stringResource(Res.string.payment_package)
    }
    PaymentKind.Cash -> stringResource(Res.string.payment_cash)
    PaymentKind.Online, null -> stringResource(Res.string.payment_online)
}

@Composable
internal fun failureMessage(failure: Failure): String = stringResource(
    when (failure) {
        Failure.Offline -> Res.string.error_unsent
        Failure.Conflict -> Res.string.error_conflict
        Failure.QuoteExpired -> Res.string.error_quote_expired
        Failure.Denied -> Res.string.error_denied
        Failure.InvalidInput, Failure.InvalidClientInfo -> Res.string.error_invalid
        Failure.Load -> Res.string.error_load
        Failure.Save -> Res.string.error_save
    },
)
