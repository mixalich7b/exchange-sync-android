package net.mixalich7b.exchangesync.infrastructure.work

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkManagerSyncSchedulerTest {
    @Test
    fun `scheduler delegates unique periodic execution continuation and cancellation policies`() = runTest {
        val gateway = RecordingWorkSchedulerGateway()
        val scheduler = WorkManagerSyncScheduler(gateway)

        scheduler.schedulePeriodic(7)
        scheduler.enqueueExecution(7, 11)
        scheduler.reconcileExecution(7, 11)
        scheduler.enqueueContinuation(7, 11)
        scheduler.cancelExecution()
        scheduler.cancelAll()

        assertEquals(
            listOf(
                "periodic:exchange-calendar-periodic:7:update",
                "execution:exchange-calendar-execution:7:11:replace",
                "execution:exchange-calendar-execution:7:11:keep",
                "execution:exchange-calendar-execution:7:11:append_or_replace",
                "cancel:exchange-calendar-execution",
                "cancel:exchange-calendar-execution",
                "cancel:exchange-calendar-periodic",
            ),
            gateway.trace,
        )
    }

    private class RecordingWorkSchedulerGateway : WorkSchedulerGateway {
        val trace = mutableListOf<String>()

        override suspend fun enqueuePeriodic(spec: PeriodicWorkSpec) {
            trace += "periodic:${spec.uniqueName}:${spec.input.generation}:${spec.reconciliation.name.lowercase()}"
        }

        override suspend fun enqueueExecution(spec: ExecutionWorkSpec) {
            trace +=
                "execution:${spec.uniqueName}:${spec.input.generation}:${spec.input.runToken}:" +
                    spec.reconciliation.name.lowercase()
        }

        override suspend fun cancelUnique(uniqueName: String) {
            trace += "cancel:$uniqueName"
        }
    }
}
