package pl.trainer

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.datetime.*
import kotlin.time.Instant
import kotlin.test.*
import pl.trainer.data.DemoTrainerRepository
import pl.trainer.domain.*
import pl.trainer.presentation.*
import pl.trainer.ui.onDate

@OptIn(ExperimentalCoroutinesApi::class)
class TrainerBehaviorTest {
    private val stores = mutableListOf<ViewModelStore>()

    @BeforeTest fun before() { Dispatchers.setMain(StandardTestDispatcher()) }
    @AfterTest fun after() { stores.forEach { it.clear() }; Dispatchers.resetMain() }
    private fun model(repository: TrainerRepository): TrainerViewModel = TrainerViewModel(repository).also { model ->
        stores += ViewModelStore().also { it.put("test", model) }
    }
    private fun at(day: Int, hour: Int) = LocalDateTime(2026, 10, day, hour, 0).toInstant(TimeZone.of("Europe/Warsaw"))

    @Test fun acceptingABookingChangesRequestedToConfirmed() = runTest {
        val repo = DemoTrainerRepository(0)
        val before = repo.load().sessions.first { it.id == "s2" }
        assertEquals(SessionStatus.Requested, before.status)
        val after = repo.acceptRequest(before.id, before.revision).sessions.first { it.id == before.id }
        assertEquals(SessionStatus.Confirmed, after.status)
        assertEquals(before.revision + 1, after.revision)
    }

    @Test fun rejectingAReschedulePreservesTheConfirmedOriginal() = runTest {
        val repo = DemoTrainerRepository(0)
        val before = repo.load().sessions.first { it.id == "s4" }
        val after = repo.rejectRequest(before.id, before.revision).sessions.first { it.id == before.id }
        assertEquals(before.start, after.start)
        assertEquals(before.end, after.end)
        assertEquals(SessionStatus.Confirmed, after.status)
        assertNull(after.proposal)
    }

    @Test fun acceptingARescheduleMovesOnlyAfterAcceptance() = runTest {
        val repo = DemoTrainerRepository(0)
        val before = repo.load().sessions.first { it.id == "s4" }
        val after = repo.acceptRequest(before.id, before.revision).sessions.first { it.id == before.id }
        assertEquals(before.proposal!!.start, after.start)
        assertEquals(SessionStatus.Confirmed, after.status)
        assertNull(after.proposal)
    }

    @Test fun trainerCannotAcceptTheirOwnProposal() = runTest {
        val repo = DemoTrainerRepository(0)
        val original = repo.load().sessions.first { it.id == "s1" }
        val pending = repo.proposeTime(original.id, original.revision, RescheduleProposal(at(29, 14), at(29, 15), ProposalAuthor.Trainer))
            .sessions.first { it.id == original.id }
        assertEquals(original.start, pending.start)
        assertEquals(SessionStatus.Confirmed, pending.status)
        val failure = assertFailsWith<TrainerException> { repo.acceptRequest(pending.id, pending.revision) }
        assertEquals(Failure.Conflict, failure.failure)
    }

    @Test fun conflictingRescheduleLeavesOriginalAndProposalUntouched() = runTest {
        val repo = DemoTrainerRepository(0)
        val session = repo.load().sessions.first { it.id == "s4" }
        repo.createSession("c3", session.proposal!!.start, session.proposal.end, "Demo studio")
        assertEquals(Failure.Conflict, assertFailsWith<TrainerException> { repo.acceptRequest(session.id, session.revision) }.failure)
        assertEquals(session, repo.load().sessions.first { it.id == session.id })
    }

    @Test fun simultaneousConflictingCreatesOnlySaveOneSession() = runTest {
        val repo = DemoTrainerRepository(1)
        val results = listOf("c1", "c3").map { client -> async {
            runCatching { repo.createSession(client, at(29, 14), at(29, 15), "Demo studio") }
        } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(Failure.Conflict, (results.first { it.isFailure }.exceptionOrNull() as TrainerException).failure)
    }

    @Test fun staleRevisionCannotOverwriteAConfirmedRequest() = runTest {
        val repo = DemoTrainerRepository(0)
        repo.acceptRequest("s2", 1)
        assertEquals(Failure.Conflict, assertFailsWith<TrainerException> { repo.rejectRequest("s2", 1) }.failure)
        assertEquals(SessionStatus.Confirmed, repo.load().sessions.first { it.id == "s2" }.status)
    }

    @Test fun inactiveRelationshipCannotSavePrivateNotes() = runTest {
        val repo = DemoTrainerRepository(0)
        assertEquals(Failure.Denied, assertFailsWith<TrainerException> { repo.saveNote("c6", "Synthetic note") }.failure)
        assertTrue(repo.load().notes.isEmpty())
    }

    @Test fun blocksCannotOverlapConfirmedSessionsAndCanBeRemoved() = runTest {
        val repo = DemoTrainerRepository(0)
        assertEquals(Failure.Conflict, assertFailsWith<TrainerException> { repo.addBlock(at(28, 9), at(28, 10)) }.failure)
        assertEquals(Failure.Conflict, assertFailsWith<TrainerException> { repo.createSession("c1", at(28, 12), at(28, 13), "Demo studio") }.failure)
        repo.removeBlock("b1")
        val data = repo.createSession("c1", at(28, 12), at(28, 13), "Demo studio")
        assertTrue(data.sessions.any { it.start == at(28, 12) })
    }

    @Test fun duplicateTapSubmitsOnlyOnce() = runTest {
        val model = model(DemoTrainerRepository(100))
        advanceUntilIdle()
        val request = model.state.value.data!!.sessions.first { it.id == "s2" }
        model.accept(request)
        model.accept(request)
        assertTrue(model.state.value.submitting)
        advanceUntilIdle()
        val saved = model.state.value.data!!.sessions.first { it.id == "s2" }
        assertEquals(2, saved.revision)
        assertNull(model.state.value.actionFailure)
        assertFalse(model.state.value.submitting)
    }

    @Test fun offlineMutationIsUnsentAndKeepsTheEditorOpen() = runTest {
        val repo = DemoTrainerRepository(0)
        val model = model(repo)
        advanceUntilIdle()
        model.scenario(DemoScenario.Offline)
        advanceUntilIdle()
        val route = Route.NoteEditor("c1")
        model.navigate(route)
        model.saveNote("c1", "Synthetic demo note")
        advanceUntilIdle()
        assertEquals(Failure.Offline, model.state.value.actionFailure)
        assertEquals(route, model.state.value.route)
        assertTrue(model.state.value.data!!.notes.isEmpty())
        assertFalse(model.state.value.saved)
    }

    @Test fun invalidTimesDoNotSaveOrPopTheForm() = runTest {
        val model = model(DemoTrainerRepository(0))
        advanceUntilIdle()
        model.navigate(Route.SessionEditor())
        model.saveTime(DemoDate, "14:00", "13:00", "c1", "Demo studio")
        assertEquals(Failure.InvalidInput, model.state.value.actionFailure)
        assertIs<Route.SessionEditor>(model.state.value.route)
        assertEquals(7, model.state.value.data!!.sessions.size)
    }

    @Test fun nonexistentDstTimeIsRejectedInsteadOfShifted() = runTest {
        val model = model(DemoTrainerRepository(0))
        advanceUntilIdle()
        model.saveTime(LocalDate(2027, 3, 28), "02:30", "04:00", "c1", "Demo studio")
        assertEquals(Failure.InvalidInput, model.state.value.actionFailure)
    }

    @Test fun clientSearchIgnoresPolishDiacriticsAndCombinesWithFilters() = runTest {
        val model = model(DemoTrainerRepository(0))
        advanceUntilIdle()
        model.search("dabrowska")
        assertEquals(listOf("c5"), model.state.value.visibleClients.map { it.id })
        model.filter(ClientFilter.Overdue)
        assertEquals(listOf("c5"), model.state.value.visibleClients.map { it.id })
        model.search("wojcik")
        assertTrue(model.state.value.visibleClients.isEmpty())
    }

    @Test fun errorCanRecoverAndNavigationKeepsSelectedDateAndSearch() = runTest {
        val model = model(DemoTrainerRepository(0))
        advanceUntilIdle()
        model.scenario(DemoScenario.Error)
        advanceUntilIdle()
        assertEquals(Failure.Load, model.state.value.failure)
        model.scenario(DemoScenario.Normal)
        advanceUntilIdle()
        assertNull(model.state.value.failure)
        model.date(LocalDate(2026, 10, 30))
        model.search("Nowak")
        model.navigate(Route.ClientDetail("c1"))
        model.back()
        assertEquals(LocalDate(2026, 10, 30), model.state.value.selectedDate)
        assertEquals("Nowak", model.state.value.search)
    }

    @Test fun archiveAndCurrentClientsAreSeparateAndFiltersStayScoped() = runTest {
        val model = model(DemoTrainerRepository(0))
        advanceUntilIdle()
        assertEquals(5, model.state.value.visibleClients.size)
        assertTrue(model.state.value.visibleClients.all { it.active })
        model.filter(ClientFilter.Overdue)
        assertEquals(setOf("c2", "c5"), model.state.value.visibleClients.map { it.id }.toSet())
        model.clientTab(ClientTab.Archive)
        assertEquals(listOf("c6"), model.state.value.visibleClients.map { it.id })
        model.clientTab(ClientTab.Current)
        assertEquals(5, model.state.value.visibleClients.size)
    }

    @Test fun proposedDateMovesAgendaPlacementButDoesNotReleaseOriginalSlot() = runTest {
        val repo = DemoTrainerRepository(0)
        val session = repo.load().sessions.first { it.id == "s4" }
        assertFalse(session.onDate(DemoDate))
        assertTrue(session.onDate(LocalDate(2026, 10, 29)))
        assertEquals(Failure.Conflict, assertFailsWith<TrainerException> {
            repo.createSession("c1", session.start, session.end, "Studio")
        }.failure)
        repo.acceptRequest(session.id, session.revision)
        repo.createSession("c1", session.start, session.end, "Studio")
    }

    @Test fun privateInformationStartsUnknownAndIsScopedToActiveRelationship() = runTest {
        val repo = DemoTrainerRepository(0)
        assertEquals(ClientInformation(), repo.loadClientInformation("c1"))
        val saved = ClientInformation(birthDate = LocalDate(2000, 10, 29), weightKg = 72.5, heightCm = 175.0)
        repo.saveClientInformation("c1", saved)
        assertEquals(saved, repo.loadClientInformation("c1"))
        assertEquals(ClientInformation(), repo.loadClientInformation("c3"))
        assertEquals(Failure.Denied, assertFailsWith<TrainerException> { repo.loadClientInformation("c6") }.failure)
        assertEquals(Failure.Denied, assertFailsWith<TrainerException> { repo.saveClientInformation("c6", saved) }.failure)
        assertEquals(25, saved.ageOn(DemoDate))
        assertEquals(26, saved.ageOn(LocalDate(2026, 10, 29)))
    }

    @Test fun informationValidationAndOfflineFailureRetainTheEditorAndPriorValues() = runTest {
        val model = model(DemoTrainerRepository(0))
        advanceUntilIdle()
        model.navigate(Route.ClientDetail("c1"))
        advanceUntilIdle()
        model.navigate(Route.ClientInformationEditor("c1"))
        model.saveClientInformation("c1", null, "-2", "175", "", "", "")
        assertEquals(Failure.InvalidClientInfo, model.state.value.actionFailure)
        assertIs<Route.ClientInformationEditor>(model.state.value.route)
        model.saveClientInformation("c1", null, "72,5", "175", "", "", "")
        advanceUntilIdle()
        assertEquals(72.5, model.state.value.clientInformation!!.weightKg)
        assertIs<Route.ClientDetail>(model.state.value.route)
        model.scenario(DemoScenario.Offline)
        advanceUntilIdle()
        model.navigate(Route.ClientDetail("c1"))
        advanceUntilIdle()
        model.navigate(Route.ClientInformationEditor("c1"))
        model.saveClientInformation("c1", null, "73", "175", "", "", "")
        advanceUntilIdle()
        assertEquals(Failure.Offline, model.state.value.actionFailure)
        assertEquals(72.5, model.state.value.clientInformation!!.weightKg)
        assertIs<Route.ClientInformationEditor>(model.state.value.route)
    }
}
