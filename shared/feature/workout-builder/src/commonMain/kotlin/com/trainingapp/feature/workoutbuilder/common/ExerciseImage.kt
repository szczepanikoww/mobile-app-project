package com.trainingapp.feature.workoutbuilder.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.theme.AppShapes
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.domain.exercise.Exercise
import com.trainingapp.feature.workoutbuilder.resources.Res
import org.jetbrains.compose.resources.decodeToImageBitmap

/** A square exercise photo of [size]. */
@Composable
fun ExerciseImage(exercise: Exercise?, size: Dp, shape: Shape = AppShapes.thumbnail, modifier: Modifier = Modifier) =
    ExerciseImage(exercise, modifier.size(size), shape)

/**
 * The exercise's start-position photo, bundled by `tools/exercise-seed --images`. [modifier] sets the size.
 * Exercises without a bundled photo (own exercises, until media upload exists) show a neutral placeholder.
 * Decorative for screen readers.
 */
@Composable
fun ExerciseImage(exercise: Exercise?, modifier: Modifier, shape: Shape = AppShapes.thumbnail) {
    val sourceId = exercise?.takeIf { it.images.isNotEmpty() }?.sourceId
    val bitmap by produceState<ImageBitmap?>(null, sourceId) {
        value = sourceId?.let { id ->
            runCatching { Res.readBytes("files/exercise_images/$id.jpg").decodeToImageBitmap() }.getOrNull()
        }
    }
    val image = bitmap
    if (image != null) {
        Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(shape))
    } else {
        Box(modifier.clip(shape).background(AppTheme.colors.subtleSurface), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.FitnessCenter, contentDescription = null, tint = AppTheme.colors.textSecondary, modifier = Modifier.size(24.dp))
        }
    }
}
