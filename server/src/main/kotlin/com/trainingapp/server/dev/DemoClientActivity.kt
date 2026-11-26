package com.trainingapp.server.dev

import com.trainingapp.contract.exercise.SetType
import com.trainingapp.contract.plan.PlanBodyDto
import com.trainingapp.contract.plan.PlanDayDto
import com.trainingapp.contract.plan.PlannedExerciseDto
import com.trainingapp.contract.plan.PlannedSetDto
import com.trainingapp.server.Services
import com.trainingapp.server.billing.Allocations
import com.trainingapp.server.billing.BillingPeriods
import com.trainingapp.server.billing.Charges
import com.trainingapp.server.billing.CreditReservations
import com.trainingapp.server.billing.FeeDecisions
import com.trainingapp.server.billing.PackageAgreements
import com.trainingapp.server.billing.Payments
import com.trainingapp.server.common.dbNow
import com.trainingapp.server.common.toOffset
import com.trainingapp.server.dev.DemoSeed.Companion.id
import com.trainingapp.server.identity.TrainerClients
import com.trainingapp.server.scheduling.Attendances
import com.trainingapp.server.scheduling.DbSessionStatus
import com.trainingapp.server.scheduling.Sessions
import com.trainingapp.server.training.AssignedPlans
import com.trainingapp.server.training.BodyMeasurements
import com.trainingapp.server.training.Exercises
import com.trainingapp.server.training.SetLogs
import com.trainingapp.server.training.WorkoutExercises
import com.trainingapp.server.training.WorkoutLogs
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toKotlinInstant
import kotlin.uuid.Uuid

/**
 * Synthetic past activity of the demo client (Katarzyna Nowak, `dev.demo-client`) with the demo trainer, so
 * "Postępy", "Portfel" and "Rozpocznij trening" have something to show:
 *
 * - six weeks of finished workouts on the first plan (FBW — 2 dni), with progressing loads → history, charts, records;
 * - weekly body weight and a few waist/hip measurements;
 * - past sessions: paid online, paid in cash, two taken from the package, one unpaid (due soon) and a late
 *   cancellation whose fee is overdue → packages, charges and payments in the wallet;
 * - a newer plan version (FBW — 3 dni) assigned today, whose first day is today's workout.
 *
 * Runs on every start after the exercise library import (it needs library exercises) and does nothing when its
 * marker row exists, so databases seeded before this existed get it too. Every row is invented; ids are derived from
 * stable demo ids.
 */
class DemoClientActivity(private val s: Services) {
    private val log = LoggerFactory.getLogger("trainer.seed")
    private val zone = ZoneId.of("Europe/Warsaw")
    private val demoClient = id(DemoSeed.CLIENT_SUBJECT)
    private val demoTrainer = id("tr-michal")
    private val marker = id("activity-log-0")

    /** Never fails the start: a database whose demo data was changed by hand (e.g. an overlapping session) is skipped. */
    suspend fun seedIfMissing() {
        try {
            seed()
        } catch (e: Exception) {
            log.warn("demo client activity not seeded: {}", e.message)
        }
    }

    private suspend fun seed() {
        val relationshipId = s.db.tx {
            TrainerClients.selectAll().where { (TrainerClients.trainerId eq demoTrainer) and (TrainerClients.clientId eq demoClient) }
                .firstOrNull()?.get(TrainerClients.id)
        } ?: return
        val firstAssignment = s.db.tx {
            AssignedPlans.selectAll().where { AssignedPlans.operationId eq id("seed-assign") }.singleOrNull()
                ?.let { it[AssignedPlans.id] to it[AssignedPlans.planVersionId] }
        } ?: return
        val missing = s.db.tx { WorkoutLogs.selectAll().where { WorkoutLogs.id eq marker }.none() }
        if (missing) {
            s.db.tx {
                val now = dbNow()
                val today = now.toJavaInstantDate()
                seedSessionsAndMoney(relationshipId, today)
                seedWorkouts(firstAssignment.first, firstAssignment.second, today, now)
                seedMeasurements(today, now)
            }
            log.info("demo client activity seeded")
        }
        // The newer plan replaces the first one (history keeps pointing at the old assignment and version). Both
        // calls are idempotent by their operation ids, so a later reassignment by the trainer is not undone.
        val v2 = s.db.tx { with(s.training) { publishPlan(demoTrainer, id("plan-fbw"), planV2(), id("seed-plan-v2")) } }
        s.training.assignPlan(demoTrainer, relationshipId, v2, null, id("seed-assign-v2"))
    }

    private fun Instant.toJavaInstantDate(): LocalDate =
        java.time.Instant.ofEpochMilli(toEpochMilliseconds()).atZone(zone).toLocalDate()

    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Instant =
        day.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toKotlinInstant()

    // ----- Library exercises -----

    private val exerciseIds = mutableMapOf<String, Uuid?>()

    /** A global library exercise by its free-exercise-db id; null when the library is not imported. */
    private fun JdbcTransaction.lib(sourceId: String): Uuid? = exerciseIds.getOrPut(sourceId) {
        Exercises.selectAll().where { (Exercises.sourceId eq sourceId) and Exercises.ownerTrainerId.isNull() }
            .firstOrNull()?.get(Exercises.id)
    }

    // ----- Sessions, charges, payments -----

    private enum class Pay { ONLINE_PAID, CASH_PAID, PACKAGE, ONLINE_OPEN }

    private fun JdbcTransaction.seedSessionsAndMoney(relationshipId: Uuid, today: LocalDate) {
        val price = 12_000L
        val completed = listOf(33 to Pay.ONLINE_PAID, 26 to Pay.CASH_PAID, 19 to Pay.PACKAGE, 12 to Pay.PACKAGE, 5 to Pay.ONLINE_OPEN)
        val period = PackageAgreements.selectAll().where { PackageAgreements.operationId eq id("seed-package") }.singleOrNull()
            ?.let { agreement -> BillingPeriods.selectAll().where { BillingPeriods.agreementId eq agreement[PackageAgreements.id] }.firstOrNull() }
            ?.get(BillingPeriods.id)

        for ((daysAgo, pay) in completed) {
            val day = today.minusDays(daysAgo.toLong())
            val start = at(day, 18)
            val kind = when (pay) { Pay.CASH_PAID -> "CASH"; Pay.PACKAGE -> if (period != null) "PACKAGE" else "CASH"; else -> "ONLINE" }
            val sessionId = insertSession("activity-session-$daysAgo", relationshipId, start, DbSessionStatus.COMPLETED, kind, if (kind == "PACKAGE") 0 else price)
            Attendances.insert {
                it[id] = id("activity-attendance-$daysAgo")
                it[Attendances.sessionId] = sessionId
                it[outcome] = "ATTENDED"
                it[markedBy] = demoTrainer
                it[markedAt] = (start + 65.minutes).toOffset()
            }
            if (kind == "PACKAGE") {
                CreditReservations.insert {
                    it[id] = id("activity-credit-$daysAgo")
                    it[billingPeriodId] = period!!
                    it[CreditReservations.sessionId] = sessionId
                    it[reservedUnits] = 100
                    it[consumedUnits] = 100
                    it[status] = "CONSUMED"
                    it[createdAt] = (start - 3.days).toOffset()
                    it[settledAt] = (start + 65.minutes).toOffset()
                }
                continue
            }
            val due = if (kind == "CASH") start else start + 7.days
            val chargeId = insertCharge("activity-charge-$daysAgo", relationshipId, "SESSION", kind, sessionId, null, price, start + 65.minutes, due)
            when (pay) {
                Pay.ONLINE_PAID -> insertPayment("activity-payment-$daysAgo", relationshipId, "BLIK", chargeId, price, start + 2.days)
                Pay.CASH_PAID -> insertPayment("activity-payment-$daysAgo", relationshipId, "CASH", chargeId, price, start + 70.minutes)
                else -> Unit
            }
        }

        // A late cancellation (2 h before, 50 %): its fee is past due, so the wallet and the trainer see it as overdue.
        val cancelledDay = today.minusDays(15)
        val cancelledStart = at(cancelledDay, 8)
        val cancelledAt = cancelledStart - 120.minutes
        val cancelled = insertSession("activity-session-cancelled", relationshipId, cancelledStart, DbSessionStatus.CANCELLED_BY_CLIENT, "ONLINE", price, closedAt = cancelledAt)
        val decision = id("activity-fee-decision")
        FeeDecisions.insert {
            it[id] = decision
            it[sessionId] = cancelled
            it[action] = "CLIENT_CANCEL"
            it[receivedAt] = cancelledAt.toOffset()
            it[policyVersionId] = id("tr-michal-policy-1")
            it[feePercent] = 50
            it[quotedMinor] = price / 2
            it[waivedMinor] = 0
            it[currency] = "PLN"
            it[usedFreeCancellation] = false
            it[exempt] = false
            it[decidedBy] = demoClient
            it[decidedAt] = cancelledAt.toOffset()
            it[operationId] = id("activity-fee-decision-op")
        }
        insertCharge("activity-charge-fee", relationshipId, "LATE_CANCELLATION_FEE", "ONLINE", cancelled, decision, price / 2, cancelledAt, cancelledAt + 7.days)
    }

    private fun JdbcTransaction.insertSession(
        key: String, relationshipId: Uuid, start: Instant, status: String, paymentKind: String, priceMinor: Long, closedAt: Instant = start + 65.minutes,
    ): Uuid {
        val sessionId = id(key)
        Sessions.insert {
            it[id] = sessionId
            it[Sessions.relationshipId] = relationshipId
            it[Sessions.trainerId] = demoTrainer
            it[Sessions.clientId] = demoClient
            it[offerId] = id("tr-michal-60")
            it[Sessions.status] = status
            it[startsAt] = start.toOffset()
            it[endsAt] = (start + 60.minutes).toOffset()
            it[blockedUntil] = (start + 75.minutes).toOffset()
            it[timeZone] = zone.id
            it[gymId] = id("gym-centrum")
            it[placeNote] = null
            it[Sessions.priceMinor] = priceMinor
            it[currency] = "PLN"
            it[Sessions.paymentKind] = paymentKind
            it[policyVersionId] = id("tr-michal-policy-1")
            it[policyAcceptedAt] = (start - 4.days).toOffset()
            it[revision] = 3
            it[createdBy] = demoClient
            it[operationId] = id("$key-op")
            it[requestedAt] = (start - 4.days).toOffset()
            it[confirmedAt] = (start - 3.days).toOffset()
            it[Sessions.closedAt] = closedAt.toOffset()
        }
        return sessionId
    }

    private fun JdbcTransaction.insertCharge(
        key: String, relationshipId: Uuid, kind: String, settlement: String, sessionId: Uuid, decisionId: Uuid?, amount: Long, createdAt: Instant, dueAt: Instant,
    ): Uuid {
        val chargeId = id(key)
        Charges.insert {
            it[id] = chargeId
            it[Charges.relationshipId] = relationshipId
            it[Charges.kind] = kind
            it[Charges.settlement] = settlement
            it[Charges.sessionId] = sessionId
            it[feeDecisionId] = decisionId
            it[amountMinor] = amount
            it[currency] = "PLN"
            it[Charges.dueAt] = dueAt.toOffset()
            it[Charges.createdAt] = createdAt.toOffset()
            it[operationId] = id("$key-op")
        }
        return chargeId
    }

    private fun JdbcTransaction.insertPayment(key: String, relationshipId: Uuid, method: String, chargeId: Uuid, amount: Long, at: Instant) {
        val paymentId = id(key)
        val cash = method == "CASH"
        Payments.insert {
            it[id] = paymentId
            it[Payments.relationshipId] = relationshipId
            it[Payments.method] = method
            it[status] = "SUCCEEDED"
            it[amountMinor] = amount
            it[currency] = "PLN"
            it[provider] = if (cash) null else "fake"
            it[providerRef] = if (cash) null else "seed_$key"
            it[recordedBy] = if (cash) demoTrainer else null
            it[createdAt] = at.toOffset()
            it[settledAt] = at.toOffset()
            it[operationId] = id("$key-op")
        }
        Allocations.insert {
            it[id] = id("$key-allocation")
            it[Allocations.paymentId] = paymentId
            it[Allocations.chargeId] = chargeId
            it[amountMinor] = amount
            it[createdAt] = at.toOffset()
            it[operationId] = id("$key-allocation-op")
        }
    }

    // ----- Workout history -----

    private data class LoggedSet(val type: SetType, val kg: Double? = null, val reps: Int? = null, val seconds: Int? = null, val planned: String? = null)

    private data class LoggedExercise(val exerciseId: Uuid?, val planned: String?, val sets: List<LoggedSet>)

    private fun JdbcTransaction.seedWorkouts(assignmentId: Uuid, versionId: Uuid, today: LocalDate, now: Instant) {
        val squat = DemoSeed.exSquat
        val bench = DemoSeed.exBench
        val row = DemoSeed.exRow
        val plank = DemoSeed.exPlank
        val daysAgo = listOf(40, 37, 33, 30, 26, 23, 19, 16, 12, 9, 5, 2)
        daysAgo.forEachIndexed { index, ago ->
            val week = index / 2 // 0..5: loads go up a little every week
            val dayA = index % 2 == 0
            val day = today.minusDays(ago.toLong())
            val start = at(day, 18, 5)
            val exercises = if (dayA) listOf(
                LoggedExercise(squat, "pe-a1", listOf(LoggedSet(SetType.WARM_UP, 40.0, 8, planned = "ps-a1-w")) +
                    List(3) { n -> LoggedSet(SetType.WORKING, 72.5 + week * 2.5, if (n == 2 && week % 2 == 0) 4 else 5, planned = if (n < 2) "ps-a1-${n + 1}" else null) }),
                LoggedExercise(bench, "pe-a2", List(3) { n -> LoggedSet(SetType.WORKING, 55.0 + (week / 2) * 2.5, 8 - (week % 2) - (if (n == 2) 1 else 0), planned = if (n < 2) "ps-a2-${n + 1}" else null) }),
                LoggedExercise(lib("Side_Lateral_Raise"), null, List(3) { LoggedSet(SetType.WORKING, if (week < 3) 6.0 else 8.0, 12) }),
            ) else listOf(
                LoggedExercise(lib("Barbell_Deadlift"), null, listOf(LoggedSet(SetType.WARM_UP, 60.0, 5)) + List(2) { LoggedSet(SetType.WORKING, 90.0 + week * 5.0, 5) }),
                LoggedExercise(row, "pe-b1", List(3) { n -> LoggedSet(SetType.WORKING, 47.5 + week * 2.0, 10 - (if (n == 2) 1 else 0), planned = if (n < 2) "ps-b1-${n + 1}" else null) }),
                LoggedExercise(lib("Pullups"), null, List(3) { n -> LoggedSet(SetType.WORKING, reps = 5 + week / 2 + (if (n == 0) 1 else 0)) }),
                LoggedExercise(plank, "pe-b2", listOf(LoggedSet(SetType.WORKING, seconds = 40 + week * 5, planned = "ps-b2-1"))),
            )
            val logId = id("activity-log-$index")
            val sessionId = if (ago in setOf(33, 26, 19, 12, 5)) id("activity-session-$ago") else null
            var minute = 0
            WorkoutLogs.insert {
                it[id] = logId
                it[WorkoutLogs.clientId] = demoClient
                it[WorkoutLogs.sessionId] = sessionId
                it[assignedPlanId] = assignmentId
                it[planVersionId] = versionId
                it[planDayId] = if (dayA) "day-a" else "day-b"
                it[name] = if (dayA) "Dzień A" else "Dzień B"
                it[state] = "FINISHED"
                it[startedAt] = start.toOffset()
                it[finishedAt] = (start + 58.minutes).toOffset()
                it[timeZone] = zone.id
                it[place] = "Studio Centrum"
                it[note] = when (index) {
                    4 -> "Przysiad szedł lekko, ostatnia seria cięższa."
                    9 -> "Martwy ciąg — pilnowałam pleców, nagrałam serię dla trenera."
                    else -> null
                }
                it[revision] = 1
                it[updatedAt] = now.toOffset()
            }
            exercises.filter { it.exerciseId != null }.forEachIndexed { position, exercise ->
                val entryId = id("activity-log-$index-ex-$position")
                WorkoutExercises.insert {
                    it[id] = entryId
                    it[workoutLogId] = logId
                    it[WorkoutExercises.position] = position.toShort()
                    it[exerciseId] = exercise.exerciseId!!
                    it[plannedExerciseId] = exercise.planned
                    it[updatedAt] = now.toOffset()
                }
                exercise.sets.forEachIndexed { setIndex, set ->
                    minute += 3
                    SetLogs.insert {
                        it[id] = id("activity-log-$index-ex-$position-set-$setIndex")
                        it[workoutExerciseId] = entryId
                        it[workoutLogId] = logId
                        it[exerciseId] = exercise.exerciseId!!
                        it[SetLogs.position] = setIndex.toShort()
                        it[type] = set.type.name
                        it[plannedSetId] = set.planned
                        it[weightGrams] = set.kg?.let { kg -> (kg * 1000).toInt() }
                        it[reps] = set.reps
                        it[durationSeconds] = set.seconds
                        it[completedAt] = (start + minute.minutes).toOffset()
                        it[revision] = 1
                        it[updatedAt] = now.toOffset()
                    }
                }
            }
        }
    }

    // ----- Measurements -----

    private fun JdbcTransaction.seedMeasurements(today: LocalDate, now: Instant) {
        val weights = listOf(68.9, 68.6, 68.7, 68.2, 67.9, 67.8, 67.4) // kg, weekly, oldest first
        weights.forEachIndexed { i, kg -> measurement("weight-$i", "BODY_WEIGHT", kg, today.minusDays((42 - i * 7).toLong()), now) }
        listOf(78.0, 77.0, 76.5).forEachIndexed { i, cm -> measurement("waist-$i", "WAIST", cm, today.minusDays((42 - i * 20).toLong()), now) }
        listOf(99.0, 98.0).forEachIndexed { i, cm -> measurement("hips-$i", "HIPS", cm, today.minusDays((42 - i * 40).toLong()), now) }
    }

    private fun JdbcTransaction.measurement(key: String, kind: String, value: Double, day: LocalDate, now: Instant) {
        BodyMeasurements.insert {
            it[id] = id("activity-measurement-$key")
            it[clientId] = demoClient
            it[BodyMeasurements.kind] = kind
            it[valueThousandths] = Math.round(value * 1000)
            it[measuredAt] = at(day, 7, 30).toOffset()
            it[updatedAt] = now.toOffset()
        }
    }

    // ----- Today's plan -----

    private fun JdbcTransaction.planV2(): PlanBodyDto {
        fun working(id: String, reps: Int, kg: Double?, max: Int = reps) = PlannedSetDto(id, SetType.WORKING, reps, max, kg?.let { (it * 1000).toInt() })
        fun exercise(id: String, exerciseId: Uuid?, rest: Int, sets: List<PlannedSetDto>, accessory: Boolean = false, note: String? = null) =
            exerciseId?.let { PlannedExerciseDto(id, it.toString(), restSeconds = rest, accessory = accessory, note = note, sets = sets) }
        return PlanBodyDto(
            name = "FBW — 3 dni",
            tags = listOf("Siła", "Hipertrofia"),
            days = listOf(
                PlanDayDto("v2-day-a", "Dzień A — przysiad", listOfNotNull(
                    exercise("v2-a1", DemoSeed.exSquat, 180, listOf(PlannedSetDto("v2-a1-w", SetType.WARM_UP, 8, 8, 40_000)) + List(3) { working("v2-a1-$it", 5, 87.5) },
                        note = "Pełny zakres, kolana na zewnątrz."),
                    exercise("v2-a2", DemoSeed.exBench, 150, List(3) { working("v2-a2-$it", 6, 62.5, 8) }),
                    exercise("v2-a3", lib("Seated_Cable_Rows"), 90, List(3) { working("v2-a3-$it", 10, 45.0, 12) }),
                    exercise("v2-a4", lib("Side_Lateral_Raise"), 60, List(3) { working("v2-a4-$it", 12, 8.0, 15) }, accessory = true),
                    exercise("v2-a5", DemoSeed.exPlank, 60, listOf(PlannedSetDto("v2-a5-1", SetType.WORKING, durationSeconds = 60)), accessory = true),
                )),
                PlanDayDto("v2-day-b", "Dzień B — martwy ciąg", listOfNotNull(
                    exercise("v2-b1", lib("Barbell_Deadlift"), 180, List(3) { working("v2-b1-$it", 5, 115.0) }, note = "Nagraj ostatnią serię z boku."),
                    exercise("v2-b2", lib("Standing_Military_Press"), 120, List(3) { working("v2-b2-$it", 6, 35.0, 8) }),
                    exercise("v2-b3", lib("Pullups"), 120, List(3) { PlannedSetDto("v2-b3-$it", SetType.WORKING, 6, 8) }),
                    exercise("v2-b4", lib("Dumbbell_Lunges"), 90, List(3) { working("v2-b4-$it", 10, 14.0) }),
                )),
                PlanDayDto("v2-day-c", "Dzień C — objętość", listOfNotNull(
                    exercise("v2-c1", lib("Romanian_Deadlift"), 120, List(3) { working("v2-c1-$it", 8, 80.0, 10) }),
                    exercise("v2-c2", lib("Incline_Dumbbell_Press"), 90, List(3) { working("v2-c2-$it", 10, 20.0, 12) }),
                    exercise("v2-c3", DemoSeed.exRow, 90, List(3) { working("v2-c3-$it", 10, 57.5) }),
                    exercise("v2-c4", lib("Barbell_Hip_Thrust"), 90, List(3) { working("v2-c4-$it", 10, 80.0, 12) }),
                    exercise("v2-c5", lib("Triceps_Pushdown"), 60, List(3) { working("v2-c5-$it", 12, 20.0, 15) }, accessory = true),
                )),
            ),
        )
    }
}
