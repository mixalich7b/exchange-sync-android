package net.mixalich7b.exchangesync.infrastructure.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mixalich7b.exchangesync.core.sync.SyncSchedulerPort

internal interface WorkSchedulerGateway {
    suspend fun enqueuePeriodic(spec: PeriodicWorkSpec)

    suspend fun enqueueExecution(spec: ExecutionWorkSpec)

    suspend fun cancelUnique(uniqueName: String)
}

public class WorkManagerSyncScheduler internal constructor(
    private val gateway: WorkSchedulerGateway,
) : SyncSchedulerPort {
    public constructor(workManager: WorkManager) : this(AndroidWorkSchedulerGateway(workManager))

    public constructor(context: Context) : this(
        AndroidWorkSchedulerGateway { WorkManager.getInstance(context.applicationContext) },
    )

    override suspend fun cancelAll() {
        gateway.cancelUnique(WorkSchedulingPolicy.EXECUTION_WORK_NAME)
        gateway.cancelUnique(WorkSchedulingPolicy.PERIODIC_WORK_NAME)
    }

    override suspend fun schedulePeriodic(generation: Long) {
        gateway.enqueuePeriodic(WorkSchedulingPolicy.periodic(generation))
    }

    override suspend fun enqueueExecution(
        generation: Long,
        runToken: Long,
    ) {
        gateway.enqueueExecution(WorkSchedulingPolicy.execution(generation, runToken))
    }

    override suspend fun reconcileExecution(
        generation: Long,
        runToken: Long,
    ) {
        gateway.enqueueExecution(WorkSchedulingPolicy.recovery(generation, runToken))
    }

    override suspend fun enqueueContinuation(
        generation: Long,
        runToken: Long,
    ) {
        gateway.enqueueExecution(WorkSchedulingPolicy.continuation(generation, runToken))
    }

    override suspend fun cancelExecution() {
        gateway.cancelUnique(WorkSchedulingPolicy.EXECUTION_WORK_NAME)
    }
}

private class AndroidWorkSchedulerGateway(
    private val workManagerProvider: () -> WorkManager,
) : WorkSchedulerGateway {
    constructor(workManager: WorkManager) : this({ workManager })

    override suspend fun enqueuePeriodic(spec: PeriodicWorkSpec) {
        val request =
            PeriodicWorkRequest.Builder(
                PeriodicSyncTriggerWorker::class.java,
                spec.intervalMinutes,
                TimeUnit.MINUTES,
            ).setConstraints(networkConstraints(spec.requiresNetwork))
                .setInputData(workDataOf(WorkerInput.GENERATION to spec.input.generation))
                .build()
        withContext(Dispatchers.IO) {
            workManagerProvider().enqueueUniquePeriodicWork(
                spec.uniqueName,
                spec.reconciliation.toPeriodicPolicy(),
                request,
            ).result.get()
        }
    }

    override suspend fun enqueueExecution(spec: ExecutionWorkSpec) {
        val request =
            OneTimeWorkRequest.Builder(SynchronizationExecutionWorker::class.java)
                .setConstraints(networkConstraints(spec.requiresNetwork))
                .setInputData(
                    workDataOf(
                        WorkerInput.GENERATION to spec.input.generation,
                        WorkerInput.RUN_TOKEN to checkNotNull(spec.input.runToken),
                    ),
                ).setBackoffCriteria(
                    spec.backoffKind.toBackoffPolicy(),
                    spec.initialBackoffSeconds,
                    TimeUnit.SECONDS,
                ).build()
        withContext(Dispatchers.IO) {
            workManagerProvider().enqueueUniqueWork(
                spec.uniqueName,
                spec.reconciliation.toOneTimePolicy(),
                request,
            ).result.get()
        }
    }

    override suspend fun cancelUnique(uniqueName: String) {
        withContext(Dispatchers.IO) {
            workManagerProvider().cancelUniqueWork(uniqueName).result.get()
        }
    }

    private fun networkConstraints(required: Boolean): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(if (required) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()

    private fun UniqueWorkReconciliation.toPeriodicPolicy(): ExistingPeriodicWorkPolicy =
        when (this) {
            UniqueWorkReconciliation.UPDATE -> ExistingPeriodicWorkPolicy.UPDATE
            UniqueWorkReconciliation.REPLACE,
            UniqueWorkReconciliation.KEEP,
            UniqueWorkReconciliation.APPEND_OR_REPLACE,
            -> error("Invalid periodic reconciliation policy")
        }

    private fun UniqueWorkReconciliation.toOneTimePolicy(): ExistingWorkPolicy =
        when (this) {
            UniqueWorkReconciliation.REPLACE -> ExistingWorkPolicy.REPLACE
            UniqueWorkReconciliation.KEEP -> ExistingWorkPolicy.KEEP
            UniqueWorkReconciliation.APPEND_OR_REPLACE -> ExistingWorkPolicy.APPEND_OR_REPLACE
            UniqueWorkReconciliation.UPDATE -> error("Invalid one-time reconciliation policy")
        }

    private fun BackoffKind.toBackoffPolicy(): BackoffPolicy =
        when (this) {
            BackoffKind.EXPONENTIAL -> BackoffPolicy.EXPONENTIAL
        }
}

internal object WorkerInput {
    const val GENERATION: String = "generation"
    const val RUN_TOKEN: String = "run_token"
}
