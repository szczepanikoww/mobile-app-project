package com.trainingapp.server.training

import com.trainingapp.contract.exercise.Equipment
import com.trainingapp.contract.exercise.ExerciseDto
import com.trainingapp.contract.exercise.ExerciseStatus
import com.trainingapp.contract.exercise.MuscleGroup
import com.trainingapp.contract.exercise.SetType
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.plan.PlanBodyDto
import com.trainingapp.contract.plan.PlanDayDto
import com.trainingapp.contract.plan.PlanVersionDto
import com.trainingapp.contract.plan.PlannedExerciseDto
import com.trainingapp.contract.plan.PlannedSetDto
import com.trainingapp.contract.sync.AssignedPlanDto
import com.trainingapp.contract.sync.AssignedPlanStatusDto
import com.trainingapp.contract.sync.MeasurementChangesDto
import com.trainingapp.contract.sync.MeasurementDto
import com.trainingapp.contract.sync.MeasurementKindDto
import com.trainingapp.contract.sync.MeasurementUpsertDto
import com.trainingapp.contract.sync.SetLogDto
import com.trainingapp.contract.sync.TodayWorkoutDto
import com.trainingapp.contract.sync.WorkoutChangesDto
import com.trainingapp.contract.sync.WorkoutExerciseDto
import com.trainingapp.contract.sync.WorkoutLogDto
import com.trainingapp.contract.sync.WorkoutStateDto
import com.trainingapp.contract.text.SearchText
import com.trainingapp.server.common.Ids
import com.trainingapp.server.common.dbNow
import com.trainingapp.server.common.invalid
import com.trainingapp.server.common.notFound
import com.trainingapp.server.common.toKotlin
import com.trainingapp.server.common.toOffset
import com.trainingapp.server.db.Db
import com.trainingapp.server.identity.CurrentUser
import com.trainingapp.server.identity.IdentityService
import com.trainingapp.server.identity.RelationshipStatus
import com.trainingapp.server.identity.TrainerClients
import com.trainingapp.server.scheduling.DbSessionStatus
import com.trainingapp.server.scheduling.Sessions
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.math.BigDecimal
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

/**
 * The Training module: assigned plans, the exercise library the client may see, workout-log sync and measurements
 * (plan §8.3–§8.4). Plan versions are immutable, so they are assembled once and cached by id in the app.
 */
class TrainingService(private val db: Db, private val identity: IdentityService) {

    /** The overlap the pull cursor goes back by: concurrent transactions can commit out of `updated_at` order. */
    private val pullOverlap = 5.seconds

    // ----- Today's workout and plans -----

    /**
     * R5: the active assignment (DB5: at most one). Pinned to today's confirmed session → that plan; otherwise the
     * first plan day (by position) without a FINISHED workout for this assignment. "Today" is in the client's zone,
     * on the server clock. Null when there is nothing to do.
     */
    suspend fun today(client: CurrentUser): TodayWorkoutDto? = db.tx {
        val now = dbNow()
        val assigned = AssignedPlans.selectAll().where { (AssignedPlans.clientId eq client.id) and (AssignedPlans.status eq "ACTIVE") }
            .singleOrNull() ?: return@tx null
        val versionId = assigned[AssignedPlans.planVersionId]
        val version = planVersion(versionId) ?: return@tx null
        val zone = ZoneId.of(client.timeZone)
        val today = now.toJavaInstant().atZone(zone).toLocalDate()
        val pinned = assigned[AssignedPlans.sessionId]?.let { sid ->
            Sessions.selectAll().where { (Sessions.id eq sid) and (Sessions.status eq DbSessionStatus.CONFIRMED) }.singleOrNull()
        }?.takeIf { it[Sessions.startsAt].toInstant().atZone(zone).toLocalDate() == today }
        val finishedDays = WorkoutLogs.selectAll().where {
            (WorkoutLogs.assignedPlanId eq assigned[AssignedPlans.id]) and (WorkoutLogs.state eq "FINISHED") and WorkoutLogs.deletedAt.isNull()
        }.mapNotNull { it[WorkoutLogs.planDayId] }.toSet()
        val day = version.body.days.firstOrNull { it.id !in finishedDays } ?: return@tx null
        TodayWorkoutDto(
            planVersion = version,
            dayId = day.id,
            assignedPlanId = assigned[AssignedPlans.id].toString(),
            sessionId = pinned?.get(Sessions.id)?.toString(),
            place = pinned?.get(Sessions.placeNote),
        )
    }

    /** Every assignment of the caller (all statuses, so history still resolves), with its immutable version. */
    suspend fun assignedPlans(client: CurrentUser): List<AssignedPlanDto> = db.tx {
        (AssignedPlans innerJoin TrainerClients).selectAll().where { AssignedPlans.clientId eq client.id }
            .orderBy(AssignedPlans.assignedAt, SortOrder.DESC).mapNotNull { row ->
                val version = planVersion(row[AssignedPlans.planVersionId]) ?: return@mapNotNull null
                AssignedPlanDto(
                    id = row[AssignedPlans.id].toString(),
                    trainerId = row[TrainerClients.trainerId].toString(),
                    status = AssignedPlanStatusDto.valueOf(row[AssignedPlans.status]),
                    sessionId = row[AssignedPlans.sessionId]?.toString(),
                    assignedAt = row[AssignedPlans.assignedAt].toKotlin(),
                    planVersion = version,
                )
            }
    }

    fun JdbcTransaction.planVersion(id: Uuid): PlanVersionDto? {
        val v = PlanVersions.selectAll().where { PlanVersions.id eq id }.singleOrNull() ?: return null
        val days = PlanDays.selectAll().where { PlanDays.planVersionId eq id }.orderBy(PlanDays.position).toList()
        val exercises = PlannedExercises.selectAll().where { PlannedExercises.planVersionId eq id }.orderBy(PlannedExercises.position).toList()
        val sets = PlannedSets.selectAll().where { PlannedSets.planVersionId eq id }.orderBy(PlannedSets.position)
            .groupBy { it[PlannedSets.plannedExerciseId] }
        return PlanVersionDto(
            id = id.toString(),
            templateId = v[PlanVersions.templateId].toString(),
            number = v[PlanVersions.number],
            createdAtEpochMs = v[PlanVersions.createdAt].toInstant().toEpochMilli(),
            basedOnVersion = v[PlanVersions.basedOnVersion],
            body = PlanBodyDto(
                name = v[PlanVersions.name],
                tags = v[PlanVersions.tags],
                days = days.map { day ->
                    PlanDayDto(
                        id = day[PlanDays.id],
                        name = day[PlanDays.name],
                        exercises = exercises.filter { it[PlannedExercises.dayId] == day[PlanDays.id] }.map { e ->
                            PlannedExerciseDto(
                                id = e[PlannedExercises.id],
                                exerciseId = e[PlannedExercises.exerciseId].toString(),
                                supersetGroup = e[PlannedExercises.supersetGroup],
                                restSeconds = e[PlannedExercises.restSeconds],
                                rpeEnabled = e[PlannedExercises.rpeEnabled],
                                accessory = e[PlannedExercises.accessory],
                                tempo = e[PlannedExercises.tempo],
                                note = e[PlannedExercises.note],
                                sets = sets[e[PlannedExercises.id]].orEmpty().map { s ->
                                    PlannedSetDto(
                                        id = s[PlannedSets.id],
                                        type = SetType.valueOf(s[PlannedSets.type]),
                                        repsMin = s[PlannedSets.repsMin],
                                        repsMax = s[PlannedSets.repsMax],
                                        weightGrams = s[PlannedSets.weightGrams],
                                        durationSeconds = s[PlannedSets.durationSeconds],
                                        distanceMeters = s[PlannedSets.distanceMeters],
                                        rpe = s[PlannedSets.rpe]?.toDouble(),
                                        restSecondsOverride = s[PlannedSets.restSecondsOverride],
                                    )
                                },
                            )
                        },
                    )
                },
            ),
        )
    }

    // ----- Exercises -----

    /**
     * The global library plus exercises of trainers the caller has (or had) a relationship with. Hidden exercises are
     * returned only when asked for by id, so history still resolves.
     */
    suspend fun exercises(user: CurrentUser, ids: List<Uuid>?, updatedSince: Instant?): List<ExerciseDto> = db.tx {
        val trainers = with(identity) { relationshipsOfClient(user.id) }.filter { it.status != RelationshipStatus.PENDING }.map { it.trainerId } +
            (if (user.isTrainer) listOf(user.id) else emptyList())
        val visible = Exercises.ownerTrainerId.isNull() or (Exercises.ownerTrainerId inList trainers.ifEmpty { listOf(Uuid.NIL) })
        val rows = Exercises.selectAll().where {
            var op = visible
            if (ids != null) op = op and (Exercises.id inList ids.ifEmpty { listOf(Uuid.NIL) })
            else op = op and (Exercises.status eq "ACTIVE")
            if (updatedSince != null) op = op and (Exercises.updatedAt greater updatedSince.toOffset())
            op
        }.limit(2000).toList()
        val exerciseIds = rows.map { it[Exercises.id] }
        if (exerciseIds.isEmpty()) return@tx emptyList()
        val names = ExerciseNames.selectAll().where { ExerciseNames.exerciseId inList exerciseIds }.groupBy { it[ExerciseNames.exerciseId] }
        val instructions = ExerciseInstructions.selectAll().where { ExerciseInstructions.exerciseId inList exerciseIds }
            .groupBy { it[ExerciseInstructions.exerciseId] }
        rows.map { row ->
            val id = row[Exercises.id]
            val exerciseNames = names[id].orEmpty()
            ExerciseDto(
                id = id.toString(),
                ownerTrainerId = row[Exercises.ownerTrainerId]?.toString(),
                source = row[Exercises.sourceName],
                sourceId = row[Exercises.sourceId],
                names = exerciseNames.filter { !it[ExerciseNames.isAlias] }.associate { it[ExerciseNames.locale] to it[ExerciseNames.name] },
                aliases = exerciseNames.filter { it[ExerciseNames.isAlias] }.map { it[ExerciseNames.name] },
                primaryMuscles = row[Exercises.primaryMuscles].mapNotNull { m -> MuscleGroup.entries.firstOrNull { it.name == m } },
                secondaryMuscles = row[Exercises.secondaryMuscles].mapNotNull { m -> MuscleGroup.entries.firstOrNull { it.name == m } },
                equipment = Equipment.entries.firstOrNull { it.name == row[Exercises.equipment] } ?: Equipment.OTHER,
                trackingType = TrackingType.valueOf(row[Exercises.trackingType]),
                instructions = instructions[id].orEmpty().associate { it[ExerciseInstructions.locale] to it[ExerciseInstructions.steps] },
                demoMediaId = row[Exercises.demoMediaId]?.toString(),
                featured = row[Exercises.featured],
                status = ExerciseStatus.valueOf(row[Exercises.status]),
                updatedAtEpochMs = row[Exercises.updatedAt].toInstant().toEpochMilli(),
            )
        }
    }

    fun JdbcTransaction.exerciseVisibleTo(exerciseId: Uuid, clientId: Uuid): Boolean {
        val row = Exercises.selectAll().where { Exercises.id eq exerciseId }.singleOrNull() ?: return false
        val owner = row[Exercises.ownerTrainerId] ?: return true
        return with(identity) { relationshipsOfClient(clientId) }.any { it.trainerId == owner && it.status != RelationshipStatus.PENDING }
    }

    // ----- Workout pull, technique video link -----

    suspend fun changes(client: CurrentUser, since: Instant?): WorkoutChangesDto = db.tx {
        val now = dbNow()
        val from = (since ?: Instant.fromEpochMilliseconds(0)) - pullOverlap
        val logs = WorkoutLogs.selectAll().where { (WorkoutLogs.clientId eq client.id) and (WorkoutLogs.updatedAt greater from.toOffset()) }
            .map { it.toLogDto() }
        val ownLogIds = WorkoutLogs.selectAll().where { WorkoutLogs.clientId eq client.id }.map { it[WorkoutLogs.id] }
        val exercises = if (ownLogIds.isEmpty()) emptyList() else WorkoutExercises.selectAll()
            .where { (WorkoutExercises.workoutLogId inList ownLogIds) and (WorkoutExercises.updatedAt greater from.toOffset()) }
            .map { it.toExerciseDto() }
        val sets = if (ownLogIds.isEmpty()) emptyList() else SetLogs.selectAll()
            .where { (SetLogs.workoutLogId inList ownLogIds) and (SetLogs.updatedAt greater from.toOffset()) }
            .map { it.toSetDto() }
        WorkoutChangesDto(logs, exercises, sets, cursor = now)
    }

    /** The set exists on the server and belongs to the client's log (a message may only reference synced sets). */
    fun JdbcTransaction.setBelongsTo(setLogId: Uuid, workoutLogId: Uuid?, clientId: Uuid): Boolean {
        val set = SetLogs.selectAll().where { SetLogs.id eq setLogId }.singleOrNull() ?: return false
        if (workoutLogId != null && set[SetLogs.workoutLogId] != workoutLogId) return false
        return WorkoutLogs.selectAll().where { (WorkoutLogs.id eq set[SetLogs.workoutLogId]) and (WorkoutLogs.clientId eq clientId) }.any()
    }

    /** `set_log.technique_media_id` as a sync-style update: the revision moves, so other devices pull it. */
    fun JdbcTransaction.attachTechniqueMedia(setLogId: Uuid, mediaId: Uuid) {
        val set = SetLogs.selectAll().where { SetLogs.id eq setLogId }.single()
        if (set[SetLogs.techniqueMediaId] == mediaId) return
        SetLogs.update({ SetLogs.id eq setLogId }) {
            it[techniqueMediaId] = mediaId
            it[revision] = set[SetLogs.revision] + 1
            it[updatedAt] = dbNow().toOffset()
        }
    }

    // ----- Measurements -----

    suspend fun measurements(client: CurrentUser, since: Instant?): MeasurementChangesDto = db.tx {
        val now = dbNow()
        val from = (since ?: Instant.fromEpochMilliseconds(0)) - pullOverlap
        MeasurementChangesDto(
            BodyMeasurements.selectAll().where { (BodyMeasurements.clientId eq client.id) and (BodyMeasurements.updatedAt greater from.toOffset()) }
                .orderBy(BodyMeasurements.measuredAt, SortOrder.DESC).map { it.toMeasurementDto() },
            cursor = now,
        )
    }

    /** Idempotent by the phone's id; another client's id is refused. */
    suspend fun putMeasurement(client: CurrentUser, id: Uuid, body: MeasurementUpsertDto): MeasurementDto {
        if (body.valueThousandths <= 0 || body.valueThousandths > 1_000_000_000L) throw invalid("valueThousandths")
        return db.tx {
            val existing = BodyMeasurements.selectAll().where { BodyMeasurements.id eq id }.singleOrNull()
            if (existing != null && existing[BodyMeasurements.clientId] != client.id) throw notFound("measurement")
            val now = dbNow().toOffset()
            BodyMeasurements.upsert(BodyMeasurements.id) {
                it[BodyMeasurements.id] = id
                it[clientId] = client.id
                it[kind] = body.kind.name
                it[valueThousandths] = body.valueThousandths
                it[measuredAt] = body.measuredAt.toOffset()
                it[updatedAt] = now
                it[deletedAt] = null
            }
            BodyMeasurements.selectAll().where { BodyMeasurements.id eq id }.single().toMeasurementDto()
        }
    }

    suspend fun deleteMeasurement(client: CurrentUser, id: Uuid) = db.tx {
        val now = dbNow().toOffset()
        BodyMeasurements.update({ (BodyMeasurements.id eq id) and (BodyMeasurements.clientId eq client.id) and BodyMeasurements.deletedAt.isNull() }) {
            it[deletedAt] = now
            it[updatedAt] = now
        }
        Unit
    }

    // ----- Trainer transitions and seed helpers (plan §8.7) -----

    /** Old ACTIVE → REPLACED and the new assignment ACTIVE, in one transaction (DB5: one active plan per client). */
    suspend fun assignPlan(trainerId: Uuid, relationshipId: Uuid, planVersionId: Uuid, sessionId: Uuid?, operationId: Uuid): Uuid = db.tx {
        assignPlanIn(trainerId, relationshipId, planVersionId, sessionId, operationId)
    }

    fun JdbcTransaction.assignPlanIn(trainerId: Uuid, relationshipId: Uuid, planVersionId: Uuid, sessionId: Uuid?, operationId: Uuid): Uuid {
        AssignedPlans.selectAll().where { AssignedPlans.operationId eq operationId }.singleOrNull()?.let { return it[AssignedPlans.id] }
        val relationship = with(identity) { relationship(relationshipId) }
        if (relationship == null || relationship.trainerId != trainerId || !relationship.isActive) throw notFound("relationship")
        if (PlanVersions.selectAll().where { PlanVersions.id eq planVersionId }.none()) throw invalid("planVersionId")
        AssignedPlans.update({ (AssignedPlans.clientId eq relationship.clientId) and (AssignedPlans.status eq "ACTIVE") }) { it[status] = "REPLACED" }
        val id = Ids.v7()
        AssignedPlans.insert {
            it[AssignedPlans.id] = id
            it[AssignedPlans.relationshipId] = relationshipId
            it[clientId] = relationship.clientId
            it[AssignedPlans.planVersionId] = planVersionId
            it[AssignedPlans.sessionId] = sessionId
            it[status] = "ACTIVE"
            it[assignedBy] = trainerId
            it[AssignedPlans.operationId] = operationId
        }
        return id
    }

    /** Publishes an immutable plan version (seed data; the trainer plan API is a separate plan). */
    fun JdbcTransaction.publishPlan(trainerId: Uuid, templateId: Uuid, body: PlanBodyDto, operationId: Uuid): Uuid {
        PlanVersions.selectAll().where { PlanVersions.operationId eq operationId }.singleOrNull()?.let { return it[PlanVersions.id] }
        val template = PlanTemplates.selectAll().where { PlanTemplates.id eq templateId }.singleOrNull()
        val number = (template?.get(PlanTemplates.latestVersion) ?: 0) + 1
        if (template == null) {
            PlanTemplates.insert {
                it[id] = templateId
                it[ownerTrainerId] = trainerId
                it[name] = body.name
                it[latestVersion] = number
            }
        } else {
            PlanTemplates.update({ PlanTemplates.id eq templateId }) { it[latestVersion] = number; it[name] = body.name }
        }
        val versionId = Ids.v7()
        PlanVersions.insert {
            it[id] = versionId
            it[PlanVersions.templateId] = templateId
            it[PlanVersions.number] = number
            it[basedOnVersion] = template?.get(PlanTemplates.latestVersion)
            it[name] = body.name
            it[tags] = body.tags
            it[createdBy] = trainerId
            it[PlanVersions.operationId] = operationId
        }
        body.days.forEachIndexed { dayIndex, day ->
            PlanDays.insert { it[planVersionId] = versionId; it[id] = day.id; it[position] = dayIndex.toShort(); it[name] = day.name }
            day.exercises.forEachIndexed { exIndex, e ->
                PlannedExercises.insert {
                    it[planVersionId] = versionId
                    it[id] = e.id
                    it[dayId] = day.id
                    it[position] = exIndex.toShort()
                    it[exerciseId] = Uuid.parse(e.exerciseId)
                    it[supersetGroup] = e.supersetGroup
                    it[restSeconds] = e.restSeconds
                    it[rpeEnabled] = e.rpeEnabled
                    it[accessory] = e.accessory
                    it[tempo] = e.tempo
                    it[note] = e.note
                }
                e.sets.forEachIndexed { setIndex, s ->
                    PlannedSets.insert {
                        it[planVersionId] = versionId
                        it[id] = s.id
                        it[plannedExerciseId] = e.id
                        it[position] = setIndex.toShort()
                        it[type] = s.type.name
                        it[repsMin] = s.repsMin
                        it[repsMax] = s.repsMax
                        it[weightGrams] = s.weightGrams
                        it[durationSeconds] = s.durationSeconds
                        it[distanceMeters] = s.distanceMeters
                        it[rpe] = s.rpe?.let { r -> BigDecimal.valueOf(r) }
                        it[restSecondsOverride] = s.restSecondsOverride
                    }
                }
            }
        }
        return versionId
    }

    /** A global library exercise (seed import upserts by `(source, source_id)` later). */
    fun JdbcTransaction.insertGlobalExercise(id: Uuid, sourceId: String, namePl: String, nameEn: String, tracking: TrackingType, equipment: Equipment, primary: MuscleGroup) {
        Exercises.insert {
            it[Exercises.id] = id
            it[sourceName] = "free-exercise-db"
            it[Exercises.sourceId] = sourceId
            it[trackingType] = tracking.name
            it[Exercises.equipment] = equipment.name
            it[primaryMuscles] = listOf(primary.name)
            it[secondaryMuscles] = emptyList()
            it[featured] = false
            it[status] = "ACTIVE"
            it[revision] = 1
        }
        for ((locale, name) in listOf("pl" to namePl, "en" to nameEn)) {
            ExerciseNames.insert {
                it[exerciseId] = id
                it[ExerciseNames.locale] = locale
                it[ExerciseNames.name] = name
                it[isAlias] = false
                it[searchText] = SearchText.normalize(name)
            }
        }
    }
}

internal fun ResultRow.toLogDto() = WorkoutLogDto(
    id = this[WorkoutLogs.id].toString(),
    sessionId = this[WorkoutLogs.sessionId]?.toString(),
    assignedPlanId = this[WorkoutLogs.assignedPlanId]?.toString(),
    planVersionId = this[WorkoutLogs.planVersionId]?.toString(),
    planDayId = this[WorkoutLogs.planDayId],
    name = this[WorkoutLogs.name],
    state = WorkoutStateDto.valueOf(this[WorkoutLogs.state]),
    startedAt = this[WorkoutLogs.startedAt].toKotlin(),
    finishedAt = this[WorkoutLogs.finishedAt]?.toKotlin(),
    timeZone = this[WorkoutLogs.timeZone],
    place = this[WorkoutLogs.place],
    note = this[WorkoutLogs.note],
    revision = this[WorkoutLogs.revision],
    updatedAt = this[WorkoutLogs.updatedAt].toKotlin(),
    deletedAt = this[WorkoutLogs.deletedAt]?.toKotlin(),
)

internal fun ResultRow.toExerciseDto() = WorkoutExerciseDto(
    id = this[WorkoutExercises.id].toString(),
    workoutLogId = this[WorkoutExercises.workoutLogId].toString(),
    position = this[WorkoutExercises.position].toInt(),
    exerciseId = this[WorkoutExercises.exerciseId].toString(),
    plannedExerciseId = this[WorkoutExercises.plannedExerciseId],
    supersetGroup = this[WorkoutExercises.supersetGroup],
    note = this[WorkoutExercises.note],
    updatedAt = this[WorkoutExercises.updatedAt].toKotlin(),
    deletedAt = this[WorkoutExercises.deletedAt]?.toKotlin(),
)

internal fun ResultRow.toSetDto() = SetLogDto(
    id = this[SetLogs.id].toString(),
    workoutExerciseId = this[SetLogs.workoutExerciseId].toString(),
    position = this[SetLogs.position].toInt(),
    type = SetType.valueOf(this[SetLogs.type]),
    plannedSetId = this[SetLogs.plannedSetId],
    weightGrams = this[SetLogs.weightGrams],
    reps = this[SetLogs.reps],
    rpe = this[SetLogs.rpe]?.toDouble(),
    durationSeconds = this[SetLogs.durationSeconds],
    distanceMeters = this[SetLogs.distanceMeters],
    completedAt = this[SetLogs.completedAt]?.toKotlin(),
    techniqueMediaId = this[SetLogs.techniqueMediaId]?.toString(),
    revision = this[SetLogs.revision],
    updatedAt = this[SetLogs.updatedAt].toKotlin(),
    deletedAt = this[SetLogs.deletedAt]?.toKotlin(),
)

internal fun ResultRow.toMeasurementDto() = MeasurementDto(
    id = this[BodyMeasurements.id].toString(),
    kind = MeasurementKindDto.valueOf(this[BodyMeasurements.kind]),
    valueThousandths = this[BodyMeasurements.valueThousandths],
    measuredAt = this[BodyMeasurements.measuredAt].toKotlin(),
    updatedAt = this[BodyMeasurements.updatedAt].toKotlin(),
    deletedAt = this[BodyMeasurements.deletedAt]?.toKotlin(),
)

