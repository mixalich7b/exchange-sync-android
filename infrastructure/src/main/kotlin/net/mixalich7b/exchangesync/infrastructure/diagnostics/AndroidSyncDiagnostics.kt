package net.mixalich7b.exchangesync.infrastructure.diagnostics

import java.util.concurrent.ConcurrentHashMap
import net.mixalich7b.exchangesync.core.sync.SyncDiagnosticEvent
import net.mixalich7b.exchangesync.core.sync.SyncDiagnosticKind
import net.mixalich7b.exchangesync.core.sync.SyncDiagnosticsPort
import net.mixalich7b.exchangesync.core.sync.SyncFence

public class AndroidSyncDiagnostics : SyncDiagnosticsPort {
    private val diagnostics = DeviceDiagnostics(AndroidLogcatDiagnosticSink())
    private val operations = ConcurrentHashMap<SyncFence, DiagnosticOperation>()

    override fun record(
        event: SyncDiagnosticEvent,
        throwable: Throwable?,
    ) {
        val operation =
            operations.computeIfAbsent(event.fence) { fence ->
                diagnostics.operation(
                    DiagnosticOperationKind.SYNCHRONIZATION,
                    fence.generation,
                    fence.runToken,
                )
            }
        diagnostics.emit(
            DeviceDiagnosticEvent(
                severity = event.kind.severity(),
                component = DiagnosticComponent.SYNCHRONIZATION,
                stage = event.kind.stage(),
                operation = operation,
                trigger = event.trigger?.code,
                phase = event.phase?.code,
                attempt = event.attempt,
                reasonCode = event.failureKind?.name,
                failureCategory = event.problem?.name,
                checkpointOutcome = event.checkpointOutcome?.let { outcome -> CheckpointOutcome.valueOf(outcome.name) },
                capacityKind =
                    event.capacityKind?.let { kind -> DiagnosticCapacityKind.valueOf(kind.name) },
                capacityCommand =
                    event.capacityKind?.let { DiagnosticActiveSyncCommand.SYNC },
                capacityOutcome =
                    event.capacityOutcome?.let { outcome -> DiagnosticCapacityOutcome.valueOf(outcome.name) },
                capacityProblem =
                    event.capacityKind?.let {
                        when (event.problem) {
                            net.mixalich7b.exchangesync.core.sync.SyncProblem.CALENDAR_PROVIDER ->
                                DiagnosticCapacityProblem.CALENDAR_PROVIDER
                            else -> DiagnosticCapacityProblem.PROTOCOL_DATA
                        }
                    },
                windowSize = event.windowSize,
                reducedWindowSize = event.reducedWindowSize,
                outcome = event.outcome,
                throwable = throwable,
            ),
        )
        if (event.kind in terminalKinds) operations.remove(event.fence)
    }

    private fun SyncDiagnosticKind.severity(): DiagnosticSeverity =
        when (this) {
            SyncDiagnosticKind.UNEXPECTED_EXCEPTION,
            SyncDiagnosticKind.BLOCK,
            -> DiagnosticSeverity.ERROR
            SyncDiagnosticKind.REMOTE_FAILURE,
            SyncDiagnosticKind.LOCAL_FAILURE,
            SyncDiagnosticKind.CHECKPOINT_FAILURE,
            SyncDiagnosticKind.RETRY,
            SyncDiagnosticKind.RESET,
            SyncDiagnosticKind.CAPACITY,
            SyncDiagnosticKind.WINDOW_REDUCTION,
            -> DiagnosticSeverity.WARN
            else -> DiagnosticSeverity.INFO
        }

    private fun SyncDiagnosticKind.stage(): DiagnosticStage =
        when (this) {
            SyncDiagnosticKind.START -> DiagnosticStage.START
            SyncDiagnosticKind.PHASE -> DiagnosticStage.PHASE
            SyncDiagnosticKind.CHECKPOINT -> DiagnosticStage.CHECKPOINT
            SyncDiagnosticKind.REMOTE_FAILURE,
            SyncDiagnosticKind.LOCAL_FAILURE,
            SyncDiagnosticKind.UNEXPECTED_EXCEPTION,
            -> DiagnosticStage.FAILURE
            SyncDiagnosticKind.CHECKPOINT_FAILURE -> DiagnosticStage.CHECKPOINT
            SyncDiagnosticKind.RETRY -> DiagnosticStage.RETRY
            SyncDiagnosticKind.RESET -> DiagnosticStage.RESET
            SyncDiagnosticKind.CAPACITY -> DiagnosticStage.PROVIDER_BATCH
            SyncDiagnosticKind.WINDOW_REDUCTION -> DiagnosticStage.WINDOW_REDUCTION
            SyncDiagnosticKind.BLOCK -> DiagnosticStage.BLOCK
            SyncDiagnosticKind.OBSOLETE -> DiagnosticStage.OBSOLETE
            SyncDiagnosticKind.CANCELLATION -> DiagnosticStage.CANCELLATION
            SyncDiagnosticKind.COMPLETE -> DiagnosticStage.COMPLETE
        }

    private companion object {
        val terminalKinds =
            setOf(
                SyncDiagnosticKind.BLOCK,
                SyncDiagnosticKind.OBSOLETE,
                SyncDiagnosticKind.CANCELLATION,
                SyncDiagnosticKind.COMPLETE,
            )
    }
}
