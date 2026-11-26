package com.trainingapp.feature.guest.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A gym with a position, as drawn on the map. */
data class MapPin(val id: String, val title: String, val subtitle: String, val latitude: Double, val longitude: Double)

/**
 * Browsable map of the gyms (pan, zoom, tap a pin). Android draws OpenStreetMap tiles; the desktop build, which has no
 * map engine, draws the pins on a plain plan. [onOpen] is called with the pin id when the user chooses a gym.
 */
@Composable
expect fun GymMap(pins: List<MapPin>, onOpen: (String) -> Unit, modifier: Modifier = Modifier)
