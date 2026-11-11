package com.trainingapp.server

import com.trainingapp.contract.api.ApiErrorDto
import com.trainingapp.contract.api.IDEMPOTENCY_KEY_HEADER
import com.trainingapp.contract.schedule.BookingOptionsDto
import com.trainingapp.contract.schedule.BookingRequestDto
import com.trainingapp.contract.schedule.CancelRequestDto
import com.trainingapp.contract.schedule.CancellationQuoteDto
import com.trainingapp.contract.schedule.ClientScheduleDto
import com.trainingapp.contract.schedule.PaymentKindDto
import com.trainingapp.contract.schedule.ProposalAnswerDto
import com.trainingapp.contract.schedule.ProposalAuthorDto
import com.trainingapp.contract.schedule.RescheduleRequestDto
import com.trainingapp.contract.schedule.RevisionDto
import com.trainingapp.contract.schedule.SessionDto
import com.trainingapp.contract.schedule.SessionStatus
import com.trainingapp.contract.schedule.SlotDto
import com.trainingapp.contract.wallet.ChargeReasonDto
import com.trainingapp.contract.wallet.ChargeStateDto
import com.trainingapp.contract.wallet.WalletDto
import com.trainingapp.server.common.ApiException
import com.trainingapp.server.common.Ids
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.uuid.Uuid

/** A 60-minute slot about [hours] from now, on a full hour and not crossing local midnight. */
fun slotIn(hours: Int): Pair<Instant, Instant> {
    var start = Instant.fromEpochSeconds((Clock.System.now() + hours.hours).epochSeconds / 3600 * 3600)
    while (start.toJavaInstant().atZone(ZoneId.of("Europe/Warsaw")).hour >= 22) start += 3.hours
    return start to start + 60.minutes
}

suspend fun HttpClient.book(subject: String, trainer: Fixtures.Trainer, slot: Pair<Instant, Instant>, key: Uuid = Ids.v7(), kind: PaymentKindDto? = null) =
    post("/v1/client/booking-requests") {
        auth(subject); json(); header(IDEMPOTENCY_KEY_HEADER, key.toString())
        setBody(BookingRequestDto(trainer.id.toString(), slot.first, slot.second, trainer.gymName, trainer.policyId.toString(), paymentKind = kind))
    }

class SchedulingTest : ApiTest() {
    private val trainer = fixtures.trainer("trainer-a", priceZloty = 120, freeLateCancellations = 0, bufferMinutes = 15)
    private val clientId = fixtures.client("client-a")
    private val otherClient = fixtures.client("client-b")

    private fun confirm(session: SessionDto) = runBlocking { services.scheduling.confirm(trainer.id, Uuid.parse(session.id)) }

    @Test
    fun `booking request is idempotent, needs the current policy and blocks nothing until confirmed`() = api { http ->
        val options = http.get("/v1/client/trainers/${trainer.id}/booking-options") { auth("client-a") }.body<BookingOptionsDto>()
        assertEquals(12000, options.price.minor)
        assertEquals(trainer.policyId.toString(), options.policy.versionId)

        val slot = slotIn(30)
        val key = Ids.v7()
        val first = http.book("client-a", trainer, slot, key)
        assertEquals(HttpStatusCode.Created, first.status)
        val session = first.body<SessionDto>()
        assertEquals(SessionStatus.REQUESTED, session.status)
        assertEquals(trainer.gymName, session.location)
        // Same key → same session; no second row.
        assertEquals(session.id, http.book("client-a", trainer, slot, key).body<SessionDto>().id)
        assertEquals(1, count("SELECT count(*) FROM scheduling.session"))
        assertEquals(1, count("SELECT count(*) FROM identity.trainer_client WHERE status = 'PENDING'")) // R1

        // An outdated policy consent is refused.
        val outdated = http.post("/v1/client/booking-requests") {
            auth("client-a"); json(); header(IDEMPOTENCY_KEY_HEADER, Ids.v7().toString())
            setBody(BookingRequestDto(trainer.id.toString(), slot.first, slot.second, trainer.gymName, Ids.v7().toString()))
        }
        assertEquals("POLICY_OUTDATED", outdated.body<ApiErrorDto>().code)

        // Another client may request the same time: requests block nothing (DB4).
        assertEquals(HttpStatusCode.Created, http.book("client-b", trainer, slot).status)

        confirm(session)
        val schedule = http.get("/v1/client/schedule") { auth("client-a") }.body<ClientScheduleDto>()
        assertEquals(SessionStatus.CONFIRMED, schedule.sessions.single().status)
        assertEquals(1, count("SELECT count(*) FROM identity.trainer_client WHERE status = 'ACTIVE'"))
        assertEquals(1, count("SELECT count(*) FROM messaging.conversation"))
        assertEquals(1, count("SELECT count(*) FROM platform.job WHERE kind = 'reminder' AND status = 'PENDING'"))

        // The confirmed session (plus the trainer's 15 min buffer) is no longer a free slot.
        val date = slot.first.toJavaInstant().atZone(ZoneId.of("Europe/Warsaw")).toLocalDate()
        val slots = http.get("/v1/client/trainers/${trainer.id}/slots?date=$date") { auth("client-b") }.body<List<SlotDto>>()
        assertTrue(slots.none { it.start < slot.second + 15.minutes && slot.first < it.end + 15.minutes })
    }

    @Test
    fun `acceptance test 1 - two concurrent confirmations of one time, one wins, the other is SLOT_TAKEN`() = api { http ->
        val slot = slotIn(40)
        val a = http.book("client-a", trainer, slot).body<SessionDto>()
        val b = http.book("client-b", trainer, slot).body<SessionDto>()
        val results = runBlocking(Dispatchers.IO) {
            listOf(a, b).map { s -> async { runCatching { services.scheduling.confirm(trainer.id, Uuid.parse(s.id)) } } }.awaitAll()
        }
        assertEquals(1, results.count { it.isSuccess })
        val failure = results.single { it.isFailure }.exceptionOrNull() as ApiException
        assertEquals("SLOT_TAKEN", failure.code)
        assertEquals(1, count("SELECT count(*) FROM scheduling.session WHERE status = 'CONFIRMED'"))
    }

    @Test
    fun `reschedule request keeps the original confirmed time until it is accepted`() = api { http ->
        val slot = slotIn(50)
        val session = http.book("client-a", trainer, slot).body<SessionDto>().let { confirm(it) }
        val target = slotIn(74)
        val requested = http.post("/v1/client/sessions/${session.id}/reschedule-requests") {
            auth("client-a"); json(); setBody(RescheduleRequestDto(session.revision, target.first, target.second, Ids.v7().toString()))
        }.body<SessionDto>()
        assertEquals(SessionStatus.CONFIRMED, requested.status)
        assertEquals(slot.first, requested.start)
        assertEquals(ProposalAuthorDto.CLIENT, requested.proposal!!.author)

        // A second open request is refused by `reschedule_one_open`; a stale revision too.
        val second = http.post("/v1/client/sessions/${session.id}/reschedule-requests") {
            auth("client-a"); json(); setBody(RescheduleRequestDto(requested.revision, target.first, target.second, Ids.v7().toString()))
        }
        assertEquals(HttpStatusCode.Conflict, second.status)
        val stale = http.post("/v1/client/sessions/${session.id}/reschedule-requests") {
            auth("client-a"); json(); setBody(RescheduleRequestDto(session.revision, target.first, target.second, Ids.v7().toString()))
        }
        assertEquals("REVISION_CONFLICT", stale.body<ApiErrorDto>().code)

        val moved = runBlocking { services.scheduling.acceptClientReschedule(trainer.id, Uuid.parse(session.id), chargeFee = false) }
        assertEquals(target.first, moved.start)
        assertNull(moved.proposal)
        assertEquals(1, count("SELECT count(*) FROM scheduling.session_event WHERE type = 'MOVED'"))
    }

    @Test
    fun `acceptance test 7 - accepting a trainer proposal replaces the old revision's reminder`() = api { http ->
        val session = http.book("client-a", trainer, slotIn(60)).body<SessionDto>().let { confirm(it) }
        val target = slotIn(90)
        val proposed = runBlocking { services.scheduling.propose(trainer.id, Uuid.parse(session.id), target.first, target.second, Ids.v7()) }
        assertEquals(ProposalAuthorDto.TRAINER, proposed.proposal!!.author)

        val staleAnswer = http.post("/v1/client/sessions/${session.id}/proposal/answer") { auth("client-a"); json(); setBody(ProposalAnswerDto(session.revision, true)) }
        assertEquals("REVISION_CONFLICT", staleAnswer.body<ApiErrorDto>().code)
        val accepted = http.post("/v1/client/sessions/${session.id}/proposal/answer") { auth("client-a"); json(); setBody(ProposalAnswerDto(proposed.revision, true)) }
            .body<SessionDto>()
        assertEquals(target.first, accepted.start)

        // The reminder for the confirmed revision was cancelled; one for the new revision exists.
        assertEquals(1, count("SELECT count(*) FROM platform.job WHERE dedupe_key = 'reminder:${session.id}:${session.revision}:24h' AND status = 'CANCELLED'"))
        assertEquals(1, count("SELECT count(*) FROM platform.job WHERE dedupe_key = 'reminder:${session.id}:${accepted.revision}:24h' AND status = 'PENDING'"))
        // Even if the old job ran, it would not send: the session moved on.
        assertNull(runBlocking { services.scheduling.reminderStillValid(Uuid.parse(session.id), session.revision) })
        assertNotNull(runBlocking { services.scheduling.reminderStillValid(Uuid.parse(session.id), accepted.revision) })
    }

    @Test
    fun `late cancellation uses a fresh server quote, checks revision and expiry, and charges the fee`() = api { http ->
        // 20 h before: the Standard preset charges 50% (24 h tier); the trainer gives no free late cancellations.
        val session = http.book("client-a", trainer, slotIn(20)).body<SessionDto>().let { confirm(it) }
        val quote = http.post("/v1/client/sessions/${session.id}/cancellation-quotes") { auth("client-a"); json(); setBody(RevisionDto(session.revision)) }
            .body<CancellationQuoteDto>()
        assertEquals(50, quote.fee.policyPercent)
        assertEquals(6000, quote.fee.amount.minor)
        assertEquals(0, quote.freeLateCancellationsLeft)

        val wrongRevision = http.post("/v1/client/sessions/${session.id}/cancel") {
            auth("client-a"); json(); setBody(CancelRequestDto(session.revision + 1, quote.quoteId, Ids.v7().toString()))
        }
        assertEquals("REVISION_CONFLICT", wrongRevision.body<ApiErrorDto>().code)

        // An expired quote → 410; nothing changes.
        db.txBlocking { exec("UPDATE billing.fee_quote SET expires_at = now() - interval '1 minute' WHERE id = '${quote.quoteId}'") }
        val expired = http.post("/v1/client/sessions/${session.id}/cancel") {
            auth("client-a"); json(); setBody(CancelRequestDto(session.revision, quote.quoteId, Ids.v7().toString()))
        }
        assertEquals(HttpStatusCode.Gone, expired.status)

        val fresh = http.post("/v1/client/sessions/${session.id}/cancellation-quotes") { auth("client-a"); json(); setBody(RevisionDto(session.revision)) }
            .body<CancellationQuoteDto>()
        val key = Ids.v7().toString()
        val cancelled = http.post("/v1/client/sessions/${session.id}/cancel") { auth("client-a"); json(); setBody(CancelRequestDto(session.revision, fresh.quoteId, key)) }
            .body<SessionDto>()
        assertEquals(SessionStatus.CANCELLED_BY_CLIENT, cancelled.status)
        // Retrying the same command returns the cancelled session, with one decision and one charge.
        val retry = http.post("/v1/client/sessions/${session.id}/cancel") { auth("client-a"); json(); setBody(CancelRequestDto(session.revision, fresh.quoteId, key)) }
        assertEquals(HttpStatusCode.OK, retry.status)
        assertEquals(1, count("SELECT count(*) FROM billing.fee_decision"))

        val wallet = http.get("/v1/client/wallet") { auth("client-a") }.body<WalletDto>()
        val charge = wallet.charges.single()
        assertEquals(ChargeReasonDto.LATE_CANCELLATION, charge.reason)
        assertEquals(6000, charge.amount.minor)
        assertEquals(ChargeStateDto.UNPAID, charge.state)
    }

    @Test
    fun `acceptance test 4 - another client's session is 404 and an audited denial`() = api { http ->
        val session = http.book("client-a", trainer, slotIn(30)).body<SessionDto>()
        val response = http.post("/v1/client/sessions/${session.id}/withdraw") { auth("client-b"); json(); setBody(RevisionDto(session.revision)) }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(1, count("SELECT count(*) FROM platform.audit_record WHERE action = 'ACCESS_DENIED' AND subject_id = '${session.id}' AND actor_id = '$otherClient'"))
        // The owner can withdraw it.
        val withdrawn = http.post("/v1/client/sessions/${session.id}/withdraw") { auth("client-a"); json(); setBody(RevisionDto(session.revision)) }.body<SessionDto>()
        assertEquals(SessionStatus.CANCELLED_BY_CLIENT, withdrawn.status)
        assertEquals(1, count("SELECT count(*) FROM platform.job WHERE kind = 'request-expiry' AND status = 'CANCELLED'"))
        assertTrue(clientId != otherClient)
    }
}
