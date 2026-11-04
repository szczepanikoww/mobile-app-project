package com.trainingapp.designsystem.component

import androidx.compose.runtime.Composable

/**
 * The initials avatar of the trainer calendar/clients branch (soft accent circle, 48 dp, 64 dp when [large] or at
 * large font scale). Decorative: the name shown next to it is what screen readers announce.
 */
@Composable
fun InitialsAvatar(initials: String, large: Boolean = false) = pl.trainer.ui.Avatar(initials, large)
