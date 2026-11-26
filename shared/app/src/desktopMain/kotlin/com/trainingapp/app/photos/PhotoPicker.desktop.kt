package com.trainingapp.app.photos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

/** The desktop preview: a file dialog for JPEG/PNG images. */
@Composable
internal actual fun rememberPhotoPicker(onPicked: (ByteArray?) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val currentOnPicked by rememberUpdatedState(onPicked)
    return {
        val dialog = FileDialog(null as Frame?, "Profile photo", FileDialog.LOAD).apply {
            setFilenameFilter { _, name -> name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") } }
            isVisible = true
        }
        val file = dialog.file?.let { File(dialog.directory, it) }
        if (file != null) scope.launch { currentOnPicked(withContext(Dispatchers.IO) { scaledJpeg(file) }) }
    }
}

private fun scaledJpeg(file: File): ByteArray? = runCatching {
    val source = ImageIO.read(file) ?: return null
    val scale = minOf(1f, ProfilePhotoMaxSide.toFloat() / max(source.width, source.height))
    val width = (source.width * scale).roundToInt().coerceAtLeast(1)
    val height = (source.height * scale).roundToInt().coerceAtLeast(1)
    val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    target.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        drawImage(source, 0, 0, width, height, null)
        dispose()
    }
    ByteArrayOutputStream().use { out ->
        ImageIO.write(target, "jpg", out)
        out.toByteArray()
    }
}.getOrNull()
