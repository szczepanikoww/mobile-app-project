package com.trainingapp.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.trainingapp.data.db.TrainingDatabase
import com.trainingapp.domain.common.CurrentUser
import com.trainingapp.domain.common.IdGenerator
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.domain.common.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Properties

fun fileDriver(file: File) = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", Properties(), TrainingDatabase.Schema)

fun memoryDriver() = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), TrainingDatabase.Schema)

class TestNetwork(online: Boolean = true) : NetworkMonitor {
    override val isOnline = MutableStateFlow(online)
}

class TestIds(private val prefix: String = "id") : IdGenerator {
    private var next = 0
    override fun newId() = "$prefix-${++next}"
}

class TestClock(var now: Long = 1_000) : TimeSource {
    override fun nowEpochMs(): Long = now++
}

val trainer = object : CurrentUser { override val trainerId = "trainer-1" }

/** For tests that save exercises without a video. */
object NoMedia : com.trainingapp.domain.media.MediaRepository {
    override fun observe(id: String) = kotlinx.coroutines.flow.flowOf(null)
    override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyMap<String, com.trainingapp.domain.media.MediaAsset>())
    override suspend fun import(video: com.trainingapp.domain.media.PickedVideo, kind: com.trainingapp.contract.media.MediaKind) =
        error("not used")
    override suspend fun retry(id: String) = Unit
    override suspend fun uploadPending() = Unit
}
