package pl.trainerapp.shell

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.trainerapp.R
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.core.designsystem.Spacing
import pl.trainerapp.feature.trainerchats.TrainerChatListRoute
import pl.trainerapp.feature.trainerchats.TrainerChatRoute
import pl.trainerapp.feature.trainerfinance.TrainerFinanceRoute
import pl.trainerapp.feature.trainerhome.TrainerHomeDestination
import pl.trainerapp.feature.trainerhome.TrainerHomeRoute
import pl.trainerapp.feature.trainernotifications.TrainerNotificationDetailRoute
import pl.trainerapp.feature.trainernotifications.TrainerNotificationListRoute
import pl.trainerapp.feature.trainernotifications.model.NotificationTarget
import pl.trainerapp.feature.trainerprofile.TrainerProfileRoute

enum class TrainerDestination(val label: Int, val icon: ImageVector) {
    Start(R.string.nav_start, Icons.Outlined.Dashboard),
    Calendar(R.string.nav_calendar, Icons.Outlined.CalendarToday),
    Clients(R.string.nav_clients, Icons.Outlined.Groups),
    Finance(R.string.nav_finance, Icons.Outlined.Payments),
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
) {
    var featureIsMain by remember { mutableStateOf(true) }
    var createRequest by remember { mutableStateOf(0) }
    var selected by rememberSaveable { mutableStateOf(TrainerDestination.Start) }
    var routeKeys by rememberSaveable { mutableStateOf<List<String>>(arrayListOf()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notAvailable = stringResource(R.string.not_available_yet)
    val showNotAvailable: () -> Unit = { scope.launch { snackbarHostState.showSnackbar(notAvailable) } }

    val push: (TrainerRoute) -> Unit = { routeKeys = ArrayList(routeKeys + it.key) }
    val pop: () -> Unit = { routeKeys = ArrayList(routeKeys.dropLast(1)) }
    val route = routeKeys.lastOrNull()?.let(TrainerRoute::fromKey)
    val showingTrainerFeature = trainerFeature != null && selected in listOf(TrainerDestination.Calendar, TrainerDestination.Clients)

    BackHandler(enabled = route != null, onBack = pop)

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
                        onNavigate = { destination ->
                            when (destination) {
                                TrainerHomeDestination.Chats -> push(TrainerRoute.Chats)
                                is TrainerHomeDestination.Conversation -> push(TrainerRoute.Chat(destination.clientId))
                                TrainerHomeDestination.Notifications -> push(TrainerRoute.Notifications)
                                TrainerHomeDestination.Profile -> push(TrainerRoute.Profile)
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
                            stringResource(R.string.not_available_yet),
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
                        is TrainerRoute.Chat -> TrainerChatRoute(route.clientId, repositories.chats, onBack = pop)
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
    val scheme = MaterialTheme.colorScheme
    val colors = AppTheme.colors
    val profileDescription = stringResource(R.string.action_profile)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.92f))
            .statusBarsPadding()
            .height(TopBarHeight)
            .padding(horizontal = Spacing.screenGutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(colors.softBrand, CircleShape)
                    .border(1.dp, colors.brandBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.FitnessCenter, contentDescription = null, tint = colors.brand, modifier = Modifier.size(20.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            IconButton(onClick = onNotifications, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Outlined.Notifications,
                    contentDescription = stringResource(R.string.action_notifications),
                    tint = colors.textMuted,
                    modifier = Modifier.size(22.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(colors.brand, CircleShape)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onProfile)
                    .semantics { contentDescription = profileDescription },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
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
        createLabel = stringResource(R.string.action_create),
        onCreate = onCreate,
    )
}
