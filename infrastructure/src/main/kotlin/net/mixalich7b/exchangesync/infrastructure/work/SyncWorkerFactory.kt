package net.mixalich7b.exchangesync.infrastructure.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import net.mixalich7b.exchangesync.core.sync.ExecuteSynchronizationAction
import net.mixalich7b.exchangesync.core.sync.RequestPeriodicSynchronizationAction

internal enum class SyncWorkerKind {
    PERIODIC_TRIGGER,
    EXECUTION,
}

internal object SyncWorkerFactoryPolicy {
    fun kind(workerClassName: String): SyncWorkerKind? =
        when (workerClassName) {
            PeriodicSyncTriggerWorker::class.java.name -> SyncWorkerKind.PERIODIC_TRIGGER
            SynchronizationExecutionWorker::class.java.name -> SyncWorkerKind.EXECUTION
            else -> null
        }
}

public class SyncWorkerFactory(
    private val requestPeriodicSynchronization: RequestPeriodicSynchronizationAction,
    private val executeSynchronization: ExecuteSynchronizationAction,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        when (SyncWorkerFactoryPolicy.kind(workerClassName)) {
            SyncWorkerKind.PERIODIC_TRIGGER ->
                PeriodicSyncTriggerWorker(appContext, workerParameters, requestPeriodicSynchronization)
            SyncWorkerKind.EXECUTION ->
                SynchronizationExecutionWorker(appContext, workerParameters, executeSynchronization)
            null -> null
        }
}
