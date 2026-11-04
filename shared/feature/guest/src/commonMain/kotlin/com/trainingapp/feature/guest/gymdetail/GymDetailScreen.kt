package com.trainingapp.feature.guest.gymdetail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DoNotDisturbOn
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.LocalParking
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Shower
import androidx.compose.material.icons.outlined.SportsGymnastics
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material.icons.outlined.WrongLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.component.BottomActionBar
import com.trainingapp.designsystem.component.DetailHeader
import com.trainingapp.designsystem.component.EmptyState
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.MetaLine
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.RowDivider
import com.trainingapp.designsystem.component.SectionHeader
import com.trainingapp.designsystem.component.StatusChip
import com.trainingapp.designsystem.component.StatusTone
import com.trainingapp.designsystem.component.TonalButton
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.feature.guest.component.LoginRequiredSheet
import com.trainingapp.feature.guest.component.RatingLine
import com.trainingapp.feature.guest.component.TrainerRow
import com.trainingapp.feature.guest.component.distanceText
import com.trainingapp.feature.guest.component.hhmm
import com.trainingapp.feature.guest.component.label
import com.trainingapp.feature.guest.data.mapsUrl
import com.trainingapp.feature.guest.component.ratingDescription
import com.trainingapp.feature.guest.component.ratingText
import com.trainingapp.feature.guest.model.Amenity
import com.trainingapp.feature.guest.model.GymDetail
import com.trainingapp.feature.guest.model.OpeningPeriod
import com.trainingapp.feature.guest.platform.rememberShareAction
import com.trainingapp.feature.guest.resources.*
import com.trainingapp.feature.guest.trainerprofile.DetailContent
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

data class GymDetailActions(
    val onBack: () -> Unit,
    val onOpenTrainer: (String) -> Unit,
    val onRequestSession: () -> Unit,
    val onDismissLoginSheet: () -> Unit,
    val onRegister: () -> Unit,
    val onLogIn: () -> Unit,
    val onRetry: () -> Unit,
)

/** Siłownia – gość. */
@Composable
fun GymDetailScreen(state: GymDetailUiState, actions: GymDetailActions) {
    val gym = (state.content as? DetailContent.Ready<GymDetail>)?.data
    val share = rememberShareAction()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copied = stringResource(Res.string.shared_copied)
    val shareText = gym?.let { stringResource(Res.string.share_gym_text, it.summary.name, it.address) }

    Scaffold(
        containerColor = AppTheme.colors.background,
        topBar = {
            DetailHeader(stringResource(Res.string.gym_detail_title), onBack = actions.onBack) {
                if (gym != null && shareText != null) {
                    IconButton(onClick = {
                        if (share.share(gym.summary.name, shareText)) scope.launch { snackbar.showSnackbar(copied) }
                    }) { Icon(Icons.Outlined.Share, stringResource(Res.string.share), tint = AppTheme.colors.text) }
                }
            }
        },
        bottomBar = {
            if (gym != null) {
                BottomActionBar {
                    PrimaryButton(stringResource(Res.string.request_cta), onClick = actions.onRequestSession, modifier = Modifier.fillMaxWidth(),
                        icon = Icons.AutoMirrored.Outlined.ArrowForward)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when (val content = state.content) {
                DetailContent.Loading -> LoadingState()
                DetailContent.Failed -> ErrorState(stringResource(Res.string.gym_error), Modifier.padding(Spacing.gutter), onRetry = actions.onRetry)
                DetailContent.NotFound -> EmptyState(
                    stringResource(Res.string.gym_unavailable_title),
                    stringResource(Res.string.gym_unavailable_body),
                    Modifier.padding(Spacing.gutter),
                    actionLabel = stringResource(Res.string.go_back),
                    onAction = actions.onBack,
                    icon = Icons.Outlined.WrongLocation,
                )
                is DetailContent.Ready -> GymBody(content.data, state.openNow, state.offline, actions)
            }
        }
    }

    if (state.loginSheetVisible) {
        LoginRequiredSheet(onRegister = actions.onRegister, onLogIn = actions.onLogIn, onDismiss = actions.onDismissLoginSheet)
    }
}

@Composable
private fun GymBody(gym: GymDetail, openNow: Boolean?, offline: Boolean, actions: GymDetailActions) {
    val summary = gym.summary
    val uriHandler = LocalUriHandler.current
    LazyColumn(
        modifier = Modifier.widthIn(max = Sizes.contentWidth).fillMaxSize().testTag("gym-detail"),
        contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        if (offline) item { InlineMessage(stringResource(Res.string.offline_banner), tone = MessageTone.Offline) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(summary.name, style = AppTheme.type.sectionTitle, color = AppTheme.colors.text, modifier = Modifier.semantics { heading() })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    StatusChip(summary.district)
                    summary.distanceMeters?.let { StatusChip(stringResource(Res.string.distance_from_you, distanceText(it))) }
                    summary.rating?.let { RatingLine(ratingText(it), ratingDescription(it)) }
                }
                MetaLine(Icons.Outlined.Place, gym.address)
            }
        }
        item {
            // Address and an external maps link only: no maps SDK and no fake map image (D7).
            Surface(Modifier.fillMaxWidth(), shape = AppShapes.frame, color = AppTheme.colors.surface, border = BorderStroke(1.dp, AppTheme.colors.outline)) {
                Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    Text(stringResource(Res.string.directions), style = AppTheme.type.itemTitle, color = AppTheme.colors.text, modifier = Modifier.semantics { heading() })
                    gym.transitNote?.let { MetaLine(Icons.Outlined.Train, it) }
                    TonalButton(stringResource(Res.string.open_maps), onClick = { runCatching { uriHandler.openUri(mapsUrl(gym.address)) } },
                        modifier = Modifier.fillMaxWidth(), icon = Icons.Outlined.Map)
                }
            }
        }
        item {
            SectionHeader(stringResource(Res.string.opening_hours), Modifier.padding(top = Spacing.m)) {
                when (openNow) {
                    true -> StatusChip(stringResource(Res.string.open_now), tone = StatusTone.Success, icon = Icons.Outlined.CheckCircle)
                    false -> StatusChip(stringResource(Res.string.closed_now), tone = StatusTone.Neutral, icon = Icons.Outlined.DoNotDisturbOn)
                    null -> Unit
                }
            }
        }
        item { HoursTable(gym.weeklyHours) }
        if (gym.amenities.isNotEmpty()) {
            item { SectionHeader(stringResource(Res.string.amenities), Modifier.padding(top = Spacing.m)) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    gym.amenities.forEach { StatusChip(stringResource(it.label()), icon = it.icon()) }
                }
            }
        }
        item {
            SectionHeader(stringResource(Res.string.trainers_here), Modifier.padding(top = Spacing.m)) {
                if (gym.trainers.isNotEmpty()) {
                    Text(pluralStringResource(Res.plurals.trainers_available, gym.trainers.size, gym.trainers.size),
                        style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                }
            }
        }
        if (gym.trainers.isEmpty()) {
            item { Text(stringResource(Res.string.no_trainers_here), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary) }
        } else {
            items(gym.trainers, key = { it.id }) { trainer -> TrainerRow(trainer, onClick = { actions.onOpenTrainer(trainer.id) }, compact = true) }
        }
        item { Spacer(Modifier.height(Spacing.l)) }
    }
}

private fun Amenity.icon() = when (this) {
    Amenity.FreeWeights -> Icons.Outlined.FitnessCenter
    Amenity.Cardio -> Icons.Outlined.MonitorHeart
    Amenity.Parking -> Icons.Outlined.LocalParking
    Amenity.Showers -> Icons.Outlined.Shower
    Amenity.Sauna -> Icons.Outlined.Thermostat
    Amenity.FunctionalZone -> Icons.Outlined.SportsGymnastics
}

/** Consecutive days with the same hours are grouped: "Poniedziałek – Piątek  06:00–22:00". */
@Composable
private fun HoursTable(hours: List<OpeningPeriod>) {
    val allDay = stringResource(Res.string.open_all_day)
    val closed = stringResource(Res.string.closed_day)
    val perDay = DayOfWeek.entries.map { day ->
        val periods = hours.filter { it.day == day }
        day to when {
            periods.isEmpty() -> closed
            periods.any { it.open == LocalTime(0, 0) && it.close == LocalTime(0, 0) } -> allDay
            else -> periods.sortedBy { it.open }.joinToString(", ") { "${it.open.hhmm()}–${it.close.hhmm()}" }
        }
    }
    val groups = mutableListOf<Triple<DayOfWeek, DayOfWeek, String>>()
    perDay.forEach { (day, text) ->
        val last = groups.lastOrNull()
        if (last != null && last.third == text) groups[groups.lastIndex] = Triple(last.first, day, text) else groups += Triple(day, day, text)
    }
    Surface(Modifier.fillMaxWidth(), shape = AppShapes.card, color = AppTheme.colors.surface, border = BorderStroke(1.dp, AppTheme.colors.outline)) {
        Column {
            groups.forEachIndexed { index, (from, to, text) ->
                if (index > 0) RowDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    val days = if (from == to) stringResource(from.label()) else stringResource(Res.string.day_range, stringResource(from.label()), stringResource(to.label()))
                    Text(days, style = AppTheme.type.compactBody, color = AppTheme.colors.text, modifier = Modifier.weight(1f))
                    Text(text, style = AppTheme.type.bodyStrong.copy(fontSize = AppTheme.type.compactBody.fontSize), color = AppTheme.colors.text)
                }
            }
        }
    }
}
