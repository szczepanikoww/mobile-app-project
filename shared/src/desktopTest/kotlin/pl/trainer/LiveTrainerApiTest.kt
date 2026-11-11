package pl.trainer

import kotlinx.coroutines.runBlocking
import pl.trainer.data.remote.RemotePolicyRepository
import pl.trainer.data.remote.RemoteTrainerRepository
import pl.trainer.data.remote.TrainerApiClient
import pl.trainer.data.DemoPolicyRepository
import pl.trainer.data.DemoSchedulingStore
import pl.trainer.domain.SessionStatus
import pl.trainer.domain.isOpenRequest
import kotlin.test.Test
import pl.trainerapp.feature.chat.data.RemoteTrainerChatsRepository
import pl.trainerapp.feature.trainerfinance.data.RemoteTrainerFinanceRepository
import pl.trainerapp.feature.trainerfinance.model.FinancePeriod
import pl.trainerapp.feature.trainerhome.data.RemoteTrainerHomeRepository
import pl.trainerapp.feature.trainerprofile.data.RemoteTrainerProfileRepository
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app's trainer repositories against a running local server with the demo seed (docs/features/trainer-backend.md).
 * Skipped unless `TRAINER_API_URL` is set, e.g. `TRAINER_API_URL=http://localhost:8080 ./gradlew :shared:desktopTest`.
 * It changes demo data (sends a chat message, rejects the seeded booking request), so run it on a development database only.
 */
class LiveTrainerApiTest {
    private val url = System.getenv("TRAINER_API_URL")?.takeIf { it.isNotBlank() }

    @Test
    fun trainerFlowsAgainstLocalServer() {
        val baseUrl = url ?: return
        runBlocking {
            val api = TrainerApiClient(baseUrl, subject = { "demo-trainer" })
            val trainer = RemoteTrainerRepository(api)

            val dashboard = trainer.load()
            assertTrue(dashboard.clients.isNotEmpty())
            assertTrue(dashboard.today != null)

            val policies = RemotePolicyRepository(api, isOwnTrainer = { it == "demo-trainer" }, others = DemoPolicyRepository(DemoSchedulingStore()))
            val policy = policies.current("demo-trainer")
            assertTrue(policy.tiers.isNotEmpty())
            assertTrue(policies.sessionPrice("demo-trainer").minor > 0)

            // Home, finance, profile and chat screens.
            assertTrue(RemoteTrainerHomeRepository(api).load().summary.toString().isNotEmpty())
            FinancePeriod.entries.forEach { RemoteTrainerFinanceRepository(api).load(it) }
            val profile = RemoteTrainerProfileRepository(api).load()
            assertTrue(profile.availability.isNotEmpty())
            val chats = RemoteTrainerChatsRepository(api)
            val conversation = chats.conversations().conversations.first()
            val sent = chats.send(conversation.relationshipId, "Test połączenia")
            assertTrue(chats.thread(conversation.relationshipId).messages.any { it.id == sent.id })

            val request = dashboard.sessions.firstOrNull { it.status == SessionStatus.Requested && it.isOpenRequest() } ?: return@runBlocking
            val review = trainer.requestReview(request.id)
            assertEquals(request.id, review.session.id)
            val after = trainer.rejectRequest(request.id, request.revision)
            assertEquals(SessionStatus.Rejected, after.sessions.first { it.id == request.id }.status)
        }
    }
}
