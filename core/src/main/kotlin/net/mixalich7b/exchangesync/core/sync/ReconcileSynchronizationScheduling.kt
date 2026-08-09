package net.mixalich7b.exchangesync.core.sync

import kotlinx.coroutines.CancellationException

public class ReconcileSynchronizationScheduling(
    private val stateRepository: SyncStateRepository,
    private val scheduler: SyncSchedulerPort,
    private val problems: SyncProblemReporterPort,
    private val resumePendingCalendarCleanup: suspend () -> Unit = {},
) {
    public suspend fun execute() {
        var recovered = stateRepository.load()
        while (true) {
            try {
                if (!recovered.enabled || recovered.phase == SyncPhase.DISABLED) {
                    problems.clear(recovered.generation)
                    scheduler.cancelAll()
                    val current = stateRepository.load()
                    if (!recovered.hasSameSchedulingIdentity(current)) {
                        recovered = current
                        continue
                    }
                    if (current.calendarCleanupPending) resumePendingCalendarCleanup()
                    return
                }
                scheduler.schedulePeriodic(recovered.generation)
                var current = stateRepository.load()
                if (!recovered.hasSameSchedulingIdentity(current)) {
                    recovered = current
                    continue
                }
                if (current.phase == SyncPhase.QUEUED) {
                    scheduler.reconcileExecution(current.generation, current.runToken)
                    current = stateRepository.load()
                    if (!recovered.hasSameSchedulingIdentity(current)) {
                        recovered = current
                        continue
                    }
                }
                val recoveredProblem = current.problem
                if (current.phase == SyncPhase.BLOCKED && recoveredProblem != null) {
                    problems.show(current.generation, recoveredProblem)
                }
                return
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                val current = stateRepository.load()
                if (!recovered.hasSameSchedulingIdentity(current)) {
                    recovered = current
                    continue
                }
                val blocked =
                    stateRepository.update { latest ->
                        if (latest.enabled && latest.generation == recovered.generation) {
                            latest.copy(
                                phase = SyncPhase.BLOCKED,
                                currentTrigger = null,
                                followUpRequested = false,
                                problem = SyncProblem.BACKGROUND_SCHEDULING,
                            )
                        } else {
                            latest
                        }
                    }
                if (
                    blocked.enabled &&
                    blocked.generation == recovered.generation &&
                    blocked.problem == SyncProblem.BACKGROUND_SCHEDULING
                ) {
                    problems.show(blocked.generation, SyncProblem.BACKGROUND_SCHEDULING)
                }
                return
            }
        }
    }
}

private fun SyncState.hasSameSchedulingIdentity(other: SyncState): Boolean =
    generation == other.generation &&
        enabled == other.enabled &&
        (phase == SyncPhase.DISABLED) == (other.phase == SyncPhase.DISABLED)
