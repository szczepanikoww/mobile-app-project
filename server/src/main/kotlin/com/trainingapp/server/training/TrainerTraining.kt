package com.trainingapp.server.training

import com.trainingapp.contract.api.AccessScope
import com.trainingapp.contract.api.AssignPlanRequest
import com.trainingapp.contract.api.AssignedPlanDto
import com.trainingapp.contract.api.MeasurementDto
import com.trainingapp.contract.api.PersonalRecordDto
import com.trainingapp.contract.api.WorkoutExerciseDto
import com.trainingapp.contract.api.WorkoutLogDto
import com.trainingapp.contract.api.WorkoutSetDto
import com.trainingapp.contract.exercise.Equipment
import com.trainingapp.contract.exercise.ExerciseDto
import com.trainingapp.contract.exercise.ExerciseSources
import com.trainingapp.contract.exercise.ExerciseStatus
import com.trainingapp.contract.exercise.ExerciseValidator
import com.trainingapp.contract.exercise.MuscleGroup
import com.trainingapp.contract.exercise.SetType
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.exercise.UpsertExerciseRequest
import com.trainingapp.contract.plan.PlanBodyDto
import com.trainingapp.contract.plan.PlanDayDto
import com.trainingapp.contract.plan.PlanTemplateDto
import com.trainingapp.contract.plan.PlanValidator
import com.trainingapp.contract.plan.PlanVersionDto
import com.trainingapp.contract.plan.PlannedExerciseDto
import com.trainingapp.contract.plan.PlannedSetDto
import com.trainingapp.contract.plan.PublishPlanRequest
import com.trainingapp.contract.plan.PublishPlanResponse
import com.trainingapp.contract.text.SearchText
import com.trainingapp.server.auth.Access
import com.trainingapp.server.auth.AuditLog
import com.trainingapp.server.db.AssignedPlans
import com.trainingapp.server.db.BodyMeasurements
import com.trainingapp.server.db.ExerciseInstructions
import com.trainingapp.server.db.ExerciseNames
import com.trainingapp.server.db.Exercises
import com.trainingapp.server.db.PersonalRecordView
import com.trainingapp.server.db.PlanDays
import com.trainingapp.server.db.PlanTemplates
import com.trainingapp.server.db.PlanVersions
import com.trainingapp.server.db.PlannedExercises
import com.trainingapp.server.db.PlannedSets
import com.trainingapp.server.db.Sessions
import com.trainingapp.server.db.SetLogs
import com.trainingapp.server.db.TrainerClients
import com.trainingapp.server.db.WorkoutExercises
import com.trainingapp.server.db.WorkoutLogs
import com.trainingapp.server.platform.Db
import com.trainingapp.server.platform.ServerClock
import com.trainingapp.server.platform.conflict
import com.trainingapp.server.platform.denied
import com.trainingapp.server.platform.invalid
import com.trainingapp.server.platform.newId
import com.trainingapp.server.platform.notFound
import com.trainingapp.server.platform.toInstantK
import com.trainingapp.server.platform.toOffset
import com.trainingapp.server.platform.toUuid
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID
import kotlin.time.Instant

/**
 * Trainer's exercise library, immutable plan versions, plan assignment and read access to a client's progress
 * (architecture §9, database-design "training"). Every command runs in one transaction.
 */
class TrainerTraining(private val db: Db, private val clock: ServerClock, private val audit: AuditLog) {

    // ------------------------------------------------------------------ exercises

    /** Global and own exercises (own HIDDEN ones too, so the app can sync status), oldest change first. */
    suspend fun exercises(trainerId: UUID, updatedSince: Instant?): List<ExerciseDto> = db.tx {
        val rows = Exercises.selectAll().where {
            val visible = Exercises.ownerTrainerId.isNull() or (Exercises.ownerTrainerId eq trainerId)
            if (updatedSince != null) visible and (Exercises.updatedAt greater updatedSince.toOffset()) else visible
        }.orderBy(Exercises.updatedAt to SortOrder.ASC, Exercises.id to SortOrder.ASC).toList()
        exerciseDtos(rows)
    }

    /** Creates or updates the trainer's own exercise. Idempotent by the client-generated [id]. */
    suspend fun upsertExercise(trainerId: UUID, id: UUID, request: UpsertExerciseRequest): ExerciseDto = db.tx {
        if (request.id.toUuid("id") != id) invalid("Id mismatch")
        val issues = ExerciseValidator.validate(request)
        if (issues.isNotEmpty()) invalid("Invalid exercise", issues.map { "${it.field}:${it.problem}" })
        if (request.names.keys.any { it !in LOCALES } || request.instructions.keys.any { it !in LOCALES }) {
            invalid("Unsupported locale")
        }
        val now = clock.now().toOffset()
        val existing = Exercises.selectAll().where { Exercises.id eq id }.forUpdate().singleOrNull()
        if (existing == null) {
            Exercises.insert {
                it[Exercises.id] = id
                it[ownerTrainerId] = trainerId
                it[sourceName] = ExerciseSources.TRAINER
                it[sourceId] = null
                it[trackingType] = request.trackingType.name
                it[equipment] = request.equipment.name
                it[primaryMuscles] = listOf(request.primaryMuscle.name)
                it[secondaryMuscles] = request.secondaryMuscles.map { m -> m.name }
                it[featured] = false
                it[status] = ExerciseStatus.ACTIVE.name
                it[demoMediaId] = request.demoMediaId?.toUuid("demoMediaId")
                it[revision] = 1
                it[createdAt] = now
                it[updatedAt] = now
            }
        } else {
            if (existing[Exercises.ownerTrainerId] != trainerId) denied("Only your own exercises can be edited")
            if (existing[Exercises.trackingType] != request.trackingType.name &&
                !PlannedExercises.selectAll().where { PlannedExercises.exerciseId eq id }.limit(1).empty()
            ) {
                conflict("Tracking type cannot change once the exercise is used in a plan")
            }
            Exercises.update({ Exercises.id eq id }) {
                it[trackingType] = request.trackingType.name
                it[equipment] = request.equipment.name
                it[primaryMuscles] = listOf(request.primaryMuscle.name)
                it[secondaryMuscles] = request.secondaryMuscles.map { m -> m.name }
                it[demoMediaId] = request.demoMediaId?.toUuid("demoMediaId")
                it[revision] = existing[Exercises.revision] + 1
                it[updatedAt] = now
            }
            ExerciseNames.deleteWhere { exerciseId eq id }
            ExerciseInstructions.deleteWhere { exerciseId eq id }
        }
        request.names.forEach { (locale, name) ->
            val trimmed = name.trim()
            ExerciseNames.insert {
                it[exerciseId] = id
                it[ExerciseNames.locale] = locale
                it[ExerciseNames.name] = trimmed
                it[isAlias] = false
                it[searchText] = SearchText.normalize(trimmed)
            }
        }
        request.instructions.filterValues { it.isNotEmpty() }.forEach { (locale, steps) ->
            ExerciseInstructions.insert {
                it[exerciseId] = id
                it[ExerciseInstructions.locale] = locale
                it[ExerciseInstructions.steps] = steps
            }
        }
        exerciseDtos(Exercises.selectAll().where { Exercises.id eq id }.toList()).single()
    }

    suspend fun setExerciseHidden(trainerId: UUID, id: UUID, hidden: Boolean): ExerciseDto = db.tx {
        val row = Exercises.selectAll().where { Exercises.id eq id }.forUpdate().singleOrNull() ?: notFound("Exercise not found")
        if (row[Exercises.ownerTrainerId] != trainerId) denied("Only your own exercises can be hidden")
        val target = if (hidden) ExerciseStatus.HIDDEN else ExerciseStatus.ACTIVE
        if (row[Exercises.status] != target.name) {
            Exercises.update({ Exercises.id eq id }) {
                it[status] = target.name
                it[revision] = row[Exercises.revision] + 1
                it[updatedAt] = clock.now().toOffset()
            }
        }
        exerciseDtos(Exercises.selectAll().where { Exercises.id eq id }.toList()).single()
    }

    private fun exerciseDtos(rows: List<ResultRow>): List<ExerciseDto> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it[Exercises.id] }
        val names = ids.chunked(500).flatMap { c -> ExerciseNames.selectAll().where { ExerciseNames.exerciseId inList c }.toList() }
            .groupBy { it[ExerciseNames.exerciseId] }
        val steps = ids.chunked(500).flatMap { c -> ExerciseInstructions.selectAll().where { ExerciseInstructions.exerciseId inList c }.toList() }
            .groupBy { it[ExerciseInstructions.exerciseId] }
        return rows.map { r ->
            val id = r[Exercises.id]
            val n = names[id].orEmpty()
            ExerciseDto(
                id = id.toString(),
                ownerTrainerId = r[Exercises.ownerTrainerId]?.toString(),
                source = r[Exercises.sourceName],
                sourceId = r[Exercises.sourceId],
                names = n.filter { !it[ExerciseNames.isAlias] }.associate { it[ExerciseNames.locale] to it[ExerciseNames.name] },
                aliases = n.filter { it[ExerciseNames.isAlias] }.map { it[ExerciseNames.name] },
                primaryMuscles = r[Exercises.primaryMuscles].map { MuscleGroup.valueOf(it) },
                secondaryMuscles = r[Exercises.secondaryMuscles].map { MuscleGroup.valueOf(it) },
                equipment = Equipment.valueOf(r[Exercises.equipment]),
                trackingType = TrackingType.valueOf(r[Exercises.trackingType]),
                instructions = steps[id].orEmpty().associate { it[ExerciseInstructions.locale] to it[ExerciseInstructions.steps] },
                images = emptyList(),
                demoMediaId = r[Exercises.demoMediaId]?.toString(),
                featured = r[Exercises.featured],
                status = ExerciseStatus.valueOf(r[Exercises.status]),
                updatedAtEpochMs = r[Exercises.updatedAt].toInstant().toEpochMilli(),
            )
        }
    }

    // ------------------------------------------------------------------ plans

    suspend fun plans(trainerId: UUID): List<PlanTemplateDto> = db.tx {
        PlanTemplates.selectAll().where { PlanTemplates.ownerTrainerId eq trainerId }
            .orderBy(PlanTemplates.updatedAt to SortOrder.DESC).map { it.toTemplateDto() }
    }

    /** [number] null = latest. */
    suspend fun version(trainerId: UUID, templateId: UUID, number: Int?): PlanVersionDto = db.tx {
        val template = ownTemplate(trainerId, templateId)
        val n = number ?: template[PlanTemplates.latestVersion]
        val row = PlanVersions.selectAll().where { (PlanVersions.templateId eq templateId) and (PlanVersions.number eq n) }
            .singleOrNull() ?: notFound("Plan version not found")
        loadVersion(row)
    }

    /**
     * Publishes a new immutable version. [templateId] null creates a template (its id is `request.templateId` or new).
     * Idempotent by operationId; a stale baseVersion is a 409.
     */
    suspend fun publish(trainerId: UUID, templateId: UUID?, request: PublishPlanRequest): PublishPlanResponse = db.tx {
        val now = clock.now().toOffset()
        val operationId = request.operationId.toUuid("operationId")
        PlanVersions.selectAll().where { PlanVersions.operationId eq operationId }.singleOrNull()?.let { existing ->
            val template = ownTemplate(trainerId, existing[PlanVersions.templateId])
            return@tx PublishPlanResponse(template.toTemplateDto(), loadVersion(existing))
        }

        val exerciseIds = request.body.days.flatMap { d -> d.exercises.map { it.exerciseId } }.distinct()
            .map { it.toUuid("exerciseId") }
        val usable: Map<String, TrackingType> = if (exerciseIds.isEmpty()) emptyMap() else Exercises.selectAll().where {
            (Exercises.id inList exerciseIds) and (Exercises.ownerTrainerId.isNull() or (Exercises.ownerTrainerId eq trainerId))
        }.associate { it[Exercises.id].toString() to TrackingType.valueOf(it[Exercises.trackingType]) }
        val issues = PlanValidator.validate(request.body) { usable[it] }
        if (issues.isNotEmpty()) invalid("Invalid plan", issues.take(20).map { it.toString() })

        val body = request.body
        val template: ResultRow
        if (templateId == null) {
            val newTemplateId = request.templateId?.toUuid("templateId") ?: newId()
            if (!PlanTemplates.selectAll().where { PlanTemplates.id eq newTemplateId }.empty()) conflict("Template already exists")
            PlanTemplates.insert {
                it[PlanTemplates.id] = newTemplateId
                it[ownerTrainerId] = trainerId
                it[name] = body.name.trim()
                it[latestVersion] = 0
                it[createdAt] = now
                it[updatedAt] = now
            }
            template = PlanTemplates.selectAll().where { PlanTemplates.id eq newTemplateId }.forUpdate().single()
        } else {
            template = PlanTemplates.selectAll()
                .where { (PlanTemplates.id eq templateId) and (PlanTemplates.ownerTrainerId eq trainerId) }
                .forUpdate().singleOrNull() ?: notFound("Plan not found")
            if (template[PlanTemplates.archivedAt] != null) conflict("Plan is archived")
            if (request.baseVersion != template[PlanTemplates.latestVersion]) conflict("Plan changed since version ${request.baseVersion}")
        }
        val tid = template[PlanTemplates.id]
        val number = template[PlanTemplates.latestVersion] + 1
        val versionId = newId()
        PlanVersions.insert {
            it[id] = versionId
            it[PlanVersions.templateId] = tid
            it[PlanVersions.number] = number
            it[basedOnVersion] = if (number == 1) null else request.baseVersion
            it[name] = body.name.trim()
            it[tags] = body.tags
            it[createdBy] = trainerId
            it[createdAt] = now
            it[PlanVersions.operationId] = operationId
        }
        body.days.forEachIndexed { dayPos, day ->
            PlanDays.insert {
                it[planVersionId] = versionId
                it[id] = day.id
                it[position] = dayPos.toShort()
                it[name] = day.name.trim()
            }
            day.exercises.forEachIndexed { exPos, ex ->
                PlannedExercises.insert {
                    it[planVersionId] = versionId
                    it[id] = ex.id
                    it[dayId] = day.id
                    it[position] = exPos.toShort()
                    it[exerciseId] = ex.exerciseId.toUuid("exerciseId")
                    it[supersetGroup] = ex.supersetGroup
                    it[restSeconds] = ex.restSeconds
                    it[rpeEnabled] = ex.rpeEnabled
                    it[accessory] = ex.accessory
                    it[tempo] = ex.tempo
                    it[note] = ex.note
                }
                ex.sets.forEachIndexed { setPos, s ->
                    PlannedSets.insert {
                        it[planVersionId] = versionId
                        it[id] = s.id
                        it[plannedExerciseId] = ex.id
                        it[position] = setPos.toShort()
                        it[type] = s.type.name
                        it[repsMin] = s.repsMin
                        it[repsMax] = s.repsMax
                        it[weightGrams] = s.weightGrams
                        it[durationSeconds] = s.durationSeconds
                        it[distanceMeters] = s.distanceMeters
                        it[rpe] = s.rpe?.let { v -> BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP) }
                        it[restSecondsOverride] = s.restSecondsOverride
                    }
                }
            }
        }
        PlanTemplates.update({ PlanTemplates.id eq tid }) {
            it[latestVersion] = number
            it[name] = body.name.trim()
            it[updatedAt] = now
        }
        val updated = PlanTemplates.selectAll().where { PlanTemplates.id eq tid }.single()
        PublishPlanResponse(updated.toTemplateDto(), loadVersion(PlanVersions.selectAll().where { PlanVersions.id eq versionId }.single()))
    }

    suspend fun archive(trainerId: UUID, templateId: UUID): PlanTemplateDto = db.tx {
        ownTemplate(trainerId, templateId)
        val now = clock.now().toOffset()
        PlanTemplates.update({ (PlanTemplates.id eq templateId) and PlanTemplates.archivedAt.isNull() }) {
            it[archivedAt] = now
            it[updatedAt] = now
        }
        ownTemplate(trainerId, templateId).toTemplateDto()
    }

    private fun ownTemplate(trainerId: UUID, templateId: UUID): ResultRow =
        PlanTemplates.selectAll().where { (PlanTemplates.id eq templateId) and (PlanTemplates.ownerTrainerId eq trainerId) }
            .singleOrNull() ?: notFound("Plan not found")

    private fun ResultRow.toTemplateDto() = PlanTemplateDto(
        id = this[PlanTemplates.id].toString(),
        ownerTrainerId = this[PlanTemplates.ownerTrainerId].toString(),
        name = this[PlanTemplates.name],
        latestVersion = this[PlanTemplates.latestVersion],
        archivedAtEpochMs = this[PlanTemplates.archivedAt]?.toInstant()?.toEpochMilli(),
        updatedAtEpochMs = this[PlanTemplates.updatedAt].toInstant().toEpochMilli(),
    )

    private fun loadVersion(v: ResultRow): PlanVersionDto {
        val vid = v[PlanVersions.id]
        val days = PlanDays.selectAll().where { PlanDays.planVersionId eq vid }.orderBy(PlanDays.position).toList()
        val exercises = PlannedExercises.selectAll().where { PlannedExercises.planVersionId eq vid }
            .orderBy(PlannedExercises.position).toList().groupBy { it[PlannedExercises.dayId] }
        val sets = PlannedSets.selectAll().where { PlannedSets.planVersionId eq vid }
            .orderBy(PlannedSets.position).toList().groupBy { it[PlannedSets.plannedExerciseId] }
        return PlanVersionDto(
            id = vid.toString(),
            templateId = v[PlanVersions.templateId].toString(),
            number = v[PlanVersions.number],
            createdAtEpochMs = v[PlanVersions.createdAt].toInstant().toEpochMilli(),
            basedOnVersion = v[PlanVersions.basedOnVersion],
            body = PlanBodyDto(
                name = v[PlanVersions.name],
                tags = v[PlanVersions.tags],
                days = days.map { d ->
                    PlanDayDto(
                        id = d[PlanDays.id],
                        name = d[PlanDays.name],
                        exercises = exercises[d[PlanDays.id]].orEmpty().map { e ->
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

    // ------------------------------------------------------------------ assignment

    /**
     * Assigns a plan version to the relationship's client (idempotent by operationId) and marks this relationship's
     * previous ACTIVE assignment REPLACED. DB5: a client has one ACTIVE plan across all trainers
     * (`assigned_plan_one_active`); if another trainer's plan is ACTIVE the insert violates that index and surfaces as 409.
     */
    suspend fun assign(trainerId: UUID, relationshipId: UUID, request: AssignPlanRequest): AssignedPlanDto = db.tx {
        val operationId = request.operationId.toUuid("operationId")
        AssignedPlans.selectAll().where { AssignedPlans.operationId eq operationId }.singleOrNull()?.let {
            if (it[AssignedPlans.assignedBy] != trainerId || it[AssignedPlans.relationshipId] != relationshipId) {
                conflict("Operation id already used")
            }
            return@tx assignedDto(it)
        }
        val relationship = Access.activeRelationship(trainerId, relationshipId)
        val versionId = request.planVersionId.toUuid("planVersionId")
        val versionTemplate = PlanVersions.selectAll().where { PlanVersions.id eq versionId }.singleOrNull()
            ?.get(PlanVersions.templateId) ?: invalid("Unknown plan version")
        ownTemplate(trainerId, versionTemplate)
        val pinnedSession = request.sessionId?.toUuid("sessionId")?.also { s ->
            if (Sessions.selectAll().where { (Sessions.id eq s) and (Sessions.relationshipId eq relationshipId) }.empty()) {
                invalid("Unknown session")
            }
        }
        val from = request.startsOn?.let { runCatching { LocalDate.parse(it) }.getOrElse { invalid("Invalid startsOn") } }
        val to = request.endsOn?.let { runCatching { LocalDate.parse(it) }.getOrElse { invalid("Invalid endsOn") } }
        if (from != null && to != null && to.isBefore(from)) invalid("endsOn is before startsOn")
        val clientProfileId = relationship[TrainerClients.clientId]
        AssignedPlans.update({
            (AssignedPlans.relationshipId eq relationshipId) and (AssignedPlans.status eq "ACTIVE")
        }) { it[status] = "REPLACED" }
        val newPlanId = newId()
        AssignedPlans.insert {
            it[id] = newPlanId
            it[AssignedPlans.relationshipId] = relationshipId
            it[clientId] = clientProfileId
            it[planVersionId] = versionId
            it[sessionId] = pinnedSession
            it[status] = "ACTIVE"
            it[startsOn] = from
            it[endsOn] = to
            it[assignedBy] = trainerId
            it[assignedAt] = clock.now().toOffset()
            it[AssignedPlans.operationId] = operationId
        }
        assignedDto(AssignedPlans.selectAll().where { AssignedPlans.id eq newPlanId }.single())
    }

    suspend fun activeAssignment(trainerId: UUID, relationshipId: UUID): AssignedPlanDto = db.tx {
        Access.trainerRelationship(trainerId, relationshipId)
        AssignedPlans.selectAll().where { (AssignedPlans.relationshipId eq relationshipId) and (AssignedPlans.status eq "ACTIVE") }
            .singleOrNull()?.let { assignedDto(it) } ?: notFound("No assigned plan")
    }

    private fun assignedDto(row: ResultRow): AssignedPlanDto {
        val v = PlanVersions.selectAll().where { PlanVersions.id eq row[AssignedPlans.planVersionId] }.single()
        return AssignedPlanDto(
            id = row[AssignedPlans.id].toString(),
            clientId = row[AssignedPlans.relationshipId].toString(),
            planVersionId = row[AssignedPlans.planVersionId].toString(),
            templateId = v[PlanVersions.templateId].toString(),
            versionNumber = v[PlanVersions.number],
            name = v[PlanVersions.name],
            status = row[AssignedPlans.status],
            startsOn = row[AssignedPlans.startsOn]?.toString(),
            endsOn = row[AssignedPlans.endsOn]?.toString(),
            assignedAt = row[AssignedPlans.assignedAt].toInstantK(),
        )
    }

    // ------------------------------------------------------------------ client progress (private, grant required)

    suspend fun workouts(trainerId: UUID, relationshipId: UUID, limit: Int): List<WorkoutLogDto> = db.tx {
        val rel = granted(trainerId, relationshipId, AccessScope.WORKOUT_HISTORY, "workout_history")
        val logs = WorkoutLogs.selectAll().where {
            (WorkoutLogs.clientId eq rel[TrainerClients.clientId]) and WorkoutLogs.deletedAt.isNull() and
                (WorkoutLogs.state inList listOf("FINISHED", "IN_PROGRESS"))
        }.orderBy(WorkoutLogs.startedAt to SortOrder.DESC).limit(limit.coerceIn(1, 100)).toList()
        if (logs.isEmpty()) return@tx emptyList()
        val logIds = logs.map { it[WorkoutLogs.id] }
        val exercises = WorkoutExercises.selectAll()
            .where { (WorkoutExercises.workoutLogId inList logIds) and WorkoutExercises.deletedAt.isNull() }
            .orderBy(WorkoutExercises.position).toList()
        val sets = SetLogs.selectAll().where { (SetLogs.workoutLogId inList logIds) and SetLogs.deletedAt.isNull() }
            .orderBy(SetLogs.position).toList().groupBy { it[SetLogs.workoutExerciseId] }
        val names = exerciseNames(exercises.map { it[WorkoutExercises.exerciseId] }.toSet())
        val byLog = exercises.groupBy { it[WorkoutExercises.workoutLogId] }
        logs.map { l ->
            WorkoutLogDto(
                id = l[WorkoutLogs.id].toString(),
                name = l[WorkoutLogs.name],
                state = l[WorkoutLogs.state],
                startedAt = l[WorkoutLogs.startedAt].toInstantK(),
                finishedAt = l[WorkoutLogs.finishedAt]?.toInstantK(),
                sessionId = l[WorkoutLogs.sessionId]?.toString(),
                exercises = byLog[l[WorkoutLogs.id]].orEmpty().map { e ->
                    WorkoutExerciseDto(
                        id = e[WorkoutExercises.id].toString(),
                        exerciseId = e[WorkoutExercises.exerciseId].toString(),
                        exerciseName = names[e[WorkoutExercises.exerciseId]] ?: e[WorkoutExercises.exerciseId].toString(),
                        sets = sets[e[WorkoutExercises.id]].orEmpty().map { s ->
                            WorkoutSetDto(
                                id = s[SetLogs.id].toString(),
                                type = s[SetLogs.type],
                                weightGrams = s[SetLogs.weightGrams],
                                reps = s[SetLogs.reps],
                                rpe = s[SetLogs.rpe]?.toDouble(),
                                durationSeconds = s[SetLogs.durationSeconds],
                                distanceMeters = s[SetLogs.distanceMeters],
                                completedAt = s[SetLogs.completedAt]?.toInstantK(),
                                techniqueMediaId = s[SetLogs.techniqueMediaId]?.toString(),
                            )
                        },
                    )
                },
            )
        }
    }

    suspend fun records(trainerId: UUID, relationshipId: UUID): List<PersonalRecordDto> = db.tx {
        val rel = granted(trainerId, relationshipId, AccessScope.WORKOUT_HISTORY, "personal_records")
        val rows = PersonalRecordView.selectAll().where { PersonalRecordView.clientId eq rel[TrainerClients.clientId] }.toList()
        val names = exerciseNames(rows.map { it[PersonalRecordView.exerciseId] }.toSet())
        rows.mapNotNull { r ->
            val weight = r[PersonalRecordView.weightGrams] ?: return@mapNotNull null
            val reps = r[PersonalRecordView.reps] ?: return@mapNotNull null
            val at = r[PersonalRecordView.completedAt] ?: return@mapNotNull null
            PersonalRecordDto(
                exerciseId = r[PersonalRecordView.exerciseId].toString(),
                exerciseName = names[r[PersonalRecordView.exerciseId]] ?: r[PersonalRecordView.exerciseId].toString(),
                weightGrams = weight,
                reps = reps,
                completedAt = at.toInstantK(),
            )
        }.sortedByDescending { it.completedAt }
    }

    suspend fun measurements(trainerId: UUID, relationshipId: UUID): List<MeasurementDto> = db.tx {
        val rel = granted(trainerId, relationshipId, AccessScope.MEASUREMENTS, "measurements")
        BodyMeasurements.selectAll().where {
            (BodyMeasurements.clientId eq rel[TrainerClients.clientId]) and BodyMeasurements.deletedAt.isNull()
        }.orderBy(BodyMeasurements.measuredAt to SortOrder.DESC).map {
            MeasurementDto(
                it[BodyMeasurements.id].toString(), it[BodyMeasurements.kind],
                it[BodyMeasurements.valueThousandths], it[BodyMeasurements.measuredAt].toInstantK(),
            )
        }
    }

    /** Active relationship plus an active grant; a missing grant is audited as DENIED and refused. */
    private fun granted(trainerId: UUID, relationshipId: UUID, scope: AccessScope, subject: String): ResultRow {
        val rel = Access.activeRelationship(trainerId, relationshipId)
        val allowed = Access.hasScope(relationshipId, scope)
        audit.record(trainerId, "read_$subject", subject, null, relationshipId, allowed)
        if (!allowed) denied("The client has not shared this with you")
        return rel
    }

    /** Display name per exercise: non-alias 'pl', then 'en', then any. */
    private fun exerciseNames(ids: Set<UUID>): Map<UUID, String> {
        if (ids.isEmpty()) return emptyMap()
        return ExerciseNames.selectAll().where { (ExerciseNames.exerciseId inList ids.toList()) and (ExerciseNames.isAlias eq false) }
            .toList().groupBy { it[ExerciseNames.exerciseId] }.mapValues { (_, rows) ->
                val pick = rows.firstOrNull { it[ExerciseNames.locale] == "pl" }
                    ?: rows.firstOrNull { it[ExerciseNames.locale] == "en" } ?: rows.first()
                pick[ExerciseNames.name]
            }
    }

    private companion object {
        val LOCALES = setOf("pl", "en")
    }
}
