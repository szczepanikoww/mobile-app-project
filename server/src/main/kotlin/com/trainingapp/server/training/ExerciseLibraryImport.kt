package com.trainingapp.server.training

import com.trainingapp.contract.exercise.ExerciseDto
import com.trainingapp.contract.exercise.ExerciseSources
import com.trainingapp.contract.exercise.ExerciseStatus
import com.trainingapp.contract.text.SearchText
import com.trainingapp.server.db.ExerciseInstructions
import com.trainingapp.server.db.ExerciseNames
import com.trainingapp.server.db.Exercises
import com.trainingapp.server.platform.ApiJson
import com.trainingapp.server.platform.Db
import com.trainingapp.server.platform.ServerClock
import com.trainingapp.server.platform.toOffset
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.util.UUID
import kotlin.time.Instant

/**
 * Loads the global `free-exercise-db` library (same JSON the app bundles) into `training.exercise`, upserting by
 * (source, source_id). Exercise images are storage keys in the file; they are not imported (exercise_image needs
 * media assets), so `ExerciseDto.images` is empty from the server.
 */
object ExerciseLibraryImport {
    private const val RESOURCE = "seed/exercise_library.json"

    /** Imports the bundled library when there are no global exercises yet. */
    suspend fun importIfEmpty(db: Db, clock: ServerClock) {
        val empty = db.tx { Exercises.selectAll().where { Exercises.ownerTrainerId.isNull() }.limit(1).empty() }
        if (!empty) return
        val json = ExerciseLibraryImport::class.java.classLoader.getResourceAsStream(RESOURCE)
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
        importFromJson(db, json, clock.now())
    }

    suspend fun importFromJson(db: Db, json: String, now: Instant) {
        val items = ApiJson.decodeFromString(ListSerializer(ExerciseDto.serializer()), json)
        db.tx { items.forEach { upsert(it, now) } }
    }

    private fun upsert(e: ExerciseDto, now: Instant) {
        val srcId = e.sourceId ?: return
        val at = now.toOffset()
        val existing = Exercises.selectAll()
            .where { (Exercises.sourceName eq ExerciseSources.FREE_EXERCISE_DB) and (Exercises.sourceId eq srcId) }
            .singleOrNull()
        val id: UUID
        if (existing == null) {
            id = UUID.fromString(e.id)
            Exercises.insert {
                it[Exercises.id] = id
                it[ownerTrainerId] = null
                it[sourceName] = ExerciseSources.FREE_EXERCISE_DB
                it[sourceId] = srcId
                it[trackingType] = e.trackingType.name
                it[equipment] = e.equipment.name
                it[primaryMuscles] = e.primaryMuscles.map { m -> m.name }
                it[secondaryMuscles] = e.secondaryMuscles.map { m -> m.name }
                it[featured] = e.featured
                it[status] = ExerciseStatus.ACTIVE.name
                it[revision] = 1
                it[createdAt] = at
                it[updatedAt] = at
            }
        } else {
            id = existing[Exercises.id]
            Exercises.update({ Exercises.id eq id }) {
                it[trackingType] = e.trackingType.name
                it[equipment] = e.equipment.name
                it[primaryMuscles] = e.primaryMuscles.map { m -> m.name }
                it[secondaryMuscles] = e.secondaryMuscles.map { m -> m.name }
                it[featured] = e.featured
                it[revision] = existing[Exercises.revision] + 1
                it[updatedAt] = at
            }
            ExerciseNames.deleteWhere { exerciseId eq id }
            ExerciseInstructions.deleteWhere { exerciseId eq id }
        }
        e.names.forEach { (locale, name) -> insertName(id, locale, name, false) }
        // Dataset aliases are English search terms.
        e.aliases.filter { a -> e.names["en"] != a }.distinct().forEach { insertName(id, "en", it, true) }
        e.instructions.filterValues { it.isNotEmpty() }.forEach { (locale, steps) ->
            ExerciseInstructions.insert {
                it[exerciseId] = id
                it[ExerciseInstructions.locale] = locale
                it[ExerciseInstructions.steps] = steps
            }
        }
    }

    private fun insertName(id: UUID, locale: String, name: String, alias: Boolean) {
        ExerciseNames.insert {
            it[exerciseId] = id
            it[ExerciseNames.locale] = locale
            it[ExerciseNames.name] = name
            it[isAlias] = alias
            it[searchText] = SearchText.normalize(name)
        }
    }
}
