package net.mixalich7b.exchangesync.infrastructure.activesync

import android.content.Context
import net.mixalich7b.exchangesync.core.sync.RemoteCalendarPort
import net.mixalich7b.exchangesync.core.sync.RemotePageOutcome
import net.mixalich7b.exchangesync.core.sync.SyncPageRequest
import net.mixalich7b.exchangesync.core.sync.SyncPhase

public class AndroidActiveSyncRemoteCalendar(context: Context) : RemoteCalendarPort {
    private val delegate: RemoteCalendarPort = AndroidActiveSyncProcessRuntime(context).remoteCalendar

    override suspend fun fetchPage(request: SyncPageRequest): RemotePageOutcome = delegate.fetchPage(request)

    override suspend fun fetchPage(
        request: SyncPageRequest,
        reportPhase: suspend (SyncPhase) -> Unit,
    ): RemotePageOutcome = delegate.fetchPage(request, reportPhase)
}
