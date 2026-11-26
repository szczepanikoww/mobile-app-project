package pl.trainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.trainer.data.DemoTrainerRepository
import pl.trainer.domain.*
import pl.trainer.presentation.*
import pl.trainer.resources.*

@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/**
 * The trainer's Start, Calendar and Clients tabs with their detail screens and the plus menu. Plans, the chat and
 * the profile belong to the app around it and are reached through [integrations]. Without the shell ([showNavigation])
 * it draws its own bottom bar, for previews and the desktop runner.
 */
@Composable
fun TrainerApp(
    model: TrainerViewModel = viewModel { TrainerViewModel(DemoTrainerRepository(), pl.trainer.data.DemoInviteLinks()) },
    showNavigation: Boolean = true,
    createRequest: Int = 0,
    onMainRouteChanged: (Boolean) -> Unit = {},
    integrations: TrainerIntegrations = TrainerIntegrations(),
) {
    val state by model.state.collectAsState()
    LaunchedEffect(state.route) { onMainRouteChanged(state.route == Route.Main) }
    TrainerTheme { TrainerAppContent(state, model, showNavigation, createRequest, integrations) }
}

private enum class Sheet { Create, Invite }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrainerAppContent(state: TrainerUiState, model: TrainerViewModel, showNavigation: Boolean, createRequest: Int, integrations: TrainerIntegrations) {
    val snackbar = remember { SnackbarHostState() }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    LaunchedEffect(createRequest) { if (createRequest > 0 && state.data != null && !state.loading && state.failure == null && !state.submitting) sheet = Sheet.Create }
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
    val openInvite = { sheet = Sheet.Invite; model.loadInvite() }
    val title = tr(when (val route = state.route) {
        Route.Main -> when (state.destination) {
            Destination.Calendar -> Res.string.calendar
            Destination.Clients -> Res.string.trainees
            else -> state.destination.label()
        }
        is Route.SessionDetail -> if (state.data?.sessions?.find { it.id == route.id }?.needsAttention() == true) Res.string.request_detail else Res.string.session_detail
        is Route.RequestDetail -> Res.string.request_detail
        is Route.ClientDetail -> Res.string.client_detail
        is Route.SessionEditor -> if (route.sessionId == null) Res.string.new_session else Res.string.propose_time
        is Route.NoteEditor -> Res.string.edit_notes
        is Route.ClientInformationEditor -> Res.string.edit_client_information
        Route.Availability -> Res.string.block_time
        Route.Requests -> Res.string.start_requests
        Route.History -> Res.string.start_history
        Route.Payments -> Res.string.start_payments
    })
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TrainerTopBar(
                title = title,
                greeting = main && state.destination == Destination.Start,
                today = state.today,
                trainerName = integrations.trainerName,
                showBack = !main,
                backEnabled = !state.submitting,
                onBack = model::back,
                integrations = integrations,
            )
        },
        bottomBar = {
            if (main && showNavigation) TrainerBottomBar(state.destination, onSelect = model::destination,
                onCreate = { sheet = Sheet.Create },
                createEnabled = state.data != null && !state.loading && state.failure == null && !state.submitting)
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
                        TrainerRouteContent(state, model, integrations, openInvite)
                    }
                }
            }
        }
    }
    when (sheet) {
        Sheet.Create -> ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = MaterialTheme.colorScheme.background) {
            CreateMenu(
                onNewSession = { sheet = null; model.navigate(Route.SessionEditor()) },
                onBlockTime = { sheet = null; model.navigate(Route.Availability) },
                onInvite = openInvite,
            )
        }
        Sheet.Invite -> ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = MaterialTheme.colorScheme.background) {
            InviteSheet(state.invite, integrations.shareText, onRegenerate = { model.loadInvite(regenerate = true) }, onRetry = { model.loadInvite() })
        }
        null -> Unit
    }
}

@Composable
private fun TrainerRouteContent(state: TrainerUiState, model: TrainerViewModel, integrations: TrainerIntegrations, openInvite: () -> Unit) {
    val data = state.data ?: return
    when (val route = state.route) {
        Route.Main -> when (state.destination) {
            Destination.Start -> StartScreen(state, onSession = model::openSessionById, onRequests = { model.navigate(Route.Requests) },
                onPayments = { model.navigate(Route.Payments) }, onHistory = { model.navigate(Route.History) },
                onNewSession = { model.navigate(Route.SessionEditor()) })
            Destination.Calendar -> CalendarScreen(state, model::date, model::mode,
                { model.navigate(Route.Availability) }, { id -> model.openSessionById(id) }, { model.navigate(Route.SessionEditor()) },
                onAccept = model::accept, onReject = model::reject)
            Destination.Clients -> ClientsScreen(state, model::search, model::filter, model::clientTab,
                { model.navigate(Route.ClientDetail(it)) }, openInvite)
            // The app renders the Plans tab; this placeholder is only seen in the standalone runner.
            Destination.Plans -> Box(Modifier.fillMaxSize().padding(Layout.gutter), contentAlignment = Alignment.Center) {
                EmptyContent(Icons.Outlined.FitnessCenter, tr(Res.string.nav_plans), tr(Res.string.screen_integration_pending))
            }
        }
        Route.Requests -> RequestsScreen(state, onAccept = model::accept, onReject = model::reject, onOpen = model::openSessionById)
        Route.History -> HistoryScreen(state, onAttended = model::complete, onNoShow = model::noShow, onOpen = model::openSessionById)
        Route.Payments -> PaymentsScreen(state, onRetry = model::loadPayments)
        is Route.SessionDetail -> data.sessions.find { it.id == route.id }?.let { session ->
            SessionDetailScreen(state, session,
                onClient = { model.navigate(Route.ClientDetail(session.clientId)) }, onEdit = { model.navigate(Route.SessionEditor(sessionId = session.id)) },
                onAccept = { model.accept(session) }, onReject = { model.reject(session) }, onCancel = { model.cancel(session) }, onComplete = { model.complete(session) },
                onNoShow = { model.noShow(session) }, onSaveComment = { model.saveSessionComment(session.id, it) }, planSection = integrations.planSection)
        }
        is Route.RequestDetail -> data.sessions.find { it.id == route.id }?.let { session ->
            RequestDetailScreen(state, session,
                onRetry = { model.loadReview(session.id) },
                onClient = { model.navigate(Route.ClientDetail(session.clientId)) },
                onAccept = { waive, reason -> model.acceptRequest(session, waive, reason) },
                onProposeOther = { model.navigate(Route.SessionEditor(sessionId = session.id)) },
                onReject = { model.rejectRequest(session) })
        }
        is Route.ClientDetail -> data.clients.find { it.id == route.id }?.let { client ->
            ClientDetailScreen(state, client, onSession = { id -> model.openSessionById(id) },
                onNewSession = { model.navigate(Route.SessionEditor(clientId = client.id)) }, onNotes = { model.navigate(Route.NoteEditor(client.id)) },
                onInformation = { model.navigate(Route.ClientInformationEditor(client.id)) }, onRetryInformation = { model.loadClientInformation(client.id) },
                onIntegration = {},
                onChat = integrations.onOpenChat?.let { open -> { open(client.id) } },
                onArchive = { model.setArchived(client.id, it) },
                planSection = integrations.planSection)
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

/**
 * The bar above every trainer view: on Start the greeting (the trainer's photo opens the profile), elsewhere the
 * title; the chat and the profile on every main tab, back on detail screens. Rendered once per screen.
 */
@Composable
private fun TrainerTopBar(
    title: String,
    greeting: Boolean,
    today: kotlinx.datetime.LocalDate,
    trainerName: String,
    showBack: Boolean,
    backEnabled: Boolean,
    onBack: () -> Unit,
    integrations: TrainerIntegrations,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = Layout.headerHeight).padding(start = if (showBack) Layout.small else Layout.gutter, end = Layout.medium, top = Layout.small, bottom = Layout.small),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
            if (showBack) IconButton(onClick = onBack, enabled = backEnabled) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr(Res.string.back)) }
            if (greeting) {
                val profile = integrations.onOpenProfile
                Box(Modifier.then(if (profile != null) Modifier.clickable(role = Role.Button, onClickLabel = tr(Res.string.action_profile), onClick = profile) else Modifier)) {
                    Portrait(initialsOf(trainerName), MyPhoto, size = 44.dp)
                }
                val first = trainerName.split(' ').firstOrNull { it.isNotBlank() }
                val hello = if (first != null) tr(Res.string.start_greeting, first) else tr(Res.string.start_greeting_plain)
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                    Text(DateFormats.date(today, DateFormats.defaultLanguage(), "EEEE, d MMMM").replaceFirstChar { it.titlecase() },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(hello, style = MaterialTheme.typography.titleLarge)
                }
            } else {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).clearAndSetSemantics { heading(); text = AnnotatedString(title) })
            }
            if (!showBack) {
                integrations.onOpenChats?.let { chats ->
                    BadgedBox(badge = { if (integrations.chatUnread > 0) Badge(containerColor = AppColors.errorAccent) }) {
                        FilledTonalIconButton(onClick = chats, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = AppColors.surface)) {
                            Icon(Icons.AutoMirrored.Outlined.Chat, tr(Res.string.action_messages))
                        }
                    }
                }
                if (!greeting) integrations.onOpenProfile?.let { profile ->
                    val label = tr(Res.string.action_profile)
                    Box(Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = profile).semantics { contentDescription = label }) {
                        Portrait(initialsOf(trainerName), MyPhoto, size = 40.dp)
                    }
                }
            }
        }
    }
}

/** "Katarzyna Nowak" → "KN". */
internal fun initialsOf(name: String): String =
    name.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

/** The plus: new session, block time, invite a trainee (last). Same on every tab. */
@Composable
private fun CreateMenu(onNewSession: () -> Unit, onBlockTime: () -> Unit, onInvite: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Layout.gutter).padding(bottom = Layout.large),
        verticalArrangement = Arrangement.spacedBy(Layout.small)) {
        SectionTitle(tr(Res.string.add_menu_title), Modifier.padding(bottom = Layout.small))
        ActionRow(Icons.Outlined.EventAvailable, tr(Res.string.new_session), tr(Res.string.new_session_hint), onClick = onNewSession)
        ActionRow(Icons.Outlined.Block, tr(Res.string.block_time), tr(Res.string.availability_action_hint), onClick = onBlockTime)
        ActionRow(Icons.Outlined.PersonAdd, tr(Res.string.invite), tr(Res.string.invite_row_hint), onClick = onInvite)
    }
}

/**
 * "Zaproś podopiecznego": the trainer's permanent link, shared through the system sheet or copied. A new link
 * replaces the old one, which stops working, so it asks first.
 */
@Composable
private fun InviteSheet(invite: InviteState, share: ((String) -> Unit)?, onRegenerate: () -> Unit, onRetry: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }
    val intro = tr(Res.string.invite_message)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Layout.gutter).padding(bottom = Layout.large),
        verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
        HeroCard {
            IconTile(Icons.Outlined.PersonAdd, TileTone.Brand)
            Spacer(Modifier.height(Layout.medium))
            Text(tr(Res.string.invite), style = MaterialTheme.typography.titleLarge, color = androidx.compose.ui.graphics.Color.White)
            Text(tr(Res.string.invite_sheet_body), style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.88f))
        }
        when {
            invite.link != null -> {
                WhiteCard {
                    Text(tr(Res.string.invite_link_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(invite.link.url, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Layout.tiny))
                    Text(tr(Res.string.invite_code, invite.link.code), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Layout.tiny))
                }
                if (share != null) PrimaryButton(tr(Res.string.invite_share)) { share(invite.link.message(intro)) }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(invite.link.url)); copied = true }, shape = Layout.pill,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target)) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(Layout.small))
                    Text(tr(if (copied) Res.string.invite_copied else Res.string.invite_copy))
                }
                TextButton(onClick = { confirmNew = true }, enabled = !invite.loading, modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target)) {
                    Text(tr(Res.string.invite_new_link))
                }
            }
            invite.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            invite.failure != null -> {
                InfoBanner(tr(Res.string.invite_failed), error = true)
                OutlinedButton(onClick = onRetry, shape = Layout.pill, modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target)) { Text(tr(Res.string.retry)) }
            }
        }
    }
    if (confirmNew) ConfirmDialog(
        title = tr(Res.string.invite_new_link_title), body = tr(Res.string.invite_new_link_body), confirm = tr(Res.string.invite_new_link),
        submitting = invite.loading, onDismiss = { confirmNew = false }, onConfirm = { confirmNew = false; copied = false; onRegenerate() },
    )
}

private fun TrainerViewModel.saveTimeForEditor(date: kotlinx.datetime.LocalDate, start: String, end: String, client: String?, location: String, session: Session?) =
    saveTime(date, start, end, client, location, session)
