package net.mixalich7b.exchangesync.core.sync

public object SyncStateTransitions {
    public fun activate(state: SyncState, trigger: SyncTrigger): SyncState =
        state.copy(
            enabled = true,
            generation = state.generation.incrementedIdentity(),
            runToken = state.runToken.incrementedIdentity(),
            fullSyncRequired = true,
            invalidKeyRecoveryUsed = false,
            checkpoints = SyncCheckpoints.EMPTY,
            phase = SyncPhase.QUEUED,
            currentTrigger = trigger,
            followUpRequested = false,
            consecutiveTransientAttempts = 0,
            problem = null,
            calendarCleanupPending = true,
        )
    public fun mayPerformSideEffect(
        state: SyncState,
        fence: SyncFence,
    ): Boolean =
        state.enabled &&
            state.generation == fence.generation &&
            state.runToken == fence.runToken &&
            state.phase != SyncPhase.CANCELLING

    public fun requestRun(
        state: SyncState,
        trigger: SyncTrigger,
    ): SyncRunRequest {
        if (!state.enabled || state.phase == SyncPhase.DISABLED) return SyncRunRequest.Ignored(state)
        if (state.phase.isActive || state.phase == SyncPhase.CANCELLING) {
            return SyncRunRequest.Coalesced(state.copy(followUpRequested = true))
        }
        return SyncRunRequest.Queued(
            state.copy(
                runToken = state.runToken + 1,
                invalidKeyRecoveryUsed = false,
                phase = SyncPhase.QUEUED,
                currentTrigger = trigger,
                followUpRequested = false,
                consecutiveTransientAttempts = 0,
                problem = null,
            ),
        )
    }
}

private fun Long.incrementedIdentity(): Long {
    check(this < Long.MAX_VALUE) { "Synchronization identity exhausted" }
    return this + 1
}
