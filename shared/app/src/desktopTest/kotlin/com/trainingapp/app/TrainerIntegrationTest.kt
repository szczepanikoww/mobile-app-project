package com.trainingapp.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.trainingapp.data.db.TrainingDatabase
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.domain.media.LocalFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.stopKoin
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class TrainerIntegrationTest {
    private fun DesktopComposeUiTest.capture(name: String) {
        val directory = File("build/reports/screenshots").also { it.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun DesktopComposeUiTest.clickOnUi(node: SemanticsNodeInteraction) {
        node.performSemanticsAction(SemanticsActions.OnClick) { action -> runOnUiThread { action() } }
    }

    @Test fun calendarClientsAndPersistedPlansShareOneShell() = runDesktopComposeUiTest(width = 390, height = 844) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), TrainingDatabase.Schema)
        val owner = object : ViewModelStoreOwner, LifecycleOwner {
            override val viewModelStore = ViewModelStore()
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val fontScale = mutableStateOf(1f)
        initKoin(object : AppPlatform {
            override fun createDriver(): SqlDriver = driver
            override val networkMonitor = object : NetworkMonitor { override val isOnline = MutableStateFlow(true) }
            override val ioDispatcher = Dispatchers.IO
            override val localFiles = LocalFiles { false }
        })
        try {
            setContent { CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalLifecycleOwner provides owner, LocalDensity provides Density(1f, fontScale.value)) { App() } }
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Katarzyna Nowak")).fetchSemanticsNodes().isNotEmpty() }
            clickOnUi(onNodeWithText("Podopieczni"))
            onNodeWithText("Twoi podopieczni").assertExists()
            clickOnUi(onNodeWithContentDescription("Dodaj"))
            onNodeWithText("Dodaj sesję").assertExists()
            onNodeWithText("Dostępność").assertExists()
            clickOnUi(onNodeWithText("Nowy plan treningowy").performScrollTo())
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            onAllNodes(hasSetTextAction())[0].performTextReplacement("Plan siłowy")
            capture("integrated-plan-editor-pl")
            runOnIdle { fontScale.value = 2f }
            onNodeWithText("Przypisz podopiecznemu").assertIsDisplayed()
            capture("integrated-plan-editor-font200-pl")
            runOnIdle { fontScale.value = 1f }
            clickOnUi(onNodeWithContentDescription("Wstecz"))
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Twoi podopieczni")).fetchSemanticsNodes().isNotEmpty() }
            clickOnUi(onNodeWithContentDescription("Dodaj"))
            clickOnUi(onNodeWithText("Plany treningowe").performScrollTo())
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Plan siłowy")).fetchSemanticsNodes().isNotEmpty() }
            capture("integrated-plan-library-pl")
            clickOnUi(onNodeWithContentDescription("Wstecz"))
            clickOnUi(onNodeWithText("Kalendarz"))
            onNodeWithText("Twój plan").assertExists()
        } finally {
            owner.viewModelStore.clear()
            stopKoin()
            driver.close()
        }
    }
}
