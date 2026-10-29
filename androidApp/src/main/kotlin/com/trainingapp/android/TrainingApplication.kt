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
