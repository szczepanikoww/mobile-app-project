package com.trainingapp.server

import com.trainingapp.contract.account.JoinedTrainerDto
import com.trainingapp.server.identity.InviteCodes
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.exposed.v1.jdbc.insert

/** "Mam kod od trenera": a client joins the trainer of an invite code (docs/plans/trainer-redesign.md). */
class InviteRedeemTest : ApiTest() {
    private val trainer = fixtures.trainer("invite-trainer", name = "Anna Trener")
    private val clientId = fixtures.client("invite-client")

    private fun code(code: String, maxUses: Int = 10, expiresAt: OffsetDateTime = OffsetDateTime.now().plusDays(10)) = db.txBlocking {
        InviteCodes.insert {
            it[InviteCodes.code] = code
            it[trainerId] = trainer.id
            it[InviteCodes.expiresAt] = expiresAt
            it[InviteCodes.maxUses] = maxUses
            it[usedCount] = 0
        }
    }

    @Test
    fun `a code creates an active relationship once and joining again is idempotent`() = api { http ->
        code("ABCD2345")
        val joined = http.post("/v1/client/invites/abcd2345/redeem") { auth("invite-client") }
        assertEquals(HttpStatusCode.OK, joined.status)
        val dto = joined.body<JoinedTrainerDto>()
        assertEquals("Anna Trener", dto.trainerName)
        assertEquals(1L, count("SELECT count(*) FROM identity.trainer_client WHERE client_id = '$clientId' AND status = 'ACTIVE' AND origin = 'INVITE_CODE'"))
        assertEquals(3L, count("SELECT count(*) FROM identity.access_grant WHERE relationship_id = '${dto.relationshipId}'"))

        val again = http.post("/v1/client/invites/ABCD2345/redeem") { auth("invite-client") }.body<JoinedTrainerDto>()
        assertEquals(dto.relationshipId, again.relationshipId)
        assertEquals(1L, count("SELECT used_count FROM identity.invite_code WHERE code = 'ABCD2345'"))
    }

    @Test
    fun `unknown, expired and used-up codes are refused`() = api { http ->
        code("EXPIRED1", expiresAt = OffsetDateTime.now().minusDays(1))
        code("USEDUP11", maxUses = 1)
        fixtures.client("first-user")
        assertEquals(HttpStatusCode.OK, http.post("/v1/client/invites/USEDUP11/redeem") { auth("first-user") }.status)
        assertEquals(HttpStatusCode.NotFound, http.post("/v1/client/invites/USEDUP11/redeem") { auth("invite-client") }.status)
        assertEquals(HttpStatusCode.NotFound, http.post("/v1/client/invites/EXPIRED1/redeem") { auth("invite-client") }.status)
        assertEquals(HttpStatusCode.NotFound, http.post("/v1/client/invites/NOPE0000/redeem") { auth("invite-client") }.status)
        assertEquals(0L, count("SELECT count(*) FROM identity.trainer_client WHERE client_id = '$clientId'"))
    }
}
