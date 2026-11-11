package com.trainingapp.server.db

import com.trainingapp.server.platform.tstz
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date

/** Exercise library entry (global or trainer-owned). */
object Exercises : Table("training.exercise") {
    val id = javaUUID("id")
    val ownerTrainerId = javaUUID("owner_trainer_id").nullable()
    val sourceName = text("source")
    val sourceId = text("source_id").nullable()
    val trackingType = text("tracking_type")
    val equipment = text("equipment")
    val primaryMuscles = array<String>("primary_muscles")
    val secondaryMuscles = array<String>("secondary_muscles")
    val featured = bool("featured")
    val status = text("status")
    val demoMediaId = javaUUID("demo_media_id").nullable()
    val revision = integer("revision")
    val createdAt = tstz("created_at")
    val updatedAt = tstz("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/** Localised exercise names and search aliases. */
object ExerciseNames : Table("training.exercise_name") {
    val exerciseId = javaUUID("exercise_id")
    val locale = text("locale")
    val name = text("name")
    val isAlias = bool("is_alias")
    val searchText = text("search_text")
    override val primaryKey = PrimaryKey(exerciseId, locale, name)
}

/** Localised step-by-step instructions of an exercise. */
object ExerciseInstructions : Table("training.exercise_instruction") {
    val exerciseId = javaUUID("exercise_id")
    val locale = text("locale")
    val steps = array<String>("steps")
    override val primaryKey = PrimaryKey(exerciseId, locale)
}

/** Ordered images of an exercise. */
object ExerciseImages : Table("training.exercise_image") {
    val exerciseId = javaUUID("exercise_id")
    val position = short("position")
    val mediaId = javaUUID("media_id")
    override val primaryKey = PrimaryKey(exerciseId, position)
}

/** Plan template (mutable header; content lives in immutable versions). */
object PlanTemplates : Table("training.plan_template") {
    val id = javaUUID("id")
    val ownerTrainerId = javaUUID("owner_trainer_id")
    val name = text("name")
    val latestVersion = integer("latest_version")
    val archivedAt = tstz("archived_at").nullable()
    val createdAt = tstz("created_at")
    val updatedAt = tstz("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/** Immutable published version of a plan template. */
object PlanVersions : Table("training.plan_version") {
    val id = javaUUID("id")
    val templateId = javaUUID("template_id")
    val number = integer("number")
    val basedOnVersion = integer("based_on_version").nullable()
    val name = text("name")
    val tags = array<String>("tags")
    val createdBy = javaUUID("created_by")
    val createdAt = tstz("created_at")
    val operationId = javaUUID("operation_id")
    override val primaryKey = PrimaryKey(id)
}

/** Immutable day of a plan version; id is unique only within the version. */
object PlanDays : Table("training.plan_day") {
    val planVersionId = javaUUID("plan_version_id")
    val id = text("id")
    val position = short("position")
    val name = text("name")
    override val primaryKey = PrimaryKey(planVersionId, id)
}

/** Immutable planned exercise within a plan day. */
object PlannedExercises : Table("training.planned_exercise") {
    val planVersionId = javaUUID("plan_version_id")
    val id = text("id")
    val dayId = text("day_id")
    val position = short("position")
    val exerciseId = javaUUID("exercise_id")
    val supersetGroup = text("superset_group").nullable()
    val restSeconds = integer("rest_seconds").nullable()
    val rpeEnabled = bool("rpe_enabled")
    val accessory = bool("accessory")
    val tempo = text("tempo").nullable()
    val note = text("note").nullable()
    override val primaryKey = PrimaryKey(planVersionId, id)
}

/** Immutable planned set within a planned exercise. */
object PlannedSets : Table("training.planned_set") {
    val planVersionId = javaUUID("plan_version_id")
    val id = text("id")
    val plannedExerciseId = text("planned_exercise_id")
    val position = short("position")
    val type = text("type")
    val repsMin = integer("reps_min").nullable()
    val repsMax = integer("reps_max").nullable()
    val weightGrams = integer("weight_grams").nullable()
    val durationSeconds = integer("duration_seconds").nullable()
    val distanceMeters = integer("distance_meters").nullable()
    val rpe = decimal("rpe", 3, 1).nullable()
    val restSecondsOverride = integer("rest_seconds_override").nullable()
    override val primaryKey = PrimaryKey(planVersionId, id)
}

/** A plan version assigned to a client; at most one ACTIVE per client. */
object AssignedPlans : Table("training.assigned_plan") {
    val id = javaUUID("id")
    val relationshipId = javaUUID("relationship_id")
    val clientId = javaUUID("client_id")
    val planVersionId = javaUUID("plan_version_id")
    val sessionId = javaUUID("session_id").nullable()
    val status = text("status")
    val startsOn = date("starts_on").nullable()
    val endsOn = date("ends_on").nullable()
    val assignedBy = javaUUID("assigned_by")
    val assignedAt = tstz("assigned_at")
    val operationId = javaUUID("operation_id")
    override val primaryKey = PrimaryKey(id)
}

/** A workout the client did; id is generated on the phone (offline logger). */
object WorkoutLogs : Table("training.workout_log") {
    val id = javaUUID("id")
    val clientId = javaUUID("client_id")
    val sessionId = javaUUID("session_id").nullable()
    val assignedPlanId = javaUUID("assigned_plan_id").nullable()
    val planVersionId = javaUUID("plan_version_id").nullable()
    val planDayId = text("plan_day_id").nullable()
    val name = text("name")
    val state = text("state")
    val startedAt = tstz("started_at")
    val finishedAt = tstz("finished_at").nullable()
    val timeZone = text("time_zone")
    val place = text("place").nullable()
    val note = text("note").nullable()
    val revision = integer("revision")
    val updatedAt = tstz("updated_at")
    val deletedAt = tstz("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** One exercise inside a workout log. */
object WorkoutExercises : Table("training.workout_exercise") {
    val id = javaUUID("id")
    val workoutLogId = javaUUID("workout_log_id")
    val position = short("position")
    val exerciseId = javaUUID("exercise_id")
    val plannedExerciseId = text("planned_exercise_id").nullable()
    val supersetGroup = text("superset_group").nullable()
    val note = text("note").nullable()
    val updatedAt = tstz("updated_at")
    val deletedAt = tstz("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** One logged set; client-generated id makes retries idempotent. */
object SetLogs : Table("training.set_log") {
    val id = javaUUID("id")
    val workoutExerciseId = javaUUID("workout_exercise_id")
    val workoutLogId = javaUUID("workout_log_id")
    val exerciseId = javaUUID("exercise_id")
    val position = short("position")
    val type = text("type")
    val plannedSetId = text("planned_set_id").nullable()
    val weightGrams = integer("weight_grams").nullable()
    val reps = integer("reps").nullable()
    val rpe = decimal("rpe", 3, 1).nullable()
    val durationSeconds = integer("duration_seconds").nullable()
    val distanceMeters = integer("distance_meters").nullable()
    val completedAt = tstz("completed_at").nullable()
    val techniqueMediaId = javaUUID("technique_media_id").nullable()
    val revision = integer("revision")
    val updatedAt = tstz("updated_at")
    val deletedAt = tstz("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Private body measurements in thousandths of the kind's unit. */
object BodyMeasurements : Table("training.body_measurement") {
    val id = javaUUID("id")
    val clientId = javaUUID("client_id")
    val kind = text("kind")
    val valueThousandths = long("value_thousandths")
    val measuredAt = tstz("measured_at")
    val updatedAt = tstz("updated_at")
    val deletedAt = tstz("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Read-only view: best working set per client and exercise (derived personal records). */
object PersonalRecordView : Table("training.personal_record") {
    val clientId = javaUUID("client_id")
    val exerciseId = javaUUID("exercise_id")
    val setLogId = javaUUID("set_log_id")
    val weightGrams = integer("weight_grams").nullable()
    val reps = integer("reps").nullable()
    val completedAt = tstz("completed_at").nullable()
}
