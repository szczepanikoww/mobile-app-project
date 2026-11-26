package com.trainingapp.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.feature.guest.GuestCatalogUiState
import com.trainingapp.feature.guest.Load
import com.trainingapp.feature.guest.gyms.GymsContent
import com.trainingapp.feature.guest.model.GymSummary
import kotlin.test.Test
import kotlin.test.assertEquals

/** The gyms tab always shows the map above the list; choosing a pin opens that gym, and gyms without a position stay off the map but in the list. */
@OptIn(ExperimentalTestApi::class)
class GymsMapTest {
    private val gyms = listOf(
        GymSummary("g1", "Studio Centrum", "Śródmieście", "06:00–22:00", null, null, 52.2297, 21.0122),
        GymSummary("g2", "Forma Wola", "Wola", "Całą dobę", null, null, 52.2370, 20.9650),
        GymSummary("g3", "Bez pozycji", "Ursynów", "07:00–22:00", null, null),
    )

    @Test
    fun mapShowsPinsForPositionedGymsAndOpensOne() = runDesktopComposeUiTest(width = 400, height = 800) {
        val opened = mutableListOf<String>()
        setContent {
            AppTheme {
                GymsContent(
                    state = GuestCatalogUiState(gyms = Load.Ready(gyms)),
                    onQueryChange = {},
                    onOpenGym = { opened += it },
                    onRetry = {},
                )
            }
        }
        waitForIdle()
        onNodeWithTag("gym-map").assertExists()
        onNodeWithTag("gym-pin-g1").assertExists()
        onNodeWithTag("gym-pin-g2").assertExists()
        onNodeWithTag("gym-pin-g3").assertDoesNotExist()
        onNodeWithTag("guest-gyms-list").performScrollToNode(hasText("Bez pozycji"))
        onNodeWithText("Bez pozycji").assertExists()
        onNodeWithTag("gym-pin-g2").performClick()
        assertEquals(listOf("g2"), opened)
    }
}
