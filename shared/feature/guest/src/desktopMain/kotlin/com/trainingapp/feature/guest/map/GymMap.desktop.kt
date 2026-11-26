package com.trainingapp.feature.guest.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Desktop has no map engine: the pins sit on a plain plan scaled to their bounding box. */
@Composable
actual fun GymMap(pins: List<MapPin>, onOpen: (String) -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).testTag("gym-map")) {
        if (pins.isEmpty()) return@BoxWithConstraints
        val minLat = pins.minOf { it.latitude }
        val maxLat = pins.maxOf { it.latitude }
        val minLng = pins.minOf { it.longitude }
        val maxLng = pins.maxOf { it.longitude }
        val pad = 48.dp
        val w = maxWidth - pad * 2
        val h = maxHeight - pad * 2
        pins.forEach { pin ->
            val x = if (maxLng == minLng) 0.5f else ((pin.longitude - minLng) / (maxLng - minLng)).toFloat()
            val y = if (maxLat == minLat) 0.5f else (1 - (pin.latitude - minLat) / (maxLat - minLat)).toFloat()
            Column(
                Modifier.offset(pad + w * x - 40.dp, pad + h * y - 24.dp).clickable { onOpen(pin.id) }.testTag("gym-pin-${pin.id}"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.LocationOn, contentDescription = pin.title, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Text(pin.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
