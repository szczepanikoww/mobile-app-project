package com.trainingapp.app

import app.cash.sqldelight.db.SqlDriver
import com.trainingapp.app.resources.Res
import com.trainingapp.data.db.createTrainingDatabase
import com.trainingapp.data.exercise.ExerciseRemoteDataSource
import com.trainingapp.data.exercise.ExerciseSeedSource
import com.trainingapp.data.exercise.FakeExerciseRemoteDataSource
import com.trainingapp.data.exercise.LocalExerciseRepository
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
import com.trainingapp.domain.plan.PlanRepository
import com.trainingapp.feature.workoutbuilder.workoutBuilderModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

/** What each platform provides: the SQLite driver, connectivity and an IO dispatcher. */
interface AppPlatform {
    fun createDriver(): SqlDriver
    val networkMonitor: NetworkMonitor
    val ioDispatcher: CoroutineDispatcher
}

/** Sign-in is not implemented yet; every session acts as this synthetic trainer. */
object DemoTrainer : CurrentUser {
    override val trainerId: String = "demo-trainer"
}

private val AppScope = named("appScope")

/**
 * The backend does not exist yet (delivery slice 4), so the remotes are the fakes from `:shared:data`. They follow
 * the server contract and add latency so the pending and saving states are visible.
 */
fun appModule(platform: AppPlatform) = module {
    single<SqlDriver> { platform.createDriver() }
    single { createTrainingDatabase(get()) }
    single<NetworkMonitor> { platform.networkMonitor }
    single<CurrentUser> { DemoTrainer }
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
}

fun initKoin(platform: AppPlatform, platformDeclaration: KoinAppDeclaration = {}) = startKoin {
    platformDeclaration()
    modules(appModule(platform), workoutBuilderModule)
}
