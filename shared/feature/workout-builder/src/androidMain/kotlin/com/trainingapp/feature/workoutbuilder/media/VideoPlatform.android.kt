package com.trainingapp.feature.workoutbuilder.media

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.trainingapp.domain.media.PickedVideo
import com.trainingapp.domain.media.VideoValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Videos live in app-private storage, so a queued upload survives the gallery file being deleted. */
private fun videoDir(context: Context) = File(context.filesDir, "videos").apply { mkdirs() }

@Composable
actual fun rememberVideoPicker(onResult: (VideoPickResult) -> Unit): VideoPickerLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    // Survives the activity being recreated while the camera app is open.
    var captureTarget by rememberSaveable { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            currentOnResult(VideoPickResult.Cancelled)
        } else {
            scope.launch { currentOnResult(withContext(Dispatchers.IO) { importFromUri(context, uri) }) }
        }
    }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { saved ->
        val path = captureTarget
        captureTarget = null
        if (!saved || path == null) {
            path?.let { File(it).delete() }
            currentOnResult(VideoPickResult.Cancelled)
        } else {
            scope.launch { currentOnResult(withContext(Dispatchers.IO) { inspect(File(path)) }) }
        }
    }
    val canRecord = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }

    return remember(pick, capture, canRecord) {
        object : VideoPickerLauncher {
            override val canRecord: Boolean = canRecord

            override fun record() {
                val file = File(videoDir(context), "${UUID.randomUUID()}.mp4")
                captureTarget = file.absolutePath
                capture.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
            }

            override fun pickFromGallery() {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }
        }
    }
}

private fun importFromUri(context: Context, uri: Uri): VideoPickResult = runCatching {
    val type = context.contentResolver.getType(uri) ?: "video/mp4"
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "mp4"
    val target = File(videoDir(context), "${UUID.randomUUID()}.$extension")
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input)
        target.outputStream().use { input.copyTo(it) }
    }
    inspect(target, type)
}.getOrDefault(VideoPickResult.Unreadable)

/** Reads size, duration and a first-frame thumbnail; deletes the copy if it breaks a limit. */
private fun inspect(file: File, contentType: String = "video/mp4"): VideoPickResult {
    if (!file.exists()) return VideoPickResult.Unreadable
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        val video = PickedVideo(file.absolutePath, file.length(), duration, thumbnailPath = null, contentType = contentType)
        VideoValidator.validate(video)?.let { problem ->
            file.delete()
            return VideoPickResult.Rejected(problem)
        }
        val thumbnail = retriever.getFrameAtTime(0)?.let { frame ->
            File(file.parentFile, "${file.nameWithoutExtension}.jpg").also { out ->
                out.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            }
        }
        VideoPickResult.Picked(video.copy(thumbnailPath = thumbnail?.absolutePath))
    } catch (e: RuntimeException) {
        file.delete()
        VideoPickResult.Unreadable
    } finally {
        retriever.release()
    }
}

@Composable
actual fun DemoVideoPlayer(url: String, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = modifier)
}

actual suspend fun loadLocalImage(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
    BitmapFactory.decodeFile(path)?.asImageBitmap()
}
