package com.trainingapp.data.db

import app.cash.sqldelight.db.SqlDriver
import kotlinx.serialization.json.Json

/** JSON used for stored drafts, versions and own exercises. Unknown keys are ignored so older rows still load. */
internal val DataJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

fun createTrainingDatabase(driver: SqlDriver): TrainingDatabase = TrainingDatabase(driver)
