package com.trainingapp.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.trainingapp.app.resources.Res
import com.trainingapp.app.resources.client_preview_chat
import com.trainingapp.app.resources.client_preview_chat_hint
import com.trainingapp.app.resources.client_preview_intro
import com.trainingapp.app.resources.client_preview_progress
import com.trainingapp.app.resources.client_preview_progress_hint
import com.trainingapp.app.resources.client_preview_title
import com.trainingapp.app.resources.client_preview_workout
import com.trainingapp.app.resources.client_preview_workout_hint
import com.trainingapp.designsystem.component.ActionRow
import com.trainingapp.designsystem.component.DetailHeader
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/**
 * Temporary entry to the client views (A1, A3, A6) while the client shell does not exist. It is not a client home:
 * Person B's shell replaces it and hosts these views as tabs and pushed screens.
 */
@Composable
fun ClientPreviewScreen(
    onBack: () -> Unit,
    onLiveWorkout: () -> Unit,
    onProgress: () -> Unit,
    onChat: (() -> Unit)?,
) {
    Scaffold(
        containerColor = AppTheme.colors.background,
        topBar = { DetailHeader(stringResource(Res.string.client_preview_title), onBack = onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(stringResource(Res.string.client_preview_intro), style = AppTheme.type.compactBody, color = AppTheme.colors.textSecondary)
            ActionRow(Icons.Outlined.FitnessCenter, stringResource(Res.string.client_preview_workout), onLiveWorkout, subtitle = stringResource(Res.string.client_preview_workout_hint))
            ActionRow(Icons.AutoMirrored.Outlined.ShowChart, stringResource(Res.string.client_preview_progress), onProgress, subtitle = stringResource(Res.string.client_preview_progress_hint))
            if (onChat != null) {
                ActionRow(Icons.AutoMirrored.Outlined.Chat, stringResource(Res.string.client_preview_chat), onChat, subtitle = stringResource(Res.string.client_preview_chat_hint))
            }
        }
    }
}
