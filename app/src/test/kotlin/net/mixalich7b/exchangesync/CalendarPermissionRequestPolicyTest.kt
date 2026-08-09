package net.mixalich7b.exchangesync

import net.mixalich7b.exchangesync.core.sync.SyncPhase
import net.mixalich7b.exchangesync.core.sync.SyncProblem
import net.mixalich7b.exchangesync.core.sync.SyncState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalendarPermissionRequestPolicyTest {
    @Test
    fun `each blocked generation requests calendar access automatically only once`() {
        val tracker = CalendarPermissionRequestTracker()
        val blocked =
            SyncState.initial().copy(
                enabled = true,
                generation = 4,
                phase = SyncPhase.BLOCKED,
                problem = SyncProblem.CALENDAR_PERMISSION,
            )

        assertTrue(tracker.shouldRequest(blocked))
        assertFalse(tracker.shouldRequest(blocked))
        assertTrue(tracker.shouldRequest(blocked.copy(generation = 5)))
        assertFalse(tracker.shouldRequest(blocked.copy(problem = SyncProblem.TLS, generation = 6)))
    }

    @Test
    fun `disabled cleanup permission block also requests access`() {
        val tracker = CalendarPermissionRequestTracker()
        val pending =
            SyncState.initial().copy(
                generation = 3,
                calendarCleanupPending = true,
                problem = SyncProblem.CALENDAR_PERMISSION,
            )

        assertTrue(tracker.shouldRequest(pending))
    }

    @Test
    fun `activity recreation restores the automatically requested generation`() {
        val blocked =
            SyncState.initial().copy(
                enabled = true,
                generation = 9,
                phase = SyncPhase.BLOCKED,
                problem = SyncProblem.CALENDAR_PERMISSION,
            )
        val original = CalendarPermissionRequestTracker()
        assertTrue(original.shouldRequest(blocked))

        val recreated = CalendarPermissionRequestTracker(original.lastRequestedGeneration)

        assertFalse(recreated.shouldRequest(blocked))
        assertTrue(recreated.shouldRequest(blocked.copy(generation = 10)))
    }

    @Test
    fun `manual recovery opens application settings only after permanent denial`() {
        val beforeFirstRequest = CalendarPermissionRequestTracker()
        assertEquals(
            CalendarPermissionDestination.RUNTIME_DIALOG,
            beforeFirstRequest.destinationForManualRequest(
                readGranted = false,
                writeGranted = false,
                shouldShowReadRationale = false,
                shouldShowWriteRationale = false,
            ),
        )

        val afterRequest = CalendarPermissionRequestTracker(initialLastRequestedGeneration = 4)
        assertEquals(
            CalendarPermissionDestination.RUNTIME_DIALOG,
            afterRequest.destinationForManualRequest(
                readGranted = false,
                writeGranted = false,
                shouldShowReadRationale = true,
                shouldShowWriteRationale = true,
            ),
        )
        assertEquals(
            CalendarPermissionDestination.APPLICATION_SETTINGS,
            afterRequest.destinationForManualRequest(
                readGranted = false,
                writeGranted = false,
                shouldShowReadRationale = false,
                shouldShowWriteRationale = false,
            ),
        )
    }
}
