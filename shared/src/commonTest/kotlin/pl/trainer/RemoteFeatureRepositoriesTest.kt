package pl.trainer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import pl.trainer.data.remote.TrainerApiClient
import pl.trainer.domain.Failure
import pl.trainer.domain.TrainerException
import pl.trainerapp.feature.trainerfinance.data.RemoteTrainerFinanceRepository
import pl.trainerapp.feature.trainerfinance.model.FinancePeriod
import pl.trainerapp.feature.trainerfinance.model.PaymentMethod
import pl.trainerapp.feature.trainerfinance.model.Pln
import pl.trainerapp.feature.trainerhome.data.RemoteTrainerHomeRepository
import pl.trainerapp.feature.trainerhome.model.AttentionItem
import pl.trainerapp.feature.trainerhome.model.ClientRef
import pl.trainerapp.feature.trainerhome.model.SessionStatus
import pl.trainerapp.feature.trainerprofile.data.RemoteTrainerProfileRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Home, finance and profile repositories against a scripted server. Synthetic data only. */
class RemoteFeatureRepositoriesTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Server(val answers: (HttpRequestData) -> String?) {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            val path = request.url.encodedPath
            if (path.endsWith("dev/token")) {
                respond("""{"token":"tok","userId":"t-1"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                val body = answers(request)
                if (body == null) respond("""{"code":"INTERNAL","message":"x"}""", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json"))
                else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        fun api() = TrainerApiClient("http://test", subject = { "demo-trainer" }, engine = HttpClient(engine))
    }

    private val session = """{"id":"s-1","clientId":"rel-1","trainerId":"t-1","start":"2026-11-12T08:00:00Z","end":"2026-11-12T09:00:00Z",
        "timeZone":"Europe/Warsaw","location":"Studio","status":"CONFIRMED","revision":1,"planTitle":"Push A",
        "payment":{"kind":"CASH"},"policyVersionId":"pol-1","price":{"minor":12000,"currency":"PLN"},"requestedAt":"2026-11-10T10:00:00Z"}"""

    private val home = """{"todaySessions":[$session],"nextSession":$session,
        "clients":[{"id":"rel-1","userId":"u-1","name":"Test Klient","initials":"TK","status":"ACTIVE","hasOverduePayment":false},
                   {"id":"rel-2","userId":"u-2","name":"Inny Klient","initials":"IK","status":"ACTIVE","hasOverduePayment":true}],
        "openRequests":1,"unreadMessages":3,"pendingVideos":1,"overdueClients":1,"weekSessionsDone":4,"weekSessionsPlanned":6,
        "serverNow":"2026-11-12T07:00:00Z"}"""

    @Test
    fun homeMapsSessionsAttentionAndWeek() = runTest {
        val video = AttentionItem.TechniqueVideo("m1", ClientRef("rel-1", "Test Klient"), "Przysiad")
        val repo = RemoteTrainerHomeRepository(Server { home }.api(), techniqueVideos = { listOf(video) })
        val snapshot = repo.load()
        val summary = snapshot.summary

        assertFalse(snapshot.isOffline)
        assertEquals(Instant.parse("2026-11-12T07:00:00Z"), summary.generatedAt)
        assertEquals("Test Klient", summary.nextSession?.client?.name)
        assertEquals(SessionStatus.Confirmed, summary.nextSession?.status)
        assertTrue(summary.nextSession!!.checkInOpen)
        assertTrue(summary.todaySessions.isEmpty()) // the next session is not repeated
        assertEquals(listOf<Any>(video, AttentionItem.OverduePayment("overdue-rel-2", ClientRef("rel-2", "Inny Klient"))), summary.attention)
        assertEquals(3, summary.unreadChats)
        assertEquals(4, summary.week?.sessionsDone)
        assertEquals(6, summary.week?.sessionsPlanned)
    }

    @Test
    fun homeReturnsLastSnapshotOfflineAndFailsWithoutOne() = runTest {
        var online = true
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("dev/token")) respond("""{"token":"tok","userId":"t-1"}""", HttpStatusCode.OK, json)
            else if (online) respond(home, HttpStatusCode.OK, json) else throw RuntimeException("no network")
        }
        val repo = RemoteTrainerHomeRepository(TrainerApiClient("http://test", { "demo-trainer" }, HttpClient(engine)))
        val fresh = repo.load()
        online = false
        val cached = repo.load()
        assertTrue(cached.isOffline)
        assertEquals(fresh.summary, cached.summary)

        val none = RemoteTrainerHomeRepository(TrainerApiClient("http://test", { "demo-trainer" }, HttpClient(MockEngine { throw RuntimeException("no network") })))
        assertEquals(Failure.Offline, assertFailsWith<TrainerException> { none.load() }.failure)
    }

    private val finance = """{"period":"WEEK","from":"2026-11-08T23:00:00Z","to":"2026-11-15T23:00:00Z","sessionsDone":5,
        "sessionsValue":{"minor":60000,"currency":"PLN"},"cancellationFees":{"minor":6000,"currency":"PLN"},"cancellationFeeCount":1,
        "collected":{"minor":30000,"currency":"PLN"},"previousCollected":{"minor":20000,"currency":"PLN"},
        "outstanding":{"minor":12000,"currency":"PLN"},"overdueClients":1,
        "buckets":[{"start":"2026-11-08T23:00:00Z","collected":{"minor":0,"currency":"PLN"}},
                   {"start":"2026-11-09T23:00:00Z","collected":{"minor":30000,"currency":"PLN"}},
                   {"start":"2026-11-10T23:00:00Z","collected":{"minor":0,"currency":"PLN"}},
                   {"start":"2026-11-11T23:00:00Z","collected":{"minor":0,"currency":"PLN"}},
                   {"start":"2026-11-12T23:00:00Z","collected":{"minor":0,"currency":"PLN"}},
                   {"start":"2026-11-13T23:00:00Z","collected":{"minor":0,"currency":"PLN"}},
                   {"start":"2026-11-14T23:00:00Z","collected":{"minor":0,"currency":"PLN"}}],
        "payments":[{"id":"p-1","clientId":"rel-1","clientName":"Test Klient","method":"BLIK","status":"SUCCEEDED",
                     "amount":{"minor":30000,"currency":"PLN"},"createdAt":"2026-11-10T10:00:00Z","settledAt":"2026-11-10T10:00:05Z"},
                    {"id":"p-2","clientId":"rel-2","clientName":"Inny Klient","method":"CARD","status":"PENDING",
                     "amount":{"minor":5000,"currency":"PLN"},"createdAt":"2026-11-11T10:00:00Z"}]}"""

    @Test
    fun financeMapsFiguresBarsAndSettledPayments() = runTest {
        val server = Server { finance }
        val repo = RemoteTrainerFinanceRepository(server.api(), now = { Instant.parse("2026-11-11T12:00:00Z") })
        val summary = repo.load(FinancePeriod.Week).summary

        assertTrue(server.requests.last().url.encodedQuery.contains("period=WEEK"))
        assertEquals(Pln(30000), summary.revenue)
        assertEquals(Pln(20000), summary.previousRevenue)
        assertEquals(Pln(12000), summary.overdue)
        assertEquals(Pln(60000), summary.sessionsValue)
        assertEquals(1, summary.cancellationFeeCount)
        assertEquals(listOf("Pn", "Wt", "Śr", "Cz", "Pt", "So", "Nd"), summary.bars.map { it.label })
        assertEquals(Pln(30000), summary.bars[1].revenue) // 23:00Z is already Tuesday in Warsaw
        assertEquals(2, summary.currentBar) // Wednesday 13:00 in Warsaw
        assertNull(summary.goal)
        val payment = summary.payments.single() // the pending payment is not money received
        assertEquals(PaymentMethod.Blik, payment.method)
        assertEquals(Instant.parse("2026-11-10T10:00:05Z"), payment.at)
    }

    private val profile = """{"userId":"t-1","displayName":"Test Trener","headline":"Siła","bio":"Opis","specialties":["Siła","Mobilność"],
        "isPublic":true,"sessionBufferMinutes":10,"defaultCurrency":"PLN","gymIds":[],"payoutAccountConnected":true}"""
    private val availability = """{"timeZone":"Europe/Warsaw","rules":[
        {"weekday":1,"startTime":"08:00","endTime":"12:00"},{"weekday":1,"startTime":"14:00","endTime":"18:00"},
        {"weekday":3,"startTime":"09:00","endTime":"17:00","validFrom":"2026-01-01"}]}"""
    private val offers = """[{"id":"o-1","kind":"PACKAGE","name":"Pakiet 8","durationMinutes":60,"price":{"minor":90000,"currency":"PLN"},"sessionsPerPeriod":8,"isPublic":true},
        {"id":"o-2","kind":"SINGLE_SESSION","name":"Sesja","durationMinutes":60,"price":{"minor":12000,"currency":"PLN"},"isPublic":true}]"""

    private fun profileServer() = Server { request ->
        val path = request.url.encodedPath
        when {
            path.endsWith("trainer/profile") -> profile
            path.endsWith("trainer/availability") -> availability
            path.endsWith("trainer/offers") && request.method == HttpMethod.Get -> offers
            path.endsWith("trainer/offers") -> """{"id":"o-3","kind":"SINGLE_SESSION","name":"Sesja","durationMinutes":60,"price":{"minor":15000,"currency":"PLN"},"isPublic":true}"""
            path.endsWith("/archive") -> ""
            else -> null
        }
    }

    @Test
    fun profileLoadMapsProfileHoursAndRate() = runTest {
        val snapshot = RemoteTrainerProfileRepository(profileServer().api(), now = { Instant.parse("2026-11-11T12:00:00Z") }).load()

        assertEquals("Test Trener", snapshot.profile.name)
        assertEquals("Opis", snapshot.profile.bio)
        assertEquals(listOf("Siła", "Mobilność"), snapshot.profile.specializations)
        assertEquals(Pln(12000), snapshot.profile.hourlyRate)
        assertTrue(snapshot.payout.connected)
        assertEquals("08:00 – 12:00, 14:00 – 18:00", snapshot.availability[0].hours)
        assertTrue(snapshot.availability[2].enabled)
        assertFalse(snapshot.availability[1].enabled)
        assertEquals("Nieczynne", snapshot.availability[6].hours)
    }

    @Test
    fun saveProfileSendsKeptFieldsAndReplacesChangedRate() = runTest {
        val server = profileServer()
        val repo = RemoteTrainerProfileRepository(server.api())
        val loaded = repo.load().profile
        repo.saveProfile(loaded.copy(name = "Nowe Imię", hourlyRate = Pln(15000)))

        val put = server.requests.single { it.method == HttpMethod.Put }
        val sent = (put.body as TextContent).text
        assertTrue(sent.contains("\"displayName\":\"Nowe Imię\""))
        assertTrue(sent.contains("\"headline\":\"Siła\"")) // unchanged server field carried over
        assertTrue(sent.contains("\"sessionBufferMinutes\":10"))
        assertTrue(server.requests.any { it.method == HttpMethod.Post && it.url.encodedPath.endsWith("trainer/offers") })
        assertTrue(server.requests.any { it.url.encodedPath.endsWith("trainer/offers/o-2/archive") })
    }
}
