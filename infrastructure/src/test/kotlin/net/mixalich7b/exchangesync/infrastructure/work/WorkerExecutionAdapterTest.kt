package net.mixalich7b.exchangesync.infrastructure.work

import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import net.mixalich7b.exchangesync.core.sync.ExecuteSynchronizationAction
import net.mixalich7b.exchangesync.core.sync.RequestPeriodicSynchronizationAction
import net.mixalich7b.exchangesync.core.sync.SyncFence
import net.mixalich7b.exchangesync.core.sync.SyncRunRequest
import net.mixalich7b.exchangesync.core.sync.SyncSliceOutcome
import net.mixalich7b.exchangesync.core.sync.SyncState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WorkerExecutionAdapterTest {
    @Test
    fun `execution worker forwards the complete fence and retries only transient outcome`() = runTest {
        val fences = mutableListOf<SyncFence>()
        val outcomes = ArrayDeque(listOf(SyncSliceOutcome.Retry, SyncSliceOutcome.Blocked(net.mixalich7b.exchangesync.core.sync.SyncProblem.TLS)))
        val adapter =
            SynchronizationWorkerAdapter(
                ExecuteSynchronizationAction { fence ->
                    fences += fence
                    outcomes.removeFirst()
                },
            )

        val retry = adapter.execute(WorkFenceInput(7, 9))
        val critical = adapter.execute(WorkFenceInput(7, 9))

        assertEquals(listOf(SyncFence(7, 9), SyncFence(7, 9)), fences)
        assertEquals(WorkerResult.RETRY, retry)
        assertEquals(WorkerResult.SUCCESS, critical)
    }

    @Test
    fun `invalid worker input fails without invoking business actions`() = runTest {
        var executions = 0
        val execution =
            SynchronizationWorkerAdapter(
                ExecuteSynchronizationAction {
                    executions += 1
                    SyncSliceOutcome.Completed
                },
            )
        val periodic =
            PeriodicWorkerAdapter(
                RequestPeriodicSynchronizationAction {
                    executions += 1
                    SyncRunRequest.Ignored(SyncState.initial())
                },
            )

        assertEquals(WorkerResult.FAILURE, execution.execute(WorkFenceInput(-1, 2)))
        assertEquals(WorkerResult.FAILURE, execution.execute(WorkFenceInput(1, null)))
        assertEquals(WorkerResult.FAILURE, periodic.execute(-1))
        assertEquals(0, executions)
    }

    @Test
    fun `periodic worker forwards its persisted generation and always remains periodic`() = runTest {
        val generations = mutableListOf<Long>()
        val adapter =
            PeriodicWorkerAdapter(
                RequestPeriodicSynchronizationAction { generation ->
                    generations += generation
                    SyncRunRequest.Ignored(SyncState.initial())
                },
            )

        val outcome = adapter.execute(18)

        assertEquals(WorkerResult.SUCCESS, outcome)
        assertEquals(listOf(18L), generations)
    }

    @Test
    fun `worker stop cancellation propagates out of the adapter`() {
        val adapter =
            SynchronizationWorkerAdapter(
                ExecuteSynchronizationAction { throw CancellationException("worker stopped") },
            )

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.test.runTest { adapter.execute(WorkFenceInput(1, 2)) }
        }
    }

    @Test
    fun `unexpected execution failure remains eligible for WorkManager retry`() = runTest {
        val adapter =
            SynchronizationWorkerAdapter(
                ExecuteSynchronizationAction { throw IllegalStateException("durable recovery unavailable") },
            )

        assertEquals(WorkerResult.RETRY, adapter.execute(WorkFenceInput(1, 2)))
    }
}
