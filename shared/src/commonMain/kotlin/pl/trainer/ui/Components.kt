package pl.trainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pl.trainer.domain.*
import pl.trainer.resources.*

@Composable
fun tr(resource: StringResource, vararg args: Any) = stringResource(resource, *args)

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        action?.invoke()
    }
}

@Composable
fun Avatar(client: Client, large: Boolean = false) {
    Box(Modifier.size(if (large || LocalDensity.current.fontScale >= 1.5f) 64.dp else Layout.target)
        .background(AppColors.softAccent, CircleShape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(client.initials, style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, color = AppColors.onSoftAccent)
    }
}

@Composable
fun MetaLine(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Layout.small), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatusLabel(session: Session) {
    val res = when {
        session.proposal?.author == ProposalAuthor.Client -> Res.string.reschedule
        session.proposal?.author == ProposalAuthor.Trainer -> Res.string.awaiting_client
        else -> when (session.status) {
        SessionStatus.Requested -> Res.string.requested
        SessionStatus.Confirmed -> Res.string.confirmed
        SessionStatus.Completed -> Res.string.completed
        SessionStatus.Rejected -> Res.string.rejected
        SessionStatus.CancelledByTrainer -> Res.string.cancelled_trainer
        SessionStatus.NoShow -> Res.string.no_show
        }
    }
    val (background, color, icon) = when {
        session.proposal?.author == ProposalAuthor.Client -> Triple(AppColors.changeSurface, AppColors.changeText, Icons.Outlined.Update)
        session.proposal?.author == ProposalAuthor.Trainer -> Triple(AppColors.waitingSurface, AppColors.waitingText, Icons.Outlined.HourglassEmpty)
        session.status == SessionStatus.Completed -> Triple(AppColors.completedSurface, AppColors.completedText, Icons.Outlined.DoneAll)
        session.status == SessionStatus.Confirmed -> Triple(AppColors.successSurface, AppColors.success, Icons.Outlined.EventAvailable)
        else -> Triple(AppColors.warningSurface, AppColors.warningText, Icons.Outlined.Schedule)
    }
    Surface(color = background, contentColor = color, shape = Layout.pill) {
        Row(Modifier.padding(horizontal = Layout.small, vertical = Layout.tiny), horizontalArrangement = Arrangement.spacedBy(Layout.tiny), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(14.dp))
            Text(tr(res), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun InfoBanner(text: String, icon: ImageVector = Icons.Outlined.Info, error: Boolean = false, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = if (error) MaterialTheme.colorScheme.errorContainer else AppColors.softAccent, shape = Layout.card) {
        Row(Modifier.padding(Layout.padding), horizontalArrangement = Arrangement.spacedBy(Layout.medium)) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (error) MaterialTheme.colorScheme.error else AppColors.onSoftAccent)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (error) MaterialTheme.colorScheme.onErrorContainer else AppColors.onSoftAccent)
        }
    }
}

@Composable
fun EmptyContent(icon: ImageVector, title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = Layout.large), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
        Surface(color = AppColors.softAccent, shape = CircleShape) {
            Icon(icon, null, Modifier.padding(Layout.padding).size(28.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) OutlinedButton(onClick = onAction, modifier = Modifier.heightIn(min = Layout.target)) { Text(action) }
    }
}

@Composable
fun failureText(failure: Failure): String = tr(when (failure) {
    Failure.Load -> Res.string.load_error_body
    Failure.Offline -> Res.string.offline_error
    Failure.Denied -> Res.string.access_body
    Failure.Conflict -> Res.string.conflict_error
    Failure.InvalidInput -> Res.string.invalid_error
    Failure.InvalidClientInfo -> Res.string.invalid_client_info
    Failure.Save -> Res.string.save_error
})

@Composable
fun PrimaryButton(label: String, enabled: Boolean = true, loading: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !loading, modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target),
        contentPadding = PaddingValues(horizontal = Layout.padding, vertical = Layout.medium)) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(Layout.small))
        }
        Text(if (loading) tr(Res.string.saving) else label)
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, submitting: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit, context: String? = null) {
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
            if (context != null) Text(context, style = MaterialTheme.typography.titleMedium)
            Text(body)
        } },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !submitting, modifier = Modifier.heightIn(min = Layout.target)) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting, modifier = Modifier.heightIn(min = Layout.target)) { Text(tr(Res.string.cancel)) } },
    )
}
