package pl.trainer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import pl.trainer.domain.DemoScenario
import pl.trainer.presentation.TrainerViewModel
import pl.trainer.resources.*

/** Developer-only surface. Opened in a separate window by :desktopApp:runPreview. */
@Composable
fun PreviewTools(model: TrainerViewModel) {
    val state by model.state.collectAsState()
    TrainerTheme {
        Surface {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Layout.gutter), verticalArrangement = Arrangement.spacedBy(Layout.medium)) {
                SectionTitle(tr(Res.string.preview_tools))
                Text(tr(Res.string.demo_explanation), style = MaterialTheme.typography.bodyMedium)
                Text(tr(Res.string.demo_date), style = MaterialTheme.typography.bodySmall)
                DemoScenario.entries.forEach { scenario ->
                    Surface(onClick = { model.scenario(scenario) }, enabled = !state.submitting,
                        color = if (state.scenario == scenario) AppColors.softAccent else MaterialTheme.colorScheme.surface, shape = Layout.card) {
                        Row(Modifier.fillMaxWidth().heightIn(min = Layout.target).padding(horizontal = Layout.padding), verticalAlignment = Alignment.CenterVertically) {
                            Text(tr(when (scenario) {
                                DemoScenario.Normal -> Res.string.normal; DemoScenario.Loading -> Res.string.loading
                                DemoScenario.Empty -> Res.string.empty; DemoScenario.Error -> Res.string.error
                                DemoScenario.Offline -> Res.string.offline; DemoScenario.Denied -> Res.string.denied
                            }), modifier = Modifier.weight(1f))
                            if (state.scenario == scenario) Icon(Icons.Outlined.Check, null)
                        }
                    }
                }
            }
        }
    }
}
