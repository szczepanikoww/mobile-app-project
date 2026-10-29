package com.trainingapp.feature.workoutbuilder.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.trainingapp.domain.media.PickedVideo
import com.trainingapp.domain.media.VideoProblem

sealed interface VideoPickResult {
    data class Picked(val video: PickedVideo) : VideoPickResult

    /** The video breaks a limit; the copy has already been deleted. */
    data class Rejected(val problem: VideoProblem) : VideoPickResult
    data object Unreadable : VideoPickResult
    data object Cancelled : VideoPickResult
}

/** Opens the platform camera or gallery. The result is copied into app storage before it is reported. */
interface VideoPickerLauncher {
    val canRecord: Boolean

    fun record()

    fun pickFromGallery()
}

@Composable
expect fun rememberVideoPicker(onResult: (VideoPickResult) -> Unit): VideoPickerLauncher

/** Plays a READY video. Never call it for media that is still uploading or processing. */
@Composable
expect fun DemoVideoPlayer(url: String, modifier: Modifier)

/** Decodes a thumbnail stored on this device, or `null`. */
expect suspend fun loadLocalImage(path: String): ImageBitmap?
