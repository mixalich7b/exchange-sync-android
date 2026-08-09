package net.mixalich7b.exchangesync.core.sync

import kotlinx.coroutines.flow.Flow

public interface SyncStateRepository {
    public val states: Flow<SyncState>

    public suspend fun load(): SyncState

    public suspend fun update(transform: (SyncState) -> SyncState): SyncState
}

public fun interface RemoteCalendarPort {
    public suspend fun fetchPage(request: SyncPageRequest): RemotePageOutcome

    public suspend fun fetchPage(
        request: SyncPageRequest,
        reportPhase: suspend (SyncPhase) -> Unit,
    ): RemotePageOutcome = fetchPage(request)
}

public interface OwnedCalendarPort {
    public suspend fun deleteOwnedCalendar(fence: SyncFence? = null): Boolean

    public suspend fun applyPage(
        fence: SyncFence,
        page: RemoteCalendarPage,
    ): LocalPageOutcome
}

public interface SyncSchedulerPort {
    public suspend fun cancelAll()

    public suspend fun schedulePeriodic(generation: Long)

    public suspend fun enqueueExecution(
        generation: Long,
        runToken: Long,
    )

    public suspend fun reconcileExecution(
        generation: Long,
        runToken: Long,
    )

    public suspend fun enqueueContinuation(
        generation: Long,
        runToken: Long,
    )

    public suspend fun cancelExecution()
}

public interface SyncPermissionPort {
    public fun hasCalendarAccess(): Boolean

    public fun hasNotificationAccess(): Boolean
}

public interface SyncProblemReporterPort {
    public suspend fun show(
        generation: Long,
        problem: SyncProblem,
    )

    public suspend fun clear(generation: Long)
}

public interface SyncClock {
    public fun nowEpochMillis(): Long

    public fun elapsedRealtimeMillis(): Long
}
