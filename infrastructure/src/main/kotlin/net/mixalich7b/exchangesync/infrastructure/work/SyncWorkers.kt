package net.mixalich7b.exchangesync.infrastructure.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import net.mixalich7b.exchangesync.core.sync.ExecuteSynchronizationAction
import net.mixalich7b.exchangesync.core.sync.RequestPeriodicSynchronizationAction
import net.mixalich7b.exchangesync.core.sync.SyncFence
import net.mixalich7b.exchangesync.core.sync.SyncSliceOutcome

public class PeriodicSyncTriggerWorker(
    appContext: Context,
    parameters: WorkerParameters,
    private val requestSynchronization: RequestPeriodicSynchronizationAction,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): ListenableWorker.Result {
        val generation = inputData.getLong(WorkerInput.GENERATION, INVALID_ID)
        return PeriodicWorkerAdapter(requestSynchronization).execute(generation).toWorkManagerResult()
    }
}

public class SynchronizationExecutionWorker(
    appContext: Context,
    parameters: WorkerParameters,
    private val executeSynchronization: ExecuteSynchronizationAction,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): ListenableWorker.Result {
        val generation = inputData.getLong(WorkerInput.GENERATION, INVALID_ID)
        val runToken = inputData.getLong(WorkerInput.RUN_TOKEN, INVALID_ID)
        return SynchronizationWorkerAdapter(executeSynchronization)
            .execute(WorkFenceInput(generation, runToken))
            .toWorkManagerResult()
    }
}

internal enum class WorkerResult {
    SUCCESS,
    RETRY,
    FAILURE,
}

internal class PeriodicWorkerAdapter(
    private val action: RequestPeriodicSynchronizationAction,
) {
    suspend fun execute(generation: Long): WorkerResult {
        if (generation < 0) return WorkerResult.FAILURE
        action.execute(generation)
        return WorkerResult.SUCCESS
    }
}

internal class SynchronizationWorkerAdapter(
    private val action: ExecuteSynchronizationAction,
) {
    suspend fun execute(input: WorkFenceInput): WorkerResult {
        val runToken = input.runToken
        if (input.generation < 0 || runToken == null || runToken < 0) return WorkerResult.FAILURE
        return try {
            WorkerResultPolicy.map(action.execute(SyncFence(input.generation, runToken)))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            WorkerResult.RETRY
        }
    }
}

internal object WorkerResultPolicy {
    fun map(outcome: SyncSliceOutcome): WorkerResult =
        when (outcome) {
            SyncSliceOutcome.Retry -> WorkerResult.RETRY
            is SyncSliceOutcome.Blocked,
            SyncSliceOutcome.Cancelled,
            SyncSliceOutcome.Completed,
            SyncSliceOutcome.Continued,
            SyncSliceOutcome.Obsolete,
            SyncSliceOutcome.PermissionRequired,
            -> WorkerResult.SUCCESS
        }
}

private fun WorkerResult.toWorkManagerResult(): ListenableWorker.Result =
    when (this) {
        WorkerResult.SUCCESS -> ListenableWorker.Result.success()
        WorkerResult.RETRY -> ListenableWorker.Result.retry()
        WorkerResult.FAILURE -> ListenableWorker.Result.failure()
    }

private const val INVALID_ID: Long = -1
