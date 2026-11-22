package com.trainingapp.app.photos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.trainingapp.designsystem.component.LocalExercisePhotos
import com.trainingapp.feature.guest.model.AuthRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import pl.trainer.ui.LocalProfilePhotoChange
import pl.trainer.ui.LocalProfilePhotos
import pl.trainer.ui.PhotoUploadState
import pl.trainer.ui.ProfilePhotoChange

/**
 * Gives every screen the photos: exercise photos (always, bundled) and, with a server, profile photos and the
 * "change profile photo" action (system picker → scaled JPEG → `PUT /v1/me/photo`). Without a server (demo mode)
 * avatars keep their initials and the action is hidden.
 */
@Composable
fun ProvidePhotos(content: @Composable () -> Unit) {
    val koin = getKoin()
    val profilePhotos: RemoteProfilePhotos? = remember(koin) { koin.getOrNull() }
    val exercisePhotos: BundledExercisePhotos = koinInject()
    val auth: AuthRepository = koinInject()
    val scope = rememberCoroutineScope()
    var upload by remember { mutableStateOf(PhotoUploadState.Idle) }

    // Another account signs in: forget the previous account's photos.
    LaunchedEffect(auth, profilePhotos) {
        auth.session.map { it?.userId }.distinctUntilChanged().collect { profilePhotos?.clear() }
    }

    val pick = rememberPhotoPicker { jpeg ->
        val photos = profilePhotos ?: return@rememberPhotoPicker
        if (jpeg == null) {
            upload = PhotoUploadState.Failed
            return@rememberPhotoPicker
        }
        scope.launch {
            upload = PhotoUploadState.Uploading
            upload = if (photos.upload(jpeg)) PhotoUploadState.Saved else PhotoUploadState.Failed
        }
    }
    CompositionLocalProvider(
        LocalExercisePhotos provides exercisePhotos,
        LocalProfilePhotos provides profilePhotos,
        LocalProfilePhotoChange provides profilePhotos?.let { ProfilePhotoChange(pick, upload) },
        content = content,
    )
}
