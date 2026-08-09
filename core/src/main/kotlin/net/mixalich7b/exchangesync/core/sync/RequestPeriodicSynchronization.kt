package net.mixalich7b.exchangesync.core.sync

public fun interface RequestPeriodicSynchronizationAction {
    public suspend fun execute(expectedGeneration: Long): SyncRunRequest
}

public class RequestPeriodicSynchronization(
    private val stateRepository: SyncStateRepository,
    private val scheduler: SyncSchedulerPort,
) : RequestPeriodicSynchronizationAction {
    override suspend fun execute(expectedGeneration: Long): SyncRunRequest {
        lateinit var result: SyncRunRequest
        stateRepository.update { current ->
            result =
                if (current.generation != expectedGeneration) {
                    SyncRunRequest.Ignored(current)
                } else if (current.phase == SyncPhase.QUEUED) {
                    SyncRunRequest.Queued(current)
                } else if (
                    current.phase == SyncPhase.BLOCKED &&
                    current.problem != SyncProblem.TRANSIENT_EXHAUSTED &&
                    !current.calendarCleanupPending
                ) {
                    SyncRunRequest.Ignored(current)
                } else {
                    SyncStateTransitions.requestRun(current, SyncTrigger.PERIODIC)
                }
            result.state
        }
        if (result is SyncRunRequest.Queued) {
            val queued = result.state
            scheduler.enqueueExecution(queued.generation, queued.runToken)
        }
        return result
    }
}
