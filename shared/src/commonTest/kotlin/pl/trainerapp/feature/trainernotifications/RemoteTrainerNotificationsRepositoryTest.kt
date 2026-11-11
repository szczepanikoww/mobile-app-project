package pl.trainerapp.feature.trainernotifications

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import pl.trainer.data.remote.TrainerApiClient
import pl.trainer.domain.TrainerException
import pl.trainerapp.feature.trainernotifications.data.RemoteTrainerNotificationsRepository
import pl.trainerapp.feature.trainernotifications.model.NotificationTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteTrainerNotificationsRepositoryTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val dashboard = """
        {"clients":[{"id":"rel-1","userId":"u-1","name":"Test Klient","initials":"TK","status":"ACTIVE","hasOverduePayment":true}],
         "sessions":[
          {"id":"s-1","clientId":"rel-1","trainerId":"t-1","start":"2026-11-12T07:00:00Z","end":"2026-11-12T08:00:00Z",
           "timeZone":"Europe/Warsaw","location":"Studio","status":"REQUESTED","revision":1,
           "payment":{"kind":"CASH"},"policyVersionId":"pol-1","price":{"minor":15000,"currency":"PLN"},"requestedAt":"2026-11-10T10:00:00Z"},
          {"id":"s-2","clientId":"rel-1","trainerId":"t-1","start":"2026-11-14T07:00:00Z","end":"2026-11-14T08:00:00Z",
           "timeZone":"Europe/Warsaw","location":"Studio","status":"CONFIRMED","revision":3,
           "proposal":{"id":"p-1","start":"2026-11-15T07:00:00Z","end":"2026-11-15T08:00:00Z","author":"CLIENT","status":"PENDING","requestedAt":"2026-11-11T09:00:00Z"},
           "payment":{"kind":"CASH"},"policyVersionId":"pol-1","price":{"minor":15000,"currency":"PLN"},"requestedAt":"2026-11-01T10:00:00Z"}],
         "blocks":[],"notes":{},"serverNow":"2026-11-11T10:00:00Z"}
    """.trimIndent()

    private val home = """
        {"todaySessions":[],"clients":[{"id":"rel-1","userId":"u-1","name":"Test Klient","initials":"TK","status":"ACTIVE","hasOverduePayment":true}],
         "openRequests":1,"unreadMessages":2,"pendingVideos":0,"overdueClients":1,"weekSessionsDone":0,"weekSessionsPlanned":0,
         "serverNow":"2026-11-11T10:00:00Z"}
    """.trimIndent()

    private fun repo(offline: () -> Boolean = { false }): RemoteTrainerNotificationsRepository {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("dev/token") -> respond("""{"token":"tok","userId":"t-1"}""", HttpStatusCode.OK, json)
                offline() -> throw IllegalStateException("offline")
                path.endsWith("trainer/dashboard") -> respond(dashboard, HttpStatusCode.OK, json)
                path.endsWith("trainer/home") -> respond(home, HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        return RemoteTrainerNotificationsRepository(TrainerApiClient("http://test", { "demo-trainer" }, HttpClient(engine)))
    }

    @Test
    fun mapsRequestProposalMessagesAndOverdueNewestFirst() = runTest {
        val s = repo().notifications()
        assertEquals(listOf("messages-2", "overdue-rel-1", "proposal-s-2-2026-11-11T09:00:00Z", "request-s-1"), s.items.map { it.id })
        assertEquals(NotificationTarget.Session("s-1"), s.items.last().target)
        assertEquals(NotificationTarget.Session("s-2"), s.items[2].target)
        assertEquals(NotificationTarget.Chat("rel-1", "Test Klient"), s.items[1].target)
        assertEquals(4, s.unreadCount)
        assertFalse(s.isOffline)
    }

    @Test
    fun openAndMarkAllReadTrackReadState() = runTest {
        val r = repo()
        r.notifications()
        assertTrue(r.open("request-s-1").notification.read)
        assertEquals(3, r.notifications().unreadCount)
        r.markAllRead()
        assertEquals(0, r.notifications().unreadCount)
        assertFailsWith<IllegalStateException> { r.open("nope") }
    }

    @Test
    fun offlineReturnsCachedSnapshotOrThrows() = runTest {
        var off = false
        val r = repo { off }
        off = true
        assertFailsWith<TrainerException> { r.notifications() }
        off = false
        r.notifications()
        off = true
        val s = r.notifications()
        assertTrue(s.isOffline)
        assertEquals(4, s.items.size)
    }
}
