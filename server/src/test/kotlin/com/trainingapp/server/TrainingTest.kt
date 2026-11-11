package com.trainingapp.server

import com.trainingapp.contract.api.ApiError
import com.trainingapp.contract.api.AssignPlanRequest
import com.trainingapp.contract.api.AssignedPlanDto
import com.trainingapp.contract.api.WorkoutLogDto
import com.trainingapp.contract.exercise.Equipment
import com.trainingapp.contract.exercise.ExerciseDto
import com.trainingapp.contract.exercise.MuscleGroup
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.exercise.UpsertExerciseRequest
import com.trainingapp.contract.plan.PlanBodyDto
import com.trainingapp.contract.plan.PlanDayDto
import com.trainingapp.contract.plan.PlanVersionDto
import com.trainingapp.contract.plan.PlannedExerciseDto
import com.trainingapp.contract.plan.PlannedSetDto
import com.trainingapp.contract.plan.PublishPlanRequest
import com.trainingapp.contract.plan.PublishPlanResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrainingTest {

    private val base = "/api/v1/trainer"

    private fun uuid() = UUID.randomUUID().toString()

    private suspend fun HttpClient.createExercise(id: String = uuid(), name: String = "Rozpiętki na wyciągu"): ExerciseDto {
        val response = put("$base/exercises/$id") {
            setBody(UpsertExerciseRequest(id, mapOf("pl" to name), MuscleGroup.CHEST, equipment = Equipment.CABLE, trackingType = TrackingType.WEIGHT_REPS))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    private fun body(exerciseId: String, name: String = "Push A") = PlanBodyDto(
        name = name,
        days = listOf(
            PlanDayDto(
                id = "day-1",
                name = "Dzień 1",
                exercises = listOf(
                    PlannedExerciseDto(
                        id = "pe-1",
                        exerciseId = exerciseId,
                        restSeconds = 90,
                        sets = listOf(PlannedSetDto(id = "set-1", repsMin = 8, repsMax = 8, weightGrams = 40000)),
                    ),
                ),
            ),
        ),
    )

    private suspend fun HttpClient.publishNew(exerciseId: String, operationId: String = uuid()) =
        post("$base/plans") { setBody(PublishPlanRequest(operationId, body = body(exerciseId))) }

    // ---------------------------------------------------------------- exercises

    @Test
    fun `a custom exercise can be created, listed and is private to its trainer`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val created = trainer.createExercise()
        assertEquals(Seed.TRAINER.toString(), created.ownerTrainerId)
        assertEquals("trainer", created.source)

        val listed = trainer.get("$base/exercises").body<List<ExerciseDto>>()
        assertEquals("Rozpiętki na wyciągu", listed.single { it.id == created.id }.names["pl"])

        val other = signedIn("demo-other-trainer").get("$base/exercises").body<List<ExerciseDto>>()
        assertTrue(other.none { it.id == created.id })

        // A path id that differs from the body id is rejected, as is editing someone else's exercise.
        val mismatch = trainer.put("$base/exercises/${uuid()}") {
            setBody(UpsertExerciseRequest(uuid(), mapOf("pl" to "Martwy ciąg"), MuscleGroup.LOWER_BACK, equipment = Equipment.BARBELL, trackingType = TrackingType.WEIGHT_REPS))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, mismatch.status)
        val foreign = signedIn("demo-other-trainer").put("$base/exercises/${created.id}") {
            setBody(UpsertExerciseRequest(created.id, mapOf("pl" to "Zmienione"), MuscleGroup.CHEST, equipment = Equipment.CABLE, trackingType = TrackingType.WEIGHT_REPS))
        }
        assertEquals(HttpStatusCode.Forbidden, foreign.status)
    }

    // ---------------------------------------------------------------- plans

    @Test
    fun `publishing a plan is idempotent by operation id`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val exercise = trainer.createExercise()
        val operationId = uuid()
        val first = trainer.publishNew(exercise.id, operationId)
        assertTrue(first.status == HttpStatusCode.OK || first.status == HttpStatusCode.Created, "status: ${first.status}")
        val firstBody = first.body<PublishPlanResponse>()
        assertEquals(1, firstBody.version.number)
        assertEquals(1, firstBody.template.latestVersion)

        val second = trainer.publishNew(exercise.id, operationId).body<PublishPlanResponse>()
        assertEquals(firstBody.version.id, second.version.id)
        assertEquals(firstBody.template.id, second.template.id)
        assertEquals(1L, TrainerTestDatabase.count("SELECT count(*) FROM training.plan_version WHERE operation_id = '$operationId'"))
        assertEquals(1L, TrainerTestDatabase.count("SELECT count(*) FROM training.plan_template WHERE owner_trainer_id = '${Seed.TRAINER}'"))
        assertEquals("pe-1", firstBody.version.body.days.single().exercises.single().id)
    }

    @Test
    fun `a new version needs the current base version`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val exercise = trainer.createExercise()
        val v1 = trainer.publishNew(exercise.id).body<PublishPlanResponse>()
        val templateId = v1.template.id

        val v2 = trainer.post("$base/plans/$templateId/versions") {
            setBody(PublishPlanRequest(uuid(), templateId, baseVersion = 1, body = body(exercise.id, "Push A v2")))
        }
        assertEquals(HttpStatusCode.OK, v2.status)
        val v2Body = v2.body<PublishPlanResponse>()
        assertEquals(2, v2Body.version.number)
        assertEquals(1, v2Body.version.basedOnVersion)
        assertEquals(2, v2Body.template.latestVersion)

        val stale = trainer.post("$base/plans/$templateId/versions") {
            setBody(PublishPlanRequest(uuid(), templateId, baseVersion = 1, body = body(exercise.id, "Push A v3")))
        }
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals("CONFLICT", stale.body<ApiError>().code)
        assertEquals(2L, TrainerTestDatabase.count("SELECT count(*) FROM training.plan_version WHERE template_id = '$templateId'"))

        // Version 1 is immutable history: its content is unchanged.
        val old = trainer.get("$base/plans/$templateId/versions/1").body<PlanVersionDto>()
        assertEquals("Push A", old.body.name)
        assertEquals(HttpStatusCode.NotFound, signedIn("demo-other-trainer").get("$base/plans/$templateId/versions/1").status)
    }

    @Test
    fun `a plan cannot use another trainer's exercise`() = apiTest {
        val foreign = signedIn("demo-other-trainer").createExercise()
        val trainer = signedIn("demo-trainer")
        val response = trainer.publishNew(foreign.id)
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertEquals("INVALID_INPUT", response.body<ApiError>().code)
        assertEquals(0L, TrainerTestDatabase.count("SELECT count(*) FROM training.plan_template WHERE owner_trainer_id = '${Seed.TRAINER}'"))
    }

    // ---------------------------------------------------------------- assignment

    @Test
    fun `assigning a plan replaces the previous active assignment`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val exercise = trainer.createExercise()
        val v1 = trainer.publishNew(exercise.id).body<PublishPlanResponse>()
        val templateId = v1.template.id
        val v2 = trainer.post("$base/plans/$templateId/versions") {
            setBody(PublishPlanRequest(uuid(), templateId, baseVersion = 1, body = body(exercise.id, "Push A v2")))
        }.body<PublishPlanResponse>()

        val operationId = uuid()
        val first = trainer.post("$base/clients/${Seed.REL_1}/assigned-plan") { setBody(AssignPlanRequest(v1.version.id, operationId = operationId)) }
        assertEquals(HttpStatusCode.Created, first.status)
        val firstDto = first.body<AssignedPlanDto>()
        assertEquals("ACTIVE", firstDto.status)
        assertEquals(1, firstDto.versionNumber)

        // A retry of the same operation returns the same assignment.
        val retry = trainer.post("$base/clients/${Seed.REL_1}/assigned-plan") { setBody(AssignPlanRequest(v1.version.id, operationId = operationId)) }.body<AssignedPlanDto>()
        assertEquals(firstDto.id, retry.id)
        assertEquals(1L, TrainerTestDatabase.count("SELECT count(*) FROM training.assigned_plan WHERE relationship_id = '${Seed.REL_1}'"))

        val second = trainer.post("$base/clients/${Seed.REL_1}/assigned-plan") { setBody(AssignPlanRequest(v2.version.id, operationId = uuid())) }
        assertEquals(HttpStatusCode.Created, second.status)
        assertEquals(2, second.body<AssignedPlanDto>().versionNumber)
        assertEquals(1L, TrainerTestDatabase.count("SELECT count(*) FROM training.assigned_plan WHERE relationship_id = '${Seed.REL_1}' AND status = 'REPLACED' AND id = '${firstDto.id}'"))
        assertEquals(1L, TrainerTestDatabase.count("SELECT count(*) FROM training.assigned_plan WHERE relationship_id = '${Seed.REL_1}' AND status = 'ACTIVE'"))
        assertEquals(2, trainer.get("$base/clients/${Seed.REL_1}/assigned-plan").body<AssignedPlanDto>().versionNumber)

        // Another trainer cannot assign to this client.
        val foreign = signedIn("demo-other-trainer").post("$base/clients/${Seed.REL_1}/assigned-plan") { setBody(AssignPlanRequest(v1.version.id, operationId = uuid())) }
        assertEquals(HttpStatusCode.NotFound, foreign.status)
    }

    // ---------------------------------------------------------------- client progress

    @Test
    fun `workout history needs the workout history grant`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val exercise = trainer.createExercise()
        val workoutId = uuid()
        val workoutExerciseId = uuid()
        val setId = uuid()
        TrainerTestDatabase.exec(
            """
            INSERT INTO training.workout_log (id, client_id, name, state, started_at, finished_at, time_zone)
            VALUES ('$workoutId', '${Seed.CLIENT_1}', 'Klatka', 'FINISHED', now() - interval '2 hours', now() - interval '1 hour', 'Europe/Warsaw');
            INSERT INTO training.workout_exercise (id, workout_log_id, position, exercise_id)
            VALUES ('$workoutExerciseId', '$workoutId', 0, '${exercise.id}');
            INSERT INTO training.set_log (id, workout_exercise_id, workout_log_id, exercise_id, position, type, weight_grams, reps, completed_at)
            VALUES ('$setId', '$workoutExerciseId', '$workoutId', '${exercise.id}', 0, 'WORKING', 80000, 6, now() - interval '90 minutes');
            """.trimIndent(),
        )

        val response = trainer.get("$base/clients/${Seed.REL_1}/workouts")
        assertEquals(HttpStatusCode.OK, response.status)
        val log = response.body<List<WorkoutLogDto>>().single()
        assertEquals("Klatka", log.name)
        val set = log.exercises.single().sets.single()
        assertEquals(80000, set.weightGrams)
        assertEquals(6, set.reps)
        assertEquals("Rozpiętki na wyciągu", log.exercises.single().exerciseName)

        // REL_3 shared nothing: the read is refused and the refusal is audited.
        val denied = trainer.get("$base/clients/${Seed.REL_3}/workouts")
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertEquals(1L, TrainerTestDatabase.count("SELECT count(*) FROM platform.audit_record WHERE relationship_id = '${Seed.REL_3}' AND outcome = 'DENIED'"))

        assertEquals(HttpStatusCode.NotFound, signedIn("demo-other-trainer").get("$base/clients/${Seed.REL_1}/workouts").status)
    }
}
