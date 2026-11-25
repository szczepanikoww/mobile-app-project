package com.trainingapp.server

import com.trainingapp.contract.account.CreateAccountRequest
import com.trainingapp.contract.account.MeDto
import com.trainingapp.contract.account.RoleDto
import com.trainingapp.contract.account.UpdateMeRequest
import com.trainingapp.contract.api.ApiErrorDto
import com.trainingapp.contract.catalog.GymDetailDto
import com.trainingapp.contract.catalog.TrainerProfileDto
import com.trainingapp.contract.catalog.TrainerSummaryDto
import com.trainingapp.server.dev.DemoSeed
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CatalogueAndAccountTest : ApiTest() {
    init {
        runBlocking { DemoSeed(services).seed() }
    }

    @Test
    fun `public catalogue lists the seeded trainers and filters like CatalogSearch`() = api { client ->
        val all = client.get("/v1/public/trainers").body<List<TrainerSummaryDto>>()
        assertEquals(12, all.size)
        // Trainers without a public price go last; otherwise cheapest first.
        assertEquals("Zofia Krawczyk", all.last().name)
        assertEquals("Ewa Dąbrowska", all.first().name)

        // Diacritic-insensitive text search ("lukasz" finds "Łukasz").
        val lukasz = client.get("/v1/public/trainers?text=lukasz").body<List<TrainerSummaryDto>>()
        assertEquals(listOf("Łukasz Kozłowski"), lukasz.map { it.name })

        // Goal labels are searchable too, and filters combine.
        val mobility = client.get("/v1/public/trainers?goal=MOBILITY&price=UP_TO_100").body<List<TrainerSummaryDto>>()
        assertEquals(listOf("Ewa Dąbrowska"), mobility.map { it.name })
        val weekend = client.get("/v1/public/trainers?availability=WEEKEND&district=Mokotów").body<List<TrainerSummaryDto>>()
        assertTrue(weekend.map { it.name }.containsAll(listOf("Anna Wiśniewska", "Marcin Mazur")))
    }

    @Test
    fun `trainer profile exposes only public data and its current policy`() = api { client ->
        val id = DemoSeed.id("tr-michal")
        val response = client.get("/v1/public/trainers/$id")
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        // Privacy (plan §13): no e-mail, client, relationship or session data in public JSON.
        for (field in listOf("email", "clientId", "relationship", "Katarzyna Nowak", "auth")) assertFalse(text.contains(field), field)
        val profile = response.body<TrainerProfileDto>()
        assertEquals(1, profile.cancellationPolicy!!.version)
        assertEquals(24, profile.cancellationPolicy!!.freeCancellationHours)
        assertEquals(4, profile.offers.size)
        assertEquals("Studio Centrum", profile.summary.mainGym!!.name)

        // ETag → 304.
        val etag = response.headers[HttpHeaders.ETag]!!
        assertEquals(HttpStatusCode.NotModified, client.get("/v1/public/trainers/$id") { header(HttpHeaders.IfNoneMatch, etag) }.status)
    }

    @Test
    fun `non-public or unknown trainers are 404, gyms format opening hours`() = api { client ->
        val hidden = fixtures.trainer("hidden-trainer", public = false)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/public/trainers/${hidden.id}").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/public/trainers/not-a-uuid").status)
        val gym = client.get("/v1/public/gyms/${DemoSeed.id("gym-centrum")}").body<GymDetailDto>()
        assertEquals("Pon–Pt 06:00–22:00 · Sob–Nd 08:00–20:00", gym.summary.hoursSummary)
        // The map on the gyms screen needs a position for every seeded gym.
        assertEquals(52.2297, gym.summary.latitude!!, 1e-6)
        assertEquals(21.0122, gym.summary.longitude!!, 1e-6)
        val wola = client.get("/v1/public/gyms/${DemoSeed.id("gym-wola")}") { header(HttpHeaders.AcceptLanguage, "en") }.body<GymDetailDto>()
        assertEquals("Open 24 hours", wola.summary.hoursSummary)
        assertTrue(gym.trainers.any { it.name == "Michał Kowalczyk" })
    }

    @Test
    fun `registration is one idempotent transaction with consent records`() = api { client ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me").status)
        val noAccount = client.get("/v1/me") { auth("new-user") }
        assertEquals(HttpStatusCode.NotFound, noAccount.status)
        assertEquals("NO_ACCOUNT", noAccount.body<ApiErrorDto>().code)

        val request = CreateAccountRequest("8f3c0e3e-0000-7000-8000-000000000001", "Nowa Osoba", RoleDto.CLIENT, "pl", "Europe/Warsaw", "demo-2026-11", "demo-2026-11")
        val first = client.post("/v1/accounts") { auth("new-user"); json(); setBody(request) }
        assertEquals(HttpStatusCode.Created, first.status)
        val retry = client.post("/v1/accounts") { auth("new-user"); json(); setBody(request) }.body<MeDto>()
        assertEquals(first.body<MeDto>().userId, retry.userId)
        assertEquals(listOf(RoleDto.CLIENT), retry.roles)
        assertEquals(1, count("SELECT count(*) FROM identity.app_user WHERE auth_subject = 'new-user'"))
        assertEquals(2, count("SELECT count(*) FROM coaching.consent_record c JOIN identity.app_user u ON u.id = c.user_id WHERE u.auth_subject = 'new-user'"))

        val invalid = client.post("/v1/accounts") { auth("other"); json(); setBody(request.copy(name = "", locale = "de")) }
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
        assertEquals(setOf("name", "locale"), invalid.body<ApiErrorDto>().fields.toSet())

        // The demo trainer holds both roles.
        val both = client.get("/v1/me") { auth(DemoSeed.TRAINER_SUBJECT) }.body<MeDto>()
        assertNotNull(both)
        assertEquals(setOf(RoleDto.TRAINER, RoleDto.CLIENT), both.roles.toSet())
    }

    @Test
    fun `the signed-in user changes only their own name, trimmed and validated`() = api { client ->
        val request = CreateAccountRequest("8f3c0e3e-0000-7000-8000-000000000002", "Stara Nazwa", RoleDto.CLIENT, "pl", "Europe/Warsaw", "demo-2026-11", "demo-2026-11")
        client.post("/v1/accounts") { auth("rename-user"); json(); setBody(request) }

        val renamed = client.patch("/v1/me") { auth("rename-user"); json(); setBody(UpdateMeRequest(displayName = "  Nowa Nazwa  ")) }
        assertEquals(HttpStatusCode.OK, renamed.status)
        assertEquals("Nowa Nazwa", renamed.body<MeDto>().displayName)
        assertEquals("pl", renamed.body<MeDto>().locale)
        assertEquals("Nowa Nazwa", client.get("/v1/me") { auth("rename-user") }.body<MeDto>().displayName)

        val blank = client.patch("/v1/me") { auth("rename-user"); json(); setBody(UpdateMeRequest(displayName = "   ")) }
        assertEquals(HttpStatusCode.UnprocessableEntity, blank.status)
        assertEquals(listOf("displayName"), blank.body<ApiErrorDto>().fields)
        val tooLong = client.patch("/v1/me") { auth("rename-user"); json(); setBody(UpdateMeRequest(displayName = "a".repeat(81))) }
        assertEquals(HttpStatusCode.UnprocessableEntity, tooLong.status)
        assertEquals("Nowa Nazwa", client.get("/v1/me") { auth("rename-user") }.body<MeDto>().displayName)

        // Other fields stay as they were when only the name is sent, and vice versa.
        val locale = client.patch("/v1/me") { auth("rename-user"); json(); setBody(UpdateMeRequest(locale = "en")) }.body<MeDto>()
        assertEquals("Nowa Nazwa", locale.displayName)
        assertEquals("en", locale.locale)
    }

}
