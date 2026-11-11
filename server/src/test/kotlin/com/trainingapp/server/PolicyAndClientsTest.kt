package com.trainingapp.server

import com.trainingapp.contract.api.AccessScope
import com.trainingapp.contract.api.ApiError
import com.trainingapp.contract.api.ClientDetailDto
import com.trainingapp.contract.api.ClientInformationDto
import com.trainingapp.contract.api.ClientSummaryDto
import com.trainingapp.contract.api.CreateInviteRequest
import com.trainingapp.contract.api.CreateSessionRequest
import com.trainingapp.contract.api.InviteDto
import com.trainingapp.contract.api.NoteDto
import com.trainingapp.contract.api.OfferDto
import com.trainingapp.contract.api.OfferKind
import com.trainingapp.contract.api.PolicyDto
import com.trainingapp.contract.api.PolicyEditorDto
import com.trainingapp.contract.api.PolicyTierDto
import com.trainingapp.contract.api.SaveNoteRequest
import com.trainingapp.contract.api.SaveOfferRequest
import com.trainingapp.contract.api.SavePolicyRequest
import com.trainingapp.contract.money.Money
import com.trainingapp.server.identity.TrainerClientsService
import com.trainingapp.server.platform.DataCipher
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class PolicyAndClientsTest {

    private val base = "/api/v1/trainer"

    private fun op() = UUID.randomUUID().toString()

    private fun policyRequest(basedOn: String?, tiers: List<PolicyTierDto> = listOf(PolicyTierDto(48, 50), PolicyTierDto(24, 100))) =
        SavePolicyRequest(basedOnVersionId = basedOn, tiers = tiers, noShowPercent = 100, graceMinutes = 15, freeLateCancellationsPerMonth = 1)

    private fun TrainerApiTest.clientsService() =
        TrainerClientsService(services.db, services.clock, services.audit, DataCipher(services.config.dataKeyId, services.config.dataKey))

    // ---------------------------------------------------------------- policy

    @Test
    fun `saving a policy on the current version creates version 2 and a stale base conflicts`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val editor = trainer.get("$base/policy").body<PolicyEditorDto>()
        assertEquals(Seed.POLICY, editor.current?.versionId)
        assertEquals(1, editor.current?.version)

        val saved = trainer.post("$base/policy") { setBody(policyRequest(Seed.POLICY)) }
        assertEquals(HttpStatusCode.Created, saved.status)
        val v2 = saved.body<PolicyDto>()
        assertEquals(2, v2.version)
        assertNotEquals(Seed.POLICY, v2.versionId)
        assertEquals(listOf(48, 24), v2.tiers.map { it.hoursBefore })

        // A second device still holding version 1 must not overwrite version 2.
        val stale = trainer.post("$base/policy") { setBody(policyRequest(Seed.POLICY)) }
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals("CONFLICT", stale.body<ApiError>().code)
        assertEquals(2L, TrainerTestDatabase.count("SELECT count(*) FROM catalogue.policy_version WHERE trainer_id = '${Seed.TRAINER}'"))

        // Version 1 stays readable: sessions keep the version the client accepted.
        assertEquals(HttpStatusCode.OK, trainer.get("$base/policy/versions/${Seed.POLICY}").status)
        assertEquals(v2.versionId, trainer.get("$base/policy").body<PolicyEditorDto>().current?.versionId)
    }

    @Test
    fun `invalid policy rules are rejected with details`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val noTiers = trainer.post("$base/policy") { setBody(policyRequest(Seed.POLICY, tiers = emptyList())) }
        assertEquals(HttpStatusCode.UnprocessableEntity, noTiers.status)
        val error = noTiers.body<ApiError>()
        assertEquals("INVALID_INPUT", error.code)
        assertTrue("NoTiers" in error.details, "details: ${error.details}")

        val badPercent = trainer.post("$base/policy") { setBody(policyRequest(Seed.POLICY, tiers = listOf(PolicyTierDto(24, 33)))) }
        assertEquals(HttpStatusCode.UnprocessableEntity, badPercent.status)
        assertTrue("Percent" in badPercent.body<ApiError>().details)
        assertEquals(1L, TrainerTestDatabase.count("SELECT count(*) FROM catalogue.policy_version WHERE trainer_id = '${Seed.TRAINER}'"))
    }

    // ---------------------------------------------------------------- clients

    @Test
    fun `client list shows three clients and the overdue flag only for the debtor`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val clients = trainer.get("$base/clients").body<List<ClientSummaryDto>>()
        assertEquals(3, clients.size)
        assertEquals(listOf(Seed.REL_1), clients.filter { it.hasOverduePayment }.map { it.id })

        val other = signedIn("demo-other-trainer").get("$base/clients").body<List<ClientSummaryDto>>()
        assertTrue(other.isEmpty())
    }

    @Test
    fun `client detail returns the grants the client gave`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val first = trainer.get("$base/clients/${Seed.REL_1}").body<ClientDetailDto>()
        assertEquals(AccessScope.entries.toSet(), first.grants)
        val second = trainer.get("$base/clients/${Seed.REL_2}").body<ClientDetailDto>()
        assertEquals(setOf(AccessScope.WORKOUT_HISTORY), second.grants)
        val third = trainer.get("$base/clients/${Seed.REL_3}").body<ClientDetailDto>()
        assertTrue(third.grants.isEmpty())
        assertEquals(HttpStatusCode.NotFound, signedIn("demo-other-trainer").get("$base/clients/${Seed.REL_1}").status)
    }

    // ---------------------------------------------------------------- notes

    @Test
    fun `coaching notes are visible only to their author`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val created = trainer.post("$base/clients/${Seed.REL_1}/notes") { setBody(SaveNoteRequest("Bolące kolano")) }
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals(listOf("Bolące kolano"), trainer.get("$base/clients/${Seed.REL_1}/notes").body<List<NoteDto>>().map { it.body })

        val other = signedIn("demo-other-trainer")
        assertEquals(HttpStatusCode.NotFound, other.get("$base/clients/${Seed.REL_1}/notes").status)
        assertEquals(HttpStatusCode.NotFound, other.post("$base/clients/${Seed.REL_1}/notes") { setBody(SaveNoteRequest("x")) }.status)
        val noteId = created.body<NoteDto>().id
        assertEquals(HttpStatusCode.NotFound, other.put("$base/clients/${Seed.REL_1}/notes/$noteId") { setBody(SaveNoteRequest("x")) }.status)
        assertEquals(HttpStatusCode.NotFound, other.delete("$base/clients/${Seed.REL_1}/notes/$noteId").status)
    }

    @Test
    fun `single note editor creates, updates in place and deletes on blank`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val path = "$base/clients/${Seed.REL_2}/note"
        val liveNotes = "SELECT count(*) FROM coaching.coaching_note WHERE relationship_id = '${Seed.REL_2}' AND deleted_at IS NULL"

        val created = trainer.put(path) { setBody(SaveNoteRequest("Pierwsza wersja")) }
        assertEquals(HttpStatusCode.OK, created.status)
        val note = created.body<NoteDto>()
        assertEquals(1L, TrainerTestDatabase.count(liveNotes))

        val updated = trainer.put(path) { setBody(SaveNoteRequest("Druga wersja")) }.body<NoteDto>()
        assertEquals(note.id, updated.id)
        assertEquals("Druga wersja", updated.body)
        assertEquals(1L, TrainerTestDatabase.count(liveNotes))

        val blank = trainer.put(path) { setBody(SaveNoteRequest("   ")) }
        assertEquals(HttpStatusCode.NoContent, blank.status)
        assertEquals(0L, TrainerTestDatabase.count(liveNotes))
        assertTrue(trainer.get("$base/clients/${Seed.REL_2}/notes").body<List<NoteDto>>().isEmpty())
    }

    // ---------------------------------------------------------------- client information

    @Test
    fun `client information needs the health grant and every attempt is audited`() = apiTest {
        val service = clientsService()
        val info = ClientInformationDto(birthDate = "1990-05-01", weightKg = 62.5, heightCm = 168.0, trainingGoal = "Siła", injuries = "Kolano")
        service.saveForClient(Seed.CLIENT_1, info)
        service.saveForClient(UUID.fromString("22222222-2222-2222-2222-222222222222"), info)

        val trainer = signedIn("demo-trainer")
        val allowed = trainer.get("$base/clients/${Seed.REL_1}/information")
        assertEquals(HttpStatusCode.OK, allowed.status)
        val read = allowed.body<ClientInformationDto>()
        assertEquals("Kolano", read.injuries)
        assertEquals(62.5, read.weightKg)
        assertEquals(
            1L,
            TrainerTestDatabase.count("SELECT count(*) FROM platform.audit_record WHERE actor_id = '${Seed.TRAINER}' AND relationship_id = '${Seed.REL_1}' AND outcome = 'ALLOWED'"),
        )
        // The stored payload is encrypted: the injury text never appears in plain form.
        assertEquals(0L, TrainerTestDatabase.count("SELECT count(*) FROM coaching.client_information WHERE position('Kolano'::bytea in payload_encrypted) > 0"))

        val denied = trainer.get("$base/clients/${Seed.REL_2}/information")
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertEquals("DENIED", denied.body<ApiError>().code)
        assertEquals(
            1L,
            TrainerTestDatabase.count("SELECT count(*) FROM platform.audit_record WHERE actor_id = '${Seed.TRAINER}' AND relationship_id = '${Seed.REL_2}' AND outcome = 'DENIED'"),
        )
    }

    // ---------------------------------------------------------------- invites

    @Test
    fun `invites can be created, listed and revoked`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val created = trainer.post("$base/invites") { setBody(CreateInviteRequest(validDays = 7, maxUses = 2)) }
        assertEquals(HttpStatusCode.Created, created.status)
        val invite = created.body<InviteDto>()
        assertEquals(8, invite.code.length)
        assertEquals(2, invite.maxUses)
        assertEquals(0, invite.usedCount)
        assertTrue(invite.expiresAt > clock.now() + 6.days && invite.expiresAt < clock.now() + 8.days)

        assertEquals(listOf(invite.code), trainer.get("$base/invites").body<List<InviteDto>>().map { it.code })
        assertTrue(signedIn("demo-other-trainer").get("$base/invites").body<List<InviteDto>>().isEmpty())
        assertEquals(HttpStatusCode.NotFound, signedIn("demo-other-trainer").delete("$base/invites/${invite.code}").status)

        assertEquals(HttpStatusCode.NoContent, trainer.delete("$base/invites/${invite.code}").status)
        assertTrue(trainer.get("$base/invites").body<List<InviteDto>>().isEmpty())

        assertEquals(HttpStatusCode.UnprocessableEntity, trainer.post("$base/invites") { setBody(CreateInviteRequest(validDays = 0)) }.status)
        assertEquals(HttpStatusCode.UnprocessableEntity, trainer.post("$base/invites") { setBody(CreateInviteRequest(maxUses = 0)) }.status)
    }

    // ---------------------------------------------------------------- ending a relationship

    @Test
    fun `ending a relationship revokes grants and refuses later commands`() = apiTest {
        val trainer = signedIn("demo-trainer")
        assertEquals(HttpStatusCode.NoContent, trainer.post("$base/clients/${Seed.REL_1}/end").status)
        assertEquals(0L, TrainerTestDatabase.count("SELECT count(*) FROM identity.access_grant WHERE relationship_id = '${Seed.REL_1}' AND revoked_at IS NULL"))
        assertEquals(1L, TrainerTestDatabase.count("SELECT count(*) FROM identity.trainer_client WHERE id = '${Seed.REL_1}' AND status = 'ENDED'"))

        val detail = trainer.get("$base/clients/${Seed.REL_1}").body<ClientDetailDto>()
        assertTrue(detail.grants.isEmpty())
        assertEquals(HttpStatusCode.Forbidden, trainer.get("$base/clients/${Seed.REL_1}/information").status)

        val start = clock.now() + 3.days
        val create = trainer.post("$base/sessions") {
            setBody(CreateSessionRequest(Seed.REL_1, start, start + 1.hours, "Sala 1", operationId = op()))
        }
        assertEquals(HttpStatusCode.Forbidden, create.status)
        assertEquals(0L, TrainerTestDatabase.count("SELECT count(*) FROM scheduling.session WHERE relationship_id = '${Seed.REL_1}' AND starts_at = '$start'"))

        // Ending twice is a conflict, not a silent success.
        assertEquals(HttpStatusCode.Conflict, trainer.post("$base/clients/${Seed.REL_1}/end").status)
    }

    // ---------------------------------------------------------------- offers

    @Test
    fun `offers can be created and archived and invalid ones are rejected`() = apiTest {
        val trainer = signedIn("demo-trainer")
        val request = SaveOfferRequest(OfferKind.SINGLE_SESSION, "Trening 45 min", 45, Money.pln(120))
        val created = trainer.post("$base/offers") { setBody(request) }
        assertEquals(HttpStatusCode.Created, created.status)
        val offer = created.body<OfferDto>()
        assertEquals(12000L, offer.price.minor)
        assertTrue(trainer.get("$base/offers").body<List<OfferDto>>().any { it.id == offer.id })

        // Another trainer cannot archive it.
        assertEquals(HttpStatusCode.NotFound, signedIn("demo-other-trainer").post("$base/offers/${offer.id}/archive").status)
        assertEquals(HttpStatusCode.NoContent, trainer.post("$base/offers/${offer.id}/archive").status)
        assertTrue(trainer.get("$base/offers").body<List<OfferDto>>().none { it.id == offer.id })
        assertEquals(HttpStatusCode.NotFound, trainer.post("$base/offers/${offer.id}/archive").status)

        val badRequests = listOf(
            request.copy(kind = OfferKind.PACKAGE, sessionsPerPeriod = null),
            request.copy(sessionsPerPeriod = 4),
            request.copy(durationMinutes = 5),
            request.copy(name = "  "),
            request.copy(price = Money(-1, "PLN")),
        )
        badRequests.forEach {
            assertEquals(HttpStatusCode.UnprocessableEntity, trainer.post("$base/offers") { setBody(it) }.status, "request: $it")
        }
    }
}
