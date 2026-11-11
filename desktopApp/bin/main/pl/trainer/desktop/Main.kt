package pl.trainer.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import pl.trainer.ui.TrainerApp
import pl.trainer.ui.PreviewTools
import pl.trainer.presentation.TrainerViewModel
import pl.trainer.data.DemoTrainerRepository
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

fun main() = application {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    val model = remember { TrainerViewModel(DemoTrainerRepository()).also { owner.viewModelStore.put("trainer", it) } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    var toolsVisible by remember { mutableStateOf(System.getProperty("trainer.preview") == "true") }
    Window(onCloseRequest = ::exitApplication, title = "Trainer", state = rememberWindowState(width = 430.dp, height = 900.dp)) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { TrainerApp(model) }
    }
    if (toolsVisible) Window(onCloseRequest = { toolsVisible = false }, title = "Narzędzia podglądu", state = rememberWindowState(width = 380.dp, height = 650.dp)) {
        PreviewTools(model)
    }
}
