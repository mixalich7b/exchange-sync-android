package net.mixalich7b.exchangesync.infrastructure.work

internal enum class UniqueWorkReconciliation {
    UPDATE,
    REPLACE,
    KEEP,
    APPEND_OR_REPLACE,
}

internal enum class BackoffKind {
    EXPONENTIAL,
}

internal data class WorkFenceInput(
    val generation: Long,
    val runToken: Long? = null,
)

internal data class PeriodicWorkSpec(
    val uniqueName: String,
    val intervalMinutes: Long,
    val requiresNetwork: Boolean,
    val input: WorkFenceInput,
    val reconciliation: UniqueWorkReconciliation,
    val remainsEligibleAfterPersistentFailure: Boolean,
)

internal data class ExecutionWorkSpec(
    val uniqueName: String,
    val requiresNetwork: Boolean,
    val input: WorkFenceInput,
    val reconciliation: UniqueWorkReconciliation,
    val backoffKind: BackoffKind,
    val initialBackoffSeconds: Long,
    val transientAttemptBudget: Int,
)

internal object WorkSchedulingPolicy {
    fun periodic(generation: Long): PeriodicWorkSpec {
        require(generation >= 0)
        return PeriodicWorkSpec(
            uniqueName = PERIODIC_WORK_NAME,
            intervalMinutes = PERIODIC_INTERVAL_MINUTES,
            requiresNetwork = true,
            input = WorkFenceInput(generation),
            reconciliation = UniqueWorkReconciliation.UPDATE,
            remainsEligibleAfterPersistentFailure = true,
        )
    }

    fun execution(
        generation: Long,
        runToken: Long,
    ): ExecutionWorkSpec = execution(generation, runToken, UniqueWorkReconciliation.REPLACE)

    fun recovery(
        generation: Long,
        runToken: Long,
    ): ExecutionWorkSpec = execution(generation, runToken, UniqueWorkReconciliation.KEEP)

    fun continuation(
        generation: Long,
        runToken: Long,
    ): ExecutionWorkSpec = execution(generation, runToken, UniqueWorkReconciliation.APPEND_OR_REPLACE)

    private fun execution(
        generation: Long,
        runToken: Long,
        reconciliation: UniqueWorkReconciliation,
    ): ExecutionWorkSpec {
        require(generation >= 0 && runToken >= 0)
        return ExecutionWorkSpec(
            uniqueName = EXECUTION_WORK_NAME,
            requiresNetwork = true,
            input = WorkFenceInput(generation, runToken),
            reconciliation = reconciliation,
            backoffKind = BackoffKind.EXPONENTIAL,
            initialBackoffSeconds = INITIAL_BACKOFF_SECONDS,
            transientAttemptBudget = TRANSIENT_ATTEMPT_BUDGET,
        )
    }

    const val PERIODIC_WORK_NAME: String = "exchange-calendar-periodic"
    const val EXECUTION_WORK_NAME: String = "exchange-calendar-execution"
    const val PERIODIC_INTERVAL_MINUTES: Long = 15
    const val INITIAL_BACKOFF_SECONDS: Long = 30
    const val TRANSIENT_ATTEMPT_BUDGET: Int = 5
}
