package pl.trainer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelStore
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import pl.trainer.data.DemoTrainerRepository
import pl.trainer.domain.*
import pl.trainer.presentation.*
import pl.trainer.ui.TrainerApp

@OptIn(ExperimentalTestApi::class)
class TrainerUiTest {
    private fun DesktopComposeUiTest.capture(name: String) {
        val directory = File("build/reports/screenshots").also { it.mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    @Test fun calendarClientsSearchAndProfileAreConnected() = runDesktopComposeUiTest(width = 390, height = 844) {
        val model = TrainerViewModel(DemoTrainerRepository(0))
        val store = ViewModelStore().also { it.put("test", model) }
        try {
            setContent { TrainerApp(model) }
            waitUntil { !model.state.value.loading }
            onNodeWithText("Katarzyna Nowak").assertExists()
            capture("calendar-pl")
            onNodeWithText("Podopieczni").performClick()
            onNodeWithText("Szukaj").assertExists()
            capture("clients-pl")
            onNode(hasSetTextAction()).performTextInput("dabrowska")
            onNodeWithText("Aleksandra Dąbrowska-Kowalczyk").assertExists()
            onNodeWithText("Katarzyna Nowak").assertDoesNotExist()
            onNodeWithText("Aleksandra Dąbrowska-Kowalczyk").performClick()
            onNodeWithText("Profil").assertExists()
            capture("client-profile-pl")
            onNodeWithContentDescription("Wstecz").performClick()
            onNode(hasSetTextAction()).assertTextContains("dabrowska")
        } finally { store.clear() }
    }

    @Test fun requestAcceptAndTrainerProposalKeepTheirDistinctStates() = runDesktopComposeUiTest(width = 390, height = 844) {
        val model = TrainerViewModel(DemoTrainerRepository(0))
        val store = ViewModelStore().also { it.put("test", model) }
        try {
            setContent { TrainerApp(model) }
            waitUntil { !model.state.value.loading }
            model.navigate(Route.SessionDetail("s4"))
            onNodeWithText("Obecny potwierdzony termin").assertExists()
            onNodeWithText("Proponowany termin").assertExists()
            capture("reschedule-pl")
            onNodeWithText("Akceptuj prośbę").performScrollTo().performClick()
            waitUntil { model.state.value.data!!.sessions.first { it.id == "s4" }.proposal == null }
            onNodeWithText("Proponowany termin").assertDoesNotExist()
        } finally { store.clear() }
    }

    @Test fun failedNoteSaveKeepsTheInput() = runDesktopComposeUiTest(width = 390, height = 844) {
        val model = TrainerViewModel(DemoTrainerRepository(0))
        val store = ViewModelStore().also { it.put("test", model) }
        try {
            setContent { TrainerApp(model) }
            waitUntil { !model.state.value.loading }
            model.scenario(DemoScenario.Offline)
            waitUntil { !model.state.value.loading }
            model.navigate(Route.NoteEditor("c1"))
            onNode(hasSetTextAction()).performTextInput("Przykładowa notatka do testu")
            onNodeWithText("Zapisz").performScrollTo().performClick()
            waitUntil { model.state.value.actionFailure == Failure.Offline }
            onNode(hasSetTextAction()).assertTextContains("Przykładowa notatka do testu")
            model.reload()
            waitUntil { !model.state.value.loading }
            onNode(hasSetTextAction()).assertTextContains("Przykładowa notatka do testu")
        } finally { store.clear() }
    }

    @Test fun smallScreenWithLargePolishTextKeepsActionsReachable() = runDesktopComposeUiTest(width = 320, height = 720) {
        val model = TrainerViewModel(DemoTrainerRepository(0))
        val store = ViewModelStore().also { it.put("test", model) }
        try {
            setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) { TrainerApp(model) } }
            waitUntil { !model.state.value.loading }
            capture("calendar-small-font200-pl")
            onNodeWithTag("calendar-list").performScrollToNode(hasText("Katarzyna Nowak"))
            capture("calendar-session-font200-pl")
            onNodeWithTag("calendar-list").performScrollToNode(hasText("Niedostępny"))
            onNodeWithText("12:00 – 13:00").assertExists()
            onNodeWithText("Podopieczni").performClick()
            capture("clients-small-font200-pl")
            onNode(hasSetTextAction()).performTextInput("dabrowska")
            onNodeWithTag("clients-list").performScrollToNode(hasText("Aleksandra Dąbrowska-Kowalczyk"))
            onNodeWithText("Aleksandra Dąbrowska-Kowalczyk").performClick()
            onNodeWithContentDescription("Edytuj notatki").performScrollTo().assertIsDisplayed()
            capture("client-small-font200-pl")
        } finally { store.clear() }
    }

    @Test fun demoScenariosRenderRecoverableStates() = runDesktopComposeUiTest(width = 390, height = 844) {
        val model = TrainerViewModel(DemoTrainerRepository(0))
        val store = ViewModelStore().also { it.put("test", model) }
        try {
            setContent { TrainerApp(model) }
            waitUntil { !model.state.value.loading }
            model.scenario(DemoScenario.Error)
            waitUntil { !model.state.value.loading }
            onNodeWithText("Nie udało się wczytać danych").assertExists()
            capture("error-pl")
            model.scenario(DemoScenario.Denied)
            waitUntil { !model.state.value.loading }
            onNodeWithText("Nie masz dostępu").assertExists()
            model.scenario(DemoScenario.Empty)
            waitUntil { !model.state.value.loading }
            onNodeWithText("Dziś jest miejsce na sesję").assertExists()
            capture("empty-calendar-pl")
            model.destination(Destination.Clients)
            onNodeWithText("Tu pojawią się Twoi klienci").assertExists()
            model.scenario(DemoScenario.Loading)
            onNodeWithText("Wczytywanie danych…").assertExists()
            model.scenario(DemoScenario.Normal)
            waitUntil { !model.state.value.loading }
            onNodeWithText("Katarzyna Nowak").assertExists()
        } finally { store.clear() }
    }

    @Test fun cleanNavigationArchiveAndSingleAddMenu() = runDesktopComposeUiTest(width = 390, height = 844) {
        val model = TrainerViewModel(DemoTrainerRepository(0))
        val store = ViewModelStore().also { it.put("test", model) }
        try {
            setContent { TrainerApp(model) }
            waitUntil { !model.state.value.loading }
            onNodeWithText("Twój plan").assertExists()
            onAllNodes(hasText("demo", substring = true, ignoreCase = true)).assertCountEquals(0)
            onNodeWithText("Dodaj", useUnmergedTree = true).performClick()
            onNodeWithText("Dostępność").assertExists()
            onNodeWithText("Dodaj sesję").assertExists()
            onNodeWithText("Dostępność").performClick()
            onNodeWithText("Dodaj blokadę").assertExists()
            model.destination(Destination.Clients)
            onNodeWithText("Twoi podopieczni").assertExists()
            onNodeWithText("Aktywna współpraca").assertDoesNotExist()
            onNodeWithText("Zaległa płatność: nie").assertDoesNotExist()
            onNodeWithText("Piotr Lewandowski").assertDoesNotExist()
            onNodeWithText("Archiwum").performClick()
            onNodeWithText("Piotr Lewandowski").assertExists()
            capture("clients-archive-pl")
            onNodeWithText("Katarzyna Nowak").assertDoesNotExist()
        } finally { store.clear() }
    }

    @Test fun proposalIsVisibleOnNewDateAndKeepsOriginalTimeInDetails() = runDesktopComposeUiTest(width = 390, height = 844) {
        val model = TrainerViewModel(DemoTrainerRepository(0))
        val store = ViewModelStore().also { it.put("test", model) }
        try {
            setContent { TrainerApp(model) }
            waitUntil { !model.state.value.loading }
            model.date(kotlinx.datetime.LocalDate(2026, 10, 29))
            onNodeWithTag("calendar-list").performScrollToNode(hasText("Marek Wiśniewski"))
            onNodeWithText("Prośba o zmianę terminu").assertExists()
            onNodeWithText("Obecnie: 28 paź · 16:00").assertExists()
            capture("calendar-proposal-pl")
            onNodeWithText("Marek Wiśniewski").performClick()
            onNodeWithText("Obecny potwierdzony termin").assertExists()
            onNodeWithText("16:00 – 17:00").assertExists()
            model.back()
            val session = model.state.value.data!!.sessions.first { it.id == "s1" }
            model.navigate(Route.SessionEditor(sessionId = session.id))
            model.saveTime(kotlinx.datetime.LocalDate(2026, 10, 30), "17:00", "18:00", null, "", session)
            waitUntil { !model.state.value.submitting }
            model.date(kotlinx.datetime.LocalDate(2026, 10, 30))
            onNodeWithTag("calendar-list").performScrollToNode(hasText("Oczekuje na klienta"))
            onNodeWithText("Oczekuje na klienta").assertExists()
            capture("calendar-waiting-pl")
            model.date(kotlinx.datetime.LocalDate(2026, 10, 26))
            onNodeWithTag("calendar-list").performScrollToNode(hasText("Zrealizowana"))
            capture("calendar-completed-pl")
        } finally { store.clear() }
    }

    @Test fun clientInformationCanBeEditedAndUnknownHealthIsNotShownAsAbsent() = runDesktopComposeUiTest(width = 390, height = 844) {
        val model = TrainerViewModel(DemoTrainerRepository(0))
        val store = ViewModelStore().also { it.put("test", model) }
        try {
            setContent { TrainerApp(model) }
            waitUntil { !model.state.value.loading }
            model.navigate(Route.ClientDetail("c1"))
            waitUntil { !model.state.value.informationLoading }
            onNodeWithText("Informacje").assertExists()
            onNodeWithText("Waga").assertExists()
            onNodeWithText("Kontuzje").assertExists()
            capture("client-information-pl")
            onNodeWithContentDescription("Edytuj informacje").performClick()
            onNode(hasSetTextAction() and hasText("Waga · kg")).performTextInput("72,5")
            onNodeWithText("Zapisz").performScrollTo().performClick()
            waitUntil { !model.state.value.submitting }
            onNodeWithText("72,5 kg").assertExists()
        } finally { store.clear() }
    }
}
