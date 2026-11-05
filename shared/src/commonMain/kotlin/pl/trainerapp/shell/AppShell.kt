package pl.trainerapp.shell

import org.jetbrains.compose.resources.StringResource
import pl.trainer.ui.PlatformBackHandler
import pl.trainerapp.core.designsystem.ScreenTopBar
import androidx.compose.material.icons.outlined.AccountCircle
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import pl.trainer.presentation.Attendance
import pl.trainer.ui.CheckInRoute
import pl.trainerapp.feature.chat.NoTechniqueVideoCard
import pl.trainerapp.feature.chat.TechniqueVideoCard
import pl.trainerapp.feature.chat.TechniqueVideoRoute
import pl.trainerapp.feature.chat.model.ChatRole
import pl.trainerapp.feature.chat.TrainerChatListRoute
import pl.trainerapp.feature.chat.TrainerChatRoute
import pl.trainerapp.feature.trainerfinance.TrainerFinanceRoute
import pl.trainerapp.feature.trainerhome.TrainerHomeDestination
import pl.trainerapp.feature.trainerhome.TrainerHomeRoute
import pl.trainerapp.feature.trainernotifications.TrainerNotificationDetailRoute
import pl.trainerapp.feature.trainernotifications.TrainerNotificationListRoute
import pl.trainerapp.feature.trainernotifications.model.NotificationTarget
import pl.trainerapp.feature.trainerprofile.TrainerProfileRoute

enum class TrainerDestination(val label: StringResource, val icon: ImageVector) {
    Start(Res.string.shell_nav_start, Icons.Outlined.Dashboard),
    Calendar(Res.string.nav_calendar, Icons.Outlined.CalendarToday),
    Clients(Res.string.nav_clients, Icons.Outlined.Groups),
    Finance(Res.string.shell_nav_finance, Icons.Outlined.Payments),
}

private val TopBarHeight = 64.dp
private val NavBarHeight = 68.dp
private val FabSize = 56.dp
private val FabOverhang = 24.dp

/**
 * Trainer shell: owns the top app bar, bottom navigation and snackbars, plus the stack of full-screen
 * views (messages, notifications, profile) that open above the tabs. Feature screens render only their content.
 */
@Composable
fun TrainerShell(
    repositories: TrainerRepositories,
    trainerFeature: (@Composable (TrainerDestination, Int, (Boolean) -> Unit) -> Unit)? = null,
    onLogOut: (() -> Unit)? = null,
    /** The technique-video player (A5); the app supplies it from the media feature. */
    videoCard: TechniqueVideoCard = NoTechniqueVideoCard,
) {
    var featureIsMain by remember { mutableStateOf(true) }
    var createRequest by remember { mutableStateOf(0) }
    var selected by rememberSaveable { mutableStateOf(TrainerDestination.Start) }
    var routeKeys by rememberSaveable { mutableStateOf<List<String>>(arrayListOf()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notAvailable = stringResource(Res.string.not_available_yet)
    val showNotAvailable: () -> Unit = { scope.launch { snackbarHostState.showSnackbar(notAvailable) } }
    val attendanceRecorded = stringResource(Res.string.check_in_recorded_present)
    val noShowRecorded = stringResource(Res.string.check_in_recorded_no_show)
    val videoReviewed = stringResource(Res.string.video_reviewed_snackbar)
    var homeRefresh by remember { mutableStateOf(0) }

    val push: (TrainerRoute) -> Unit = { routeKeys = ArrayList(routeKeys + it.key) }
    val pop: () -> Unit = { routeKeys = ArrayList(routeKeys.dropLast(1)) }
    val route = routeKeys.lastOrNull()?.let(TrainerRoute::fromKey)
    val showingTrainerFeature = trainerFeature != null && selected in listOf(TrainerDestination.Calendar, TrainerDestination.Clients)

    PlatformBackHandler(enabled = route != null, onBack = pop)

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                // The home screen has its own header (profile, messages, notifications), so it gets no shell bar.
                if (selected != TrainerDestination.Start && !showingTrainerFeature) {
                    TrainerTopBar(
                        title = stringResource(selected.label),
                        onNotifications = { push(TrainerRoute.Notifications) },
                        onProfile = { push(TrainerRoute.Profile) },
                    )
                }
            },
            bottomBar = {
                if (!showingTrainerFeature || featureIsMain) TrainerBottomBar(selected, onSelect = { selected = it; createRequest = 0 }, onCreate = {
                    if (trainerFeature != null) {
                        if (!showingTrainerFeature) selected = TrainerDestination.Calendar
                        createRequest++
                    } else showNotAvailable()
                })
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                when (selected) {
                    TrainerDestination.Start -> TrainerHomeRoute(
                        repository = repositories.home,
                        refreshKey = homeRefresh,
                        onNavigate = { destination ->
                            when (destination) {
                                TrainerHomeDestination.Chats -> push(TrainerRoute.Chats)
                                is TrainerHomeDestination.Conversation -> push(TrainerRoute.Chat(destination.clientId))
                                TrainerHomeDestination.Notifications -> push(TrainerRoute.Notifications)
                                TrainerHomeDestination.Profile -> push(TrainerRoute.Profile)
                                // Q8: the home's demo session ids may not match the calendar's; see the feature plan.
                                is TrainerHomeDestination.CheckIn -> push(TrainerRoute.CheckIn(destination.sessionId))
                                is TrainerHomeDestination.TechniqueVideoReview -> push(TrainerRoute.TechniqueVideo(destination.mediaId))
                                else -> showNotAvailable() // destinations owned by other features do not exist yet
                            }
                        },
                    )
                    TrainerDestination.Finance -> TrainerFinanceRoute(
                        repository = repositories.finance,
                        onNavigate = { showNotAvailable() }, // payment details and payouts are owned by other features
                    )
                    else -> if (trainerFeature != null) {
                        trainerFeature(selected, createRequest) { featureIsMain = it }
                    } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(Res.string.not_available_yet),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                        is TrainerRoute.CheckIn -> CheckInRoute(
                            sessionId = route.sessionId,
                            repository = repositories.calendar,
                            onBack = pop,
                            onRecorded = { attendance ->
                                pop()
                                homeRefresh++
                                scope.launch {
                                    snackbarHostState.showSnackbar(if (attendance == Attendance.Present) attendanceRecorded else noShowRecorded)
                                }
                            },
                        )
                        is TrainerRoute.TechniqueVideo -> TechniqueVideoRoute(
                            mediaId = route.mediaId,
                            viewer = ChatRole.Trainer,
                            store = repositories.chatStore,
                            chats = repositories.chats,
                            mediaSource = repositories.chatMedia,
                            onBack = pop,
                            onReviewed = {
                                homeRefresh++
                                scope.launch { snackbarHostState.showSnackbar(videoReviewed) }
                            },
                            videoCard = videoCard,
                        )
                        TrainerRoute.Notifications -> TrainerNotificationListRoute(
                            repository = repositories.notifications,
                            onBack = pop,
                            onOpenNotification = { push(TrainerRoute.Notification(it)) },
                        )
                        is TrainerRoute.Notification -> TrainerNotificationDetailRoute(
                            id = route.id,
                            repository = repositories.notifications,
                            onBack = pop,
                            onOpenTarget = { target ->
                                when (target) {
                                    is NotificationTarget.Chat -> push(TrainerRoute.Chat(target.clientId))
                                    NotificationTarget.Finance -> {
                                        routeKeys = arrayListOf()
                                        selected = TrainerDestination.Finance
                                    }
                                    is NotificationTarget.Session -> showNotAvailable()
                                    NotificationTarget.None -> Unit
                                }
                            },
                        )
                        TrainerRoute.Profile -> TrainerProfileRoute(
                            repository = repositories.profile,
                            onBack = pop,
                            onUnavailable = showNotAvailable,
                            onLogOut = onLogOut ?: showNotAvailable,
                        )
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
private fun TrainerTopBar(title: String, onNotifications: () -> Unit, onProfile: () -> Unit) {
    ScreenTopBar(
        actions = {
            IconButton(onClick = onNotifications) {
                Icon(Icons.Outlined.Notifications, contentDescription = stringResource(Res.string.action_notifications))
            }
            IconButton(onClick = onProfile) {
                Icon(Icons.Outlined.AccountCircle, contentDescription = stringResource(Res.string.action_profile))
            }
        },
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
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
