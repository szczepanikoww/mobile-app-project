package com.trainingapp.feature.guest

import com.trainingapp.domain.media.MediaState
import com.trainingapp.feature.guest.data.DemoCatalog
import com.trainingapp.feature.guest.model.PublicMedia
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicDataTest {
    @Test
    fun onlyReadyMediaWithAUrlIsPlayable() {
        MediaState.entries.forEach { state ->
            assertFalse(PublicMedia("m", "t", null, state, playbackUrl = null).playable)
            assertTrue(PublicMedia("m", "t", null, state, playbackUrl = "https://example.com/v.mp4").playable == (state == MediaState.READY))
        }
    }

    @Test
    fun fixturesUseOneConsistentPricePerTrainer() {
        DemoCatalog.trainers.forEach { profile ->
            val listed = profile.summary.startingPrice
            assertTrue(listed == profile.offers.firstOrNull(), "${profile.summary.id} lists the same starting price as its profile")
        }
        DemoCatalog.gyms.forEach { gym ->
            gym.trainers.forEach { trainer ->
                assertTrue(trainer == DemoCatalog.trainers.first { it.summary.id == trainer.id }.summary)
            }
        }
    }

    @Test
    fun fixturesContainOnlySyntheticPublicData() {
        val text = DemoCatalog.trainers.joinToString(" ") { it.toString() } + DemoCatalog.gyms.joinToString(" ") { it.toString() }
        // Real gym brands from the mockups must not appear as partners.
        listOf("CityFit", "McFIT", "Zdrofit", "Holmes").forEach { brand -> assertFalse(brand in text, brand) }
        // Public models have no private fields; their string form never mentions private records.
        listOf("injur", "kontuzj", "notatk", "health", "zdrowi", "@").forEach { word -> assertFalse(word in text.lowercase(), word) }
    }
}
