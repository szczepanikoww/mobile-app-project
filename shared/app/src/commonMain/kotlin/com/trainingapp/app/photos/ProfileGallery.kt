package com.trainingapp.app.photos

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.trainingapp.app.resources.Res
import com.trainingapp.app.resources.gallery_add
import com.trainingapp.app.resources.gallery_empty
import com.trainingapp.app.resources.gallery_failed
import com.trainingapp.app.resources.gallery_full
import com.trainingapp.app.resources.gallery_remove
import com.trainingapp.app.resources.gallery_title
import com.trainingapp.data.remote.ApiClient
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource
import pl.trainer.ui.AppColors
import pl.trainer.ui.Layout

/** At most this many photos per user (the server checks it too). */
const val GalleryLimit = 9

/** "Zdjęcia": photos about the user beyond the profile photo (`/v1/users/{id}/gallery`, `/v1/me/gallery`). */
class RemoteProfileGallery(private val api: ApiClient) {
    private val images = mutableMapOf<String, ImageBitmap>()

    private fun base(userId: String) = if (userId == pl.trainer.ui.MyPhoto) "v1/me" else "v1/users/$userId"

    /** The ids of [userId]'s photos ([pl.trainer.ui.MyPhoto] for the signed-in user), oldest first; null on failure. */
    suspend fun list(userId: String): List<String>? = runCatching {
        val response = api.raw(HttpMethod.Get, "${base(userId)}/gallery")
        if (response.status.isSuccess()) response.body<List<String>>() else null
    }.getOrElse { if (it is CancellationException) throw it else null }

    suspend fun image(userId: String, photoId: String): ImageBitmap? {
        images[photoId]?.let { return it }
        return runCatching {
            val response = api.raw(HttpMethod.Get, "${base(userId)}/gallery/$photoId")
            if (response.status.isSuccess()) response.readRawBytes().decodeToImageBitmap().also { images[photoId] = it } else null
        }.getOrElse { if (it is CancellationException) throw it else null }
    }

    /** Adds [jpeg] to the signed-in user's photos. Returns true when the server stored it. */
    suspend fun add(jpeg: ByteArray): Boolean = runCatching {
        api.raw(HttpMethod.Post, "v1/me/gallery") { contentType(ContentType.Image.JPEG); setBody(jpeg) }.status.isSuccess()
    }.getOrElse { if (it is CancellationException) throw it else false }

    suspend fun remove(photoId: String): Boolean = runCatching {
        api.raw(HttpMethod.Delete, "v1/me/gallery/$photoId").status.isSuccess().also { if (it) images.remove(photoId) }
    }.getOrElse { if (it is CancellationException) throw it else false }
}

/**
 * The signed-in trainer's own photos in the profile: thumbnails, "Add photo" (system picker, scaled like the profile
 * photo) and remove.
 */
@Composable
fun ProfileGallerySection(gallery: RemoteProfileGallery, userId: String = pl.trainer.ui.MyPhoto) {
    val scope = rememberCoroutineScope()
    var ids by remember { mutableStateOf<List<String>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(userId, reload) {
        val loaded = gallery.list(userId)
        if (loaded != null) ids = loaded else failed = true
    }
    val pick = rememberPhotoPicker { jpeg ->
        if (jpeg == null) { failed = true; return@rememberPhotoPicker }
        scope.launch {
            busy = true
            failed = !gallery.add(jpeg)
            busy = false
            reload++
        }
    }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
        Text(stringResource(Res.string.gallery_title), style = MaterialTheme.typography.titleLarge)
        val current = ids.orEmpty()
        if (ids != null && current.isEmpty()) {
            Text(stringResource(Res.string.gallery_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Layout.small), verticalArrangement = Arrangement.spacedBy(Layout.small)) {
            current.forEach { id ->
                GalleryThumbnail(gallery, userId, id, onRemove = {
                    scope.launch {
                        busy = true
                        failed = !gallery.remove(id)
                        busy = false
                        reload++
                    }
                })
            }
        }
        if (current.size < GalleryLimit) {
            TextButton(onClick = pick, enabled = !busy) {
                Icon(Icons.Outlined.AddAPhoto, null, Modifier.size(18.dp))
                Text(stringResource(Res.string.gallery_add), Modifier.padding(start = Layout.small))
            }
        } else {
            Text(stringResource(Res.string.gallery_full, GalleryLimit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (failed) Text(stringResource(Res.string.gallery_failed), style = MaterialTheme.typography.bodySmall, color = AppColors.errorText)
    }
}

@Composable
private fun GalleryThumbnail(gallery: RemoteProfileGallery, userId: String, photoId: String, onRemove: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, photoId) { value = gallery.image(userId, photoId) }
    Box(Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)).background(AppColors.completedSurface)) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(96.dp)) }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(32.dp).background(AppColors.surface.copy(alpha = 0.9f), CircleShape),
        ) {
            Icon(Icons.Outlined.Close, stringResource(Res.string.gallery_remove), Modifier.size(18.dp))
        }
    }
}
