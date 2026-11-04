package com.trainingapp.feature.guest

import com.trainingapp.feature.guest.data.CatalogSearch
import com.trainingapp.feature.guest.data.DemoCatalog
import com.trainingapp.feature.guest.model.Availability
import com.trainingapp.feature.guest.model.Goal
import com.trainingapp.feature.guest.model.GymQuery
import com.trainingapp.feature.guest.model.PriceRange
import com.trainingapp.feature.guest.model.TrainerQuery
import com.trainingapp.feature.guest.model.TrainerSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogSearchTest {
    private val all = DemoCatalog.trainers.map { it.summary }

    private fun search(query: TrainerQuery) = CatalogSearch.trainers(all, query)

    @Test
    fun emptyQueryReturnsEveryTrainer() {
        assertEquals(all.size, search(TrainerQuery()).size)
        assertTrue(all.size >= 12)
        assertTrue(DemoCatalog.gyms.size >= 6)
    }

    @Test
    fun textSearchIgnoresCaseAndPolishDiacritics() {
        assertEquals(listOf("tr-lukasz"), search(TrainerQuery(text = "LUKASZ kozlowski")).map { it.id })
        // Gym name and district are searchable too.
        assertTrue(search(TrainerQuery(text = "zoliborz")).all { "Żoliborz" in it.districts })
        assertTrue(search(TrainerQuery(text = "trojboj")).isNotEmpty())
    }

    @Test
    fun goalLabelsAreSearchable() {
        val found = CatalogSearch.trainers(all, TrainerQuery(text = "mobilnosc"), goalLabels = { if (it == Goal.Mobility) "Mobilność" else it.name })
        assertTrue(found.isNotEmpty())
        assertTrue(found.all { Goal.Mobility in it.goals || it.tags.any { tag -> tag.startsWith("Mobil") } })
    }

    @Test
    fun eachFilterNarrowsTheResults() {
        assertTrue(search(TrainerQuery(goals = setOf(Goal.Competition))).all { Goal.Competition in it.goals })
        assertTrue(search(TrainerQuery(gymId = "gym-wola")).all { "gym-wola" in it.gymIds })
        assertTrue(search(TrainerQuery(districts = setOf("Mokotów"))).all { "Mokotów" in it.districts })
        assertTrue(search(TrainerQuery(availability = setOf(Availability.Weekend))).all { Availability.Weekend in it.availability })
        val cheap = search(TrainerQuery(priceRange = PriceRange.UpTo100))
        assertTrue(cheap.isNotEmpty())
        assertTrue(cheap.all { it.startingPrice!!.price.minor <= 100_00 })
    }

    @Test
    fun priceFilterExcludesTrainersWithoutAPublicPrice() {
        val withoutPrice = all.filter { it.startingPrice == null }.map { it.id }
        assertTrue(withoutPrice.isNotEmpty())
        PriceRange.entries.forEach { range ->
            assertTrue(search(TrainerQuery(priceRange = range)).none { it.id in withoutPrice })
        }
    }

    @Test
    fun filtersCombine() {
        val result = search(TrainerQuery(goals = setOf(Goal.Strength), districts = setOf("Śródmieście"), availability = setOf(Availability.Evening)))
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { Goal.Strength in it.goals && "Śródmieście" in it.districts && Availability.Evening in it.availability })
    }

    @Test
    fun sortsByPriceBothWaysWithUnpricedLast() {
        val ascending = search(TrainerQuery(sort = TrainerSort.PriceAscending))
        val prices = ascending.mapNotNull { it.startingPrice?.price?.minor }
        assertEquals(prices.sorted(), prices)
        assertEquals(null, ascending.last().startingPrice)
        val descending = search(TrainerQuery(sort = TrainerSort.PriceDescending))
        assertEquals(prices.sortedDescending(), descending.mapNotNull { it.startingPrice?.price?.minor })
        assertEquals(null, descending.last().startingPrice)
    }

    @Test
    fun ratingSortPutsUnratedLast() {
        val sorted = search(TrainerQuery(sort = TrainerSort.Rating))
        val ratings = sorted.map { it.rating?.average ?: -1.0 }
        assertEquals(ratings.sortedDescending(), ratings)
        assertEquals(null, sorted.last().rating)
    }

    @Test
    fun clearingKeepsTheSortOrder() {
        val query = TrainerQuery(text = "x", goals = setOf(Goal.Mobility), sort = TrainerSort.Rating)
        assertTrue(query.hasFilters)
        val cleared = query.cleared()
        assertFalse(cleared.hasFilters)
        assertEquals(TrainerSort.Rating, cleared.sort)
    }

    @Test
    fun gymsFilterByTextAndDistrictNearestFirst() {
        val gyms = DemoCatalog.gyms.map { it.summary }
        val all = CatalogSearch.gyms(gyms, GymQuery())
        assertEquals(all.map { it.distanceMeters }, all.map { it.distanceMeters }.sortedBy { it })
        assertEquals(listOf("gym-zoliborz"), CatalogSearch.gyms(gyms, GymQuery(text = "puls zoliborz")).map { it.id })
        assertEquals(listOf("gym-wola"), CatalogSearch.gyms(gyms, GymQuery(districts = setOf("Wola"))).map { it.id })
    }
}
