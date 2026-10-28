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
