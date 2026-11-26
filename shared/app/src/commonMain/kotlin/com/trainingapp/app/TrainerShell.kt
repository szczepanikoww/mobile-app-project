package com.trainingapp.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trainingapp.app.remote.RemotePlanAssigner
import com.trainingapp.domain.media.MediaState
import com.trainingapp.feature.guest.model.AuthRepository
import com.trainingapp.feature.guest.platform.rememberShareAction
import com.trainingapp.feature.workoutbuilder.media.DemoVideoCard
import kotlinx.coroutines.flow.first
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import pl.trainer.data.DemoInviteLinks
import pl.trainer.data.remote.RemoteInviteLinks
import pl.trainer.data.remote.TrainerApiClient
import pl.trainer.domain.PolicyRepository
import pl.trainer.domain.TrainerRepository
import pl.trainer.presentation.Destination
import pl.trainer.presentation.TrainerViewModel
import pl.trainer.ui.TrainerApp
import pl.trainer.ui.TrainerIntegrations
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.feature.chat.ChatMediaSource
import pl.trainerapp.feature.chat.TechniqueVideoCard
import pl.trainerapp.feature.chat.data.DemoChatStore
import pl.trainerapp.feature.chat.data.DemoTrainerChatsRepository
import pl.trainerapp.feature.chat.data.RemoteTrainerChatsRepository
import pl.trainerapp.feature.chat.data.remote.RemoteTechniqueVideoReviews
import pl.trainerapp.feature.trainerfinance.data.DemoTrainerFinanceRepository
import pl.trainerapp.feature.trainerfinance.data.RemoteTrainerFinanceRepository
import pl.trainerapp.feature.trainerhome.data.DemoTrainerHomeRepository
import pl.trainerapp.feature.trainerhome.data.RemoteTrainerHomeRepository
import pl.trainerapp.feature.trainerhome.model.AttentionItem
import pl.trainerapp.feature.trainerhome.model.ClientRef
import pl.trainerapp.feature.trainernotifications.data.DemoTrainerNotificationsRepository
import pl.trainerapp.feature.trainernotifications.data.RemoteTrainerNotificationsRepository
import pl.trainerapp.feature.trainerprofile.data.DemoTrainerProfileRepository
import pl.trainerapp.feature.trainerprofile.data.RemoteTrainerProfileRepository
import pl.trainerapp.shell.TrainerDestination
import pl.trainerapp.shell.TrainerRepositories
import pl.trainerapp.shell.TrainerShell as MainTrainerShell

/** Actions the trainer shell needs from the app. [onLogOut] returns to the guest views (trainer profile screen). */
data class WorkoutNavigationActions(
    val onNewPlan: () -> Unit,
    val onOpenPlans: () -> Unit,
    val onOpenExercises: () -> Unit,
    val onLogOut: () -> Unit = {},
    /** Opens a stored plan draft in the editor (a plan written out for one session). */
    val onOpenDraft: (draftId: String) -> Unit = {},
)

/** The technique video in A5: the media card from the workout builder, playable only when READY. */
internal val AppTechniqueVideoCard: TechniqueVideoCard = { asset, durationMs, onRetry ->
    DemoVideoCard(thumbnailPath = asset?.thumbnailPath, durationMs = durationMs, asset = asset, onRetry = onRetry)
}

/**
 * The one trainer shell used by Android and the desktop preview (docs/plans/trainer-redesign.md): Start, Calendar
 * and Trainees from the trainer feature, Plans from the workout builder, chat and profile from the main shell.
 * With a server address everything comes from the trainer API; otherwise from the app's demo stores, so the trainer
 * and client views share them.
 */
@Composable
fun TrainerShell(actions: WorkoutNavigationActions) {
    val chatStore: DemoChatStore = koinInject()
    val chatMedia: ChatMediaSource = koinInject()
    val calendar: TrainerRepository = koinInject()
    val policies: PolicyRepository = koinInject()
    val auth: AuthRepository = koinInject()
    val session by auth.session.collectAsState()
    // The trainer API when the app has a server address (docs/features/trainer-backend.md §8); demo data otherwise.
    val api: TrainerApiClient? = getKoin().getOrNull()
    // Server-backed technique-video inbox and review (remote mode only).
    val reviews: RemoteTechniqueVideoReviews? = if (api != null) getKoin().getOrNull() else null
    val repositories = remember(chatStore, chatMedia, calendar, policies, api, reviews) {
        val techniqueVideos: suspend () -> List<AttentionItem.TechniqueVideo> = reviews?.let { r -> { r.techniqueVideos() } } ?: {
            chatStore.pendingReviews()
                .filter { chatMedia.observe(it.mediaId).first()?.state == MediaState.READY }
                .map { AttentionItem.TechniqueVideo(it.mediaId, ClientRef(it.relationshipId, it.clientName), it.exerciseName) }
        }
        TrainerRepositories(
            home = api?.let { RemoteTrainerHomeRepository(it, techniqueVideos) } ?: DemoTrainerHomeRepository(techniqueVideos = techniqueVideos),
            finance = api?.let { RemoteTrainerFinanceRepository(it) } ?: DemoTrainerFinanceRepository(),
            chats = api?.let { RemoteTrainerChatsRepository(it) } ?: DemoTrainerChatsRepository(chatStore),
            notifications = api?.let { RemoteTrainerNotificationsRepository(it) } ?: DemoTrainerNotificationsRepository(),
            profile = api?.let { RemoteTrainerProfileRepository(it) } ?: DemoTrainerProfileRepository(),
            calendar = calendar,
            chatStore = reviews ?: chatStore,
            chatMedia = chatMedia,
            policies = policies,
        )
    }
    val assigner = remember(api) { api?.let { RemotePlanAssigner(it) } }
    val gallery: com.trainingapp.app.photos.RemoteProfileGallery? = getKoin().getOrNull()
    val share = rememberShareAction()
    AppTheme {
        val trainer: TrainerViewModel = viewModel {
            TrainerViewModel(calendar, invites = api?.let { RemoteInviteLinks(it) } ?: DemoInviteLinks(), finance = repositories.finance)
        }
        MainTrainerShell(
            repositories,
            trainerFeature = { destination, createRequest, onMain, links ->
                LaunchedEffect(destination) {
                    trainer.destination(
                        when (destination) {
                            TrainerDestination.Calendar -> Destination.Calendar
                            TrainerDestination.Clients -> Destination.Clients
                            else -> Destination.Start
                        },
                    )
                    // Requests and changes from clients arrive on the server; show them when the tab opens.
                    if (api != null) trainer.reload()
                }
                TrainerApp(
                    trainer,
                    showNavigation = false,
                    createRequest = createRequest,
                    onMainRouteChanged = onMain,
                    integrations = TrainerIntegrations(
                        trainerName = session?.displayName.orEmpty(),
                        onOpenChats = links.onOpenChats,
                        onOpenChat = links.onOpenChat,
                        onOpenProfile = links.onOpenProfile,
                        planSection = { target ->
                            TrainerPlanSection(
                                target = target,
                                assigner = assigner,
                                onChanged = trainer::reload,
                                onOpenDraft = actions.onOpenDraft,
                                onOpenPlans = actions.onOpenPlans,
                            )
                        },
                        shareText = { text -> share.share(text.lineSequence().first(), text) },
                    ),
                )
            },
            plansTab = { TrainerPlansTab(actions) },
            // "Zdjęcia" in the profile: needs the server (photos are stored there).
            profileExtras = gallery?.let { g -> { com.trainingapp.app.photos.ProfileGallerySection(g) } },
            onLogOut = actions.onLogOut,
            videoCard = AppTechniqueVideoCard,
        )
    }
}
