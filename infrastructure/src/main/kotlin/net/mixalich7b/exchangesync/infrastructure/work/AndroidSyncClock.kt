package net.mixalich7b.exchangesync.infrastructure.work

import android.os.SystemClock
import net.mixalich7b.exchangesync.core.sync.SyncClock

public object AndroidSyncClock : SyncClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}
