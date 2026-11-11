package com.trainingapp.feature.guest

import com.trainingapp.contract.api.ApiErrorDto
import com.trainingapp.contract.api.MoneyDto
import com.trainingapp.contract.catalog.AvailabilityBucketDto
import com.trainingapp.contract.catalog.GoalDto
import com.trainingapp.contract.catalog.GymRefDto
import com.trainingapp.contract.catalog.GymSummaryDto
import com.trainingapp.contract.catalog.OfferDto
import com.trainingapp.contract.catalog.PolicyTierDto
import com.trainingapp.contract.catalog.PublicPolicyDto
import com.trainingapp.contract.catalog.TrainerGymDto
import com.trainingapp.contract.catalog.TrainerProfileDto
import com.trainingapp.contract.catalog.TrainerSummaryDto
import com.trainingapp.data.remote.ApiClient
import com.trainingapp.feature.guest.data.RemoteGuestCatalogRepository
import com.trainingapp.feature.guest.model.Availability
import com.trainingapp.feature.guest.model.CatalogOfflineException
import com.trainingapp.feature.guest.model.Goal
import com.trainingapp.feature.guest.model.PriceRange
import com.trainingapp.feature.guest.model.TrainerQuery
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The app decodes exactly what the server serialises: both sides use the `:contract` DTOs (plan §13 contract tests).
 * The responses below are encoded with those DTOs, not hand-written JSON.
 */
class RemoteGuestCatalogRepositoryTest {
    private val json = Json { encodeDefaults = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val summary = TrainerSummaryDto(
        id = "t1", name = "Anna Testowa", initials = "AT", headline = "Trenerka", mainGym = GymRefDto("g1", "Sala Testowa"),
        tags = listOf("Mobilność"), goals = listOf(GoalDto.MOBILITY), districts = listOf("Testowo"), gymIds = listOf("g1"),
        availability = listOf(AvailabilityBucketDto.WEEKEND),
        startingPrice = OfferDto("o1", "Trening personalny", price = MoneyDto(14000, "PLN"), durationMinutes = 60, sessionCount = 1),
    )

    @Test
    fun `trainers sends the query as server parameters and maps the summaries`() = runTest {
        var url = ""
        val engine = MockEngine { request ->
            url = request.url.toString()
            respond(json.encodeToString(ListSerializer(TrainerSummaryDto.serializer()), listOf(summary)), HttpStatusCode.OK, jsonHeaders)
        }
        val repo = RemoteGuestCatalogRepository(ApiClient("http://server", engine))
        val result = repo.trainers(TrainerQuery(text = "anna", goals = setOf(Goal.Mobility), priceRange = PriceRange.From100To150, availability = setOf(Availability.Weekend)))

        val trainer = result.single()
        assertEquals(setOf(Goal.Mobility), trainer.goals)
        assertEquals(setOf(Availability.Weekend), trainer.availability)
        assertEquals(14000, trainer.startingPrice!!.price.minor)
        for (part in listOf("/v1/public/trainers", "text=anna", "goal=MOBILITY", "price=FROM_100_TO_150", "availability=WEEKEND", "sort=PRICE_ASCENDING")) {
            assertEquals(true, url.contains(part), "$part in $url")
        }
    }

    @Test
    fun `a trainer profile maps its policy, and a non-public one is null`() = runTest {
        val profile = TrainerProfileDto(
            summary = summary, bio = "Bio", specialisations = listOf("Mobilność"),
            gyms = listOf(TrainerGymDto(GymSummaryDto("g1", "Sala Testowa", "Testowo", "Pon–Nd 07:00–22:00"), main = true)),
            offers = listOf(summary.startingPrice!!),
            cancellationPolicy = PublicPolicyDto("p1", 2, listOf(PolicyTierDto(24, 50), PolicyTierDto(12, 100)), 100, 15, 1, freeCancellationHours = 24),
        )
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/t1")) respond(json.encodeToString(TrainerProfileDto.serializer(), profile), HttpStatusCode.OK, jsonHeaders)
            else respond(json.encodeToString(ApiErrorDto.serializer(), ApiErrorDto("NOT_FOUND", "trainer not found")), HttpStatusCode.NotFound, jsonHeaders)
        }
        val repo = RemoteGuestCatalogRepository(ApiClient("http://server", engine))

        val mapped = repo.trainer("t1")!!
        assertEquals(2, mapped.cancellationPolicy!!.version)
        assertEquals(24, mapped.cancellationPolicy!!.freeCancellationHours)
        assertEquals(
            listOf("Ponad 24 h przed sesją: bez opłaty", "Mniej niż 24 h przed sesją: 50% ceny sesji", "Mniej niż 12 h przed sesją: 100% ceny sesji", "Nieobecność bez odwołania: 100% ceny sesji"),
            mapped.cancellationPolicy!!.tiers.map { it.description },
        )
        assertEquals(true, mapped.gyms.single().main)
        assertNull(repo.trainer("hidden"))
    }

    @Test
    fun `no connection is the catalogue's offline error, so screens keep their last data`() = runTest {
        val engine = MockEngine { throw java.io.IOException("connection refused") }
        val repo = RemoteGuestCatalogRepository(ApiClient("http://server", engine))
        assertFailsWith<CatalogOfflineException> { repo.home() }
    }
}
