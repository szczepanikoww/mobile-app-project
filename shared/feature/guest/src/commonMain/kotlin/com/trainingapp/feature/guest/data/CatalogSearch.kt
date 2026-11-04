package com.trainingapp.feature.guest.data

import com.trainingapp.contract.text.SearchText
import com.trainingapp.feature.guest.model.GymQuery
import com.trainingapp.feature.guest.model.GymSummary
import com.trainingapp.feature.guest.model.TrainerQuery
import com.trainingapp.feature.guest.model.TrainerSort
import com.trainingapp.feature.guest.model.TrainerSummary

/**
 * Filtering and sorting of the public catalogue. A pure function, so a server can take it over later without
 * changing the screens. Text search is case- and Polish-diacritic-insensitive ("lodz" finds "Łódź").
 */
object CatalogSearch {

    /** [goalLabels] lets the text search also match a goal's visible name ("siła", "redukcja"). */
    fun trainers(
        all: List<TrainerSummary>,
        query: TrainerQuery,
        goalLabels: (com.trainingapp.feature.guest.model.Goal) -> String = { it.name },
    ): List<TrainerSummary> {
        val tokens = SearchText.tokens(query.text)
        val filtered = all.filter { trainer ->
            val haystack = SearchText.normalize(
                buildString {
                    append(trainer.name).append(' ').append(trainer.headline).append(' ')
                    trainer.mainGym?.let { append(it.name).append(' ') }
                    trainer.tags.forEach { append(it).append(' ') }
                    trainer.districts.forEach { append(it).append(' ') }
                    trainer.goals.forEach { append(goalLabels(it)).append(' ') }
                },
            )
            tokens.all { it in haystack } &&
                (query.goals.isEmpty() || trainer.goals.any { it in query.goals }) &&
                (query.gymId == null || query.gymId in trainer.gymIds) &&
                (query.districts.isEmpty() || trainer.districts.any { it in query.districts }) &&
                (query.availability.isEmpty() || trainer.availability.any { it in query.availability }) &&
                (query.priceRange == null || trainer.startingPrice?.price?.minor?.let { price ->
                    (query.priceRange.minMinor == null || price >= query.priceRange.minMinor) &&
                        (query.priceRange.maxMinor == null || price <= query.priceRange.maxMinor)
                } == true)
        }
        val byName = compareBy<TrainerSummary> { SearchText.normalize(it.name) }
        return when (query.sort) {
            // Trainers without a public price go last in both price orders.
            TrainerSort.PriceAscending -> filtered.sortedWith(
                compareBy<TrainerSummary> { it.startingPrice == null }.thenBy { it.startingPrice?.price?.minor }.then(byName),
            )
            TrainerSort.PriceDescending -> filtered.sortedWith(
                compareBy<TrainerSummary> { it.startingPrice == null }.thenByDescending { it.startingPrice?.price?.minor }.then(byName),
            )
            TrainerSort.Rating -> filtered.sortedWith(
                compareByDescending<TrainerSummary> { it.rating?.average ?: -1.0 }
                    .thenByDescending { it.rating?.count ?: 0 }
                    .then(byName),
            )
        }
    }

    fun gyms(all: List<GymSummary>, query: GymQuery): List<GymSummary> {
        val tokens = SearchText.tokens(query.text)
        return all
            .filter { gym ->
                val haystack = SearchText.normalize("${gym.name} ${gym.district}")
                tokens.all { it in haystack } && (query.districts.isEmpty() || gym.district in query.districts)
            }
            // Nearest first when distances are known; otherwise alphabetical.
            .sortedWith(compareBy<GymSummary> { it.distanceMeters ?: Int.MAX_VALUE }.thenBy { SearchText.normalize(it.name) })
    }
}
