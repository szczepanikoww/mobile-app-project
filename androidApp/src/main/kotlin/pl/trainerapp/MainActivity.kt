package pl.trainerapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import pl.trainerapp.feature.chat.TechniqueVideoRoute
import pl.trainerapp.feature.chat.model.ChatRole
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trainingapp.android.TrainingApplication
import com.trainingapp.app.App
import com.trainingapp.app.WorkoutCreateActions
import com.trainingapp.domain.media.MediaState
import com.trainingapp.feature.workoutlogging.rest.AndroidRestTimerNotifier
import kotlinx.coroutines.flow.first
import pl.trainer.presentation.Destination
import pl.trainer.presentation.TrainerViewModel
import pl.trainer.ui.TrainerApp
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.feature.chat.ClientChatRoute
import pl.trainerapp.feature.chat.data.DemoClientChatRepository
import pl.trainerapp.feature.chat.data.DemoTrainerChatsRepository
import pl.trainerapp.feature.trainerfinance.data.DemoTrainerFinanceRepository
import pl.trainerapp.feature.trainerhome.data.DemoTrainerHomeRepository
import pl.trainerapp.feature.trainerhome.model.AttentionItem
import pl.trainerapp.feature.trainerhome.model.ClientRef
import pl.trainerapp.feature.trainernotifications.data.DemoTrainerNotificationsRepository
import pl.trainerapp.feature.trainerprofile.data.DemoTrainerProfileRepository
import pl.trainerapp.shell.TrainerDestination
import pl.trainerapp.shell.TrainerRepositories
import pl.trainerapp.shell.TrainerShell

class MainActivity : ComponentActivity() {
    private val app get() = application as TrainingApplication

    // Explicit demo data sources: no backend exists yet. Chat, calendar and media state live in the application,
    // so the trainer and client views share them and they survive configuration changes.
    private val repositories by lazy {
        TrainerRepositories(
            home = DemoTrainerHomeRepository(
                techniqueVideos = {
                    app.chatStore.pendingReviews()
                        // "Wymaga uwagi" lists only videos that are ready to watch.
                        .filter { app.chatMedia.observe(it.mediaId).first()?.state == MediaState.READY }
                        .map { AttentionItem.TechniqueVideo(it.mediaId, ClientRef(it.relationshipId, it.clientName), it.exerciseName) }
                },
            ),
            finance = DemoTrainerFinanceRepository(),
            chats = DemoTrainerChatsRepository(app.chatStore),
            notifications = DemoTrainerNotificationsRepository(),
            profile = DemoTrainerProfileRepository(),
            calendar = app.trainerRepository,
            chatStore = app.chatStore,
            chatMedia = app.chatMedia,
        )
    }

    /** Incremented when the rest-timer notification is tapped; the app then opens the live workout. */
    private var openWorkoutRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            App(
                host = { actions ->
                    AppTheme {
                        val trainer: TrainerViewModel = viewModel { TrainerViewModel(app.trainerRepository) }
                        TrainerShell(repositories, trainerFeature = { destination, createRequest, onMain ->
                            LaunchedEffect(destination) {
                                trainer.destination(if (destination == TrainerDestination.Calendar) Destination.Calendar else Destination.Clients)
                            }
                            TrainerApp(trainer, showNavigation = false, createRequest = createRequest, onMainRouteChanged = onMain,
                                extraCreateActions = { dismiss -> WorkoutCreateActions(actions, dismiss) })
                        }, onLogOut = actions.onLogOut)
                    }
                },
                // A6 in the temporary client preview: the same conversation the trainer sees.
                clientChat = { onBack ->
                    AppTheme {
                        var openVideo by rememberSaveable { mutableStateOf<String?>(null) }
                        val videoId = openVideo
                        if (videoId == null) {
                            ClientChatRoute(
                                repository = DemoClientChatRepository(app.chatStore, relationshipId = "c1"),
                                onBack = onBack,
                                mediaSource = app.chatMedia,
                                onOpenVideo = { openVideo = it },
                            )
                        } else {
                            // The client's own video opens as a read-only player.
                            BackHandler { openVideo = null }
                            TechniqueVideoRoute(
                                mediaId = videoId,
                                viewer = ChatRole.Client,
                                store = app.chatStore,
                                chats = repositories.chats,
                                mediaSource = app.chatMedia,
                                onBack = { openVideo = null },
                            )
                        }
                    }
                },
                openLiveWorkoutRequest = openWorkoutRequests,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(AndroidRestTimerNotifier.EXTRA_OPEN_WORKOUT, false) == true) {
            intent.removeExtra(AndroidRestTimerNotifier.EXTRA_OPEN_WORKOUT)
            openWorkoutRequests++
        }
    }
}
