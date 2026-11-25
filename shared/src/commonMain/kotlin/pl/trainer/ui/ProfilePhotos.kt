package pl.trainer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow

/*
 * Profile photos for every shell (trainer and client). The app provides a [ProfilePhotoSource]; without one
 * (previews, tests, demo mode) avatars show initials. Lives in :shared so the trainer views can use it too.
 */

/** The key of the signed-in user's own photo. */
const val MyPhoto = "me"

/** The progress of a profile photo upload, shown as a snackbar by the shells. */
enum class PhotoUploadState { Idle, Uploading, Saved, Failed }

/** Loads profile photos. [revision] changes when a photo changes (an upload), so shown avatars reload. */
interface ProfilePhotoSource {
    val revision: StateFlow<Int>

    /** The photo of a user id or [MyPhoto]; null when there is none or it cannot be loaded now. */
    suspend fun load(key: String): ImageBitmap?
}

val LocalProfilePhotos = staticCompositionLocalOf<ProfilePhotoSource?> { null }

/** The app's "change profile photo": opens the picker and uploads; [state] is the latest upload's progress. */
data class ProfilePhotoChange(val pick: () -> Unit, val state: PhotoUploadState)

/** Null where the photo cannot be changed (no server or no picker on this platform). */
val LocalProfilePhotoChange = compositionLocalOf<ProfilePhotoChange?> { null }

/** The photo for [key] (a user id or [MyPhoto]), or null while loading, without a photo or without a source. */
@Composable
fun rememberProfilePhoto(key: String?): ImageBitmap? {
    val source = LocalProfilePhotos.current ?: return null
    if (key == null) return null
    val revision by source.revision.collectAsState()
    val photo by produceState<ImageBitmap?>(null, source, key, revision) { value = source.load(key) }
    return photo
}
