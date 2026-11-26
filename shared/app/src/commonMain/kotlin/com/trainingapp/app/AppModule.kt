package com.trainingapp.app

import app.cash.sqldelight.db.SqlDriver
import com.trainingapp.app.resources.Res
import com.trainingapp.data.db.createTrainingDatabase
import com.trainingapp.data.exercise.ExerciseRemoteDataSource
import com.trainingapp.data.exercise.ExerciseSeedSource
import com.trainingapp.data.exercise.FakeExerciseRemoteDataSource
import com.trainingapp.data.exercise.LocalExerciseRepository
import com.trainingapp.data.media.FakeMediaRemoteDataSource
import com.trainingapp.data.media.LocalMediaRepository
import com.trainingapp.data.media.MediaRemoteDataSource
import com.trainingapp.data.plan.FakePlanRemoteDataSource
import com.trainingapp.data.plan.LocalPlanRepository
import com.trainingapp.data.plan.PlanRemoteDataSource
import com.trainingapp.data.plan.PublishedPlanExerciseUsage
import com.trainingapp.data.auth.FirebaseAuthApi
import com.trainingapp.data.auth.FirebaseTokenProvider
import com.trainingapp.data.auth.GoogleIdTokenSource
import com.trainingapp.data.auth.IdentityConfig
import com.trainingapp.data.auth.InMemoryTokenStore
import com.trainingapp.data.auth.TokenStore
import com.trainingapp.app.photos.exerciseSourceId
import com.trainingapp.data.remote.ApiClient
import com.trainingapp.data.remote.BackendConfig
import com.trainingapp.data.remote.HttpEngines
import io.ktor.client.engine.HttpClientEngine
import com.trainingapp.domain.auth.TokenProvider
import com.trainingapp.feature.guest.data.FirebaseAuthRepository
import com.trainingapp.feature.guest.data.RemoteAuthRepository
import com.trainingapp.app.remote.ApiClientAdapter
import com.trainingapp.app.remote.client.RemoteTodayWorkoutSource
import com.trainingapp.app.remote.client.WorkoutSyncer
import com.trainingapp.data.workout.LocalWorkoutSyncStore
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import pl.trainer.data.remote.client.ClientApi
import pl.trainer.data.remote.client.PublicPolicyRepository
import pl.trainer.data.remote.client.RemoteClientScheduleRepository
import pl.trainer.data.remote.client.RemoteWalletRepository
import com.trainingapp.feature.guest.data.RemoteGuestCatalogRepository
import com.trainingapp.domain.common.AppLanguage
import com.trainingapp.domain.common.CurrentUser
import com.trainingapp.domain.common.IdGenerator
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.domain.common.TimeSource
import com.trainingapp.domain.exercise.ExerciseRepository
import com.trainingapp.domain.exercise.ExerciseUsage
import com.trainingapp.domain.media.LocalFiles
import com.trainingapp.domain.media.MediaRepository
import com.trainingapp.domain.plan.PlanRepository
import com.trainingapp.feature.guest.data.DemoAccounts
import com.trainingapp.feature.guest.guestModule
import com.trainingapp.feature.guest.model.AccountUpdateResult
import com.trainingapp.feature.guest.model.AuthRepository
import com.trainingapp.feature.guest.model.AuthSession
import com.trainingapp.feature.guest.model.GuestCatalogRepository
import com.trainingapp.feature.guest.model.TrainerProfile
import com.trainingapp.feature.client.clientModule
import com.trainingapp.feature.client.presentation.ClientAccount
import pl.trainer.data.AdvancingDemoClock
import pl.trainer.data.CatalogTrainer
import pl.trainer.data.ClientIdentity
import pl.trainer.data.DemoClientScheduleRepository
import pl.trainer.data.DemoPolicyRepository
import pl.trainer.data.DemoSchedulingStore
import pl.trainer.data.DemoTrainerRepository
import pl.trainer.data.DemoWalletRepository
import pl.trainer.data.remote.RemotePolicyRepository
import pl.trainer.data.remote.RemoteTrainerRepository
import pl.trainer.data.remote.TrainerApiClient
import pl.trainer.domain.ClientScheduleRepository
import pl.trainer.domain.PolicyRepository
import pl.trainer.domain.TrainerRepository
import pl.trainer.domain.WalletRepository
import com.trainingapp.feature.guest.model.Role
import com.trainingapp.data.workout.DemoWorkoutData
import com.trainingapp.data.workout.LocalMeasurementRepository
import com.trainingapp.data.workout.LocalWorkoutRepository
import com.trainingapp.domain.workout.CurrentClient
import com.trainingapp.domain.workout.MeasurementRepository
import com.trainingapp.domain.workout.TechniqueVideoContext
import com.trainingapp.domain.workout.TechniqueVideoOutbox
import com.trainingapp.domain.workout.TodayWorkoutSource
import com.trainingapp.domain.workout.WorkoutRepository
import com.trainingapp.feature.workoutbuilder.workoutBuilderModule
import pl.trainerapp.feature.chat.ChatMediaSource
import pl.trainerapp.feature.chat.data.DemoChatStore
import pl.trainerapp.feature.chat.data.RepositoryChatMediaSource
import pl.trainerapp.feature.chat.ClientChatRepository
import pl.trainerapp.feature.chat.data.remote.ChatServerApi
import pl.trainerapp.feature.chat.data.remote.CompositeChatMediaSource
import pl.trainerapp.feature.chat.data.remote.RemoteChatMediaSource
import pl.trainerapp.feature.chat.data.remote.RemoteClientChatRepository
import pl.trainerapp.feature.chat.data.remote.RemoteClientContext
import pl.trainerapp.feature.chat.data.remote.RemoteTechniqueVideoReviews
import pl.trainerapp.feature.chat.data.remote.rebaseUrl
import com.trainingapp.app.remote.media.ApiClientChatServer
import com.trainingapp.app.remote.media.RemoteClientMediaRemoteDataSource
import com.trainingapp.app.remote.media.RemoteTechniqueVideoOutbox
import pl.trainerapp.feature.chat.model.ChatRole
import pl.trainerapp.feature.chat.model.MediaRef
import com.trainingapp.feature.workoutlogging.rest.RestTimerNotifier
import com.trainingapp.feature.workoutlogging.workoutLoggingModule
import kotlinx.coroutines.launch
import com.trainingapp.contract.media.CreateUploadSessionRequest
import com.trainingapp.contract.media.MediaKind
import com.trainingapp.contract.media.MediaStatusDto
import com.trainingapp.app.remote.RemoteExerciseRemoteDataSource
import com.trainingapp.app.remote.RemoteMediaRemoteDataSource
import com.trainingapp.app.remote.RemotePlanRemoteDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

/** What each platform provides: the SQLite driver, connectivity, an IO dispatcher and file deletion. */
interface AppPlatform {
    fun createDriver(): SqlDriver
    val networkMonitor: NetworkMonitor
    val ioDispatcher: CoroutineDispatcher
    val localFiles: LocalFiles

    /**
     * The trainer API (`/api/v1` on the same server as [backend], docs/features/trainer-backend.md). Null keeps the
     * in-memory demo server, so the app runs without a backend.
     */
    val trainerApiUrl: String? get() = backend.baseUrl

    /** The rest-end system notification; platforms without notifications keep the default. */
    val restTimerNotifier: RestTimerNotifier get() = RestTimerNotifier.None

    /** Which server to use (`trainer.backend`). The default keeps every demo source, so no server is needed. */
    val backend: BackendConfig get() = BackendConfig.Demo

    /** How users sign in when [backend] is a server (`trainer.auth`, docs/plans/firebase-auth.md §8). */
    val identity: IdentityConfig get() = IdentityConfig.Dev

    /** Where a Firebase session survives restarts. Read once at start-up; Android encrypts it with the Keystore. */
    fun createTokenStore(): TokenStore = InMemoryTokenStore()

    /** Google sign-in (Android Credential Manager); null where the platform has none. */
    val googleIdTokens: GoogleIdTokenSource? get() = null

    /** A new HTTP engine for the backend and Firebase clients. Android uses OkHttp (see [HttpEngines]). */
    fun createHttpEngine(): HttpClientEngine = HttpEngines.default()
}

/** The token the trainer API client sends: the dev subject locally, the Firebase session otherwise. */
val TrainerTokens = named("trainerTokens")

/** The demo trainer whose synthetic data the trainer views show. Signing in as the demo trainer maps to this id. */
object DemoTrainer : CurrentUser {
    override val trainerId: String = DemoAccounts.DEMO_TRAINER_ID
}

/** The signed-in trainer from the session; the demo trainer until a trainer signs in (no identity provider yet). */
private class SessionCurrentUser(private val auth: AuthRepository) : CurrentUser {
    override val trainerId: String
        get() = auth.session.value?.takeIf { Role.Trainer in it.roles }?.userId ?: DemoTrainer.trainerId
}

/**
 * The demo client (Katarzyna "Kasia" Nowak) when nobody is signed in as a client. Matches the demo trainer chat
 * fixtures (relationship "c1"), so a technique video lands in that conversation.
 */
object DemoClient : CurrentClient {
    override val clientId: String = "c1"
    override val firstName: String = "Kasia"
    override val trainerRelationshipId: String = "c1"
}

/** The signed-in client from the session, mapped to the demo server's client id; the demo client otherwise. */
private class SessionCurrentClient(private val auth: AuthRepository, private val store: DemoSchedulingStore) : CurrentClient {
    private val session get() = auth.session.value?.takeIf { Role.Client in it.roles }
    override val clientId: String get() = session?.let { store.clientIdOf(it.userId) ?: it.userId } ?: DemoClient.clientId
    override val firstName: String
        get() = session?.let { if (store.clientIdOf(it.userId) == DemoClient.clientId) DemoClient.firstName else it.displayName.substringBefore(' ') }
            ?: DemoClient.firstName
    override val trainerRelationshipId: String get() = clientId
}

/** The signed-in client in remote mode: the session subject; the relationship comes from the server. */
private class RemoteCurrentClient(private val auth: AuthRepository, private val context: RemoteClientContext) : CurrentClient {
    private val session get() = auth.session.value?.takeIf { Role.Client in it.roles }
    override val clientId: String get() = session?.userId.orEmpty()
    override val firstName: String get() = session?.displayName?.substringBefore(' ').orEmpty()
    override val trainerRelationshipId: String get() = context.relationshipId.orEmpty()
}

/** The client's own name, saved through [auth]; the session (and so the shell's header) updates on success. */
private class SessionClientAccount(private val auth: AuthRepository) : ClientAccount {
    override suspend fun saveDisplayName(name: String): ClientAccount.SaveResult = when (auth.updateDisplayName(name)) {
        AccountUpdateResult.Saved -> ClientAccount.SaveResult.Saved
        AccountUpdateResult.Offline -> ClientAccount.SaveResult.Offline
        AccountUpdateResult.Invalid -> ClientAccount.SaveResult.Invalid
        AccountUpdateResult.Unavailable -> ClientAccount.SaveResult.Failed
    }
}

/** The session-backed account plus joining a trainer with an invite code (`POST /v1/client/invites/{code}/redeem`). */
private class RemoteClientAccount(private val session: ClientAccount, private val api: ClientApi) : ClientAccount by session {
    override suspend fun joinTrainer(code: String): ClientAccount.JoinResult = try {
        val joined = api.send<Unit, com.trainingapp.contract.account.JoinedTrainerDto>(
            io.ktor.http.HttpMethod.Post, "v1/client/invites/${code.trim().uppercase()}/redeem",
            response = com.trainingapp.contract.account.JoinedTrainerDto.serializer(),
        )
        if (joined != null) ClientAccount.JoinResult.Joined(joined.trainerName) else ClientAccount.JoinResult.Failed
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: pl.trainer.data.remote.client.ClientApiOfflineException) {
        ClientAccount.JoinResult.Offline
    } catch (e: pl.trainer.data.remote.client.ClientApiHttpException) {
        if (e.status == 404) ClientAccount.JoinResult.NotFound else ClientAccount.JoinResult.Failed
    } catch (e: Exception) {
        ClientAccount.JoinResult.Failed
    }
}

/** A public trainer profile from the guest catalogue, as the demo server needs it for a booking request. */
private fun TrainerProfile.toCatalogTrainer() = CatalogTrainer(
    id = summary.id,
    name = summary.name,
    initials = summary.initials,
    price = offers.firstOrNull { it.sessionCount == 1 }?.price ?: summary.startingPrice?.price,
    places = gyms.map { it.gym.name },
    freeCancellationHours = cancellationPolicy?.freeCancellationHours,
)

private val AppScope = named("appScope")

/** Posts a recorded technique video into the client's conversation with the trainer. */
private class ChatStoreVideoOutbox(private val store: DemoChatStore) : TechniqueVideoOutbox {
    override suspend fun send(relationshipId: String, mediaId: String, context: TechniqueVideoContext): Boolean {
        store.send(
            relationshipId,
            ChatRole.Client,
            text = "",
            attachment = MediaRef(
                mediaId = mediaId,
                exerciseName = context.exerciseName,
                setNumber = context.setNumber,
                setCount = context.setCount,
                weightGrams = context.weightGrams,
                reps = context.reps,
            ),
        )
        return true
    }
}

private fun AuthSession.clientIdentity(): ClientIdentity? = takeIf { Role.Client in roles }?.let { ClientIdentity(it.userId, it.displayName) }

/**
 * The backend does not exist yet (delivery slice 4), so the remotes are the fakes from `:shared:data`. They follow
 * the server contract and add latency so the pending and saving states are visible.
 */
fun appModule(platform: AppPlatform) = module {
    // A plan the trainer is writing out for one session, until the editor assigns it (TrainerPlans.kt).
    single { PendingSessionPlan() }
    // With a server address the trainer features use the trainer API (docs/features/trainer-backend.md §8);
    // without one they keep the demo data and fakes.
    val apiUrl = platform.trainerApiUrl?.takeIf { it.isNotBlank() }
    // `dev` sign-in: the signed-in trainer's subject, or the demo trainer (as before). The Firebase modes replace it
    // with the Firebase session in [remoteModule].
    single<TokenProvider>(TrainerTokens) { TokenProvider.dev { get<CurrentUser>().trainerId } }
    if (apiUrl != null) single { TrainerApiClient(apiUrl, tokens = get(TrainerTokens)) }

    single<SqlDriver> { platform.createDriver() }
    single { createTrainingDatabase(get()) }
    single<NetworkMonitor> { platform.networkMonitor }
    single<CurrentUser> { SessionCurrentUser(get()) }
    // The app is Polish only, whatever the device language is.
    // The UI language (English for now, see MainActivity); server texts and exercise names follow it.
    single<AppLanguage> { AppLanguage { pl.trainer.ui.DateFormats.defaultLanguage() } }
    single<IdGenerator> { IdGenerator.Uuids }
    single<TimeSource> { TimeSource.System }
    single(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    // "Od trenera" on exercises: the trainer API with a server, otherwise kept in memory (workout builder).
    single<com.trainingapp.domain.exercise.TrainerExerciseNotes> {
        if (apiUrl != null) com.trainingapp.app.remote.RemoteTrainerExerciseNotes(get()) else com.trainingapp.domain.exercise.InMemoryTrainerExerciseNotes()
    }
    single<ExerciseRemoteDataSource> { if (apiUrl != null) RemoteExerciseRemoteDataSource(get()) else FakeExerciseRemoteDataSource(get(), latencyMs = 800) }
    single<ExerciseRepository> {
        LocalExerciseRepository(
            db = get(),
            seed = ExerciseSeedSource { Res.readBytes("files/exercise_library.json").decodeToString() },
            remote = get(),
            network = get(),
            time = get(),
            scope = get(AppScope),
            io = platform.ioDispatcher,
        )
    }
    single<PlanRemoteDataSource> {
        if (apiUrl != null) RemotePlanRemoteDataSource(get()) else FakePlanRemoteDataSource(get(), get(), get(), get(), get(), platform.ioDispatcher, latencyMs = 600)
    }
    single<PlanRepository> { LocalPlanRepository(get(), get(), get(), platform.ioDispatcher) }
    single<ExerciseUsage> { PublishedPlanExerciseUsage(get()) }
    // Exercise photos bundled with the app, for Home and other screens (LocalExercisePhotos).
    single { com.trainingapp.app.photos.BundledExercisePhotos(get()) }

    single<LocalFiles> { platform.localFiles }
    single<MediaRemoteDataSource> {
        val fake = FakeMediaRemoteDataSource(get(), stepDelayMs = 300)
        if (apiUrl != null) TrainerMediaRouting(RemoteMediaRemoteDataSource(get()), fake) else fake
    }
    single<MediaRepository> {
        LocalMediaRepository(get(), get(), get(), get(), get(), get(), get(AppScope), platform.ioDispatcher)
    }

    // One demo server for sessions, requests, policy versions and the wallet, shared by the trainer and client views
    // (docs/features/client-shell-sessions-wallet.md, D10). Its clock starts at the fixture date and moves forward.
    single { DemoSchedulingStore(AdvancingDemoClock()::now, get(AppScope)) }
    single { DemoTrainerRepository(store = get()) }
    // The client views stay on the demo server until the client backend exists.
    if (apiUrl != null) {
        single<TrainerRepository> { RemoteTrainerRepository(get()) }
    } else {
        single<TrainerRepository> { get<DemoTrainerRepository>() }
    }
    single<ClientScheduleRepository> {
        val auth: AuthRepository = get()
        val network: NetworkMonitor = get()
        val catalog: GuestCatalogRepository = get()
        DemoClientScheduleRepository(
            store = get(),
            identity = { auth.session.value?.clientIdentity() },
            isOnline = { network.isOnline.value },
            catalog = { id -> runCatching { catalog.trainer(id) }.getOrNull()?.toCatalogTrainer() },
        )
    }
    single<WalletRepository> {
        val auth: AuthRepository = get()
        val network: NetworkMonitor = get()
        DemoWalletRepository(get(), identity = { auth.session.value?.clientIdentity() }, isOnline = { network.isOnline.value })
    }
    single<PolicyRepository> {
        val network: NetworkMonitor = get()
        val demo = DemoPolicyRepository(get(), isOnline = { network.isOnline.value })
        if (apiUrl == null) demo else {
            val user: CurrentUser = get()
            RemotePolicyRepository(get(), isOwnTrainer = { it == user.trainerId }, others = demo)
        }
    }

    // Client workout logging (docs/features/workout-logging-video.md). Local only: no sync in this phase.
    single<CurrentClient> { SessionCurrentClient(get(), get()) }
    // "Edytuj dane" on the client profile: saved through the sign-in session (`PATCH /v1/me` or the demo session).
    single<ClientAccount> { SessionClientAccount(get()) }
    single<WorkoutRepository> { LocalWorkoutRepository(get(), platform.ioDispatcher) }
    single<MeasurementRepository> { LocalMeasurementRepository(get(), platform.ioDispatcher) }
    // Integration point: the plan assignment flow replaces this demo source.
    single<TodayWorkoutSource> { DemoWorkoutData.todaySource }
    single<RestTimerNotifier> { platform.restTimerNotifier }
    // A technique video from the live workout becomes a message in the client's conversation with the trainer.
    single<TechniqueVideoOutbox> { ChatStoreVideoOutbox(get()) }

    // One conversation store behind the trainer and client chats (A5, A6), with upload state from the media repository.
    single { DemoChatStore() }
    single<ChatMediaSource> { RepositoryChatMediaSource(get()) }
}

/**
 * The trainer API accepts only the trainer's exercise demo videos; a client's technique video goes to [other] (the
 * client API in remote mode, the fake otherwise). Status asks the trainer API first and falls back to [other].
 */
private class TrainerMediaRouting(private val server: MediaRemoteDataSource, private val other: MediaRemoteDataSource) : MediaRemoteDataSource {
    override suspend fun upload(request: CreateUploadSessionRequest, localPath: String, onProgress: suspend (Float) -> Unit) =
        (if (request.kind == MediaKind.EXERCISE_DEMO) server else other).upload(request, localPath, onProgress)

    override suspend fun status(mediaId: String): MediaStatusDto? = server.status(mediaId) ?: other.status(mediaId)
}

/** Synthetic history so the progress view is not empty in the demo. Runs once per device. */
private fun seedDemoClient(koin: org.koin.core.Koin) {
    val client = koin.get<CurrentClient>()
    koin.get<CoroutineScope>(AppScope).launch {
        runCatching {
            DemoWorkoutData.seedIfEmpty(client.clientId, koin.get(), koin.get(), koin.get(), koin.get<TimeSource>().nowEpochMs())
        }
    }
}

/**
 * Remote sources that replace demo ones when [AppPlatform.backend] points at a server. Loaded last, so its
 * definitions override the demo bindings. Connected so far: the public catalogue (guest views); sign-in, sessions,
 * wallet, workouts and chat still use the demo sources (docs/plans/backend-client-guest.md §11).
 */
fun remoteModule(
    backend: BackendConfig,
    io: CoroutineDispatcher = Dispatchers.Default,
    identity: IdentityConfig = IdentityConfig.Dev,
    googleIdTokens: GoogleIdTokenSource? = null,
    httpEngine: () -> HttpClientEngine = HttpEngines::default,
) = module {
    val baseUrl = backend.baseUrl!!
    // The session's userId is the auth subject in remote mode: the dev subject or the Firebase UID.
    when (identity) {
        // `dev`: the server accepts `dev.<subject>` tokens (decision TB2); no password is checked.
        IdentityConfig.Dev -> {
            single {
                val auth = lazy { get<AuthRepository>() }
                ApiClient(baseUrl, httpEngine(), language = { get<AppLanguage>().current() }, tokens = TokenProvider.dev { auth.value.session.value?.userId })
            }
            single<AuthRepository> {
                RemoteAuthRepository(apiFor = { tokens -> ApiClient(baseUrl, httpEngine(), language = { get<AppLanguage>().current() }, tokens = tokens) })
            }
        }
        // Firebase Authentication (docs/plans/firebase-auth.md §6): one session gives the token to both API clients.
        is IdentityConfig.Firebase -> {
            single { FirebaseAuthApi(identity.firebase, httpEngine(), nowMillis = { get<TimeSource>().nowEpochMs() }) }
            single { FirebaseTokenProvider(get(), get(), nowMillis = { get<TimeSource>().nowEpochMs() }) }
            single<TokenProvider>(TrainerTokens) { get<FirebaseTokenProvider>() }
            single { ApiClient(baseUrl, httpEngine(), language = { get<AppLanguage>().current() }, tokens = get<FirebaseTokenProvider>()) }
            single { FirebaseAuthRepository(get(), get(), get(), google = googleIdTokens) }
            single<AuthRepository> { get<FirebaseAuthRepository>() }
        }
    }
    single<GuestCatalogRepository> { RemoteGuestCatalogRepository(get()) }
    // Profile photos (`/v1/me/photo`, `/v1/users/{id}/photo`): shown in both shells, changed from the account menu.
    single { com.trainingapp.app.photos.RemoteProfilePhotos(get()) }
    single { com.trainingapp.app.photos.RemoteProfileGallery(get()) }
    // Exercise photos for the server's plans: their exercise ids are resolved through `/v1/exercises`.
    single {
        val api: ApiClient = get()
        com.trainingapp.app.photos.BundledExercisePhotos(get(), remoteSourceId = { id -> api.exerciseSourceId(id) })
    }

    // Client workouts: today's plan day from the server; local logs and measurements are synced to it.
    single { LocalWorkoutSyncStore(get(), io) }
    single<TodayWorkoutSource> { RemoteTodayWorkoutSource(get(), get()) }
    single {
        WorkoutSyncer(get(), get(), get(), get(), get(), timeZone = { TimeZone.currentSystemDefault().id })
    }

    // Client sessions, requests, cancellations and wallet (`/v1/client`). One update counter, so a cancellation
    // fee also refreshes the wallet.
    single<ClientApi> { ApiClientAdapter(get()) }
    // "I have a code from my trainer" needs the server: the remote account adds it to the session-backed one.
    single<ClientAccount> { RemoteClientAccount(SessionClientAccount(get()), get()) }
    single(ClientUpdates) { MutableStateFlow(0L) }
    single<ClientScheduleRepository> {
        val auth: AuthRepository = get()
        RemoteClientScheduleRepository(get(), identity = { auth.session.value?.userId }, updated = get(ClientUpdates))
    }
    single<WalletRepository> {
        val auth: AuthRepository = get()
        RemoteWalletRepository(get(), identity = { auth.session.value?.userId }, updated = get(ClientUpdates))
    }
    // Own policy from the trainer API; any other trainer's (the client views) from the public client API.
    single<PolicyRepository> {
        val user: CurrentUser = get()
        RemotePolicyRepository(get(), isOwnTrainer = { it == user.trainerId }, others = PublicPolicyRepository(get()))
    }

    // Chat and technique videos. The shared ApiClient carries the signed-in subject's token, so it serves the
    // client chat and, when a trainer is signed in, the media status the trainer needs to watch client videos.
    single<ChatServerApi> { ApiClientChatServer(get()) }
    single { RemoteClientContext(get()) }
    single<CurrentClient> { RemoteCurrentClient(get(), get()) }
    single<ClientChatRepository> { RemoteClientChatRepository(get(), get()) }
    single<ChatMediaSource> {
        val server: ChatServerApi = get()
        CompositeChatMediaSource(
            local = RepositoryChatMediaSource(get<MediaRepository>()),
            remote = RemoteChatMediaSource({ server.mediaStatus(it) }, rebase = { rebaseUrl(it, baseUrl) }),
        )
    }
    single<TechniqueVideoOutbox> { RemoteTechniqueVideoOutbox(get(), get(), get(AppScope)) }
    single<MediaRemoteDataSource> {
        TrainerMediaRouting(RemoteMediaRemoteDataSource(get()), RemoteClientMediaRemoteDataSource(get(), get(), baseUrl))
    }
    // The trainer's technique-video inbox; one instance so the review screen and the home list share its cache.
    single {
        val server: ChatServerApi = get()
        RemoteTechniqueVideoReviews(get<TrainerApiClient>(), { server.mediaStatus(it) })
    }
}

private val ClientUpdates = named("clientUpdates")

/**
 * The trainer's answers (confirmations, proposals, charges) reach the client's sessions and wallet without a push
 * channel yet, so while a client is signed in and online the open screens reload quietly every few seconds.
 */
private fun startClientRefresh(koin: org.koin.core.Koin) {
    val auth = koin.get<AuthRepository>()
    val online = koin.get<NetworkMonitor>().isOnline
    val updates = koin.get<MutableStateFlow<Long>>(ClientUpdates)
    koin.get<CoroutineScope>(AppScope).launch {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            if (online.value && auth.session.value?.roles?.contains(Role.Client) == true) updates.value++
        }
    }
}

/** Starts pushing the client's workout logs and measurements to the server (on reconnect and every few minutes). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
private fun startWorkoutSync(koin: org.koin.core.Koin) {
    val syncer = koin.get<WorkoutSyncer>()
    val scope = koin.get<CoroutineScope>(AppScope)
    val online = koin.get<NetworkMonitor>().isOnline
    syncer.startPeriodicSync(scope, online)
    // Also right after a client signs in and shortly after a workout is saved or changed, not only every few minutes.
    val auth = koin.get<AuthRepository>()
    val workouts = koin.get<WorkoutRepository>()
    val client = koin.get<CurrentClient>()
    scope.launch {
        auth.session
            .flatMapLatest { session ->
                if (session == null || Role.Client !in session.roles) kotlinx.coroutines.flow.emptyFlow()
                else workouts.observeHistory(client.clientId).map { logs -> logs.map { it.id to it.syncState } }
            }
            .distinctUntilChanged()
            .debounce(1_500)
            .collect { if (online.value) runCatching { syncer.syncOnce() } }
    }
}

/** Keeps the client's trainer relationship (chat, technique videos) in step with the signed-in session. */
private fun startClientContextSync(koin: org.koin.core.Koin) {
    val context = koin.get<RemoteClientContext>()
    val auth = koin.get<AuthRepository>()
    koin.get<CoroutineScope>(AppScope).launch {
        auth.session.collect { session ->
            if (session != null && Role.Client in session.roles) runCatching { context.refresh() } else context.clear()
        }
    }
}

fun initKoin(platform: AppPlatform, platformDeclaration: KoinAppDeclaration = {}) = startKoin {
    platformDeclaration()
    modules(appModule(platform), workoutBuilderModule, guestModule, workoutLoggingModule, clientModule)
    if (platform.backend.isRemote) {
        modules(module { single<TokenStore> { platform.createTokenStore() } })
        modules(remoteModule(platform.backend, platform.ioDispatcher, platform.identity, platform.googleIdTokens, platform::createHttpEngine))
    }
}.also {
    // Synthetic history only for the demo; with a server the history comes from the client's workout logs there.
    if (!platform.backend.isRemote) {
        seedDemoClient(it.koin)
    } else {
        startWorkoutSync(it.koin)
        startClientContextSync(it.koin)
        startClientRefresh(it.koin)
        if (platform.identity is IdentityConfig.Firebase) revalidateRestoredSession(it.koin)
    }
}

/** A session restored from storage is confirmed with the server in the background (roles may have changed). */
private fun revalidateRestoredSession(koin: org.koin.core.Koin) {
    val auth = koin.get<FirebaseAuthRepository>()
    koin.get<CoroutineScope>(AppScope).launch { runCatching { auth.revalidate() } }
}
