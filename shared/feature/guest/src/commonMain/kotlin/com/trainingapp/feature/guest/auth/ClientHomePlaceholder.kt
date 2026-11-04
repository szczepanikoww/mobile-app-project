package com.trainingapp.feature.guest.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.trainingapp.designsystem.component.EmptyState
import com.trainingapp.designsystem.component.InlineMessage
import com.trainingapp.designsystem.component.SecondaryButton
import com.trainingapp.designsystem.component.TopLevelHeader
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.feature.guest.model.AuthSession
import com.trainingapp.feature.guest.model.GuestIntent
import com.trainingapp.feature.guest.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * D12: there is no client shell yet. A signed-in client lands here; the pending intent is acknowledged but never
 * turned into a request, because a request needs an explicit slot choice.
 */
@Composable
fun ClientHomePlaceholder(session: AuthSession?, pendingIntent: GuestIntent?, onLogOut: () -> Unit) {
    Scaffold(
        containerColor = AppTheme.colors.background,
        topBar = { TopLevelHeader(stringResource(Res.string.client_home_title)) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = Sizes.contentWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxl),
            ) {
                session?.let {
                    Text(stringResource(Res.string.client_home_heading, it.displayName), style = AppTheme.type.screenTitle, color = AppTheme.colors.text,
                        modifier = Modifier.semantics { heading() })
                }
                if (pendingIntent is GuestIntent.RequestSession) InlineMessage(stringResource(Res.string.client_pending_request))
                EmptyState(stringResource(Res.string.client_home_title), stringResource(Res.string.client_home_body), icon = Icons.Outlined.Construction)
                SecondaryButton(stringResource(Res.string.log_out), onClick = onLogOut, modifier = Modifier.fillMaxWidth(),
                    icon = Icons.AutoMirrored.Outlined.Logout)
            }
        }
    }
}
