package net.mixalich7b.exchangesync.core.sync

public enum class SyncDiagnosticKind {
    START,
    PHASE,
    REMOTE_FAILURE,
    LOCAL_FAILURE,
    CHECKPOINT,
    CHECKPOINT_FAILURE,
    RETRY,
    RESET,
    CAPACITY,
    WINDOW_REDUCTION,
    BLOCK,
    OBSOLETE,
    CANCELLATION,
    COMPLETE,
    UNEXPECTED_EXCEPTION,
}

public enum class SyncCheckpointOutcome {
    COMMITTED,
    SKIPPED,
    FAILED,
}

public enum class SyncCapacityKind {
    CALENDAR_PROVIDER_TRANSACTION,
}

public enum class SyncCapacityOutcome {
    WINDOW_REDUCTION,
    MINIMUM_WINDOW_BLOCK,
}

public data class SyncDiagnosticEvent(
    public val kind: SyncDiagnosticKind,
    public val fence: SyncFence,
    public val trigger: SyncTrigger? = null,
    public val phase: SyncPhase? = null,
    public val attempt: Int? = null,
    public val failureKind: SyncFailureKind? = null,
    public val problem: SyncProblem? = null,
    public val checkpointOutcome: SyncCheckpointOutcome? = null,
    public val capacityKind: SyncCapacityKind? = null,
    public val capacityOutcome: SyncCapacityOutcome? = null,
    public val windowSize: Int? = null,
    public val reducedWindowSize: Int? = null,
    public val outcome: String? = null,
)

public fun interface SyncDiagnosticsPort {
    public fun record(
        event: SyncDiagnosticEvent,
        throwable: Throwable?,
    )
}

public object NoOpSyncDiagnostics : SyncDiagnosticsPort {
    override fun record(
        event: SyncDiagnosticEvent,
        throwable: Throwable?,
    ): Unit = Unit
}
