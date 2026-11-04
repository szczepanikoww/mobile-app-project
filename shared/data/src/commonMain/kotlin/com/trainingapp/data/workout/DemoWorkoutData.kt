package com.trainingapp.data.workout

import com.trainingapp.contract.exercise.SetType
import com.trainingapp.contract.exercise.TrackingType
import com.trainingapp.contract.plan.PlanBodyDto
import com.trainingapp.contract.plan.PlanDayDto
import com.trainingapp.contract.plan.PlanVersionDto
import com.trainingapp.contract.plan.PlannedExerciseDto
import com.trainingapp.contract.plan.PlannedSetDto
import com.trainingapp.domain.common.IdGenerator
import com.trainingapp.domain.workout.BodyMeasurement
import com.trainingapp.domain.workout.MeasurementKind
import com.trainingapp.domain.workout.MeasurementRepository
import com.trainingapp.domain.workout.TodayWorkout
import com.trainingapp.domain.workout.TodayWorkoutSource
import com.trainingapp.domain.workout.WorkoutLog
import com.trainingapp.domain.workout.WorkoutLogReducer
import com.trainingapp.domain.workout.WorkoutRepository

/**
 * Synthetic demo data for the client workout views. It stands in for the plan assignment flow, which does not exist
 * yet; nothing here is real client data. Exercise ids are from the bundled exercise library.
 */
object DemoWorkoutData {
    const val BENCH_PRESS = "3a905a8f-a317-562b-aeb6-e1ae7cfea800"
    const val INCLINE_DB_PRESS = "2493622d-7843-5701-932c-84939451fb27"
    const val MILITARY_PRESS = "225b522e-125a-5b11-9a42-bf074393ad46"
    const val LATERAL_RAISE = "e8287635-6463-563a-b5a7-4e4c778f4a09"
    const val TRICEPS_PUSHDOWN = "48cf88a6-dafd-50b4-b21d-45de92bb07c9"
    const val PLANK = "30440af6-d827-5149-a8e3-7b15d78d075b"
    const val SQUAT = "31027ab9-c68d-56f5-af10-0b76db519a50"

    val trackingTypes = mapOf(
        BENCH_PRESS to TrackingType.WEIGHT_REPS,
        INCLINE_DB_PRESS to TrackingType.WEIGHT_REPS,
        MILITARY_PRESS to TrackingType.WEIGHT_REPS,
        LATERAL_RAISE to TrackingType.WEIGHT_REPS,
        TRICEPS_PUSHDOWN to TrackingType.WEIGHT_REPS,
        PLANK to TrackingType.TIME,
        SQUAT to TrackingType.WEIGHT_REPS,
    )

    private fun set(id: String, reps: Int?, kg: Double?, type: SetType = SetType.WORKING, seconds: Int? = null) =
        PlannedSetDto(id, type, repsMin = reps, repsMax = reps, weightGrams = kg?.let { (it * 1000).toInt() }, durationSeconds = seconds)

    val pushDay = PlanDayDto(
        id = "demo-day-push-a",
        name = "Push A",
        exercises = listOf(
            PlannedExerciseDto(
                "demo-pe-1", BENCH_PRESS, restSeconds = 120, rpeEnabled = true,
                sets = listOf(
                    set("demo-ps-1", 10, 60.0, SetType.WARM_UP),
                    set("demo-ps-2", 8, 80.0),
                    set("demo-ps-3", 8, 82.5),
                    set("demo-ps-4", 6, 85.0),
                ),
            ),
            PlannedExerciseDto("demo-pe-2", INCLINE_DB_PRESS, restSeconds = 90, sets = List(3) { set("demo-ps-2$it", 10, 26.0) }),
            PlannedExerciseDto("demo-pe-3", MILITARY_PRESS, restSeconds = 90, sets = List(3) { set("demo-ps-3$it", 8, 40.0) }),
            PlannedExerciseDto("demo-pe-4", LATERAL_RAISE, restSeconds = 60, sets = List(3) { set("demo-ps-4$it", 12, 10.0) }),
            PlannedExerciseDto("demo-pe-5", TRICEPS_PUSHDOWN, restSeconds = 60, sets = List(3) { set("demo-ps-5$it", 12, 25.0) }),
            PlannedExerciseDto("demo-pe-6", PLANK, restSeconds = 45, sets = List(2) { set("demo-ps-6$it", null, null, seconds = 60) }),
        ),
    )

    val planVersion = PlanVersionDto(
        id = "demo-plan-version-1",
        templateId = "demo-plan",
        number = 1,
        createdAtEpochMs = 0,
        body = PlanBodyDto(name = "Siła i rekompozycja", days = listOf(pushDay)),
    )

    val todaySource = TodayWorkoutSource { TodayWorkout(planVersion, pushDay.id, place = "Siłownia Centrum") }

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Adds a few synthetic past workouts and measurements when the client has none, so the progress view has
     * something to show. Dates are relative to [nowEpochMs]; nothing depends on a fixed mockup date.
     */
    suspend fun seedIfEmpty(
        clientId: String,
        workouts: WorkoutRepository,
        measurements: MeasurementRepository,
        ids: IdGenerator,
        nowEpochMs: Long,
    ) {
        if (workouts.history(clientId).isNotEmpty() || workouts.getActive(clientId) != null) return
        // (days ago, bench working sets as kg × reps)
        val sessions = listOf(
            28 to listOf(75.0 to 8, 77.5 to 8, 80.0 to 5),
            21 to listOf(77.5 to 8, 80.0 to 7, 80.0 to 6),
            14 to listOf(80.0 to 8, 82.5 to 6, 82.5 to 5),
            7 to listOf(80.0 to 8, 82.5 to 7, 85.0 to 5),
        )
        sessions.forEach { (daysAgo, bench) ->
            val start = nowEpochMs - daysAgo * DAY_MS
            var log = WorkoutLogReducer.startFromPlan(ids.newId(), clientId, pushDay.name, planVersion.id, pushDay, trackingTypes, start, ids, place = "Siłownia Centrum")
            val benchEntry = log.exercises.first()
            val benchSets = benchEntry.sets.filter { it.type != SetType.WARM_UP }
            benchSets.zip(bench).forEach { (set, value) ->
                log = log.copy(exercises = log.exercises.map { e ->
                    if (e.id != benchEntry.id) e else e.copy(sets = e.sets.map { if (it.id == set.id) it.copy(weightGrams = (value.first * 1000).toInt(), reps = value.second) else it })
                })
            }
            var t = start
            log.allSets.forEach { set ->
                t += 3 * 60 * 1000
                log = WorkoutLogReducer.toggleComplete(log, set.id, t)
            }
            workouts.save(WorkoutLogReducer.finish(log, start + 62 * 60 * 1000))
        }
        listOf(28 to 74_800, 21 to 74_200, 14 to 73_600, 7 to 73_100).forEach { (daysAgo, grams) ->
            measurements.add(BodyMeasurement(ids.newId(), clientId, MeasurementKind.BODY_WEIGHT, grams, nowEpochMs - daysAgo * DAY_MS))
        }
        listOf(28 to 82_000, 14 to 80_500).forEach { (daysAgo, mm) ->
            measurements.add(BodyMeasurement(ids.newId(), clientId, MeasurementKind.WAIST, mm, nowEpochMs - daysAgo * DAY_MS))
        }
    }

    /** For previews and tests: a finished workout built from the demo day. */
    fun finishedSample(id: String, clientId: String, startEpochMs: Long, ids: IdGenerator): WorkoutLog {
        var log = WorkoutLogReducer.startFromPlan(id, clientId, pushDay.name, planVersion.id, pushDay, trackingTypes, startEpochMs, ids)
        log.allSets.forEachIndexed { i, s -> log = WorkoutLogReducer.toggleComplete(log, s.id, startEpochMs + (i + 1) * 120_000L) }
        return WorkoutLogReducer.finish(log, startEpochMs + 3_600_000L)
    }
}
