package com.trainingapp.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.trainingapp.data.db.TrainingDatabase
import com.trainingapp.domain.common.NetworkMonitor
import com.trainingapp.domain.media.LocalFiles
import com.trainingapp.feature.guest.platform.InMemoryWelcomePreferences
import com.trainingapp.feature.guest.platform.WelcomePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.loadKoinModules
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * The guest flow end to end in Polish: Auth (start screen) → browse → trainer → gym → login-required sheet → register →
 * client shell (a new client without a trainer lands on Start with "Znajdź trenera"). Screenshots go to build/reports/screenshots/ for comparison with the trainer previews.
 */
@OptIn(ExperimentalTestApi::class)
class GuestIntegrationTest {
    private fun DesktopComposeUiTest.capture(name: String) {
        val directory = File("build/reports/screenshots").also { it.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun DesktopComposeUiTest.clickOnUi(node: SemanticsNodeInteraction) {
        node.performSemanticsAction(SemanticsActions.OnClick) { action -> runOnUiThread { action() } }
    }

    private fun DesktopComposeUiTest.waitForText(text: String, substring: Boolean = false) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    private fun runGuest(width: Int, height: Int, fontScale: Float, online: Boolean = true, block: DesktopComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = width, height = height) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), TrainingDatabase.Schema)
            val owner = object : ViewModelStoreOwner, LifecycleOwner {
                override val viewModelStore = ViewModelStore()
                override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
            initKoin(object : AppPlatform {
                override fun createDriver(): SqlDriver = driver
                override val networkMonitor = object : NetworkMonitor { override val isOnline = MutableStateFlow(online) }
                override val ioDispatcher = Dispatchers.IO
                override val localFiles = LocalFiles { false }
            })
            // Tests never touch the real per-user preferences.
            loadKoinModules(module { single<WelcomePreferences> { InMemoryWelcomePreferences() } })
            val scale = mutableStateOf(fontScale)
            try {
                setContent {
                    CompositionLocalProvider(
                        LocalViewModelStoreOwner provides owner,
                        LocalLifecycleOwner provides owner,
                        LocalDensity provides Density(1f, scale.value),
                    ) { App() }
                }
                block()
            } finally {
                owner.viewModelStore.clear()
                stopKoin()
                driver.close()
            }
        }

    @Test
    fun authFirstThenBrowseTrainerGymSheetRegisterClient() = runGuest(390, 844, 1f) {
        waitForText("Logowanie i rejestracja")
        // The start screen has no back arrow: there is nothing behind it.
        onAllNodesWithContentDescription("Wstecz").assertCountEquals(0)
        capture("guest-auth-start-pl")
        clickOnUi(onNodeWithText("Przeglądaj bez logowania").performScrollTo())

        waitForText("Michał Kowalczyk")
        onNodeWithText("Znajdź swojego trenera").assertExists()
        capture("guest-start-pl")

        clickOnUi(onNodeWithText("Trenerzy"))
        waitForText("12 wyników")
        capture("guest-trainers-pl")

        clickOnUi(onNodeWithText("Michał Kowalczyk").performScrollTo())
        waitForText("Profil trenera")
        waitForText("O moim podejściu")
        capture("guest-trainer-profile-pl")

        clickOnUi(onAllNodesWithText("Studio Centrum")[0].performScrollTo())
        waitForText("Szczegóły siłowni")
        waitForText("Godziny otwarcia")
        capture("guest-gym-detail-pl")

        clickOnUi(onNodeWithText("Zaloguj się, aby poprosić o termin"))
        waitForText("Zaloguj się, aby kontynuować")
        capture("guest-login-required-pl")
        clickOnUi(onNodeWithText("Nie teraz"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Zaloguj się, aby kontynuować")).fetchSemanticsNodes().isEmpty() }
        onNodeWithText("Godziny otwarcia").assertExists()

        clickOnUi(onNodeWithText("Zaloguj się, aby poprosić o termin"))
        waitForText("Zaloguj się, aby kontynuować")
        clickOnUi(onAllNodesWithText("Załóż konto")[0])
        waitForText("Logowanie i rejestracja")
        onAllNodes(hasSetTextAction())[0].performTextReplacement("Ala Testowa")
        onAllNodes(hasSetTextAction())[1].performTextReplacement("ala.testowa@example.com")
        onAllNodes(hasSetTextAction())[2].performTextReplacement("haslo-testowe")
        clickOnUi(onNodeWithText("Akceptuję regulamin i politykę prywatności").performScrollTo())
        capture("guest-register-pl")
        clickOnUi(onNodeWithTag("auth-submit").performScrollTo())

        waitForText("Hej, Ala")
        // The gym intent has no trainer and the new client has none yet: no booking sheet, the empty state instead.
        waitForText("Nie masz jeszcze trenera")
        onNodeWithText("Znajdź trenera").assertExists()
        capture("guest-client-home-pl")
    }

    @Test
    fun registerValidationKeepsInputAndShowsErrors() = runGuest(390, 844, 1f) {
        waitForText("Logowanie i rejestracja")
        clickOnUi(onAllNodesWithText("Załóż konto")[0])
        onAllNodes(hasSetTextAction())[1].performTextReplacement("ala@")
        clickOnUi(onNodeWithTag("auth-submit").performScrollTo())
        waitForText("Podaj poprawny adres e-mail")
        kotlin.test.assertTrue(onAllNodesWithText("To pole jest wymagane").fetchSemanticsNodes().isNotEmpty())
        onNodeWithText("Zaakceptuj regulamin, aby założyć konto").assertExists()
        onNodeWithText("ala@").assertExists()
    }

    @Test
    fun offlineRegistrationIsNotSent() = runGuest(390, 844, 1f, online = false) {
        waitForText("Logowanie i rejestracja")
        clickOnUi(onAllNodesWithText("Załóż konto")[0])
        onAllNodes(hasSetTextAction())[0].performTextReplacement("Ala Testowa")
        onAllNodes(hasSetTextAction())[1].performTextReplacement("ala@example.com")
        onAllNodes(hasSetTextAction())[2].performTextReplacement("haslo-testowe")
        clickOnUi(onNodeWithText("Akceptuję regulamin i politykę prywatności").performScrollTo())
        clickOnUi(onNodeWithTag("auth-submit").performScrollTo())
        waitForText("Nie wysłano. Sprawdź połączenie.")
        onNodeWithText("Ala Testowa").assertExists()
        capture("guest-register-offline-pl")
    }

    @Test
    fun smallScreenLargeText() = runGuest(320, 720, 2f) {
        waitForText("Logowanie i rejestracja")
        clickOnUi(onNodeWithText("Przeglądaj bez logowania").performScrollTo())
        waitForText("Michał Kowalczyk")
        capture("guest-start-font200-pl")
        clickOnUi(onNodeWithText("Trenerzy"))
        waitForText("12 wyników")
        // The grid toggle is hidden at large text.
        onAllNodesWithContentDescription("Widok siatki").assertCountEquals(0)
        capture("guest-trainers-font200-pl")
    }
}
