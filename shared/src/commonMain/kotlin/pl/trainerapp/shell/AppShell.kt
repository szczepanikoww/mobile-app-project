package pl.trainerapp.shell

import org.jetbrains.compose.resources.StringResource
import pl.trainer.ui.PlatformBackHandler
import pl.trainerapp.core.designsystem.ScreenTopBar
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.trainer.resources.*
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.core.designsystem.Spacing
import pl.trainerapp.feature.chat.NoTechniqueVideoCard
import pl.trainerapp.feature.chat.TechniqueVideoCard
import pl.trainerapp.feature.chat.TechniqueVideoRoute
import pl.trainerapp.feature.chat.model.ChatRole
import pl.trainerapp.feature.chat.TrainerChatListRoute
import pl.trainerapp.feature.chat.TrainerChatRoute
import pl.trainerapp.feature.trainerprofile.TrainerProfileRoute
import pl.trainer.data.DemoTrainerId

enum class TrainerDestination(val label: StringResource, val icon: ImageVector) {
    Start(Res.string.shell_nav_start, Icons.Outlined.Dashboard),
    Calendar(Res.string.nav_calendar, Icons.Outlined.CalendarToday),
    Clients(Res.string.nav_clients, Icons.Outlined.Groups),
    Plans(Res.string.nav_plans, Icons.Outlined.FitnessCenter),
}

/** What the trainer views open in the shell: the conversations, one conversation and the profile. */
class TrainerShellLinks(
    val onOpenChats: () -> Unit,
    val onOpenChat: (clientId: String) -> Unit,
    val onOpenProfile: () -> Unit,
)

/**
 * Trainer shell (docs/plans/trainer-redesign.md): four tabs (Start, Calendar, Trainees, Plans), the plus and the
 * full-screen views that open above the tabs (chat, profile, technique video). Start, Calendar and Trainees are the
 * [trainerFeature]; Plans is the app's [plansTab]. Feature screens render only their content.
 */
@Composable
fun TrainerShell(
    repositories: TrainerRepositories,
    trainerFeature: (@Composable (TrainerDestination, Int, (Boolean) -> Unit, TrainerShellLinks) -> Unit)? = null,
    plansTab: (@Composable () -> Unit)? = null,
    onLogOut: (() -> Unit)? = null,
    /** The technique-video player (A5); the app supplies it from the media feature. */
    videoCard: TechniqueVideoCard = NoTechniqueVideoCard,
    /** The profile's extra sections (gyms, working hours), supplied by the app. */
    profileExtras: (@Composable () -> Unit)? = null,
) {
    var featureIsMain by remember { mutableStateOf(true) }
    var createRequest by remember { mutableStateOf(0) }
    var selected by rememberSaveable { mutableStateOf(TrainerDestination.Start) }
    var routeKeys by rememberSaveable { mutableStateOf<List<String>>(arrayListOf()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notAvailable = stringResource(Res.string.not_available_yet)
    val showNotAvailable: () -> Unit = { scope.launch { snackbarHostState.showSnackbar(notAvailable) } }
    val videoReviewed = stringResource(Res.string.video_reviewed_snackbar)
    // "Change profile photo" (trainer profile): its progress as a snackbar.
    val photoUpload = pl.trainer.ui.LocalProfilePhotoChange.current?.state
    val photoMessages = mapOf(
        pl.trainer.ui.PhotoUploadState.Uploading to stringResource(Res.string.profile_photo_uploading),
        pl.trainer.ui.PhotoUploadState.Saved to stringResource(Res.string.profile_photo_saved),
        pl.trainer.ui.PhotoUploadState.Failed to stringResource(Res.string.profile_photo_failed),
    )
    LaunchedEffect(photoUpload) { photoMessages[photoUpload]?.let { snackbarHostState.showSnackbar(it) } }

    val push: (TrainerRoute) -> Unit = { routeKeys = ArrayList(routeKeys + it.key) }
    val pop: () -> Unit = { routeKeys = ArrayList(routeKeys.dropLast(1)) }
    val route = routeKeys.lastOrNull()?.let(TrainerRoute::fromKey)
    val showingTrainerFeature = trainerFeature != null && selected != TrainerDestination.Plans
    val links = remember {
        TrainerShellLinks(onOpenChats = { push(TrainerRoute.Chats) }, onOpenChat = { push(TrainerRoute.Chat(it)) }, onOpenProfile = { push(TrainerRoute.Profile) })
    }

    PlatformBackHandler(enabled = route != null, onBack = pop)

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                // The trainer feature draws its own bar (greeting on Start, back on details); Plans uses this one.
                if (!showingTrainerFeature) {
                    TrainerTopBar(
                        title = stringResource(selected.label),
                        onMessages = links.onOpenChats,
                        onProfile = links.onOpenProfile,
                    )
                }
            },
            bottomBar = {
                if (!showingTrainerFeature || featureIsMain) TrainerBottomBar(selected, onSelect = { selected = it; createRequest = 0 }, onCreate = {
                    if (trainerFeature != null) {
                        // The plus does the same on every tab; from Plans it opens over the calendar.
                        if (!showingTrainerFeature) selected = TrainerDestination.Calendar
                        createRequest++
                    } else showNotAvailable()
                })
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                when {
                    selected == TrainerDestination.Plans -> plansTab?.invoke() ?: NotAvailable()
                    trainerFeature != null -> trainerFeature(selected, createRequest, { featureIsMain = it }, links)
                    else -> NotAvailable()
                }
            }
        }

        // Full-screen views sit above the tabs, which stay composed underneath so their scroll position survives.
        if (route != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                    when (route) {
                        TrainerRoute.Chats -> TrainerChatListRoute(
                            repository = repositories.chats,
                            onBack = pop,
                            onOpenChat = { push(TrainerRoute.Chat(it)) },
                        )
                        is TrainerRoute.Chat -> TrainerChatRoute(
                            route.clientId,
                            repositories.chats,
                            onBack = pop,
                            mediaSource = repositories.chatMedia,
                            onOpenVideo = { push(TrainerRoute.TechniqueVideo(it)) },
                        )
                        is TrainerRoute.TechniqueVideo -> TechniqueVideoRoute(
                            mediaId = route.mediaId,
                            viewer = ChatRole.Trainer,
                            store = repositories.chatStore,
                            chats = repositories.chats,
                            mediaSource = repositories.chatMedia,
                            onBack = pop,
                            onReviewed = {
                                scope.launch { snackbarHostState.showSnackbar(videoReviewed) }
                            },
                            videoCard = videoCard,
                        )
                        TrainerRoute.Profile -> TrainerProfileRoute(
                            repository = repositories.profile,
                            onBack = pop,
                            onUnavailable = showNotAvailable,
                            onLogOut = onLogOut ?: showNotAvailable,
                            extras = profileExtras,
                        )
                        // Removed in the redesign (notifications, check-in, cancellation policy): an old saved key closes.
                        else -> LaunchedEffect(route) { pop() }
                    }
                }
                SnackbarHost(
                    snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                )
            }
        }
    }
}

@Composable
private fun NotAvailable() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(Res.string.not_available_yet), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrainerTopBar(title: String, onMessages: () -> Unit, onProfile: () -> Unit) {
    ScreenTopBar(
        actions = {
            IconButton(onClick = onMessages) {
                Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = stringResource(Res.string.action_messages))
            }
            val profileLabel = stringResource(Res.string.action_profile)
            IconButton(onClick = onProfile, modifier = Modifier.semantics { contentDescription = profileLabel }) {
                pl.trainer.ui.Portrait("", pl.trainer.ui.MyPhoto, size = 36.dp)
            }
        },
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    }
}


@Composable
private fun TrainerBottomBar(
    selected: TrainerDestination,
    onSelect: (TrainerDestination) -> Unit,
    onCreate: () -> Unit,
) {
    pl.trainer.ui.SharedBottomBar(
        items = TrainerDestination.entries.map { destination ->
            pl.trainer.ui.SharedNavItem(stringResource(destination.label), destination.icon, destination == selected) { onSelect(destination) }
        },
        createLabel = stringResource(Res.string.action_create),
        onCreate = onCreate,
    )
}
