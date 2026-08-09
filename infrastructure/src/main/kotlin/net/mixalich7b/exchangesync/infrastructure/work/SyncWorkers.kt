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
import net.mixalich7b.exchangesync.infrastructure.diagnostics.AndroidLogcatDiagnosticSink
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnosticEvent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnostics
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticComponent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperation
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperationKind
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticSeverity
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticStage

public class PeriodicSyncTriggerWorker(
    appContext: Context,
    parameters: WorkerParameters,
    private val requestSynchronization: RequestPeriodicSynchronizationAction,
) : CoroutineWorker(appContext, parameters) {
    private val diagnostics = WorkerDiagnostics.logcat()

    override suspend fun doWork(): ListenableWorker.Result {
        val generation = inputData.getLong(WorkerInput.GENERATION, INVALID_ID)
        return PeriodicWorkerAdapter(requestSynchronization, diagnostics).execute(generation).toWorkManagerResult()
    }
}

public class SynchronizationExecutionWorker(
    appContext: Context,
    parameters: WorkerParameters,
    private val executeSynchronization: ExecuteSynchronizationAction,
) : CoroutineWorker(appContext, parameters) {
    private val diagnostics = WorkerDiagnostics.logcat()

    override suspend fun doWork(): ListenableWorker.Result {
        val generation = inputData.getLong(WorkerInput.GENERATION, INVALID_ID)
        val runToken = inputData.getLong(WorkerInput.RUN_TOKEN, INVALID_ID)
        return SynchronizationWorkerAdapter(executeSynchronization, diagnostics)
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
    private val diagnostics: WorkerDiagnostics = WorkerDiagnostics(),
) {
    suspend fun execute(generation: Long): WorkerResult {
        val operation = diagnostics.input(DiagnosticOperationKind.PERIODIC_WORKER, generation, null)
        if (generation < 0) return WorkerResult.FAILURE.also { diagnostics.result(operation, it, "invalid_input") }
        return try {
            action.execute(generation)
            WorkerResult.SUCCESS.also { diagnostics.result(operation, it, "scheduled") }
        } catch (cancellation: CancellationException) {
            diagnostics.cancellation(operation, cancellation)
            throw cancellation
        } catch (failure: Exception) {
            diagnostics.failure(operation, "unexpected_exception", failure)
            throw failure
        }
    }
}

internal class SynchronizationWorkerAdapter(
    private val action: ExecuteSynchronizationAction,
    private val diagnostics: WorkerDiagnostics = WorkerDiagnostics(),
) {
    suspend fun execute(input: WorkFenceInput): WorkerResult {
        val runToken = input.runToken
        val operation = diagnostics.input(DiagnosticOperationKind.EXECUTION_WORKER, input.generation, runToken)
        if (input.generation < 0 || runToken == null || runToken < 0) {
            return WorkerResult.FAILURE.also { diagnostics.result(operation, it, "invalid_input") }
        }
        return try {
            val outcome = action.execute(SyncFence(input.generation, runToken))
            WorkerResultPolicy.map(outcome).also { result ->
                diagnostics.result(operation, result, outcome.javaClass.simpleName)
            }
        } catch (cancellation: CancellationException) {
            diagnostics.cancellation(operation, cancellation)
            throw cancellation
        } catch (failure: Exception) {
            diagnostics.failure(operation, "unexpected_exception", failure)
            WorkerResult.RETRY.also { diagnostics.result(operation, it, "unexpected_exception") }
        }
    }
}

internal class WorkerDiagnostics(
    private val diagnostics: DeviceDiagnostics = DeviceDiagnostics(),
) {
    fun input(
        kind: DiagnosticOperationKind,
        generation: Long,
        runToken: Long?,
    ): DiagnosticOperation =
        diagnostics.operation(kind, generation.takeIf { it >= 0 }, runToken?.takeIf { it >= 0 }).also { operation ->
            diagnostics.emit(
                DeviceDiagnosticEvent(
                    DiagnosticSeverity.INFO,
                    DiagnosticComponent.WORKER,
                    DiagnosticStage.INPUT,
                    operation,
                    outcome = "received",
                ),
            )
        }

    fun result(
        operation: DiagnosticOperation,
        result: WorkerResult,
        outcome: String,
    ) {
        diagnostics.emit(
            DeviceDiagnosticEvent(
                severity = if (result == WorkerResult.RETRY) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,
                component = DiagnosticComponent.WORKER,
                stage = DiagnosticStage.RESULT,
                operation = operation,
                reasonCode = result.name,
                outcome = outcome,
            ),
        )
    }

    fun failure(
        operation: DiagnosticOperation,
        outcome: String,
        throwable: Throwable,
    ) {
        diagnostics.emit(
            DeviceDiagnosticEvent(
                DiagnosticSeverity.ERROR,
                DiagnosticComponent.WORKER,
                DiagnosticStage.FAILURE,
                operation,
                outcome = outcome,
                throwable = throwable,
            ),
        )
    }

    fun cancellation(
        operation: DiagnosticOperation,
        throwable: Throwable,
    ) {
        diagnostics.emit(
            DeviceDiagnosticEvent(
                DiagnosticSeverity.INFO,
                DiagnosticComponent.WORKER,
                DiagnosticStage.CANCELLATION,
                operation,
                outcome = "cancelled",
                throwable = throwable,
            ),
        )
    }

    companion object {
        fun logcat(): WorkerDiagnostics = WorkerDiagnostics(DeviceDiagnostics(AndroidLogcatDiagnosticSink()))
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
