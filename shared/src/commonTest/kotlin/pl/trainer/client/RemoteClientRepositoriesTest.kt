package pl.trainer.client

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import pl.trainer.data.remote.client.ClientApi
import pl.trainer.data.remote.client.ClientApiHttpException
import pl.trainer.data.remote.client.ClientApiOfflineException
import pl.trainer.data.remote.client.PublicPolicyRepository
import pl.trainer.data.remote.client.RemoteClientScheduleRepository
import pl.trainer.data.remote.client.RemoteWalletRepository
import pl.trainer.domain.BookingRequest
import pl.trainer.domain.ChargeKind
import pl.trainer.domain.ChargeReason
import pl.trainer.domain.ChargeState
import pl.trainer.domain.ClientSessionState
import pl.trainer.domain.Failure
import pl.trainer.domain.PaymentMethod
import pl.trainer.domain.PaymentState
import pl.trainer.domain.RescheduleRequest
import pl.trainer.domain.TrainerException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The client repositories against a scripted [ClientApi]. Synthetic data only. */
class RemoteClientRepositoriesTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private class Call(val method: HttpMethod, val path: String, val body: String?, val operationId: String?)

    /** Answers by `"METHOD path"` prefix; a null answer is a `204`; a Throwable is thrown. */
    private inner class FakeApi : ClientApi {
        val calls = mutableListOf<Call>()
        val answers = mutableMapOf<String, Any?>()

        private fun answer(method: HttpMethod, path: String): Any? {
            val key = answers.keys.firstOrNull { "${method.value} $path".startsWith(it) } ?: error("unscripted ${method.value} $path")
            val value = answers[key]
            if (value is Throwable) throw value
            return value
        }

        override suspend fun <T> get(path: String, serializer: KSerializer<T>, query: List<Pair<String, String>>): T {
            val full = path + if (query.isEmpty()) "" else query.joinToString("&", "?") { "${it.first}=${it.second}" }
            calls += Call(HttpMethod.Get, full, null, null)
            return json.decodeFromString(serializer, answer(HttpMethod.Get, full) as String)
        }

        override suspend fun <B, T> send(
            method: HttpMethod, path: String, body: B?, bodySerializer: KSerializer<B>?, response: KSerializer<T>?, operationId: String?,
        ): T? {
            calls += Call(method, path, if (body != null && bodySerializer != null) json.encodeToString(bodySerializer, body) else null, operationId)
            val text = answer(method, path) as String? ?: return null
            return response?.let { json.decodeFromString(it, text) }
        }
    }

    private val trainer = """{"id":"t-1","name":"Test Trener","initials":"TT"}"""

    private fun session(id: String = "s-1", status: String = "CONFIRMED", revision: Int = 2, proposal: String? = null) =
        """{"id":"$id","trainerId":"t-1","clientId":"c-1","start":"2026-11-12T08:00:00Z","end":"2026-11-12T09:00:00Z",
        "timeZone":"Europe/Warsaw","location":"Studio","status":"$status","revision":$revision,
        ${if (proposal != null) "\"proposal\":$proposal," else ""}
        "payment":{"kind":"PACKAGE","packageUsed":2,"packageTotal":8},"policyVersionId":"pol-1",
        "price":{"minor":12000,"currency":"PLN"},"requestedAt":"2026-11-10T10:00:00Z","futureField":1}"""

    private fun proposal(author: String) =
        """{"start":"2026-11-13T08:00:00Z","end":"2026-11-13T09:00:00Z","author":"$author","requestedAt":"2026-11-11T09:00:00Z"}"""

    private fun schedule(vararg sessions: String) =
        """{"trainers":[$trainer],"sessions":[${sessions.joinToString(",")}],"serverNow":"2026-11-11T10:00:00Z"}"""

    private val policy = """{"versionId":"pol-1","trainerId":"t-1","version":3,"createdAt":"2026-10-01T00:00:00Z",
        "tiers":[{"hoursBefore":24,"feePercent":50},{"hoursBefore":12,"feePercent":100}],"noShowPercent":100,
        "graceMinutes":15,"freeLateCancellationsPerMonth":1,"lateRescheduleIsCancel":false}"""

    private fun repo(api: FakeApi) = RemoteClientScheduleRepository(api, identity = { "client-1" })

    @Test
    fun scheduleMapsRequestedConfirmedAndProposals() = runTest {
        val api = FakeApi()
        api.answers["GET v1/client/schedule"] = schedule(
            session("s-req", "REQUESTED", 1),
            session("s-ok"),
            session("s-prop", proposal = proposal("TRAINER")),
            session("s-resch", proposal = proposal("CLIENT")),
            session("s-x", "CANCELLED_BY_TRAINER", 4),
        )
        val loaded = repo(api).load()
        val byId = loaded.sessions.associateBy { it.id }

        assertEquals(ClientSessionState.AwaitingTrainer, byId.getValue("s-req").state)
        assertEquals(ClientSessionState.Confirmed, byId.getValue("s-ok").state)
        // A reschedule negotiation never changes the confirmed time or status.
        val proposed = byId.getValue("s-prop")
        assertEquals(ClientSessionState.ProposalFromTrainer, proposed.state)
        assertEquals(Instant.parse("2026-11-12T08:00:00Z"), proposed.session.start)
        assertEquals(Instant.parse("2026-11-13T08:00:00Z"), proposed.agendaStart)
        assertEquals(ClientSessionState.RescheduleRequested, byId.getValue("s-resch").state)
        assertTrue(byId.getValue("s-resch").canWithdraw)
        assertEquals(ClientSessionState.Cancelled, byId.getValue("s-x").state)
        assertEquals(2, proposed.revision)
        assertEquals(12000L, proposed.session.price?.minor)
        assertEquals("Test Trener", proposed.trainer.name)
        assertEquals(Instant.parse("2026-11-11T10:00:00Z"), loaded.serverNow)
    }

    @Test
    fun bookingRequestIsOnlyARequestAndSendsTheOperationIdOnceAndReusesItOnRetry() = runTest {
        val api = FakeApi()
        api.answers["GET v1/client/schedule"] = schedule()
        val request = BookingRequest("t-1", Instant.parse("2026-11-12T08:00:00Z"), Instant.parse("2026-11-12T09:00:00Z"), "Studio", "pol-1")
        val repository = repo(api)

        // First attempt: the connection is lost, so nothing is confirmed.
        api.answers["POST v1/client/booking-requests"] = ClientApiOfflineException()
        assertEquals(Failure.Offline, assertFailsWith<TrainerException> { repository.requestBooking(request) }.failure)
        val first = api.calls.last { it.path == "v1/client/booking-requests" }

        // Retry of the same request: same operation id (header and body), now accepted.
        api.answers["POST v1/client/booking-requests"] = session("s-new", "REQUESTED", 1)
        val created = repository.requestBooking(request)
        val second = api.calls.last { it.path == "v1/client/booking-requests" }
        assertEquals(ClientSessionState.AwaitingTrainer, created.state)
        assertEquals(first.operationId, second.operationId)
        assertTrue(second.body!!.contains("\"operationId\":\"${second.operationId}\""))
        assertTrue(second.body!!.contains("\"acceptedPolicyVersionId\":\"pol-1\""))

        // A later identical request (e.g. after a rejection) is a new action with a new id.
        repository.requestBooking(request)
        assertNotEquals(second.operationId, api.calls.last { it.path == "v1/client/booking-requests" }.operationId)
        assertEquals(2, api.calls.count { it.path == "v1/client/booking-requests" && it.operationId == second.operationId })
    }

    @Test
    fun bookingErrorsMapToFailures() = runTest {
        val api = FakeApi()
        val request = BookingRequest("t-1", Instant.parse("2026-11-12T08:00:00Z"), Instant.parse("2026-11-12T09:00:00Z"), "Studio", "pol-0")
        val repository = repo(api)
        val expected = mapOf(
            ClientApiHttpException(409, "SLOT_TAKEN") to Failure.Conflict,
            ClientApiHttpException(409, "POLICY_OUTDATED") to Failure.Conflict,
            ClientApiHttpException(409, "REVISION_CONFLICT") to Failure.Conflict,
            ClientApiHttpException(422, "VALIDATION") to Failure.InvalidInput,
            ClientApiHttpException(403, "FORBIDDEN") to Failure.Denied,
            ClientApiHttpException(404, "NO_ACCOUNT") to Failure.Denied,
            ClientApiHttpException(500, "HTTP_500") to Failure.Save,
        )
        for ((error, failure) in expected) {
            api.answers["POST v1/client/booking-requests"] = error
            assertEquals(failure, assertFailsWith<TrainerException> { repository.requestBooking(request) }.failure, error.code)
        }
    }

    @Test
    fun cancelUsesTheQuoteAndNeverCancelsOnFailure() = runTest {
        val api = FakeApi()
        val quote = """{"quoteId":"q-1","sessionId":"s-1","revision":2,"fee":{"tierHoursBefore":24,"policyPercent":50,
            "amount":{"minor":6000,"currency":"PLN"},"usesFreeCancellation":false},"policyVersionId":"pol-1",
            "freeLateCancellationsLeft":0,"chargeKind":"ONLINE","quotedAt":"2026-11-11T10:00:00Z","expiresAt":"2026-11-11T10:05:00Z"}"""
        api.answers["POST v1/client/sessions/s-1/cancellation-quotes"] = quote
        val repository = repo(api)

        val q = repository.quoteCancellation("s-1", 2)
        assertEquals(6000L, q.fee.amount.minor)
        assertEquals(50, q.fee.policyPercent)
        assertTrue(q.fee.late)
        assertEquals(ChargeKind.Online, q.chargeKind)
        assertEquals(Instant.parse("2026-11-11T10:05:00Z"), q.expiresAt)

        api.answers["POST v1/client/sessions/s-1/cancel"] = ClientApiHttpException(410, "QUOTE_EXPIRED")
        assertEquals(Failure.QuoteExpired, assertFailsWith<TrainerException> { repository.cancel("s-1", 2, q.quoteId) }.failure)
        api.answers["POST v1/client/sessions/s-1/cancel"] = ClientApiHttpException(409, "REVISION_CONFLICT")
        assertEquals(Failure.QuoteExpired, assertFailsWith<TrainerException> { repository.cancel("s-1", 2, q.quoteId) }.failure)

        api.answers["POST v1/client/sessions/s-1/cancel"] = session("s-1", "CANCELLED_BY_CLIENT", 3)
        val cancelled = repository.cancel("s-1", 2, q.quoteId)
        assertEquals(ClientSessionState.Cancelled, cancelled.state)
        val sent = api.calls.last { it.path.endsWith("/cancel") }
        assertTrue(sent.body!!.contains("\"quoteId\":\"q-1\""))
        assertTrue(sent.body!!.contains("\"revision\":2"))
        // The same quote always maps to the same operation id, so a retry cannot cancel or charge twice.
        assertEquals(api.calls.first { it.path.endsWith("/cancel") }.operationId, sent.operationId)
        assertEquals(sent.operationId, Regex("\"operationId\":\"([^\"]+)\"").find(sent.body!!)!!.groupValues[1])
    }

    @Test
    fun rescheduleKeepsTheOriginalTimeAndAnswersMapConflicts() = runTest {
        val api = FakeApi()
        val repository = repo(api)
        api.answers["POST v1/client/sessions/s-1/reschedule-requests"] = session(proposal = proposal("CLIENT"), revision = 3)
        val result = repository.requestReschedule(
            RescheduleRequest("s-1", 2, Instant.parse("2026-11-13T08:00:00Z"), Instant.parse("2026-11-13T09:00:00Z")),
        )
        assertEquals(ClientSessionState.RescheduleRequested, result.state)
        assertEquals(Instant.parse("2026-11-12T08:00:00Z"), result.session.start)
        assertEquals(result.session.proposal?.start, Instant.parse("2026-11-13T08:00:00Z"))

        api.answers["POST v1/client/sessions/s-1/proposal/answer"] = ClientApiHttpException(409, "SLOT_TAKEN")
        assertEquals(Failure.Conflict, assertFailsWith<TrainerException> { repository.answerProposal("s-1", 3, true) }.failure)

        // A fee preview of 204 means the change is not late.
        api.answers["GET v1/client/sessions/s-1/reschedule-fee"] = null
        assertNull(repository.previewRescheduleFee("s-1", 2))
    }

    @Test
    fun scheduleFallsBackToMemoryWhenOfflineAndNeverFakesChanges() = runTest {
        val api = FakeApi()
        val repository = repo(api)
        api.answers["GET v1/client/schedule"] = ClientApiOfflineException()
        // No data yet: offline is an error, not an empty agenda.
        assertEquals(Failure.Offline, assertFailsWith<TrainerException> { repository.load() }.failure)

        api.answers["GET v1/client/schedule"] = schedule(session())
        assertFalse(repository.load().sessions.isEmpty())
        assertFalse(repository.lastLoadWasCached)

        api.answers["GET v1/client/schedule"] = ClientApiOfflineException()
        assertEquals(1, repository.load().sessions.size)
        assertTrue(repository.lastLoadWasCached)

        api.answers["POST v1/client/sessions/s-1/withdraw"] = ClientApiOfflineException()
        assertEquals(Failure.Offline, assertFailsWith<TrainerException> { repository.withdrawRequest("s-1", 2) }.failure)
        assertEquals(0L, repository.updates.value)
    }

    @Test
    fun slotsBookingOptionsAndPolicyAreRead() = runTest {
        val api = FakeApi()
        api.answers["GET v1/client/trainers/t-1/slots"] = """[{"start":"2026-11-12T08:00:00Z","end":"2026-11-12T09:00:00Z"}]"""
        api.answers["GET v1/client/trainers/t-1/booking-options"] = """{"trainer":$trainer,"policy":$policy,
            "price":{"minor":12000,"currency":"PLN"},"places":["Studio"],"timeZone":"Europe/Warsaw","durationMinutes":60}"""
        val repository = repo(api)

        val slots = repository.availableSlots("t-1", LocalDate(2026, 11, 12), excludeSessionId = "s-1")
        assertEquals(1, slots.size)
        assertEquals("v1/client/trainers/t-1/slots?date=2026-11-12&excludeSessionId=s-1", api.calls.last().path)

        val options = repository.bookingOptions("t-1")
        assertEquals("pol-1", options.policy.versionId)
        assertEquals(listOf(24, 12), options.policy.sortedTiers.map { it.hoursBefore })
        assertEquals("Studio", options.places.single())

        // The version seen in the options is cached: no second request is needed to show it.
        repository.policy("pol-1")
        assertTrue(api.calls.none { it.path.startsWith("v1/policies/") })
    }

    @Test
    fun publicPolicyRepositoryUsesVersionsAndBookingOptionsAndDeniesSaving() = runTest {
        val api = FakeApi()
        api.answers["GET v1/policies/pol-1"] = policy
        api.answers["GET v1/client/trainers/t-1/booking-options"] = """{"trainer":$trainer,"policy":$policy,
            "price":{"minor":15000,"currency":"PLN"},"places":[],"timeZone":"Europe/Warsaw","durationMinutes":60}"""
        val policies = PublicPolicyRepository(api)

        assertEquals(3, policies.version("pol-1").version)
        policies.version("pol-1")
        assertEquals(1, api.calls.count { it.path == "v1/policies/pol-1" })
        assertEquals("pol-1", policies.current("t-1").versionId)
        assertEquals(15000L, policies.sessionPrice("t-1").minor)

        api.answers["GET v1/policies/pol-2"] = ClientApiHttpException(404, "NOT_FOUND")
        assertEquals(Failure.Load, assertFailsWith<TrainerException> { policies.version("pol-2") }.failure)
        assertEquals(
            Failure.Denied,
            assertFailsWith<TrainerException> { policies.save("t-1", policies.current("t-1").rules, "pol-1") }.failure,
        )
    }

    private val wallet = """{"packages":[{"id":"pk-1","name":"Pakiet 8","trainer":$trainer,"used":2,"total":8}],
        "charges":[
          {"id":"ch-1","reason":"LATE_CANCELLATION","sessionId":"s-1","sessionStart":"2026-11-12T08:00:00Z","trainer":$trainer,
           "amount":{"minor":6000,"currency":"PLN"},"open":{"minor":6000,"currency":"PLN"},"kind":"ONLINE","state":"%STATE%",
           "createdAt":"2026-11-11T09:00:00Z"},
          {"id":"ch-2","reason":"SESSION","sessionId":null,"sessionStart":null,"trainer":$trainer,
           "amount":{"minor":100,"currency":"PLN"},"open":{"minor":100,"currency":"PLN"},"kind":"ONLINE","state":"UNPAID",
           "createdAt":"2026-11-11T09:00:00Z"}],
        "payments":[
          {"id":"pay-1","chargeId":"ch-1","reason":"LATE_CANCELLATION","trainer":$trainer,"amount":{"minor":6000,"currency":"PLN"},
           "method":"TEST_MODE","state":"PENDING","at":"2026-11-11T09:30:00Z"},
          {"id":"pay-2","chargeId":null,"reason":null,"trainer":$trainer,"amount":{"minor":5000,"currency":"PLN"},
           "method":"CASH","state":"PAID","at":"2026-11-10T09:30:00Z"}],
        "serverNow":"2026-11-11T10:00:00Z"}"""

    @Test
    fun walletShowsPendingUntilTheServerSettlesAndPaymentIsIdempotent() = runTest {
        val api = FakeApi()
        val repository = RemoteWalletRepository(api, identity = { "client-1" })
        api.answers["GET v1/client/wallet"] = wallet.replace("%STATE%", "UNPAID")
        val before = repository.load()
        val charge = before.charges.first { it.id == "ch-1" }
        assertEquals(ChargeState.Unpaid, charge.state)
        assertTrue(charge.payableInApp)
        assertEquals(6000L, charge.amount.minor)
        // A session charge and a cash payment are mapped, not dropped.
        assertEquals(ChargeReason.Session, before.charges.first { it.id == "ch-2" }.reason)
        assertEquals(setOf("pay-1", "pay-2"), before.payments.map { it.id }.toSet())
        val cash = before.payments.first { it.id == "pay-2" }
        assertEquals(PaymentMethod.Cash, cash.method)
        assertEquals(PaymentState.Paid, cash.state)

        api.answers["POST v1/client/charges/ch-1/payments"] =
            """{"payment":{"id":"pay-1","chargeId":"ch-1","reason":"LATE_CANCELLATION","trainer":$trainer,
            "amount":{"minor":6000,"currency":"PLN"},"method":"TEST_MODE","state":"PENDING","at":"2026-11-11T09:30:00Z"},"clientSecret":"x"}"""
        val first = repository.pay("ch-1", PaymentMethod.TestMode, "key-1")
        repository.pay("ch-1", PaymentMethod.TestMode, "key-1")
        assertEquals(PaymentState.Pending, first.state)
        val sent = api.calls.filter { it.method == HttpMethod.Post }
        assertEquals(sent[0].operationId, sent[1].operationId)
        assertTrue(sent[0].body!!.contains("TEST_MODE"))
        assertEquals(2L, repository.updates.value)

        // The charge is pending until the server's webhook says otherwise, then settled.
        api.answers["GET v1/client/wallet"] = wallet.replace("%STATE%", "PAYMENT_PENDING")
        assertEquals(ChargeState.PaymentPending, repository.load().charges.first { it.id == "ch-1" }.state)
        api.answers["GET v1/client/wallet"] = wallet.replace("%STATE%", "SETTLED")
        assertEquals(ChargeState.Settled, repository.load().charges.first { it.id == "ch-1" }.state)
    }

    @Test
    fun walletOfflineUsesMemoryAndPaymentFailsWithoutSending() = runTest {
        val api = FakeApi()
        val repository = RemoteWalletRepository(api, identity = { "client-1" })
        api.answers["GET v1/client/wallet"] = ClientApiOfflineException()
        assertEquals(Failure.Offline, assertFailsWith<TrainerException> { repository.load() }.failure)

        api.answers["GET v1/client/wallet"] = wallet.replace("%STATE%", "UNPAID")
        repository.load()
        api.answers["GET v1/client/wallet"] = ClientApiOfflineException()
        assertEquals(2, repository.load().charges.size)
        assertTrue(repository.lastLoadWasCached)

        api.answers["POST v1/client/charges/ch-1/payments"] = ClientApiOfflineException()
        assertEquals(Failure.Offline, assertFailsWith<TrainerException> { repository.pay("ch-1", PaymentMethod.Blik, "k") }.failure)
        assertEquals(0L, repository.updates.value)

        api.answers["POST v1/client/charges/ch-1/payments"] = ClientApiHttpException(409, "CONFLICT")
        assertEquals(Failure.Conflict, assertFailsWith<TrainerException> { repository.pay("ch-1", PaymentMethod.Blik, "k") }.failure)
    }

    @Test
    fun cachedDataIsDroppedWhenTheAccountChanges() = runTest {
        val api = FakeApi()
        var who = "client-1"
        val repository = RemoteClientScheduleRepository(api, identity = { who })
        api.answers["GET v1/client/schedule"] = schedule(session())
        repository.load()
        who = "client-2"
        api.answers["GET v1/client/schedule"] = ClientApiOfflineException()
        assertEquals(Failure.Offline, assertFailsWith<TrainerException> { repository.load() }.failure)
    }
}
