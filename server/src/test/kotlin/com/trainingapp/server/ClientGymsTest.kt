package com.trainingapp.server

import com.trainingapp.contract.account.ClientGymsDto
import com.trainingapp.contract.account.SaveClientGymsRequest
import com.trainingapp.contract.api.ClientSummaryDto
import com.trainingapp.server.identity.InviteCodes
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.jdbc.insert

/** "Moje siłownie" of a client, and the trainer reading them in the client card (docs/plans/trainer-redesign.md). */
class ClientGymsTest : ApiTest() {
    private val trainer = fixtures.trainer("gyms-trainer")
    private val clientId = fixtures.client("gyms-client", name = "Ola Klient")

    @Test
    fun `a client picks public gyms and their trainer sees the names`() = api { http ->
        val choices = http.get("/v1/client/gyms") { auth("gyms-client"); json() }.body<ClientGymsDto>()
        assertTrue(choices.selected.isEmpty())
        val gym = choices.all.first { it.name == trainer.gymName }

        val saved = http.put("/v1/client/gyms") { auth("gyms-client"); json(); setBody(SaveClientGymsRequest(listOf(gym.id))) }
        assertEquals(HttpStatusCode.OK, saved.status)
        assertEquals(listOf(gym.id), saved.body<ClientGymsDto>().selected)
        // Unknown gyms are refused and the list stays as it was.
        val bad = http.put("/v1/client/gyms") { auth("gyms-client"); json(); setBody(SaveClientGymsRequest(listOf("00000000-0000-0000-0000-000000000000"))) }
        assertEquals(HttpStatusCode.UnprocessableEntity, bad.status)
        assertEquals(1L, count("SELECT count(*) FROM identity.client_gym WHERE client_id = '$clientId'"))

        // The client joins the trainer; the trainer's client list carries the gym names.
        db.txBlocking {
            InviteCodes.insert {
                it[code] = "GYMS0001"; it[trainerId] = trainer.id; it[expiresAt] = OffsetDateTime.now().plusDays(1); it[maxUses] = 1; it[usedCount] = 0
            }
        }
        http.post("/v1/client/invites/GYMS0001/redeem") { auth("gyms-client") }
        // The trainer API reads the list through SessionReads (its routes are not part of this test application).
        val clients: List<ClientSummaryDto> = db.txBlocking { com.trainingapp.server.scheduling.SessionReads.clients(java.util.UUID.fromString(trainer.id.toString())) }
        assertEquals(listOf(trainer.gymName), clients.single { it.name == "Ola Klient" }.gyms)
    }
}
