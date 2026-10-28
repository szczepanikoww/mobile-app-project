package com.trainingapp.feature.workoutbuilder.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.domain.media.PickedVideo
import com.trainingapp.domain.media.VideoValidator
import com.trainingapp.feature.workoutbuilder.resources.Res
import com.trainingapp.feature.workoutbuilder.resources.video_desktop_playback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Image
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.UUID

/** The desktop preview has no camera; it picks a file and cannot read its duration (the server checks it). */
@Composable
actual fun rememberVideoPicker(onResult: (VideoPickResult) -> Unit): VideoPickerLauncher {
    val currentOnResult by rememberUpdatedState(onResult)
    return remember { desktopPicker { currentOnResult(it) } }
}

private fun desktopPicker(onResult: (VideoPickResult) -> Unit): VideoPickerLauncher =
    object : VideoPickerLauncher {
        override val canRecord: Boolean = false

        override fun record() = Unit

        override fun pickFromGallery() {
            val dialog = FileDialog(null as Frame?, "", FileDialog.LOAD).apply {
                setFilenameFilter { _, name -> name.lowercase().let { it.endsWith(".mp4") || it.endsWith(".mov") || it.endsWith(".m4v") } }
                isVisible = true
            }
            val name = dialog.file ?: return onResult(VideoPickResult.Cancelled)
            val source = File(dialog.directory, name)
            val dir = File(System.getProperty("user.home"), ".trainingapp/videos").apply { mkdirs() }
            val target = File(dir, "${UUID.randomUUID()}.${source.extension.ifEmpty { "mp4" }}")
            val result = runCatching {
                source.copyTo(target)
                val video = PickedVideo(target.absolutePath, target.length(), durationMs = null, thumbnailPath = null)
                VideoValidator.validate(video)?.let { problem ->
                    target.delete()
                    VideoPickResult.Rejected(problem)
                } ?: VideoPickResult.Picked(video)
            }.getOrDefault(VideoPickResult.Unreadable)
            onResult(result)
        }
    }

@Composable
actual fun DemoVideoPlayer(url: String, modifier: Modifier) {
    Box(modifier.background(AppTheme.colors.text).padding(Spacing.l), contentAlignment = Alignment.Center) {
        Text(stringResource(Res.string.video_desktop_playback), style = AppTheme.type.body, color = AppTheme.colors.surface)
    }
}

actual suspend fun loadLocalImage(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching { Image.makeFromEncoded(File(path).readBytes()).toComposeImageBitmap() }.getOrNull()
}
