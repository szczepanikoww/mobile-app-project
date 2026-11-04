package com.trainingapp.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.trainingapp.app.resources.Res
import com.trainingapp.app.resources.assign_message
import com.trainingapp.app.resources.assign_title
import com.trainingapp.app.resources.understood
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.feature.guest.ClientHomeDestination
import com.trainingapp.feature.guest.GuestHomeDestination
import com.trainingapp.feature.guest.GuestWelcomeDestination
import com.trainingapp.feature.guest.guestGraph
import com.trainingapp.feature.guest.model.AuthRepository
import com.trainingapp.feature.guest.model.AuthSession
import com.trainingapp.feature.guest.model.Role
import com.trainingapp.feature.guest.platform.WelcomePreferences
import com.trainingapp.feature.workoutbuilder.media.DemoVideoPlayer
import com.trainingapp.feature.workoutbuilder.common.ExerciseBrowserDestination
import com.trainingapp.feature.workoutbuilder.common.PlanEditorDestination
import com.trainingapp.feature.workoutbuilder.common.PlanLibraryDestination
import com.trainingapp.feature.workoutbuilder.workoutBuilderGraph
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** The trainer's top-level destinations (bottom navigation). Detail screens are pushed over it. */
@Serializable
data object TrainerHomeDestination

/** Where the app opens: the role shell for a signed-in session, otherwise Welcome (first launch) or the guest shell. */
internal fun startDestinationFor(session: AuthSession?, welcomeSeen: Boolean): Any = when {
    session == null -> if (welcomeSeen) GuestHomeDestination else GuestWelcomeDestination
    session.activeRole == Role.Trainer -> TrainerHomeDestination
    else -> ClientHomeDestination()
}

/**
 * The app entry point shared by Android and the desktop preview. It starts in the role shell of the signed-in
 * session ([initialSession] lets tests and previews start signed in), otherwise in the guest views. Client and
 * trainer shells use the same theme.
 */
@Composable
fun App(
    initialSession: AuthSession? = null,
    host: (@Composable (WorkoutNavigationActions) -> Unit)? = null,
) {
    AppTheme {
        val navController = rememberNavController()
        val auth: AuthRepository = koinInject()
        val welcome: WelcomePreferences = koinInject()
        val scope = rememberCoroutineScope()
        var showAssignInfo by rememberSaveable { mutableStateOf(false) }
        val startDestination = remember { startDestinationFor(initialSession ?: auth.session.value, welcome.welcomeSeen()) }

        // After sign-in or sign-out the whole back stack is replaced, so Back never returns to Auth or the guest views.
        val replaceStack: (Any) -> Unit = { destination ->
            navController.navigate(destination) { popUpTo(navController.graph.id) { inclusive = true } }
        }
        val logOut: () -> Unit = {
            scope.launch { auth.logOut() }
            welcome.markWelcomeSeen()
            replaceStack(GuestHomeDestination)
        }

        NavHost(navController = navController, startDestination = startDestination) {
            composable<TrainerHomeDestination> {
                val actions = WorkoutNavigationActions(
                    onNewPlan = { navController.navigate(PlanEditorDestination()) },
                    onOpenPlans = { navController.navigate(PlanLibraryDestination) },
                    onOpenExercises = { navController.navigate(ExerciseBrowserDestination()) },
                    onLogOut = logOut,
                )
                if (host != null) host(actions) else TrainerShell(actions)
            }
            workoutBuilderGraph(
                navController = navController,
                // Integration point: the assignment flow belongs to the relationship-scoped assignment API, which is not connected yet.
                onAssignToClient = { _, _ -> showAssignInfo = true },
            )
            guestGraph(
                navController = navController,
                // The pending intent reaches the role shell; the booking request screen will consume it (not built yet).
                onSignedIn = { session, intent ->
                    replaceStack(if (session.activeRole == Role.Trainer) TrainerHomeDestination else ClientHomeDestination.of(intent))
                },
                onLogOut = logOut,
                videoPlayer = { url, modifier -> DemoVideoPlayer(url, modifier) },
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
    }
}
