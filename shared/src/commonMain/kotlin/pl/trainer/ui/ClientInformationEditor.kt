package pl.trainer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import pl.trainer.domain.*
import pl.trainer.presentation.*
import pl.trainer.resources.*

@Composable
fun ClientInformationEditor(state: TrainerUiState, clientId: String, onRetry: () -> Unit,
    onSave: (LocalDate?, String, String, String, String, String) -> Unit) {
    when {
        state.informationLoading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); return }
        state.informationFailure != null -> {
            EmptyContent(Icons.Outlined.Lock, tr(Res.string.client_information), failureText(state.informationFailure), tr(Res.string.retry), onRetry)
            return
        }
    }
    val information = state.clientInformation?.takeIf { state.informationClientId == clientId } ?: return
    var birthDate by rememberSaveable { mutableStateOf(information.birthDate?.toString().orEmpty()) }
    var weight by rememberSaveable { mutableStateOf(information.weightKg?.let { DateFormats.number(it, DateFormats.defaultLanguage()) }.orEmpty()) }
    var height by rememberSaveable { mutableStateOf(information.heightCm?.let { DateFormats.number(it, DateFormats.defaultLanguage()) }.orEmpty()) }
    var goal by rememberSaveable { mutableStateOf(information.trainingGoal) }
    var injuries by rememberSaveable { mutableStateOf(information.injuries) }
    var limitations by rememberSaveable { mutableStateOf(information.movementLimitations) }
    var datePicker by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Layout.gutter), verticalArrangement = Arrangement.spacedBy(Layout.section)) {
        Text(state.data!!.client(clientId).name, style = MaterialTheme.typography.titleLarge)
        Text(tr(Res.string.client_information_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(Layout.small)) {
            Text(tr(Res.string.birth_date), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { datePicker = true }, enabled = !state.submitting, shape = Layout.card, modifier = Modifier.fillMaxWidth().heightIn(min = Layout.target)) {
                Icon(Icons.Outlined.CalendarToday, null)
                Spacer(Modifier.width(Layout.medium))
                Text(if (birthDate.isBlank()) tr(Res.string.not_provided) else DateFormats.date(LocalDate.parse(birthDate), DateFormats.defaultLanguage(), "d MMMM yyyy"), modifier = Modifier.weight(1f))
            }
            if (birthDate.isNotBlank()) TextButton(onClick = { birthDate = "" }, enabled = !state.submitting) { Text(tr(Res.string.clear_birth_date)) }
        }
        OutlinedTextField(weight, { weight = it }, label = { Text(tr(Res.string.weight_input)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !state.submitting,
            modifier = Modifier.fillMaxWidth(), shape = Layout.card, isError = state.actionFailure == Failure.InvalidClientInfo)
        OutlinedTextField(height, { height = it }, label = { Text(tr(Res.string.height_input)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !state.submitting,
            modifier = Modifier.fillMaxWidth(), shape = Layout.card, isError = state.actionFailure == Failure.InvalidClientInfo)
        OutlinedTextField(goal, { goal = it }, label = { Text(tr(Res.string.training_goal)) }, enabled = !state.submitting,
            modifier = Modifier.fillMaxWidth(), shape = Layout.card, minLines = 2)
        OutlinedTextField(injuries, { injuries = it }, label = { Text(tr(Res.string.injuries)) }, enabled = !state.submitting,
            modifier = Modifier.fillMaxWidth(), shape = Layout.card, minLines = 2)
        OutlinedTextField(limitations, { limitations = it }, label = { Text(tr(Res.string.movement_limitations)) }, enabled = !state.submitting,
            modifier = Modifier.fillMaxWidth(), shape = Layout.card, minLines = 2)
        PrimaryButton(tr(Res.string.save), loading = state.submitting, onClick = {
            onSave(birthDate.takeIf { it.isNotBlank() }?.let(LocalDate::parse), weight, height, goal, injuries, limitations)
        })
    }
    if (datePicker) AppDatePicker(birthDate.takeIf { it.isNotBlank() }?.let(LocalDate::parse) ?: DemoDate.minus(30, DateTimeUnit.YEAR),
        onDismiss = { datePicker = false }, onDate = { birthDate = it.toString(); datePicker = false })
}
