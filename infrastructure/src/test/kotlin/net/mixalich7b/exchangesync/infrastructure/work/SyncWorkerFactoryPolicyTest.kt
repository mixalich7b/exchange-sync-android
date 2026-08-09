package net.mixalich7b.exchangesync.infrastructure.work

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SyncWorkerFactoryPolicyTest {
    @Test
    fun `factory routes only the two application worker class names`() {
        assertEquals(
            SyncWorkerKind.PERIODIC_TRIGGER,
            SyncWorkerFactoryPolicy.kind(PeriodicSyncTriggerWorker::class.java.name),
        )
        assertEquals(
            SyncWorkerKind.EXECUTION,
            SyncWorkerFactoryPolicy.kind(SynchronizationExecutionWorker::class.java.name),
        )
        assertNull(SyncWorkerFactoryPolicy.kind("example.UnrelatedWorker"))
    }
}
