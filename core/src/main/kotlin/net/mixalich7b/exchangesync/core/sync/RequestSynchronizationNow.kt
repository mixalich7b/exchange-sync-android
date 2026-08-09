package net.mixalich7b.exchangesync.core.sync

public fun interface RequestSynchronizationAction {
    public suspend fun execute(): SyncRunRequest
}

public class RequestSynchronizationNow(
    private val stateRepository: SyncStateRepository,
    private val scheduler: SyncSchedulerPort,
) : RequestSynchronizationAction {
    override suspend fun execute(): SyncRunRequest {
        lateinit var result: SyncRunRequest
        stateRepository.update { current ->
            SyncStateTransitions.requestRun(current, SyncTrigger.MANUAL)
                .also { transition -> result = transition }
                .state
        }
        if (result is SyncRunRequest.Queued) {
            val state = result.state
            scheduler.enqueueExecution(state.generation, state.runToken)
        }
        return result
    }
}
