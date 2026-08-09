package net.mixalich7b.exchangesync

import net.mixalich7b.exchangesync.core.sync.SyncProblem
import net.mixalich7b.exchangesync.core.sync.SyncState

internal enum class CalendarPermissionDestination {
    RUNTIME_DIALOG,
    APPLICATION_SETTINGS,
}

internal class CalendarPermissionRequestTracker(
    initialLastRequestedGeneration: Long? = null,
) {
    var lastRequestedGeneration: Long? = initialLastRequestedGeneration
        private set

    fun shouldRequest(state: SyncState): Boolean {
        val needsAccess =
            state.problem == SyncProblem.CALENDAR_PERMISSION &&
                (state.enabled || state.calendarCleanupPending)
        if (!needsAccess || lastRequestedGeneration == state.generation) return false
        lastRequestedGeneration = state.generation
        return true
    }

    fun destinationForManualRequest(
        readGranted: Boolean,
        writeGranted: Boolean,
        shouldShowReadRationale: Boolean,
        shouldShowWriteRationale: Boolean,
    ): CalendarPermissionDestination {
        val permanentlyDenied =
            lastRequestedGeneration != null &&
                ((!readGranted && !shouldShowReadRationale) ||
                    (!writeGranted && !shouldShowWriteRationale))
        return if (permanentlyDenied) {
            CalendarPermissionDestination.APPLICATION_SETTINGS
        } else {
            CalendarPermissionDestination.RUNTIME_DIALOG
        }
    }
}
