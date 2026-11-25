package com.trainingapp.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme

/* Exercise photos for any screen, provided by the app so screens stay previewable. Profile photos: pl.trainer.ui.ProfilePhotos. */

/** Loads exercise photos by exercise id (an id from a plan) or by the library's source id. */
interface ExercisePhotoSource {
    suspend fun byExerciseId(exerciseId: String): ImageBitmap?
    suspend fun bySourceId(sourceId: String): ImageBitmap?
}

/** Null in previews and tests without photos: banners show a neutral placeholder. */
val LocalExercisePhotos = staticCompositionLocalOf<ExercisePhotoSource?> { null }

/** Which exercise photo to show: from a plan's exercise id, or a library photo by source id. */
sealed interface ExercisePhoto {
    data class OfExercise(val exerciseId: String) : ExercisePhoto
    data class OfLibrary(val sourceId: String) : ExercisePhoto
}

@Composable
fun rememberExercisePhoto(photo: ExercisePhoto?): ImageBitmap? {
    val source = LocalExercisePhotos.current ?: return null
    val image by produceState<ImageBitmap?>(null, source, photo) {
        value = when (photo) {
            is ExercisePhoto.OfExercise -> source.byExerciseId(photo.exerciseId)
            is ExercisePhoto.OfLibrary -> source.bySourceId(photo.sourceId)
            null -> null
        }
    }
    return image
}

/**
 * An exercise photo filling [modifier] (cropped), with a neutral placeholder until it loads or when there is none.
 * [scrim] darkens it from the bottom so white text on top stays readable. Decorative.
 */
@Composable
fun ExercisePhotoBox(
    photo: ExercisePhoto?,
    modifier: Modifier = Modifier,
    shape: Shape = AppShapes.thumbnail,
    scrim: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val image = rememberExercisePhoto(photo)
    Box(modifier.clip(shape).background(AppTheme.colors.subtleSurface).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else if (!scrim) {
            Icon(Icons.Outlined.FitnessCenter, contentDescription = null, tint = AppTheme.colors.textSecondary, modifier = Modifier.size(24.dp))
        }
        if (scrim) {
            val dark = AppTheme.colors.brandStrong
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(listOf(dark.copy(alpha = if (image != null) 0.35f else 1f), dark.copy(alpha = if (image != null) 0.92f else 1f))),
                ),
            )
        }
        content()
    }
}
