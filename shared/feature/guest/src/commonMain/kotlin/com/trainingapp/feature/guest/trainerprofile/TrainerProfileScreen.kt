package com.trainingapp.feature.guest.trainerprofile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trainingapp.designsystem.component.AppBottomSheet
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.BottomActionBar
import com.trainingapp.designsystem.component.DetailHeader
import com.trainingapp.designsystem.component.EmptyState
import com.trainingapp.designsystem.component.ErrorState
import com.trainingapp.designsystem.component.InitialsAvatar
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.LoadingState
import com.trainingapp.designsystem.component.MessageTone
import com.trainingapp.designsystem.component.MetaLine
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.SectionHeader
import com.trainingapp.designsystem.component.StatusChip
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.feature.guest.component.GymRow
import com.trainingapp.feature.guest.component.LoginRequiredSheet
import com.trainingapp.feature.guest.component.MediaTile
import com.trainingapp.feature.guest.component.PriceList
import com.trainingapp.feature.guest.component.RatingLine
import com.trainingapp.feature.guest.component.VerifiedChip
import com.trainingapp.feature.guest.component.ratingDescription
import com.trainingapp.feature.guest.component.ratingText
import com.trainingapp.feature.guest.model.PublicMedia
import com.trainingapp.feature.guest.model.TrainerProfile
import com.trainingapp.feature.guest.platform.rememberShareAction
import com.trainingapp.feature.guest.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Plays a READY video; supplied by the app so the guest feature does not depend on the workout builder (D9). */
typealias VideoPlayerSlot = @Composable (url: String, modifier: Modifier) -> Unit

data class TrainerProfileActions(
    val onBack: () -> Unit,
    val onOpenGym: (String) -> Unit,
    val onRequestSession: () -> Unit,
    val onDismissLoginSheet: () -> Unit,
    val onShowPolicy: (Boolean) -> Unit,
    val onRegister: () -> Unit,
    val onLogIn: () -> Unit,
    val onRetry: () -> Unit,
)

/** Profil trenera – gość: helps a guest decide whether to ask this trainer for a session. One dominant action. */
@Composable
fun TrainerProfileScreen(
    state: TrainerProfileUiState,
    actions: TrainerProfileActions,
    videoPlayer: VideoPlayerSlot,
) {
    val profile = (state.content as? DetailContent.Ready<TrainerProfile>)?.data
    val share = rememberShareAction()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copied = stringResource(Res.string.shared_copied)
    val shareText = profile?.let { stringResource(Res.string.share_trainer_text, it.summary.name, it.summary.headline) }
    var playing by remember { mutableStateOf<PublicMedia?>(null) }

    Scaffold(
        containerColor = AppTheme.colors.background,
        topBar = {
            DetailHeader(stringResource(Res.string.trainer_profile_title), onBack = actions.onBack) {
                if (profile != null && shareText != null) {
                    IconButton(onClick = {
                        if (share.share(profile.summary.name, shareText)) scope.launch { snackbar.showSnackbar(copied) }
                    }) { Icon(Icons.Outlined.Share, stringResource(Res.string.share), tint = AppTheme.colors.text) }
                }
            }
        },
        bottomBar = {
            if (profile != null) {
                BottomActionBar {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs), horizontalAlignment = Alignment.CenterHorizontally) {
                        PrimaryButton(stringResource(Res.string.request_cta), onClick = actions.onRequestSession, modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Outlined.Lock)
                        Text(stringResource(Res.string.request_cta_hint), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when (val content = state.content) {
                DetailContent.Loading -> LoadingState()
                DetailContent.Failed -> ErrorState(stringResource(Res.string.profile_error), Modifier.padding(Spacing.gutter), onRetry = actions.onRetry)
                DetailContent.NotFound -> EmptyState(
                    stringResource(Res.string.profile_unavailable_title),
                    stringResource(Res.string.profile_unavailable_body),
                    Modifier.padding(Spacing.gutter),
                    actionLabel = stringResource(Res.string.go_back),
                    onAction = actions.onBack,
                    icon = Icons.Outlined.PersonOff,
                )
                is DetailContent.Ready -> ProfileBody(content.data, state.offline, actions, onPlay = { playing = it })
            }
        }
    }

    if (state.loginSheetVisible) {
        LoginRequiredSheet(onRegister = actions.onRegister, onLogIn = actions.onLogIn, onDismiss = actions.onDismissLoginSheet)
    }
    val policy = profile?.cancellationPolicy
    if (state.policySheetVisible && policy != null) {
        AppBottomSheet(onDismiss = { actions.onShowPolicy(false) }, title = stringResource(Res.string.cancellation_title)) {
            Text(policy.summary, style = AppTheme.type.body, color = AppTheme.colors.text)
            policy.tiers.forEach { tier -> MetaLine(Icons.Outlined.Schedule, tier.description) }
            Text(stringResource(Res.string.policy_version, policy.version), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
        }
    }
    playing?.let { media ->
        val url = media.playbackUrl
        if (media.playable && url != null) {
            AppBottomSheet(onDismiss = { playing = null }, title = media.title) {
                videoPlayer(url, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                AppTextButton(stringResource(Res.string.close), onClick = { playing = null }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ProfileBody(profile: TrainerProfile, offline: Boolean, actions: TrainerProfileActions, onPlay: (PublicMedia) -> Unit) {
    val summary = profile.summary
    LazyColumn(
        modifier = Modifier.widthIn(max = Sizes.contentWidth).fillMaxSize().testTag("trainer-profile"),
        contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        if (offline) item { InlineMessage(stringResource(Res.string.offline_banner), tone = MessageTone.Offline) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l), verticalAlignment = Alignment.Top) {
                InitialsAvatar(summary.initials, large = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(summary.name, style = AppTheme.type.sectionTitle, color = AppTheme.colors.text, modifier = Modifier.semantics { heading() })
                    Text(summary.headline, style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                    FlowRow(Modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        summary.rating?.let { RatingLine(ratingText(it), ratingDescription(it)) }
                        if (summary.verified) VerifiedChip()
                    }
                }
            }
        }
        if (profile.yearsOfExperience != null || profile.clientCountLabel != null) item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                profile.yearsOfExperience?.let { MetaLine(Icons.Outlined.Schedule, pluralStringResource(Res.plurals.years_experience, it, it)) }
                profile.clientCountLabel?.let { MetaLine(Icons.Outlined.Groups, stringResource(Res.string.clients_label, it)) }
            }
        }
        item { Section(stringResource(Res.string.about)) }
        item { Text(profile.bio, style = AppTheme.type.body, color = AppTheme.colors.text) }
        if (profile.specialisations.isNotEmpty()) {
            item { Section(stringResource(Res.string.specialisations)) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    profile.specialisations.forEach { StatusChip(it) }
                }
            }
        }
        if (profile.gyms.isNotEmpty()) {
            item {
                SectionHeader(stringResource(Res.string.where_i_train), Modifier.padding(top = Spacing.m)) {
                    Text(pluralStringResource(Res.plurals.locations_count, profile.gyms.size, profile.gyms.size),
                        style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
                }
            }
            items(profile.gyms, key = { it.gym.id }) { trainerGym ->
                GymRow(trainerGym.gym, onClick = { actions.onOpenGym(trainerGym.gym.id) },
                    badge = if (trainerGym.main) stringResource(Res.string.main_gym) else null)
            }
        }
        if (profile.media.isNotEmpty()) {
            item { Section(stringResource(Res.string.how_i_work)) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    items(profile.media, key = { it.id }) { media -> MediaTile(media, onPlay = { onPlay(media) }) }
                }
            }
        }
        if (profile.offers.isNotEmpty()) {
            item { Section(stringResource(Res.string.prices_title)) }
            item { PriceList(profile.offers) }
        }
        profile.cancellationPolicy?.let { policy ->
            item {
                InlineMessage(policy.summary, Modifier.padding(top = Spacing.s), tone = MessageTone.Info) {
                    AppTextButton(stringResource(Res.string.policy_details), onClick = { actions.onShowPolicy(true) })
                }
            }
        }
        item { Spacer(Modifier.height(Spacing.l)) }
    }
}

@Composable
private fun Section(title: String) = SectionHeader(title, Modifier.padding(top = Spacing.m))
