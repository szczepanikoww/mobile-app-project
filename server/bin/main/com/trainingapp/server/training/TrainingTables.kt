package com.trainingapp.server.training

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// Exposed mappings of the `training` schema (V1). Plan versions and their days/exercises/sets are immutable.

object Exercises : Table("training.exercise") {
    val id = uuid("id")
    val ownerTrainerId = uuid("owner_trainer_id").nullable()
    val sourceName = text("source")
    val sourceId = text("source_id").nullable()
    val trackingType = text("tracking_type")
    val equipment = text("equipment")
    val primaryMuscles = array<String>("primary_muscles")
    val secondaryMuscles = array<String>("secondary_muscles")
    val featured = bool("featured")
    val status = text("status")
    val demoMediaId = uuid("demo_media_id").nullable()
    val revision = integer("revision")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object ExerciseNames : Table("training.exercise_name") {
    val exerciseId = uuid("exercise_id")
    val locale = text("locale")
    val name = text("name")
    val isAlias = bool("is_alias")
    val searchText = text("search_text")
    override val primaryKey = PrimaryKey(exerciseId, locale, name)
}

object ExerciseInstructions : Table("training.exercise_instruction") {
    val exerciseId = uuid("exercise_id")
    val locale = text("locale")
    val steps = array<String>("steps")
    override val primaryKey = PrimaryKey(exerciseId, locale)
}

object PlanTemplates : Table("training.plan_template") {
    val id = uuid("id")
    val ownerTrainerId = uuid("owner_trainer_id")
    val name = text("name")
    val latestVersion = integer("latest_version")
    val archivedAt = timestampWithTimeZone("archived_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object PlanVersions : Table("training.plan_version") {
    val id = uuid("id")
    val templateId = uuid("template_id")
    val number = integer("number")
    val basedOnVersion = integer("based_on_version").nullable()
    val name = text("name")
    val tags = array<String>("tags")
    val createdBy = uuid("created_by")
    val createdAt = timestampWithTimeZone("created_at")
    val operationId = uuid("operation_id")
    override val primaryKey = PrimaryKey(id)
}

object PlanDays : Table("training.plan_day") {
    val planVersionId = uuid("plan_version_id")
    val id = text("id")
    val position = short("position")
    val name = text("name")
    override val primaryKey = PrimaryKey(planVersionId, id)
}

object PlannedExercises : Table("training.planned_exercise") {
    val planVersionId = uuid("plan_version_id")
    val id = text("id")
    val dayId = text("day_id")
    val position = short("position")
    val exerciseId = uuid("exercise_id")
    val supersetGroup = text("superset_group").nullable()
    val restSeconds = integer("rest_seconds").nullable()
    val rpeEnabled = bool("rpe_enabled")
    val accessory = bool("accessory")
    val tempo = text("tempo").nullable()
    val note = text("note").nullable()
    override val primaryKey = PrimaryKey(planVersionId, id)
}

object PlannedSets : Table("training.planned_set") {
    val planVersionId = uuid("plan_version_id")
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

object AssignedPlans : Table("training.assigned_plan") {
    val id = uuid("id")
    val relationshipId = uuid("relationship_id").references(com.trainingapp.server.identity.TrainerClients.id)
    val clientId = uuid("client_id")
    val planVersionId = uuid("plan_version_id")
    val sessionId = uuid("session_id").nullable()
    val status = text("status")
    val startsOn = date("starts_on").nullable()
    val endsOn = date("ends_on").nullable()
    val assignedBy = uuid("assigned_by")
    val assignedAt = timestampWithTimeZone("assigned_at")
    val operationId = uuid("operation_id")
    override val primaryKey = PrimaryKey(id)
}

object WorkoutLogs : Table("training.workout_log") {
    val id = uuid("id")
    val clientId = uuid("client_id")
    val sessionId = uuid("session_id").nullable()
    val assignedPlanId = uuid("assigned_plan_id").nullable()
    val planVersionId = uuid("plan_version_id").nullable()
    val planDayId = text("plan_day_id").nullable()
    val name = text("name")
    val state = text("state")
    val startedAt = timestampWithTimeZone("started_at")
    val finishedAt = timestampWithTimeZone("finished_at").nullable()
    val timeZone = text("time_zone")
    val place = text("place").nullable()
    val note = text("note").nullable()
    val revision = integer("revision")
    val updatedAt = timestampWithTimeZone("updated_at")
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object WorkoutExercises : Table("training.workout_exercise") {
    val id = uuid("id")
    val workoutLogId = uuid("workout_log_id")
    val position = short("position")
    val exerciseId = uuid("exercise_id")
    val plannedExerciseId = text("planned_exercise_id").nullable()
    val supersetGroup = text("superset_group").nullable()
    val note = text("note").nullable()
    val updatedAt = timestampWithTimeZone("updated_at")
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object SetLogs : Table("training.set_log") {
    val id = uuid("id")
    val workoutExerciseId = uuid("workout_exercise_id")
    val workoutLogId = uuid("workout_log_id")
    val exerciseId = uuid("exercise_id")
    val position = short("position")
    val type = text("type")
    val plannedSetId = text("planned_set_id").nullable()
    val weightGrams = integer("weight_grams").nullable()
    val reps = integer("reps").nullable()
    val rpe = decimal("rpe", 3, 1).nullable()
    val durationSeconds = integer("duration_seconds").nullable()
    val distanceMeters = integer("distance_meters").nullable()
    val completedAt = timestampWithTimeZone("completed_at").nullable()
    val techniqueMediaId = uuid("technique_media_id").nullable()
    val revision = integer("revision")
    val updatedAt = timestampWithTimeZone("updated_at")
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object BodyMeasurements : Table("training.body_measurement") {
    val id = uuid("id")
    val clientId = uuid("client_id")
    val kind = text("kind")
    val valueThousandths = long("value_thousandths")
    val measuredAt = timestampWithTimeZone("measured_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}
