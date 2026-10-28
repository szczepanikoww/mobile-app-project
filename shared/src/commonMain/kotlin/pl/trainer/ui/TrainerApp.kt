package pl.trainer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.trainer.data.DemoTrainerRepository
import pl.trainer.domain.*
import pl.trainer.presentation.*
import pl.trainer.resources.*

@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

@Composable
fun TrainerApp(model: TrainerViewModel = viewModel { TrainerViewModel(DemoTrainerRepository()) }) {
    val state by model.state.collectAsState()
    TrainerTheme { TrainerAppContent(state, model) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrainerAppContent(state: TrainerUiState, model: TrainerViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val floatingElevation = FloatingActionButtonDefaults.elevation(
        defaultElevation = Layout.floatingElevation, pressedElevation = Layout.activeFloatingElevation,
        focusedElevation = Layout.floatingElevation, hoveredElevation = Layout.activeFloatingElevation,
    )
    var addMenu by remember { mutableStateOf(false) }
    var infoDialog by remember { mutableStateOf<String?>(null) }
    val savedMessage = tr(Res.string.saved)
    val main = state.route == Route.Main
    val screenState = rememberSaveableStateHolder()
    val screenKey = if (main) "main-${state.destination}" else state.route.toString()
    DisposableEffect(screenKey) {
        onDispose { screenState.removeState(screenKey) }
    }
    PlatformBackHandler(!main) { model.back() }
    LaunchedEffect(state.saved) {
        if (state.saved) { snackbar.showSnackbar(savedMessage); model.dismissFeedback() }
    }
    val title = tr(when (val route = state.route) {
        Route.Main -> if (state.destination == Destination.Calendar) Res.string.your_schedule else Res.string.your_clients
        is Route.SessionDetail -> if (state.data?.sessions?.find { it.id == route.id }?.needsAttention() == true) Res.string.request_detail else Res.string.session_detail
        is Route.ClientDetail -> Res.string.client_detail
        is Route.SessionEditor -> if (route.sessionId == null) Res.string.new_session else Res.string.propose_time
        is Route.NoteEditor -> Res.string.edit_notes
        is Route.ClientInformationEditor -> Res.string.edit_client_information
        Route.Availability -> Res.string.availability
    })
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                    .heightIn(min = Layout.headerHeight).padding(horizontal = if (main) Layout.gutter else Layout.small,
                    vertical = Layout.small), verticalAlignment = Alignment.CenterVertically) {
                    if (!main) IconButton(onClick = model::back, enabled = !state.submitting) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr(Res.string.back)) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Layout.tiny)) {
                        Text(title, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.clearAndSetSemantics { heading(); text = AnnotatedString(title) })
                    }
                }

            }
        },
        bottomBar = {
            if (main) Surface(shape = Layout.navigation, color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                NavigationBar(modifier = Modifier.clip(Layout.navigation), containerColor = Color.Transparent, tonalElevation = 0.dp) {
                    Destination.entries.forEach { destination ->
                        NavigationBarItem(selected = state.destination == destination, onClick = { model.destination(destination) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent,
                                selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary),
                            icon = { Icon(if (destination == Destination.Calendar) Icons.Outlined.CalendarMonth else Icons.Outlined.PeopleOutline, null) },
                            label = { Text(tr(if (destination == Destination.Calendar) Res.string.calendar else Res.string.trainees)) })
                    }
                }
            }
        },
        floatingActionButton = {
            if (main && state.destination == Destination.Calendar && state.data != null && !state.loading && state.failure == null) {
                if (LocalDensity.current.fontScale >= 1.5f) FloatingActionButton(onClick = { addMenu = true },
                    shape = Layout.pill,
                    elevation = floatingElevation,
                    containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Icon(Icons.Outlined.Add, tr(Res.string.add))
                } else ExtendedFloatingActionButton(onClick = { addMenu = true },
                    shape = Layout.pill,
                    elevation = floatingElevation,
                    containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Outlined.Add, null) }, text = { Text(tr(Res.string.add)) })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxHeight().widthIn(max = Layout.contentWidth).fillMaxWidth()) {
                if (state.offline) InfoBanner(tr(Res.string.offline_body), Icons.Outlined.WifiOff, modifier = Modifier.padding(Layout.gutter))
                state.actionFailure?.let { failure ->
                    Column(Modifier.padding(horizontal = Layout.gutter, vertical = Layout.small).semantics { liveRegion = LiveRegionMode.Polite }) {
                        InfoBanner(failureText(failure), error = true)
                        if (failure == Failure.Conflict) TextButton(onClick = model::reload) { Text(tr(Res.string.refresh)) }
                    }
                }
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Layout.padding)) {
                            CircularProgressIndicator()
                            Text(tr(Res.string.loading_data), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    state.failure != null -> Box(Modifier.padding(Layout.gutter)) {
                        EmptyContent(if (state.failure == Failure.Denied) Icons.Outlined.Lock else Icons.Outlined.CloudOff,
                            tr(if (state.failure == Failure.Denied) Res.string.access_title else Res.string.load_error_title),
                            failureText(state.failure), tr(Res.string.retry), model::reload)
                    }
                    state.data != null -> screenState.SaveableStateProvider(screenKey) {
                        when (val route = state.route) {
                            Route.Main -> if (state.destination == Destination.Calendar) CalendarScreen(state, model::date, model::mode,
                                { model.navigate(Route.Availability) }, { model.navigate(Route.SessionDetail(it)) }, { model.navigate(Route.SessionEditor()) })
                            else ClientsScreen(state, model::search, model::filter, model::clientTab, { model.navigate(Route.ClientDetail(it)) }, { infoDialog = "invite" })
                            is Route.SessionDetail -> state.data.sessions.find { it.id == route.id }?.let { session ->
                                SessionDetailScreen(state, session,
                                    onClient = { model.navigate(Route.ClientDetail(session.clientId)) }, onEdit = { model.navigate(Route.SessionEditor(sessionId = session.id)) },
                                    onAccept = { model.accept(session) }, onReject = { model.reject(session) }, onCancel = { model.cancel(session) }, onComplete = { model.complete(session) })
                            }
                            is Route.ClientDetail -> state.data.clients.find { it.id == route.id }?.let { client ->
                                ClientDetailScreen(state, client, onSession = { model.navigate(Route.SessionDetail(it)) },
                                    onNewSession = { model.navigate(Route.SessionEditor(clientId = client.id)) }, onNotes = { model.navigate(Route.NoteEditor(client.id)) },
                                    onInformation = { model.navigate(Route.ClientInformationEditor(client.id)) }, onRetryInformation = { model.loadClientInformation(client.id) }, onIntegration = { infoDialog = "integration" })
                            }
                            is Route.SessionEditor -> SessionEditorScreen(state, route, model::saveTimeForEditor, model::clearActionFailure)
                            is Route.ClientInformationEditor -> ClientInformationEditor(state, route.clientId, onRetry = { model.loadClientInformation(route.clientId) },
                                onSave = { birth, weight, height, goal, injuries, limitations -> model.saveClientInformation(route.clientId, birth, weight, height, goal, injuries, limitations) })
                            is Route.NoteEditor -> NoteEditorScreen(state, route.clientId) { model.saveNote(route.clientId, it) }
                            Route.Availability -> AvailabilityScreen(state,
                                onSave = { date, start, end -> model.saveTime(date, start, end, null, "", block = true) },
                                onRemove = model::removeBlock, onInput = model::clearActionFailure)
                        }
                    }
                }
            }
        }
    }
    if (addMenu) ModalBottomSheet(onDismissRequest = { addMenu = false }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Layout.gutter).padding(bottom = Layout.large), verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
            SectionTitle(tr(Res.string.add_menu_title))
            ActionRow(Icons.Outlined.EventAvailable, tr(Res.string.new_session), onClick = {
                addMenu = false; model.navigate(Route.SessionEditor())
            })
            ActionRow(Icons.Outlined.Block, tr(Res.string.availability), tr(Res.string.availability_action_hint), onClick = {
                addMenu = false; model.navigate(Route.Availability)
            })
        }
    }
    infoDialog?.let { kind -> AlertDialog(onDismissRequest = { infoDialog = null },
        title = { Text(tr(if (kind == "invite") Res.string.invite_title else Res.string.integration_title)) },
        text = { Text(tr(if (kind == "invite") Res.string.invite_body else Res.string.integration_body)) },
        confirmButton = { TextButton(onClick = { infoDialog = null }) { Text(tr(Res.string.understood)) } }) }
}

private fun TrainerViewModel.saveTimeForEditor(date: kotlinx.datetime.LocalDate, start: String, end: String, client: String?, location: String, session: Session?) =
    saveTime(date, start, end, client, location, session)
