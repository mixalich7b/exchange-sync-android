package net.mixalich7b.exchangesync.core.sync

public fun interface RequestSynchronizationAction {
    public suspend fun execute(): SyncRunRequest
}

public class RequestSynchronizationNow(
    private val stateRepository: SyncStateRepository,
    private val scheduler: SyncSchedulerPort,
    private val problems: SyncProblemReporterPort,
) : RequestSynchronizationAction {
    override suspend fun execute(): SyncRunRequest {
        lateinit var result: SyncRunRequest
        var clearsTerminalProblem = false
        stateRepository.update { current ->
            clearsTerminalProblem = current.problem != null
            SyncStateTransitions.requestRun(current, SyncTrigger.MANUAL)
                .also { transition -> result = transition }
                .state
        }
        if (result is SyncRunRequest.Queued) {
            val state = result.state
            if (clearsTerminalProblem) problems.clear(state.generation)
            scheduler.enqueueExecution(state.generation, state.runToken)
        }
        return result
    }
}
