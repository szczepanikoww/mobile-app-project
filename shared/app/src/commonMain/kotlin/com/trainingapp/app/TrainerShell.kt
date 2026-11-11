package com.trainingapp.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trainingapp.app.resources.*
import com.trainingapp.domain.media.MediaState
import com.trainingapp.feature.workoutbuilder.media.DemoVideoCard
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import pl.trainer.data.remote.TrainerApiClient
import pl.trainerapp.feature.chat.data.RemoteTrainerChatsRepository
import pl.trainerapp.feature.trainerfinance.data.RemoteTrainerFinanceRepository
import pl.trainerapp.feature.trainerhome.data.RemoteTrainerHomeRepository
import pl.trainerapp.feature.trainerprofile.data.RemoteTrainerProfileRepository
import pl.trainer.domain.PolicyRepository
import pl.trainer.domain.TrainerRepository
import pl.trainer.presentation.Destination
import pl.trainer.presentation.TrainerViewModel
import pl.trainer.ui.ActionRow
import pl.trainer.ui.TrainerApp
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.feature.chat.ChatMediaSource
import pl.trainerapp.feature.chat.TechniqueVideoCard
import pl.trainerapp.feature.chat.data.DemoChatStore
import pl.trainerapp.feature.chat.data.remote.RemoteTechniqueVideoReviews
import pl.trainerapp.feature.chat.data.DemoTrainerChatsRepository
import pl.trainerapp.feature.trainerfinance.data.DemoTrainerFinanceRepository
import pl.trainerapp.feature.trainerhome.data.DemoTrainerHomeRepository
import pl.trainerapp.feature.trainerhome.model.AttentionItem
import pl.trainerapp.feature.trainerhome.model.ClientRef
import pl.trainerapp.feature.trainernotifications.data.DemoTrainerNotificationsRepository
import pl.trainerapp.feature.trainernotifications.data.RemoteTrainerNotificationsRepository
import pl.trainerapp.feature.trainerprofile.data.DemoTrainerProfileRepository
import pl.trainerapp.shell.TrainerDestination
import pl.trainerapp.shell.TrainerRepositories
import pl.trainerapp.shell.TrainerShell as MainTrainerShell

/** Actions the trainer shell needs from the app. [onLogOut] returns to the guest views (trainer profile screen). */
data class WorkoutNavigationActions(
    val onNewPlan: () -> Unit,
    val onOpenPlans: () -> Unit,
    val onOpenExercises: () -> Unit,
    val onLogOut: () -> Unit = {},
)

/** The technique video in A5: the media card from the workout builder, playable only when READY. */
internal val AppTechniqueVideoCard: TechniqueVideoCard = { asset, durationMs, onRetry ->
    DemoVideoCard(thumbnailPath = asset?.thumbnailPath, durationMs = durationMs, asset = asset, onRetry = onRetry)
}

/**
 * The one trainer shell used by Android and the desktop preview: home, finance, chats, notifications and profile
 * from the main shell, with the calendar/clients feature and the workout-plan create actions inside it. Chats, the
 * calendar, cancellation policies and media state come from the app's demo stores, so the trainer and client views share them.
 */
@Composable
fun TrainerShell(actions: WorkoutNavigationActions) {
    val chatStore: DemoChatStore = koinInject()
    val chatMedia: ChatMediaSource = koinInject()
    val calendar: TrainerRepository = koinInject()
    val policies: PolicyRepository = koinInject()
    // The trainer API when the app has a server address (docs/features/trainer-backend.md §8); demo data otherwise.
    val api: TrainerApiClient? = getKoin().getOrNull()
    // Server-backed technique-video inbox and review (remote mode only).
    val reviews: RemoteTechniqueVideoReviews? = if (api != null) getKoin().getOrNull() else null
    val repositories = remember(chatStore, chatMedia, calendar, policies, api, reviews) {
        val techniqueVideos: suspend () -> List<AttentionItem.TechniqueVideo> = reviews?.let { r -> { r.techniqueVideos() } } ?: {
            chatStore.pendingReviews()
                // "Wymaga uwagi" lists only videos that are ready to watch.
                .filter { chatMedia.observe(it.mediaId).first()?.state == MediaState.READY }
                .map { AttentionItem.TechniqueVideo(it.mediaId, ClientRef(it.relationshipId, it.clientName), it.exerciseName) }
        }
        TrainerRepositories(
            home = api?.let { RemoteTrainerHomeRepository(it, techniqueVideos) } ?: DemoTrainerHomeRepository(techniqueVideos = techniqueVideos),
            finance = api?.let { RemoteTrainerFinanceRepository(it) } ?: DemoTrainerFinanceRepository(),
            chats = api?.let { RemoteTrainerChatsRepository(it) } ?: DemoTrainerChatsRepository(chatStore),
            // No notifications table on the server: derived from the dashboard and home counts.
            notifications = api?.let { RemoteTrainerNotificationsRepository(it) } ?: DemoTrainerNotificationsRepository(),
            profile = api?.let { RemoteTrainerProfileRepository(it) } ?: DemoTrainerProfileRepository(),
            calendar = calendar,
            chatStore = reviews ?: chatStore,
            chatMedia = chatMedia,
            policies = policies,
        )
    }
    AppTheme {
        val trainer: TrainerViewModel = viewModel { TrainerViewModel(calendar) }
        MainTrainerShell(
            repositories,
            trainerFeature = { destination, createRequest, onMain ->
                LaunchedEffect(destination) {
                    trainer.destination(if (destination == TrainerDestination.Calendar) Destination.Calendar else Destination.Clients)
                }
                TrainerApp(
                    trainer,
                    showNavigation = false,
                    createRequest = createRequest,
                    onMainRouteChanged = onMain,
                    extraCreateActions = { dismiss -> WorkoutCreateActions(actions, dismiss) },
                )
            },
            onLogOut = actions.onLogOut,
            videoCard = AppTechniqueVideoCard,
        )
    }
}

/** The same create sheet extends the session/availability actions on both platforms. */
@Composable
fun WorkoutCreateActions(actions: WorkoutNavigationActions, dismiss: () -> Unit) {
    ActionRow(Icons.Outlined.FitnessCenter, stringResource(Res.string.add_new_plan), stringResource(Res.string.add_new_plan_hint)) {
        dismiss(); actions.onNewPlan()
    }
    ActionRow(Icons.AutoMirrored.Outlined.Assignment, stringResource(Res.string.add_plans), stringResource(Res.string.add_plans_hint)) {
        dismiss(); actions.onOpenPlans()
    }
    ActionRow(Icons.AutoMirrored.Outlined.LibraryBooks, stringResource(Res.string.add_exercises), stringResource(Res.string.add_exercises_hint)) {
        dismiss(); actions.onOpenExercises()
    }
}
