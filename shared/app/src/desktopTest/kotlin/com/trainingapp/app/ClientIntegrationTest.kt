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
import com.trainingapp.feature.guest.data.DemoAccounts
import com.trainingapp.feature.guest.data.DemoAuthRepository
import com.trainingapp.feature.guest.model.AuthRepository
import com.trainingapp.feature.guest.model.AuthSession
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
 * The client shell end to end in Polish with the demo client (Katarzyna Nowak, synthetic): Start, the calendar,
 * the session details with the cancellation quote, and the wallet. Screenshots go to build/reports/screenshots/.
 */
@OptIn(ExperimentalTestApi::class)
class ClientIntegrationTest {
    private fun DesktopComposeUiTest.capture(name: String) {
        val directory = File("build/reports/screenshots").also { it.mkdirs() }
        // An open sheet or dialog is a second root; capture the topmost one.
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun DesktopComposeUiTest.clickOnUi(node: SemanticsNodeInteraction) {
        node.performSemanticsAction(SemanticsActions.OnClick) { action -> runOnUiThread { action() } }
    }

    private fun DesktopComposeUiTest.waitForText(text: String, substring: Boolean = false) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    private fun runClient(width: Int, height: Int, fontScale: Float, session: AuthSession = DemoAccounts.client, block: DesktopComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = width, height = height) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), TrainingDatabase.Schema)
            val owner = object : ViewModelStoreOwner, LifecycleOwner {
                override val viewModelStore = ViewModelStore()
                override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
            val network = object : NetworkMonitor { override val isOnline = MutableStateFlow(true) }
            initKoin(object : AppPlatform {
                override fun createDriver(): SqlDriver = driver
                override val networkMonitor = network
                override val ioDispatcher = Dispatchers.IO
                override val localFiles = LocalFiles { false }
            })
            loadKoinModules(module {
                single<WelcomePreferences> { InMemoryWelcomePreferences() }
                // Signed in as the demo client.
                single<AuthRepository> { DemoAuthRepository(network, initialSession = session, latencyMs = 0) }
            })
            val scale = mutableStateOf(fontScale)
            try {
                setContent {
                    CompositionLocalProvider(
                        LocalViewModelStoreOwner provides owner,
                        LocalLifecycleOwner provides owner,
                        LocalDensity provides Density(1f, scale.value),
                    ) { App(initialSession = session) }
                }
                block()
            } finally {
                owner.viewModelStore.clear()
                stopKoin()
                driver.close()
            }
        }

    @Test
    fun startCalendarCancellationQuoteAndWallet() = runClient(390, 844, 1f) {
        waitForText("Hej, Katarzyna")
        waitForText("Najbliższa sesja")
        onNodeWithText("Trener proponuje nowy termin").assertExists()
        onNodeWithText("Do zapłaty", substring = true).assertExists()
        // No notifications bell and no own bottom bar in the tab content (D4, B0).
        onAllNodesWithContentDescription("Powiadomienia").assertCountEquals(0)
        capture("client-start-pl")

        clickOnUi(onNodeWithText("Kalendarz"))
        waitForText("Poproś o sesję")
        capture("client-calendar-pl")

        clickOnUi(onNodeWithText("Start"))
        waitForText("Najbliższa sesja")
        clickOnUi(onNodeWithText("Szczegóły").performScrollTo())
        waitForText("Szczegóły sesji")
        onNodeWithText("Płatność online").assertExists()
        capture("client-session-detail-pl")
        clickOnUi(onNodeWithText("Odwołaj sesję").performScrollTo())
        waitForText("Późna rezygnacja", substring = true)
        onNodeWithText("Odwołaj sesję (", substring = true).assertExists()
        capture("client-cancel-quote-pl")
        clickOnUi(onNodeWithText("Wróć do szczegółów sesji").performScrollTo())
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Późna rezygnacja", substring = true)).fetchSemanticsNodes().isEmpty() }
        // Nothing was cancelled: the session is still confirmed.
        onNodeWithText("Potwierdzona").assertExists()

        clickOnUi(onNodeWithContentDescription("Wstecz"))
        clickOnUi(onNodeWithText("Portfel"))
        waitForText("Historia płatności")
        onNodeWithText("Opłata za późne odwołanie").assertExists()
        capture("client-wallet-pl")
    }

    @Test
    fun messagesListOpensChatAndAvatarOpensProfile() = runClient(390, 844, 1f) {
        waitForText("Hej, Katarzyna")
        clickOnUi(onNodeWithContentDescription("Wiadomości", substring = true))
        waitForText("Wiadomości")
        // The trainer's conversation is the one row; tapping it opens the thread.
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasClickAction()).fetchSemanticsNodes().size > 1 }
        capture("client-messages-pl")
        clickOnUi(onAllNodes(hasClickAction()).onLast())
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        capture("client-chat-pl")
        clickOnUi(onNodeWithContentDescription("Wstecz"))
        waitForText("Wiadomości")
        clickOnUi(onNodeWithContentDescription("Wstecz"))

        waitForText("Hej, Katarzyna")
        clickOnUi(onNodeWithContentDescription("Profil i ustawienia"))
        waitForText("Twój trener")
        capture("client-profile-pl")
        clickOnUi(onNodeWithText("Ustawienia"))
        waitForText("Wyloguj")
        onNodeWithText("Zmień hasło").assertExists()
        capture("client-settings-pl")
        clickOnUi(onNodeWithContentDescription("Wstecz"))
        waitForText("Hej, Katarzyna")
    }

    @Test
    fun profileEditsName() = runClient(390, 844, 1f) {
        waitForText("Hej, Katarzyna")
        clickOnUi(onNodeWithContentDescription("Profil i ustawienia"))
        waitForText("Twój trener")
        clickOnUi(onNodeWithText("Edytuj dane"))
        waitForText("Imię i nazwisko")
        onNode(hasSetTextAction()).performTextReplacement("   ")
        clickOnUi(onNodeWithText("Zapisz"))
        waitForText("Wpisz imię i nazwisko.")
        onNode(hasSetTextAction()).performTextReplacement("Katarzyna Nowak-Kowalska")
        capture("client-profile-edit-pl")
        clickOnUi(onNodeWithText("Zapisz"))
        waitForText("Dane zostały zapisane")
        waitForText("Katarzyna Nowak-Kowalska")
        capture("client-profile-saved-pl")
    }

    @Test
    fun dumbbellStartsAnOwnWorkoutAndExercisesAreAddedWhileLogging() = runClient(390, 844, 1f) {
        waitForText("Hej, Katarzyna")
        // The centre dumbbell opens today's plan, with an empty workout of one's own as the alternative.
        clickOnUi(onAllNodes(hasContentDescription("Rozpocznij trening")).onFirst())
        waitForText("Zacznij pusty trening zamiast planu")
        capture("client-live-not-started-pl")
        clickOnUi(onNodeWithText("Zacznij pusty trening zamiast planu"))

        // The library opens in pick mode, without the trainer's create tools.
        waitForText("Dodaj ćwiczenia")
        onNodeWithText("Nowe ćwiczenie").assertDoesNotExist()
        onNode(hasSetTextAction()).performTextReplacement("Wyciskanie sztangi")
        waitForText("Wyciskanie sztangi leżąc")
        clickOnUi(onAllNodes(hasText("Wyciskanie sztangi leżąc")).onFirst())
        waitForText("Dodaj (1)")
        capture("client-live-pick-pl")
        clickOnUi(onNodeWithText("Dodaj (1)"))

        waitForText("Własny trening")
        waitForText("Wyciskanie sztangi leżąc")
        onAllNodes(hasText("Dodaj ćwiczenie")).fetchSemanticsNodes().isNotEmpty().let { assert(it) }
        capture("client-live-own-workout-pl")
    }

    @Test
    fun progressShowsTheExercisePhotoAboveTheChart() = runClient(390, 844, 1f) {
        waitForText("Hej, Katarzyna")
        clickOnUi(onNodeWithText("Postępy"))
        waitForText("Ćwiczenia")
        clickOnUi(onNodeWithText("Ćwiczenia"))
        waitForIdle()
        capture("client-progress-exercises-pl")
    }

    @Test
    fun bookingRequestNeedsPolicyConsent() = runClient(390, 844, 1f) {
        waitForText("Najbliższa sesja")
        clickOnUi(onNodeWithText("Kalendarz"))
        waitForText("Poproś o sesję")
        clickOnUi(onAllNodesWithText("Poproś o sesję")[0])
        // First the trainer list, then the chosen trainer's month calendar.
        waitForText("Dotknij trenera", substring = true)
        capture("client-booking-trainers-pl")
        clickOnUi(onAllNodesWithText("Michał Kowalczyk").onLast())
        waitForText("Wybierz dzień")
        waitForText("Akceptuję zasady odwołań", substring = true)
        onNodeWithText("Wyślij prośbę").assertIsNotEnabled()
        capture("client-booking-sheet-pl")
    }

    @Test
    fun smallScreenLargeText() = runClient(320, 720, 2f) {
        waitForText("Najbliższa sesja")
        capture("client-start-font200-pl")
        clickOnUi(onNodeWithText("Portfel"))
        waitForText("Historia płatności")
        capture("client-wallet-font200-pl")
    }
}
