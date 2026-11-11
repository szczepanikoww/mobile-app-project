package com.trainingapp.feature.guest

import com.trainingapp.data.remote.ApiClient
import com.trainingapp.feature.guest.data.RemoteGuestCatalogRepository
import com.trainingapp.feature.guest.model.GymQuery
import com.trainingapp.feature.guest.model.TrainerQuery
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end check against a running backend with the demo seed: the app's own repository code, real HTTP, real
 * database. Skipped unless `TRAINER_E2E_URL` is set, e.g.
 * `TRAINER_E2E_URL=http://localhost:8080 ./gradlew :shared:feature:guest:desktopTest --tests "*AgainstServer*"`.
 */
class RemoteCatalogAgainstServerTest {
    private val baseUrl = System.getenv("TRAINER_E2E_URL")

    @Test
    fun `the seeded catalogue comes from the server`() {
        if (baseUrl.isNullOrBlank()) return
        val repo = RemoteGuestCatalogRepository(ApiClient(baseUrl))
        runBlocking {
            val trainers = repo.trainers(TrainerQuery())
            assertEquals(12, trainers.size)
            val michal = trainers.single { it.name == "Michał Kowalczyk" }
            val profile = assertNotNull(repo.trainer(michal.id))
            assertEquals(24, profile.cancellationPolicy?.freeCancellationHours)
            assertTrue(repo.home().featuredTrainers.isNotEmpty())
            assertEquals(6, repo.gyms(GymQuery()).size)
            assertEquals(listOf("Łukasz Kozłowski"), repo.trainers(TrainerQuery(text = "lukasz")).map { it.name })
        }
    }
}
