package com.trainingapp.server

import com.trainingapp.contract.api.AcceptRequest
import com.trainingapp.contract.api.ApiError
import com.trainingapp.contract.api.CheckInDto
import com.trainingapp.contract.api.CreateSessionRequest
import com.trainingapp.contract.api.FeeWaiverDto
import com.trainingapp.contract.api.ProposeTimeRequest
import com.trainingapp.contract.api.ProposerRole
import com.trainingapp.contract.api.RequestKind
import com.trainingapp.contract.api.RequestReviewDto
import com.trainingapp.contract.api.RevisionRequest
import com.trainingapp.contract.api.SessionDto
import com.trainingapp.contract.api.SessionResultDto
import com.trainingapp.contract.api.SessionStatus
import com.trainingapp.contract.api.TrainerDashboardDto
import com.trainingapp.contract.money.Money
import com.trainingapp.contract.policy.FeeCalculator
import com.trainingapp.server.catalogue.Policies
import com.trainingapp.server.catalogue.toContract
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class TrainerSessionsTest {

    private fun op() = UUID.randomUUID().toString()

    private suspend fun HttpClient.create(clientId: String, start: Instant, end: Instant = start + 1.hours): HttpResponse =
        post("/api/v1/trainer/sessions") { setBody(CreateSessionRequest(clientId, start, end, "Sala 1", operationId = op())) }

    private suspend fun HttpResponse.session(): SessionDto = body<SessionResultDto>().session

    private suspend fun HttpResponse.errorCode(): String = body<ApiError>().code

    @Test
    fun `accepting a booking request confirms it and a stale revision conflicts`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val accepted = trainer.post("/api/v1/trainer/sessions/${Seed.REQUESTED_SESSION}/accept") { setBody(AcceptRequest(revision = 1)) }
        assertEquals(HttpStatusCode.OK, accepted.status)
        val session = accepted.session()
        assertEquals(SessionStatus.CONFIRMED, session.status)
        assertEquals(2, session.revision)

        val stale = trainer.post("/api/v1/trainer/sessions/${Seed.REQUESTED_SESSION}/reject") { setBody(RevisionRequest(revision = 1)) }
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals("CONFLICT", stale.errorCode())
        assertEquals(1L, TestDatabase.count("SELECT count(*) FROM scheduling.session_event WHERE session_id = '${Seed.REQUESTED_SESSION}' AND type = 'CONFIRMED'"))
    }

    @Test
    fun `concurrent bookings of the same slot - exactly one wins (acceptance test 1)`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val start = clock.now() + 5.days
        val results = coroutineScope {
            listOf(Seed.REL_1, Seed.REL_2, Seed.REL_3).map { rel -> async { trainer.create(rel, start).status } }.awaitAll()
        }
        assertEquals(1, results.count { it == HttpStatusCode.Created }, "results: $results")
        assertEquals(2, results.count { it == HttpStatusCode.Conflict }, "results: $results")
    }

    @Test
    fun `the buffer keeps the next slot blocked`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val start = clock.now() + 3.days
        assertEquals(HttpStatusCode.Created, trainer.create(Seed.REL_1, start).status)
        // Seed buffer is 15 min: a session starting 10 min after the end overlaps the blocked time.
        assertEquals(HttpStatusCode.Conflict, trainer.create(Seed.REL_2, start + 70.minutes).status)
        assertEquals(HttpStatusCode.Created, trainer.create(Seed.REL_2, start + 75.minutes).status)
    }

    @Test
    fun `another trainer cannot see or change the session (acceptance test 4)`() = apiTest {
        val other = signedIn("demo-other-trainer")
        val review = other.get("/api/v1/trainer/sessions/${Seed.REQUESTED_SESSION}/review")
        assertEquals(HttpStatusCode.NotFound, review.status)
        val accept = other.post("/api/v1/trainer/sessions/${Seed.REQUESTED_SESSION}/accept") { setBody(AcceptRequest(1)) }
        assertEquals(HttpStatusCode.NotFound, accept.status)
        val create = other.create(Seed.REL_1, clock.now() + 2.days)
        assertEquals(HttpStatusCode.NotFound, create.status)
        val dashboard = other.get("/api/v1/trainer/dashboard").body<TrainerDashboardDto>()
        assertTrue(dashboard.clients.isEmpty())
        assertTrue(dashboard.sessions.isEmpty())
    }

    @Test
    fun `a client without the trainer role is denied`() = apiTest {
        val client = signedIn("demo-client-1")
        val response = client.get("/api/v1/trainer/dashboard")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `requests without a token are unauthenticated`() = apiTest {
        assertEquals(HttpStatusCode.Unauthorized, client().get("/api/v1/trainer/dashboard").status)
    }

    @Test
    fun `trainer cancellation never charges the client`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val session = trainer.create(Seed.REL_1, clock.now() + 2.hours).session()
        val cancelled = trainer.post("/api/v1/trainer/sessions/${session.id}/cancel") { setBody(RevisionRequest(session.revision)) }.session()
        assertEquals(SessionStatus.CANCELLED_BY_TRAINER, cancelled.status)
        assertEquals(0L, TestDatabase.count("SELECT count(*) FROM billing.charge WHERE session_id = '${session.id}'"))
        assertEquals(1L, TestDatabase.count("SELECT count(*) FROM billing.fee_decision WHERE session_id = '${session.id}' AND action = 'TRAINER_CANCEL' AND quoted_minor = 0"))
    }

    @Test
    fun `no-show unlocks after the grace period and charges the policy fee`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val session = trainer.create(Seed.REL_2, clock.now() + 1.hours).session()
        clock.advance(1.hours + 5.minutes)
        val checkIn = trainer.get("/api/v1/trainer/sessions/${session.id}/check-in").body<CheckInDto>()
        assertTrue(checkIn.started)
        assertTrue(!checkIn.noShowAllowed)
        assertEquals(15, checkIn.graceMinutes)
        val early = trainer.post("/api/v1/trainer/sessions/${session.id}/no-show") { setBody(RevisionRequest(session.revision)) }
        assertEquals(HttpStatusCode.Conflict, early.status)

        clock.advance(11.minutes)
        val noShow = trainer.post("/api/v1/trainer/sessions/${session.id}/no-show") { setBody(RevisionRequest(session.revision)) }.session()
        assertEquals(SessionStatus.NO_SHOW, noShow.status)
        // Standard preset: no-show 100 % of 150 zł.
        assertEquals(15000L, TestDatabase.count("SELECT amount_minor FROM billing.charge WHERE session_id = '${session.id}' AND kind = 'NO_SHOW_FEE'"))
    }

    @Test
    fun `completing a cash session creates its charge once`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val session = trainer.create(Seed.REL_2, clock.now() + 30.minutes).session()
        val tooEarly = trainer.post("/api/v1/trainer/sessions/${session.id}/complete") { setBody(RevisionRequest(session.revision)) }
        assertEquals(HttpStatusCode.Conflict, tooEarly.status)
        clock.advance(40.minutes)
        val done = trainer.post("/api/v1/trainer/sessions/${session.id}/complete") { setBody(RevisionRequest(session.revision)) }.session()
        assertEquals(SessionStatus.COMPLETED, done.status)
        assertEquals(1L, TestDatabase.count("SELECT count(*) FROM billing.charge WHERE session_id = '${session.id}' AND kind = 'SESSION' AND settlement = 'CASH'"))
        assertEquals(1L, TestDatabase.count("SELECT count(*) FROM scheduling.attendance WHERE session_id = '${session.id}' AND outcome = 'ATTENDED'"))
    }

    /** Inserts what the client backend would: a client's reschedule proposal received at [receivedAt]. */
    private fun clientProposal(sessionId: String, revision: Int, start: Instant, receivedAt: Instant) {
        TestDatabase.exec(
            """
            INSERT INTO scheduling.reschedule_request (id, session_id, base_revision, proposed_by, proposer_role,
                proposed_starts_at, proposed_ends_at, status, received_at, operation_id)
            VALUES ('${UUID.randomUUID()}', '$sessionId', $revision, '${Seed.CLIENT_1}', 'CLIENT',
                '$start', '${start + 1.hours}', 'PENDING', '$receivedAt', '${UUID.randomUUID()}')
            """.trimIndent(),
        )
    }

    @Test
    fun `late reschedule fee equals the app's FeeCalculator and can be waived (acceptance tests 5 and 6)`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val session = trainer.create(Seed.REL_1, clock.now() + 10.hours).session()
        val newStart = clock.now() + 3.days
        clientProposal(session.id, session.revision, newStart, receivedAt = clock.now())

        val review = trainer.get("/api/v1/trainer/sessions/${session.id}/review").body<RequestReviewDto>()
        assertEquals(RequestKind.RESCHEDULE, review.kind)
        assertEquals(newStart, review.requestedStart)
        assertTrue(review.slotAvailable)
        val lateFee = assertNotNull(review.lateFee)
        val policy = services.db.tx { Policies.byId(UUID.fromString(Seed.POLICY)) }.toContract()
        val appFee = FeeCalculator.reschedule(policy, session.start, clock.now(), Money(15000, "PLN"))
        assertEquals(appFee.amount, lateFee.amount)
        assertEquals(15000L, lateFee.amount.minor) // < 12 h: 100 %

        val accepted = trainer.post("/api/v1/trainer/sessions/${session.id}/accept") {
            setBody(AcceptRequest(session.revision, waiver = FeeWaiverDto("Choroba")))
        }.session()
        assertEquals(newStart, accepted.start)
        assertNull(accepted.proposal)
        assertEquals(0L, TestDatabase.count("SELECT count(*) FROM billing.charge WHERE session_id = '${session.id}'"))
        assertEquals(15000L, TestDatabase.count("SELECT waived_minor FROM billing.fee_decision WHERE session_id = '${session.id}'"))
    }

    @Test
    fun `late reschedule without waiver creates the fee charge`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val session = trainer.create(Seed.REL_1, clock.now() + 20.hours).session()
        clientProposal(session.id, session.revision, clock.now() + 4.days, receivedAt = clock.now())
        val accepted = trainer.post("/api/v1/trainer/sessions/${session.id}/accept") { setBody(AcceptRequest(session.revision)) }
        assertEquals(HttpStatusCode.OK, accepted.status)
        // < 24 h: 50 % of 150 zł.
        assertEquals(7500L, TestDatabase.count("SELECT amount_minor FROM billing.charge WHERE session_id = '${session.id}' AND kind = 'LATE_RESCHEDULE_FEE'"))
    }

    @Test
    fun `trainer counter-proposal keeps the confirmed time`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val session = trainer.create(Seed.REL_1, clock.now() + 2.days).session()
        clientProposal(session.id, session.revision, clock.now() + 3.days, receivedAt = clock.now())
        val counter = clock.now() + 4.days
        val result = trainer.post("/api/v1/trainer/sessions/${session.id}/propose") {
            setBody(ProposeTimeRequest(session.revision, counter, counter + 1.hours, op()))
        }.session()
        assertEquals(session.start, result.start)
        assertEquals(SessionStatus.CONFIRMED, result.status)
        assertEquals(ProposerRole.TRAINER, result.proposal?.author)
        assertEquals(counter, result.proposal?.start)
    }

    @Test
    fun `unanswered requests expire at their start`() = apiTest {
        clock.advance(3.days)
        val expired = services.worker.runOnce()
        assertTrue(expired >= 1)
        assertEquals(1L, TestDatabase.count("SELECT count(*) FROM scheduling.session WHERE id = '${Seed.REQUESTED_SESSION}' AND status = 'EXPIRED'"))
    }

    @Test
    fun `dashboard shows clients with the overdue flag only`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val dashboard = trainer.get("/api/v1/trainer/dashboard").body<TrainerDashboardDto>()
        assertEquals(3, dashboard.clients.size)
        val katarzyna = dashboard.clients.single { it.id == Seed.REL_1 }
        assertTrue(katarzyna.hasOverduePayment)
        assertTrue(dashboard.sessions.any { it.id == Seed.REQUESTED_SESSION && it.status == SessionStatus.REQUESTED })
    }
}
