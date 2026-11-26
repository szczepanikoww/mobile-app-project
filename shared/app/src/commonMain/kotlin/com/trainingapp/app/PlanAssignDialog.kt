package com.trainingapp.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.trainingapp.app.remote.RemotePlanAssigner
import com.trainingapp.app.resources.Res
import com.trainingapp.app.resources.assign_cancel
import com.trainingapp.app.resources.assign_choose_client
import com.trainingapp.app.resources.assign_confirm
import com.trainingapp.app.resources.assign_failed
import com.trainingapp.app.resources.assign_load_failed
import com.trainingapp.app.resources.assign_no_clients
import com.trainingapp.app.resources.assign_offline
import com.trainingapp.app.resources.assign_retry
import com.trainingapp.app.resources.assign_title
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import pl.trainer.domain.Client
import pl.trainer.domain.Failure
import pl.trainer.domain.TrainerException
import pl.trainer.domain.TrainerRepository
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A published plan version waiting to be assigned. */
data class PlanToAssign(val templateId: String, val versionNumber: Int)

private sealed interface ClientsState {
    data object Loading : ClientsState
    data object Failed : ClientsState
    data class Loaded(val clients: List<Client>) : ClientsState
}

/**
 * Picks one of the trainer's active clients and assigns [plan] on the server. The operation id per client is kept
 * while the dialog is open, so a retry after a lost answer cannot create a second assignment. [onAssigned] gets the
 * plan name and the client's name.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun PlanAssignDialog(
    plan: PlanToAssign,
    clients: TrainerRepository,
    assigner: RemotePlanAssigner,
    onDismiss: () -> Unit,
    onAssigned: (planName: String, clientName: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ClientsState>(ClientsState.Loading) }
    var loadKey by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Failure?>(null) }
    val operationIds = remember { mutableMapOf<String, String>() }

    LaunchedEffect(loadKey) {
        state = ClientsState.Loading
        state = try {
            ClientsState.Loaded(clients.load().clients.filter { it.active }.sortedBy { it.name })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ClientsState.Failed
        }
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(Res.string.assign_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val s = state) {
                    ClientsState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    ClientsState.Failed -> Text(stringResource(Res.string.assign_load_failed), style = AppTheme.type.body)
                    is ClientsState.Loaded -> if (s.clients.isEmpty()) {
                        Text(stringResource(Res.string.assign_no_clients), style = AppTheme.type.body)
                    } else {
                        Text(stringResource(Res.string.assign_choose_client), style = AppTheme.type.body)
                        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                            s.clients.forEach { client ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .selectable(
                                            selected = selected == client.id,
                                            enabled = !submitting,
                                            role = Role.RadioButton,
                                            onClick = { selected = client.id; error = null },
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = selected == client.id, onClick = null, enabled = !submitting)
                                    Text(client.name, style = AppTheme.type.body, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                error?.let {
                    Text(
                        stringResource(if (it == Failure.Offline) Res.string.assign_offline else Res.string.assign_failed),
                        style = AppTheme.type.body,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            val current = state
            when (current) {
                ClientsState.Failed -> AppTextButton(stringResource(Res.string.assign_retry), onClick = { loadKey++ })
                is ClientsState.Loaded -> if (current.clients.isNotEmpty()) AppTextButton(
                    stringResource(Res.string.assign_confirm),
                    enabled = selected != null && !submitting,
                    onClick = {
                        val clientId = selected ?: return@AppTextButton
                        val client = current.clients.first { it.id == clientId }
                        submitting = true
                        error = null
                        scope.launch {
                            try {
                                val opId = operationIds.getOrPut(clientId) { Uuid.random().toString() }
                                val assigned = assigner.assign(clientId, plan.templateId, plan.versionNumber, opId)
                                onAssigned(assigned.name, client.name)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: TrainerException) {
                                error = e.failure
                            } catch (e: Exception) {
                                error = Failure.Save
                            } finally {
                                submitting = false
                            }
                        }
                    },
                )
                ClientsState.Loading -> Unit
            }
        },
        dismissButton = { AppTextButton(stringResource(Res.string.assign_cancel), enabled = !submitting, onClick = onDismiss) },
    )
}
