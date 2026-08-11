package net.mixalich7b.exchangesync.infrastructure.activesync

import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.sync.ActiveSyncVersion
import net.mixalich7b.exchangesync.core.sync.SyncFence
import okhttp3.HttpUrl
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperation

internal data class ActiveSyncLiveCapability(
    val terminalEndpoint: HttpUrl,
    val version: ActiveSyncVersion,
    val supportedVersions: Set<ActiveSyncVersion> = setOf(version),
) {
    init {
        require(version in supportedVersions)
    }
}

internal data class ActiveSyncPreparedFolder(
    val fence: SyncFence,
    val version: ActiveSyncVersion,
    val terminalEndpoint: HttpUrl,
    val folderSyncKey: String,
    val primaryCalendarId: String,
) {
    init {
        require(folderSyncKey.isNotBlank())
        require(primaryCalendarId.isNotBlank())
    }
}

internal class ActiveSyncRequestPacer(
    private val minimumIntervalNanos: Long = 2_000_000_000L,
    private val nanoTime: () -> Long = System::nanoTime,
    private val waitMillis: suspend (Long) -> Unit = { millis -> delay(millis) },
) {
    private val mutex = Mutex()
    private var lastCompletionNanos: Long? = null

    init {
        require(minimumIntervalNanos > 0)
    }

    suspend fun <T> exchange(block: suspend () -> T): T {
        return exchange(beforeDispatch = { true }, block = block)
    }

    suspend fun <T> exchange(
        beforeDispatch: suspend () -> Boolean,
        block: suspend () -> T,
    ): T {
        mutex.lock()
        try {
            awaitMinimumInterval()
            if (!beforeDispatch()) throw ObsoleteActiveSyncSynchronizationException()
            return try {
                block()
            } finally {
                lastCompletionNanos = nanoTime()
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun awaitMinimumInterval() {
        val completion = lastCompletionNanos ?: return
        while (true) {
            val remainingNanos = minimumIntervalNanos - (nanoTime() - completion)
            if (remainingNanos <= 0) return
            waitMillis((remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
        }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND: Long = 1_000_000L
    }
}

internal fun interface ActiveSyncSynchronizationFenceValidator {
    suspend fun isCurrent(operation: DiagnosticOperation): Boolean
}

internal object AlwaysCurrentActiveSyncSynchronizationFence : ActiveSyncSynchronizationFenceValidator {
    override suspend fun isCurrent(operation: DiagnosticOperation): Boolean = true
}

internal class ObsoleteActiveSyncSynchronizationException :
    CancellationException("Synchronization fence became obsolete before request dispatch")

internal class ActiveSyncProfileSession(
    val requestPacer: ActiveSyncRequestPacer = ActiveSyncRequestPacer(),
) {
    val cookieJar = InMemoryCookieJar()

    @Volatile
    private var capability: ActiveSyncLiveCapability? = null

    @Volatile
    private var preparedFolder: ActiveSyncPreparedFolder? = null

    fun liveCapability(): ActiveSyncLiveCapability? = capability

    fun recordCapability(value: ActiveSyncLiveCapability) {
        capability = value
    }

    fun preparedFolder(
        fence: SyncFence,
        version: ActiveSyncVersion,
    ): ActiveSyncPreparedFolder? =
        preparedFolder?.takeIf { prepared -> prepared.fence == fence && prepared.version == version }

    fun recordPreparedFolder(value: ActiveSyncPreparedFolder) {
        preparedFolder = value
    }

    fun hasPreparedFolder(): Boolean = preparedFolder != null

    fun clearPreparedFolder() {
        preparedFolder = null
    }
}

internal class ActiveSyncProfileSessionRegistry(
    private val maximumProfiles: Int = 4,
    private val pacerFactory: () -> ActiveSyncRequestPacer = { ActiveSyncRequestPacer() },
) {
    private val lock = Any()
    private val sessions = LinkedHashMap<ConnectionProfile, ActiveSyncProfileSession>(maximumProfiles, 0.75f, true)

    init {
        require(maximumProfiles > 0)
    }

    fun acquire(profile: ConnectionProfile): ActiveSyncProfileSession =
        synchronized(lock) {
            sessions[profile]
                ?: ActiveSyncProfileSession(pacerFactory()).also { session ->
                    sessions[profile] = session
                    if (sessions.size > maximumProfiles) {
                        val eldest = sessions.entries.iterator()
                        eldest.next()
                        eldest.remove()
                    }
                }
        }
}
