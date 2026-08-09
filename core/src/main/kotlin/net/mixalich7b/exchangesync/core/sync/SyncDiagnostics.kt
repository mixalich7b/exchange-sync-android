package net.mixalich7b.exchangesync.core.sync

public enum class SyncDiagnosticKind {
    START,
    PHASE,
    REMOTE_FAILURE,
    LOCAL_FAILURE,
    CHECKPOINT_FAILURE,
    RETRY,
    RESET,
    WINDOW_REDUCTION,
    BLOCK,
    OBSOLETE,
    CANCELLATION,
    COMPLETE,
    UNEXPECTED_EXCEPTION,
}

public data class SyncDiagnosticEvent(
    public val kind: SyncDiagnosticKind,
    public val fence: SyncFence,
    public val trigger: SyncTrigger? = null,
    public val phase: SyncPhase? = null,
    public val attempt: Int? = null,
    public val failureKind: SyncFailureKind? = null,
    public val problem: SyncProblem? = null,
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
