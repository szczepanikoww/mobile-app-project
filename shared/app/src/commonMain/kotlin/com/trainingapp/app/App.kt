package com.trainingapp.app

import org.koin.compose.getKoin
import pl.trainer.data.remote.TrainerApiClient
import com.trainingapp.app.resources.assign_done
import com.trainingapp.app.remote.RemotePlanAssigner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.trainingapp.app.resources.Res
import com.trainingapp.app.resources.assign_message
import com.trainingapp.app.resources.assign_title
import com.trainingapp.app.resources.understood
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.app.photos.ProvidePhotos
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.feature.client.ClientShellArgs
import com.trainingapp.feature.client.ClientShellRoute
import com.trainingapp.feature.client.ui.ClientShellActions
import com.trainingapp.feature.guest.AuthDestination
import com.trainingapp.feature.guest.ClientHomeDestination
import com.trainingapp.feature.guest.GuestHomeDestination
import com.trainingapp.feature.guest.guestGraph
import com.trainingapp.feature.guest.model.AuthRepository
import com.trainingapp.feature.guest.model.AuthSession
import com.trainingapp.feature.guest.model.GuestIntent
import com.trainingapp.feature.guest.model.Role
import com.trainingapp.feature.workoutbuilder.common.ExerciseBrowserDestination
import com.trainingapp.feature.workoutbuilder.common.PlanEditorDestination
import com.trainingapp.feature.workoutbuilder.common.PlanLibraryDestination
import com.trainingapp.feature.workoutbuilder.media.DemoVideoPlayer
import com.trainingapp.feature.workoutbuilder.workoutBuilderGraph
import com.trainingapp.feature.workoutlogging.LiveWorkoutDestination
import com.trainingapp.feature.workoutlogging.WorkoutSummaryDestination
import com.trainingapp.feature.workoutlogging.progress.ProgressRoute
import com.trainingapp.feature.workoutlogging.workoutLoggingGraph
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import com.trainingapp.domain.workout.CurrentClient
import pl.trainer.domain.ClientSession
import pl.trainerapp.feature.chat.data.DemoChatStore
import pl.trainerapp.feature.chat.model.ChatRole

/** The trainer's top-level destinations (bottom navigation). Detail screens are pushed over it. */
@Serializable
data object TrainerHomeDestination

/** The client's conversation list ("Wiadomości", A6), behind the message icon of the client shell. */
@Serializable
data object ClientMessagesDestination

/** One of the client's conversations (A6); null is the conversation with the client's own trainer. */
@Serializable
data class ClientChatDestination(val relationshipId: String? = null)

/**
 * Where the app opens: the role shell for a signed-in session, otherwise "Zaloguj się / Załóż konto". Guests reach
 * the public catalogue from there with "Przeglądaj bez logowania".
 */
internal fun startDestinationFor(session: AuthSession?): Any = when {
    session == null -> AuthDestination()
    session.activeRole == Role.Trainer -> TrainerHomeDestination
    else -> ClientHomeDestination()
}

/** "Katarzyna Nowak" → "KN". */
internal fun initialsOf(name: String): String =
    name.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

/**
 * The app entry point shared by Android and the desktop preview. It starts in the role shell of the signed-in
 * session ([initialSession] lets tests and previews start signed in), otherwise in the guest views. Client and
 * trainer shells use the same theme.
 *
 * Incrementing [openLiveWorkoutRequest] opens the live workout, e.g. after tapping the rest notification.
 * [addToCalendar] is the Android calendar insert intent offered in the client's session details (G5, D6).
 */
@Composable
fun App(
    initialSession: AuthSession? = null,
    host: (@Composable (WorkoutNavigationActions) -> Unit)? = null,
    openLiveWorkoutRequest: Int = 0,
    addToCalendar: ((ClientSession) -> Unit)? = null,
) {
    // Client and guest views use the club palette; the trainer's shell keeps the teal one (one switch, at the root).
    val themeAuth: AuthRepository = koinInject()
    val themeSession by themeAuth.session.collectAsState()
    val clubPalette = (themeSession ?: initialSession)?.activeRole != Role.Trainer
    androidx.compose.runtime.CompositionLocalProvider(pl.trainer.ui.LocalClubPalette provides clubPalette) {
    AppTheme { ProvidePhotos {
        val navController = rememberNavController()
        val auth: AuthRepository = koinInject()
        val scope = rememberCoroutineScope()
        var showAssignInfo by rememberSaveable { mutableStateOf(false) }
        var assignPlan by remember { mutableStateOf<PlanToAssign?>(null) }
        var assignedMessage by remember { mutableStateOf<Pair<String, String>?>(null) }
        // The assignment API is the trainer API; without a server the dialog explains that it is not connected.
        val planAssigner: RemotePlanAssigner? = getKoin().getOrNull<TrainerApiClient>()?.let { remember(it) { RemotePlanAssigner(it) } }
        val startDestination = remember { startDestinationFor(initialSession ?: auth.session.value) }
        val pendingSessionPlan: PendingSessionPlan = koinInject()

        // After sign-in, sign-out or a role switch the whole back stack is replaced, so Back never returns to Auth,
        // the guest views or the other role's shell.
        val replaceStack: (Any) -> Unit = { destination ->
            navController.navigate(destination) { popUpTo(navController.graph.id) { inclusive = true } }
        }
        val logOut: () -> Unit = {
            scope.launch { auth.logOut() }
            replaceStack(AuthDestination())
        }
        val switchRole: (Role) -> Unit = { role ->
            scope.launch {
                auth.switchRole(role)?.let { replaceStack(if (it.activeRole == Role.Trainer) TrainerHomeDestination else ClientHomeDestination()) }
            }
        }

        // A session that ends by itself (refresh token revoked in Firebase, account removed) returns to sign-in.
        // A normal log-out has already navigated there.
        LaunchedEffect(auth) {
            var wasSignedIn = auth.session.value != null
            auth.session.collect { session ->
                if (session == null && wasSignedIn && navController.currentDestination?.hasRoute<AuthDestination>() != true) {
                    replaceStack(AuthDestination())
                }
                wasSignedIn = session != null
            }
        }

        LaunchedEffect(openLiveWorkoutRequest) {
            if (openLiveWorkoutRequest > 0) {
                navController.navigate(LiveWorkoutDestination) { launchSingleTop = true }
            }
        }

        NavHost(navController = navController, startDestination = startDestination) {
            composable<TrainerHomeDestination> {
                val actions = WorkoutNavigationActions(
                    onNewPlan = { navController.navigate(PlanEditorDestination()) },
                    onOpenPlans = { navController.navigate(PlanLibraryDestination) },
                    onOpenExercises = { navController.navigate(ExerciseBrowserDestination()) },
                    onLogOut = logOut,
                    onOpenDraft = { draftId -> navController.navigate(PlanEditorDestination(draftId = draftId)) },
                )
                if (host != null) host(actions) else TrainerShell(actions)
            }
            workoutBuilderGraph(
                navController = navController,
                onAssignToClient = { templateId, version ->
                    // A plan written out for one session goes straight to that client and session.
                    val forSession = pendingSessionPlan.target
                    when {
                        planAssigner == null -> showAssignInfo = true
                        forSession != null -> scope.launch {
                            val assigned = runCatching {
                                planAssigner.assign(forSession.clientId, templateId, version, sessionId = forSession.sessionId)
                            }.getOrNull()
                            if (assigned != null) {
                                pendingSessionPlan.target = null
                                assignedMessage = assigned.name to forSession.clientName
                                navController.popBackStack<TrainerHomeDestination>(inclusive = false)
                            } else {
                                assignPlan = PlanToAssign(templateId, version)
                            }
                        }
                        else -> assignPlan = PlanToAssign(templateId, version)
                    }
                },
            )
            guestGraph(
                navController = navController,
                // A pending intent reaches the client shell, which opens the booking request sheet with it.
                onSignedIn = { session, intent ->
                    replaceStack(if (session.activeRole == Role.Trainer) TrainerHomeDestination else ClientHomeDestination.of(intent))
                },
                onLogOut = logOut,
                videoPlayer = { url, modifier -> DemoVideoPlayer(url, modifier) },
                clientHome = { destination, session ->
                    // The chat icon in the client shell: unread messages of the client's conversation with the trainer.
                    val chatStore: DemoChatStore = koinInject()
                    val client: CurrentClient = koinInject()
                    val unreadFlow = remember(chatStore, client.trainerRelationshipId) {
                        chatStore.version.map {
                            chatStore.conversations(ChatRole.Client).firstOrNull { it.relationshipId == client.trainerRelationshipId }?.unread ?: 0
                        }
                    }
                    val unread by unreadFlow.collectAsState(0)
                    val name = session?.displayName.orEmpty()
                    val intent = destination.pendingIntent as? GuestIntent.RequestSession
                    ClientShellRoute(
                        displayName = name,
                        initials = initialsOf(name),
                        args = ClientShellArgs(pendingIntent = intent != null, pendingTrainerId = intent?.trainerId),
                        actions = ClientShellActions(
                            onStartWorkout = { navController.navigate(LiveWorkoutDestination) { launchSingleTop = true } },
                            onOpenChat = { navController.navigate(ClientChatDestination()) },
                            onOpenMessages = { navController.navigate(ClientMessagesDestination) { launchSingleTop = true } },
                            onFindTrainer = { navController.navigate(GuestHomeDestination) },
                            onSwitchToTrainer = if (session != null && Role.Trainer in session.roles) {
                                { switchRole(Role.Trainer) }
                            } else {
                                null
                            },
                            onLogOut = logOut,
                            onAddToCalendar = addToCalendar,
                        ),
                        // A3 as a tab: the shell owns the header and the bottom navigation.
                        progressTab = {
                            ProgressRoute(
                                onOpenWorkout = { id -> navController.navigate(WorkoutSummaryDestination(id, readOnly = true)) },
                                showHeader = false,
                            )
                        },
                        chatUnread = unread,
                    )
                },
            )
            workoutLoggingGraph(navController)
            composable<ClientMessagesDestination> {
                ClientMessages(
                    onBack = { navController.popBackStack() },
                    onOpenChat = { navController.navigate(ClientChatDestination(it)) },
                    onOpenTrainer = { navController.navigate(com.trainingapp.feature.guest.TrainerProfileDestination(it)) },
                )
            }
            composable<ClientChatDestination> { entry ->
                val client: CurrentClient = koinInject()
                val chosen = entry.toRoute<ClientChatDestination>().relationshipId
                ClientChat(chosen ?: client.trainerRelationshipId, pinned = chosen != null, onBack = { navController.popBackStack() })
            }
        }

        val pendingAssign = assignPlan
        if (pendingAssign != null && planAssigner != null) {
            PlanAssignDialog(
                plan = pendingAssign,
                clients = koinInject(),
                assigner = planAssigner,
                onDismiss = { assignPlan = null },
                onAssigned = { planName, clientName -> assignPlan = null; assignedMessage = planName to clientName },
            )
        }
        assignedMessage?.let { (planName, clientName) ->
            AlertDialog(
                onDismissRequest = { assignedMessage = null },
                title = { Text(stringResource(Res.string.assign_title)) },
                text = { Text(stringResource(Res.string.assign_done, planName, clientName), style = AppTheme.type.body) },
                confirmButton = { AppTextButton(stringResource(Res.string.understood), onClick = { assignedMessage = null }) },
            )
        }
        if (showAssignInfo) {
            AlertDialog(
                onDismissRequest = { showAssignInfo = false },
                title = { Text(stringResource(Res.string.assign_title)) },
                text = { Text(stringResource(Res.string.assign_message), style = AppTheme.type.body) },
                confirmButton = { AppTextButton(stringResource(Res.string.understood), onClick = { showAssignInfo = false }) },
            )
        }
    } }
    }
}
