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
import com.trainingapp.feature.guest.model.AuthRepository
import com.trainingapp.feature.guest.model.AuthSession
import com.trainingapp.feature.guest.model.GuestCatalogRepository
import com.trainingapp.feature.guest.model.TrainerProfile
import com.trainingapp.feature.client.clientModule
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
import pl.trainerapp.feature.chat.model.ChatRole
import pl.trainerapp.feature.chat.model.MediaRef
import com.trainingapp.feature.workoutlogging.rest.RestTimerNotifier
import com.trainingapp.feature.workoutlogging.workoutLoggingModule
import kotlinx.coroutines.launch
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
     * The trainer API (`server`, docs/features/trainer-backend.md), e.g. `http://10.0.2.2:8080` from the Android
     * emulator. Null keeps the in-memory demo server, so the app runs without a backend.
     */
    val trainerApiUrl: String? get() = null

    /** The rest-end system notification; platforms without notifications keep the default. */
    val restTimerNotifier: RestTimerNotifier get() = RestTimerNotifier.None
}

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
    single<SqlDriver> { platform.createDriver() }
    single { createTrainingDatabase(get()) }
    single<NetworkMonitor> { platform.networkMonitor }
    single<CurrentUser> { SessionCurrentUser(get()) }
    // The app is Polish only, whatever the device language is.
    single<AppLanguage> { AppLanguage { "pl" } }
    single<IdGenerator> { IdGenerator.Uuids }
    single<TimeSource> { TimeSource.System }
    single(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    single<ExerciseRemoteDataSource> { FakeExerciseRemoteDataSource(get(), latencyMs = 800) }
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
        FakePlanRemoteDataSource(get(), get(), get(), get(), get(), platform.ioDispatcher, latencyMs = 600)
    }
    single<PlanRepository> { LocalPlanRepository(get(), get(), get(), platform.ioDispatcher) }
    single<ExerciseUsage> { PublishedPlanExerciseUsage(get()) }

    single<LocalFiles> { platform.localFiles }
    single<MediaRemoteDataSource> { FakeMediaRemoteDataSource(get(), stepDelayMs = 300) }
    single<MediaRepository> {
        LocalMediaRepository(get(), get(), get(), get(), get(), get(), get(AppScope), platform.ioDispatcher)
    }

    // One demo server for sessions, requests, policy versions and the wallet, shared by the trainer and client views
    // (docs/features/client-shell-sessions-wallet.md, D10). Its clock starts at the fixture date and moves forward.
    single { DemoSchedulingStore(AdvancingDemoClock()::now, get(AppScope)) }
    single { DemoTrainerRepository(store = get()) }
    // With a server address the trainer's calendar, requests, clients and policy come from the trainer API; the
    // client views stay on the demo server until the client backend exists.
    val apiUrl = platform.trainerApiUrl?.takeIf { it.isNotBlank() }
    if (apiUrl != null) {
        single { TrainerApiClient(apiUrl, subject = { get<CurrentUser>().trainerId }) }
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

/** Synthetic history so the progress view is not empty in the demo. Runs once per device. */
private fun seedDemoClient(koin: org.koin.core.Koin) {
    val client = koin.get<CurrentClient>()
    koin.get<CoroutineScope>(AppScope).launch {
        runCatching {
            DemoWorkoutData.seedIfEmpty(client.clientId, koin.get(), koin.get(), koin.get(), koin.get<TimeSource>().nowEpochMs())
        }
    }
}

fun initKoin(platform: AppPlatform, platformDeclaration: KoinAppDeclaration = {}) = startKoin {
    platformDeclaration()
    modules(appModule(platform), workoutBuilderModule, guestModule, workoutLoggingModule, clientModule)
}.also { seedDemoClient(it.koin) }
