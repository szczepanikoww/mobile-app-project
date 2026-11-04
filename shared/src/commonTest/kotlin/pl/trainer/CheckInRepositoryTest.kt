package pl.trainer

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import pl.trainer.data.DemoTrainerRepository
import pl.trainer.domain.*
import kotlin.test.*

/** A4: attendance rules of the demo server (grace period from the policy version, server clock, revisions). */
class CheckInRepositoryTest {
    private fun at(hour: Int, minute: Int) = LocalDateTime(2026, 10, 28, hour, minute).toInstant(TimeZone.of("Europe/Warsaw"))

    @Test fun noShowIsLockedUntilTheGracePeriodHasPassed() = runTest {
        val repo = DemoTrainerRepository(0, serverNow = { at(9, 10) })
        val info = repo.checkIn("s1")
        assertTrue(info.canCheckIn)
        assertTrue(info.started)
        assertFalse(info.noShowAllowed)
        assertEquals(at(9, 15), info.noShowAvailableAt)
        assertEquals(Failure.Conflict, assertFailsWith<TrainerException> { repo.markNoShow("s1", info.session.revision) }.failure)
    }

    @Test fun afterTheGracePeriodNoShowIsRecordedOnce() = runTest {
        val repo = DemoTrainerRepository(0, serverNow = { at(9, 16) })
        val info = repo.checkIn("s1")
        assertTrue(info.noShowAllowed)
        val updated = repo.markNoShow("s1", info.session.revision).sessions.first { it.id == "s1" }
        assertEquals(SessionStatus.NoShow, updated.status)
        // A second tap with the old revision is a conflict, not a second record.
        assertEquals(Failure.Conflict, assertFailsWith<TrainerException> { repo.markNoShow("s1", info.session.revision) }.failure)
    }

    @Test fun aSessionThatHasNotStartedOnlyAllowsPresent() = runTest {
        val repo = DemoTrainerRepository(0, serverNow = { at(9, 20) })
        val info = repo.checkIn("s3")
        assertFalse(info.started)
        assertFalse(info.noShowAllowed)
        val completed = repo.completeSession("s3", info.session.revision).sessions.first { it.id == "s3" }
        assertEquals(SessionStatus.Completed, completed.status)
    }

    @Test fun presentTwiceWithTheSameRevisionCompletesOnce() = runTest {
        val repo = DemoTrainerRepository(0)
        val info = repo.checkIn("s1")
        repo.completeSession("s1", info.session.revision)
        assertEquals(Failure.Conflict, assertFailsWith<TrainerException> { repo.completeSession("s1", info.session.revision) }.failure)
    }

    @Test fun requestedSessionsCannotBeCheckedIn() = runTest {
        val info = DemoTrainerRepository(0).checkIn("s2")
        assertEquals(SessionStatus.Requested, info.session.status)
        assertFalse(info.canCheckIn)
    }

    @Test fun offlineCheckInIsNotSentAndUnknownSessionsConflict() = runTest {
        val repo = DemoTrainerRepository(0).apply { scenario = DemoScenario.Offline }
        val info = repo.checkIn("s1")
        assertEquals(Failure.Offline, assertFailsWith<TrainerException> { repo.completeSession("s1", info.session.revision) }.failure)
        assertEquals(SessionStatus.Confirmed, repo.load().sessions.first { it.id == "s1" }.status)
        assertEquals(Failure.Conflict, assertFailsWith<TrainerException> { repo.checkIn("missing") }.failure)
    }

    @Test fun paymentIsReportedReadOnly() = runTest {
        val session = DemoTrainerRepository(0).checkIn("s1").session
        assertEquals(SessionPayment(PaymentKind.Package, 6, 8), session.payment)
        assertEquals("Push A", session.planTitle)
    }
}
