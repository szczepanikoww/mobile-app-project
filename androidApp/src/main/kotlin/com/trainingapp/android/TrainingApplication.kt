package com.trainingapp.android


import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import android.util.Log
import com.trainingapp.android.auth.CredentialManagerGoogleIdTokens
import com.trainingapp.android.auth.KeystoreTokenStore
import com.trainingapp.app.AppPlatform
import com.trainingapp.app.initKoin
import com.trainingapp.data.auth.GoogleIdTokenSource
import com.trainingapp.data.auth.IdentityConfig
import com.trainingapp.data.auth.TokenStore
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import com.trainingapp.data.db.TrainingDatabase
import com.trainingapp.data.remote.BackendConfig
import pl.trainerapp.BuildConfig
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.domain.media.LocalFiles
import com.trainingapp.feature.workoutlogging.rest.AndroidRestTimerNotifier
import com.trainingapp.feature.workoutlogging.rest.RestTimerNotifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.android.ext.koin.androidContext
import java.io.File

class TrainingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(AndroidPlatform(this)) { androidContext(this@TrainingApplication) }
    }
}

private class AndroidPlatform(private val context: Context) : AppPlatform {
    override fun createDriver(): SqlDriver = AndroidSqliteDriver(TrainingDatabase.Schema, context, "training.db")
    override val networkMonitor: NetworkMonitor = AndroidNetworkMonitor(context)
    override val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    override val localFiles: LocalFiles = LocalFiles { File(it).delete() }
    override val restTimerNotifier: RestTimerNotifier = AndroidRestTimerNotifier(context)
    override val backend: BackendConfig = BackendConfig.parse(BuildConfig.TRAINER_BACKEND, localHost = "10.0.2.2")

    // `-Ptrainer.auth` and the Firebase settings from local.properties (docs/plans/firebase-auth.md §8).
    override val identity: IdentityConfig = IdentityConfig.parse(
        mode = BuildConfig.TRAINER_AUTH,
        apiKey = BuildConfig.FIREBASE_API_KEY,
        googleWebClientId = BuildConfig.FIREBASE_WEB_CLIENT_ID,
    ).also {
        IdentityConfig.describeProblem(BuildConfig.TRAINER_AUTH, BuildConfig.FIREBASE_API_KEY)?.let { problem -> Log.w("TrainerAuth", problem) }
    }

    override fun createTokenStore(): TokenStore = KeystoreTokenStore(context)

    override val googleIdTokens: GoogleIdTokenSource = CredentialManagerGoogleIdTokens()

    // OkHttp honours the network security config; CIO's TLS fails every HTTPS call on Android (see HttpEngines).
    override fun createHttpEngine(): HttpClientEngine = OkHttp.create()
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
