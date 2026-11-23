package com.trainingapp.feature.guest.map

import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@Composable
actual fun GymMap(pins: List<MapPin>, onOpen: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val currentOnOpen by rememberUpdatedState(onOpen)
    // The view is fitted to the pins only when the set of pins changes, never on an ordinary recomposition.
    val fitKey = remember(pins) { pins.map { it.id } }
    val fitted = remember { arrayOf<List<String>?>(null) }
    val map = remember {
        // OpenStreetMap asks clients to identify themselves; tiles are cached in the app's own cache folder.
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = context.cacheDir
            osmdroidTileCache = context.cacheDir.resolve("osm-tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            minZoomLevel = 4.0
            // Keep the gesture for the map while a finger is on it.
            setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    AndroidView(
        factory = { map },
        modifier = modifier,
        update = { view ->
            view.overlays.clear()
            pins.forEach { pin ->
                view.overlays += Marker(view).apply {
                    position = GeoPoint(pin.latitude, pin.longitude)
                    title = pin.title
                    snippet = pin.subtitle
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    // First tap shows the name; tapping the marker again opens the gym.
                    setOnMarkerClickListener { marker, _ ->
                        if (marker.isInfoWindowShown) currentOnOpen(pin.id) else marker.showInfoWindow()
                        true
                    }
                }
            }
            if (fitted[0] != fitKey) {
                fitted[0] = fitKey
                view.post {
                when {
                    pins.isEmpty() -> Unit
                    pins.size == 1 -> view.controller.run { setZoom(14.0); setCenter(GeoPoint(pins[0].latitude, pins[0].longitude)) }
                    else -> view.zoomToBoundingBox(
                        BoundingBox.fromGeoPoints(pins.map { GeoPoint(it.latitude, it.longitude) }).increaseByScale(1.3f),
                        false,
                    )
                }
                view.invalidate()
                }
            }
            view.invalidate()
        },
    )
}
