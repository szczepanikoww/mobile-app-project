package com.trainingapp.android

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.trainingapp.app.AppPlatform
import com.trainingapp.app.initKoin
import com.trainingapp.data.db.TrainingDatabase
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.domain.media.LocalFiles
import com.trainingapp.domain.media.MediaRepository
import com.trainingapp.domain.workout.TechniqueVideoOutbox
import com.trainingapp.feature.workoutlogging.rest.AndroidRestTimerNotifier
import com.trainingapp.feature.workoutlogging.rest.RestTimerNotifier
import org.koin.core.context.GlobalContext
import pl.trainer.data.DemoTrainerRepository
import pl.trainerapp.feature.chat.ChatMediaSource
import pl.trainerapp.feature.chat.data.DemoChatStore
import pl.trainerapp.feature.chat.data.RepositoryChatMediaSource
import pl.trainerapp.feature.chat.model.ChatRole
import pl.trainerapp.feature.chat.model.MediaRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.android.ext.koin.androidContext
import java.io.File

class TrainingApplication : Application() {
    /** The one demo conversation store for the trainer and client views. Kept for the process; resets on restart. */
    val chatStore = DemoChatStore()

    /**
     * The calendar's demo repository, shared by the calendar tabs and the check-in screen. It reads the one demo
     * scheduling store from Koin, which the client views use too.
     */
    val trainerRepository: DemoTrainerRepository by lazy { GlobalContext.get().get() }

    /** Upload state of chat videos, from the shared media repository. */
    val chatMedia: ChatMediaSource by lazy { RepositoryChatMediaSource(GlobalContext.get().get<MediaRepository>()) }

    override fun onCreate() {
        super.onCreate()
        initKoin(AndroidPlatform(this, chatStore)) { androidContext(this@TrainingApplication) }
    }
}

private class AndroidPlatform(private val context: Context, private val chatStore: DemoChatStore) : AppPlatform {
    override fun createDriver(): SqlDriver = AndroidSqliteDriver(TrainingDatabase.Schema, context, "training.db")
    override val networkMonitor: NetworkMonitor = AndroidNetworkMonitor(context)
    override val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    override val localFiles: LocalFiles = LocalFiles { File(it).delete() }
    override val restTimerNotifier: RestTimerNotifier = AndroidRestTimerNotifier(context)

    /** A technique video from the live workout becomes a message in the client's conversation with the trainer. */
    override val techniqueVideoOutbox: TechniqueVideoOutbox = TechniqueVideoOutbox { relationshipId, mediaId, video ->
        chatStore.send(
            relationshipId,
            ChatRole.Client,
            text = "",
            attachment = MediaRef(
                mediaId = mediaId,
                exerciseName = video.exerciseName,
                setNumber = video.setNumber,
                setCount = video.setCount,
                weightGrams = video.weightGrams,
                reps = video.reps,
            ),
        )
        true
    }
}

/** Online when the default network has validated internet access. */
private class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(isOnlineNow())
    override val isOnline: StateFlow<Boolean> = state

    init {
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    state.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }

                override fun onLost(network: Network) {
                    state.value = false
                }
            },
        )
    }

    private fun isOnlineNow(): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
