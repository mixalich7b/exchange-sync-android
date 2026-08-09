package net.mixalich7b.exchangesync.core.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public class SynchronizationMutationLock {
    private val mutex = Mutex()

    public suspend fun <T> withLock(action: suspend () -> T): T = mutex.withLock { action() }
}
