package com.trainingapp.server

import com.trainingapp.contract.sync.MeasurementChangesDto
import com.trainingapp.contract.sync.TodayWorkoutDto
import com.trainingapp.contract.sync.WorkoutChangesDto
import com.trainingapp.contract.wallet.ChargeStateDto
import com.trainingapp.contract.wallet.PaymentStateDto
import com.trainingapp.contract.wallet.WalletDto
import com.trainingapp.server.dev.DemoClientActivity
import com.trainingapp.server.dev.DemoSeed
import com.trainingapp.server.platform.ServerClock
import com.trainingapp.server.training.ExerciseLibraryImport
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.trainingapp.server.platform.Db as TrainerDb

class DemoClientActivityTest : ApiTest() {
    init {
        runBlocking {
            DemoSeed(services).seed()
            ExerciseLibraryImport.importIfIncomplete(TrainerDb(db.dataSource), ServerClock.System)
            // Twice: the second start must not add or fail anything.
            DemoClientActivity(services).seedIfMissing()
            DemoClientActivity(services).seedIfMissing()
        }
    }

    private val subject = DemoSeed.CLIENT_SUBJECT

    @Test
    fun `demo client has history, measurements, wallet entries and today's plan`() = api { http ->
        val changes = http.get("/v1/client/workouts") { auth(subject) }.body<WorkoutChangesDto>()
        assertEquals(12, changes.logs.size)
        assertTrue(changes.sets.size > 100)

        val measurements = http.get("/v1/client/measurements") { auth(subject) }.body<MeasurementChangesDto>()
        assertEquals(12, measurements.measurements.size)

        val wallet = http.get("/v1/client/wallet") { auth(subject) }.body<WalletDto>()
        assertEquals(2, wallet.packages.single().used)
        assertEquals(2, wallet.charges.count { it.state == ChargeStateDto.UNPAID })
        assertEquals(2, wallet.payments.count { it.state == PaymentStateDto.PAID })

        val today = http.get("/v1/client/today") { auth(subject) }.body<TodayWorkoutDto>()
        assertEquals("v2-day-a", today.dayId)
        assertEquals(5, today.planVersion.body.days.first().exercises.size)
    }
}
