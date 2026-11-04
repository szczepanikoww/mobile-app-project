package com.trainingapp.feature.guest

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trainingapp.feature.guest.auth.AuthActions
import com.trainingapp.feature.guest.auth.AuthMode
import com.trainingapp.feature.guest.auth.AuthScreen
import com.trainingapp.feature.guest.auth.AuthViewModel
import com.trainingapp.feature.guest.auth.ClientHomePlaceholder
import com.trainingapp.feature.guest.gymdetail.GymDetailActions
import com.trainingapp.feature.guest.gymdetail.GymDetailScreen
import com.trainingapp.feature.guest.gymdetail.GymDetailViewModel
import com.trainingapp.feature.guest.model.AuthRepository
import com.trainingapp.feature.guest.model.AuthSession
import com.trainingapp.feature.guest.model.GuestIntent
import com.trainingapp.feature.guest.platform.WelcomePreferences
import com.trainingapp.feature.guest.platform.rememberAutoAdvanceAllowed
import com.trainingapp.feature.guest.trainerprofile.TrainerProfileActions
import com.trainingapp.feature.guest.trainerprofile.TrainerProfileScreen
import com.trainingapp.feature.guest.trainerprofile.TrainerProfileViewModel
import com.trainingapp.feature.guest.trainerprofile.VideoPlayerSlot
import com.trainingapp.feature.guest.welcome.WelcomeScreen
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Not the start screen any more (the app opens on Auth); kept for a possible onboarding entry. */
@Serializable
data object GuestWelcomeDestination

/** The guest shell: Start, Trenerzy and Siłownie tabs. */
@Serializable
data object GuestHomeDestination

@Serializable
data class TrainerProfileDestination(val trainerId: String)

@Serializable
data class GymDetailDestination(val gymId: String)

/**
 * Log in / register. The pending intent is flattened into plain arguments so it survives process death and needs no
 * custom navigation type.
 */
@Serializable
data class AuthDestination(
    val register: Boolean = false,
    val intentTrainerId: String? = null,
    val intentGymId: String? = null,
    val hasIntent: Boolean = false,
) {
    val mode: AuthMode get() = if (register) AuthMode.Register else AuthMode.LogIn
    val pendingIntent: GuestIntent? get() = if (hasIntent) GuestIntent.RequestSession(intentTrainerId, intentGymId) else null

    companion object {
        fun of(mode: AuthMode, intent: GuestIntent? = null) = AuthDestination(
            register = mode == AuthMode.Register,
            intentTrainerId = (intent as? GuestIntent.RequestSession)?.trainerId,
            intentGymId = (intent as? GuestIntent.RequestSession)?.gymId,
            hasIntent = intent != null,
        )
    }
}

/** D12: where a signed-in client lands until the client shell exists. */
@Serializable
data class ClientHomeDestination(val intentTrainerId: String? = null, val intentGymId: String? = null, val hasIntent: Boolean = false) {
    val pendingIntent: GuestIntent? get() = if (hasIntent) GuestIntent.RequestSession(intentTrainerId, intentGymId) else null

    companion object {
        fun of(intent: GuestIntent?) = ClientHomeDestination(
            intentTrainerId = (intent as? GuestIntent.RequestSession)?.trainerId,
            intentGymId = (intent as? GuestIntent.RequestSession)?.gymId,
            hasIntent = intent != null,
        )
    }
}

/**
 * The guest destinations, added to the app's NavHost next to the workout builder graph.
 *
 * [onSignedIn] opens the role shell; the app clears the back stack so Back never returns to Auth or the guest
 * screens. [onLogOut] returns a signed-in client to the guest shell. [videoPlayer] plays READY public media (D9).
 */
fun NavGraphBuilder.guestGraph(
    navController: NavController,
    onSignedIn: (AuthSession, GuestIntent?) -> Unit,
    onLogOut: () -> Unit,
    videoPlayer: VideoPlayerSlot,
) {
    val openAuth: (AuthMode, GuestIntent?) -> Unit = { mode, intent -> navController.navigate(AuthDestination.of(mode, intent)) }
    val browse: () -> Unit = {
        if (!navController.popBackStack<GuestHomeDestination>(inclusive = false)) {
            navController.navigate(GuestHomeDestination) { popUpTo(navController.graph.id) { inclusive = true } }
        }
    }

    composable<GuestWelcomeDestination> {
        val preferences: WelcomePreferences = koinInject()
        WelcomeScreen(
            onRegister = { preferences.markWelcomeSeen(); openAuth(AuthMode.Register, null) },
            onLogIn = { preferences.markWelcomeSeen(); openAuth(AuthMode.LogIn, null) },
            onBrowse = {
                preferences.markWelcomeSeen()
                navController.navigate(GuestHomeDestination) { popUpTo<GuestWelcomeDestination> { inclusive = true } }
            },
            autoAdvance = rememberAutoAdvanceAllowed(),
        )
    }
    composable<GuestHomeDestination> {
        val viewModel: GuestCatalogViewModel = koinViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        GuestShell(
            viewModel = viewModel,
            state = state,
            actions = GuestShellActions(
                onOpenTrainer = { navController.navigate(TrainerProfileDestination(it)) },
                onOpenGym = { navController.navigate(GymDetailDestination(it)) },
                onLogIn = { openAuth(AuthMode.LogIn, null) },
                onRegister = { openAuth(AuthMode.Register, null) },
            ),
        )
    }
    composable<TrainerProfileDestination> { entry ->
        val destination = entry.toRoute<TrainerProfileDestination>()
        val viewModel: TrainerProfileViewModel = koinViewModel { parametersOf(destination.trainerId) }
        val state by viewModel.state.collectAsStateWithLifecycle()
        val intent = GuestIntent.RequestSession(trainerId = destination.trainerId)
        TrainerProfileScreen(
            state = state,
            actions = TrainerProfileActions(
                onBack = { navController.popBackStack() },
                onOpenGym = { navController.navigate(GymDetailDestination(it)) },
                onRequestSession = viewModel::requestSession,
                onDismissLoginSheet = viewModel::dismissLoginSheet,
                onShowPolicy = viewModel::showPolicy,
                onRegister = { viewModel.dismissLoginSheet(); openAuth(AuthMode.Register, intent) },
                onLogIn = { viewModel.dismissLoginSheet(); openAuth(AuthMode.LogIn, intent) },
                onRetry = viewModel::retry,
            ),
            videoPlayer = videoPlayer,
        )
    }
    composable<GymDetailDestination> { entry ->
        val destination = entry.toRoute<GymDetailDestination>()
        val viewModel: GymDetailViewModel = koinViewModel { parametersOf(destination.gymId) }
        val state by viewModel.state.collectAsStateWithLifecycle()
        val intent = GuestIntent.RequestSession(gymId = destination.gymId)
        GymDetailScreen(
            state = state,
            actions = GymDetailActions(
                onBack = { navController.popBackStack() },
                onOpenTrainer = { navController.navigate(TrainerProfileDestination(it)) },
                onRequestSession = viewModel::requestSession,
                onDismissLoginSheet = viewModel::dismissLoginSheet,
                onRegister = { viewModel.dismissLoginSheet(); openAuth(AuthMode.Register, intent) },
                onLogIn = { viewModel.dismissLoginSheet(); openAuth(AuthMode.LogIn, intent) },
                onRetry = viewModel::retry,
            ),
        )
    }
    composable<AuthDestination> { entry ->
        val destination = entry.toRoute<AuthDestination>()
        // Auth is the app's first screen for signed-out users; there it has no back arrow.
        val onBack: (() -> Unit)? = if (navController.previousBackStackEntry != null) ({ navController.popBackStack() }) else null
        AuthRoute(destination, onBack = onBack, onBrowse = browse, onSignedIn = onSignedIn)
    }
    composable<ClientHomeDestination> { entry ->
        val destination = entry.toRoute<ClientHomeDestination>()
        val auth: AuthRepository = koinInject()
        val session by auth.session.collectAsStateWithLifecycle()
        ClientHomePlaceholder(session, destination.pendingIntent, onLogOut = onLogOut)
    }
}

@Composable
private fun AuthRoute(
    destination: AuthDestination,
    onBack: (() -> Unit)?,
    onBrowse: () -> Unit,
    onSignedIn: (AuthSession, GuestIntent?) -> Unit,
) {
    val viewModel: AuthViewModel = koinViewModel { parametersOf(destination.mode) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.signedIn) {
        state.signedIn?.let { onSignedIn(it, destination.pendingIntent) }
    }
    AuthScreen(
        state = state,
        actions = AuthActions(
            onBack = onBack,
            onModeChange = viewModel::onModeChange,
            onNameChange = viewModel::onNameChange,
            onEmailChange = viewModel::onEmailChange,
            onPasswordChange = viewModel::onPasswordChange,
            onRoleChange = viewModel::onRoleChange,
            onTermsChange = viewModel::onTermsChange,
            onSubmit = viewModel::submit,
            onGoogle = viewModel::continueWithGoogle,
            onBrowse = onBrowse,
        ),
    )
}
