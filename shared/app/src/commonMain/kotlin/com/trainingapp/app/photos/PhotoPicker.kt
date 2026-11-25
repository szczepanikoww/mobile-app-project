package com.trainingapp.app.photos

import androidx.compose.runtime.Composable

/** The longest side of an uploaded profile photo, in pixels. Keeps uploads far below the server's 2 MB limit. */
internal const val ProfilePhotoMaxSide = 512

/**
 * Remembers a photo picker. Calling the returned function opens the platform picker; [onPicked] gets the chosen
 * photo as a JPEG scaled to at most [ProfilePhotoMaxSide] px, or null when nothing usable was picked.
 */
@Composable
internal expect fun rememberPhotoPicker(onPicked: (ByteArray?) -> Unit): () -> Unit
