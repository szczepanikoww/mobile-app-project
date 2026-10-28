package com.trainingapp.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.trainingapp.app.App
import com.trainingapp.app.AppPlatform
import com.trainingapp.app.initKoin
import com.trainingapp.data.db.TrainingDatabase
import com.trainingapp.domain.common.NetworkMonitor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Properties

/** Set `-Dtrainer.offline=true` to try the offline states. */
private object DesktopPlatform : AppPlatform {
    override fun createDriver(): SqlDriver {
        val dir = File(System.getProperty("user.home"), ".trainingapp").apply { mkdirs() }
        return JdbcSqliteDriver("jdbc:sqlite:${File(dir, "training.db").absolutePath}", Properties(), TrainingDatabase.Schema)
    }

    override val networkMonitor: NetworkMonitor = object : NetworkMonitor {
        override val isOnline = MutableStateFlow(System.getProperty("trainer.offline") != "true")
    }
    override val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
}

fun main() {
    initKoin(DesktopPlatform)
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Trainer – desktop preview",
            state = rememberWindowState(width = 412.dp, height = 892.dp),
        ) { App() }
    }
}
