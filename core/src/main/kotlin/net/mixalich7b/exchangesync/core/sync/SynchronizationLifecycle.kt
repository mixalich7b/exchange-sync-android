package net.mixalich7b.exchangesync.core.sync

import kotlinx.coroutines.CancellationException

public fun interface ProfileSynchronizationActivator {
    public suspend fun activateProfile(profile: net.mixalich7b.exchangesync.core.connection.ConnectionProfile): SyncLifecycleOutcome
}

public fun interface ProfileActivationCommitter {
    public suspend fun commitActivatedProfile(
        profile: net.mixalich7b.exchangesync.core.connection.ConnectionProfile,
    ): SyncState
}

public class ProfileActivationPersistenceException(cause: Throwable) : RuntimeException(cause)

public interface SynchronizationLifecycleActions {
    public suspend fun cancel(): SyncCancellationOutcome

    public suspend fun disable(): SyncDisableOutcome

    public suspend fun enable(): SyncLifecycleOutcome

    public suspend fun onCalendarPermissionResult(): SyncLifecycleOutcome

    public suspend fun onNotificationPermissionResult()
}

public class SynchronizationLifecycle(
    private val stateRepository: SyncStateRepository,
    private val scheduler: SyncSchedulerPort,
    private val permissions: SyncPermissionPort,
    private val ownedCalendar: OwnedCalendarPort,
    private val problems: SyncProblemReporterPort,
    private val profileActivationCommitter: ProfileActivationCommitter? = null,
    private val mutationLock: SynchronizationMutationLock = SynchronizationMutationLock(),
) : ProfileSynchronizationActivator,
    SynchronizationLifecycleActions {
    override suspend fun activateProfile(
        profile: net.mixalich7b.exchangesync.core.connection.ConnectionProfile,
    ): SyncLifecycleOutcome {
        val committer = profileActivationCommitter
        val activated =
            if (committer == null) {
                startNewGeneration(SyncTrigger.PROFILE_ACTIVATION)
            } else {
                try {
                    committer.commitActivatedProfile(profile)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    throw ProfileActivationPersistenceException(failure)
                }
            }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            configureActivatedGeneration(activated, permissionAlreadyChecked = false)
        }
    }

    override suspend fun enable(): SyncLifecycleOutcome {
        val current = stateRepository.load()
        if (current.enabled) return SyncLifecycleOutcome.Ignored
        val activated = startNewGeneration(SyncTrigger.ENABLE)
        return configureActivatedGeneration(activated, permissionAlreadyChecked = false)
    }

    override suspend fun onCalendarPermissionResult(): SyncLifecycleOutcome {
        val current = stateRepository.load()
        if (!current.enabled && current.calendarCleanupPending) {
            if (!permissions.hasCalendarAccess()) {
                markCleanupPending(current, SyncProblem.CALENDAR_PERMISSION)
                return SyncLifecycleOutcome.PermissionRequired(current.generation)
            }
            if (!ownedCalendar.deleteOwnedCalendar(current.fence)) {
                markCleanupPending(current, SyncProblem.CALENDAR_PROVIDER)
                return SyncLifecycleOutcome.Blocked(current.generation, SyncProblem.CALENDAR_PROVIDER)
            }
            val cleaned =
                stateRepository.update { latest ->
                    if (!latest.enabled && latest.generation == current.generation && latest.calendarCleanupPending) {
                        latest.copy(calendarCleanupPending = false, problem = null)
                    } else {
                        latest
                    }
                }
            if (!cleaned.enabled && !cleaned.calendarCleanupPending && cleaned.generation == current.generation) {
                problems.clear(cleaned.generation)
            }
            return SyncLifecycleOutcome.Ignored
        }
        if (!current.enabled) return SyncLifecycleOutcome.Ignored
        recordNotificationPermission(current.generation)
        if (!permissions.hasCalendarAccess()) {
            return blockGeneration(current, SyncProblem.CALENDAR_PERMISSION)
                .let { SyncLifecycleOutcome.PermissionRequired(current.generation) }
        }
        val resumed =
            stateRepository.update { latest ->
                if (latest.enabled && latest.generation == current.generation) {
                    latest.copy(
                        fullSyncRequired = true,
                        checkpoints = SyncCheckpoints.EMPTY,
                        phase = SyncPhase.QUEUED,
                        currentTrigger = latest.currentTrigger ?: SyncTrigger.CONTINUATION,
                        problem = null,
                        calendarCleanupPending = true,
                    )
                } else {
                    latest
                }
            }
        if (!resumed.enabled || resumed.generation != current.generation) return SyncLifecycleOutcome.Ignored
        return configureActivatedGeneration(resumed, permissionAlreadyChecked = true)
    }

    override suspend fun onNotificationPermissionResult() {
        val generation = stateRepository.load().generation
        recordNotificationPermission(generation)
        val current = stateRepository.load()
        val problem = current.problem
        if (
            current.generation == generation &&
            problem != null &&
            permissions.hasNotificationAccess()
        ) {
            problems.show(generation, problem)
        }
    }

    override suspend fun cancel(): SyncCancellationOutcome {
        val before = stateRepository.load()
        if (!before.enabled || !before.phase.isCancellable) return SyncCancellationOutcome.Ignored

        val cancelling =
            mutationLock.withLock {
                stateRepository.update { current ->
                    if (
                        current.generation != before.generation ||
                        current.runToken != before.runToken ||
                        !current.phase.isCancellable
                    ) {
                        current
                    } else {
                        current.copy(
                            runToken = current.runToken.incremented(),
                            phase = SyncPhase.CANCELLING,
                            currentTrigger = null,
                            followUpRequested = false,
                        )
                    }
                }
            }
        if (cancelling.phase != SyncPhase.CANCELLING) return SyncCancellationOutcome.Ignored

        scheduler.cancelExecution()
        stateRepository.update { current ->
            if (
                current.enabled &&
                current.generation == cancelling.generation &&
                current.runToken == cancelling.runToken &&
                current.phase == SyncPhase.CANCELLING
            ) {
                current.copy(phase = SyncPhase.IDLE)
            } else {
                current
            }
        }
        return SyncCancellationOutcome.Cancelled
    }

    override suspend fun disable(): SyncDisableOutcome {
        val before = stateRepository.load()
        if (!before.enabled && !before.calendarCleanupPending) return SyncDisableOutcome.Ignored

        val disabled =
            mutationLock.withLock {
                stateRepository.update { current ->
                    current.copy(
                        enabled = false,
                        generation = current.generation.incremented(),
                        runToken = current.runToken.incremented(),
                        fullSyncRequired = false,
                        invalidKeyRecoveryUsed = false,
                        checkpoints = SyncCheckpoints.EMPTY,
                        phase = SyncPhase.DISABLED,
                        currentTrigger = null,
                        followUpRequested = false,
                        consecutiveTransientAttempts = 0,
                        problem = null,
                        calendarCleanupPending = true,
                    )
                }
            }

        scheduler.cancelAll()
        problems.clear(disabled.generation)
        if (!permissions.hasCalendarAccess()) {
            markCleanupPending(disabled, SyncProblem.CALENDAR_PERMISSION)
            return SyncDisableOutcome.CleanupPending(SyncProblem.CALENDAR_PERMISSION)
        }
        if (!ownedCalendar.deleteOwnedCalendar(disabled.fence)) {
            markCleanupPending(disabled, SyncProblem.CALENDAR_PROVIDER)
            return SyncDisableOutcome.CleanupPending(SyncProblem.CALENDAR_PROVIDER)
        }
        val cleaned =
            stateRepository.update { current ->
                if (
                    current.generation == disabled.generation &&
                    !current.enabled &&
                    current.calendarCleanupPending
                ) {
                    current.copy(calendarCleanupPending = false, problem = null)
                } else {
                    current
                }
            }
        if (cleaned.enabled || cleaned.generation != disabled.generation || cleaned.calendarCleanupPending) {
            return SyncDisableOutcome.Ignored
        }
        return SyncDisableOutcome.Disabled
    }

    private suspend fun startNewGeneration(trigger: SyncTrigger): SyncState =
        mutationLock.withLock {
            stateRepository.update { current -> SyncStateTransitions.activate(current, trigger) }
        }

    private suspend fun configureActivatedGeneration(
        activated: SyncState,
        permissionAlreadyChecked: Boolean,
    ): SyncLifecycleOutcome =
        try {
            scheduler.cancelAll()
            problems.clear(activated.generation)
            recordNotificationPermission(activated.generation)
            if (!permissionAlreadyChecked && !permissions.hasCalendarAccess()) {
                blockGeneration(activated, SyncProblem.CALENDAR_PERMISSION)
                SyncLifecycleOutcome.PermissionRequired(activated.generation)
            } else if (!isGenerationCurrent(activated)) {
                SyncLifecycleOutcome.Ignored
            } else if (!ownedCalendar.deleteOwnedCalendar(activated.fence)) {
                if (isGenerationCurrent(activated)) {
                    blockGeneration(activated, SyncProblem.CALENDAR_PROVIDER)
                } else {
                    SyncLifecycleOutcome.Ignored
                }
            } else {
                val cleaned =
                    stateRepository.update { latest ->
                        if (
                            SyncStateTransitions.mayPerformSideEffect(latest, activated.fence) &&
                            latest.calendarCleanupPending
                        ) {
                            latest.copy(calendarCleanupPending = false)
                        } else {
                            latest
                        }
                    }
                if (
                    !SyncStateTransitions.mayPerformSideEffect(cleaned, activated.fence) ||
                    cleaned.calendarCleanupPending
                ) {
                    SyncLifecycleOutcome.Ignored
                } else {
                    scheduler.schedulePeriodic(activated.generation)
                    if (!isGenerationCurrent(activated)) {
                        SyncLifecycleOutcome.Ignored
                    } else {
                        scheduler.enqueueExecution(activated.generation, activated.runToken)
                        SyncLifecycleOutcome.Scheduled(activated.generation)
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            blockGeneration(activated, SyncProblem.BACKGROUND_SCHEDULING)
        }

    private suspend fun recordNotificationPermission(generation: Long) {
        val denied = !permissions.hasNotificationAccess()
        val current = stateRepository.load()
        if (current.generation != generation || current.notificationPermissionDenied == denied) return
        stateRepository.update { latest ->
            if (latest.generation == generation) {
                latest.copy(notificationPermissionDenied = denied)
            } else {
                latest
            }
        }
    }

    private suspend fun blockGeneration(
        activated: SyncState,
        problem: SyncProblem,
    ): SyncLifecycleOutcome.Blocked {
        val blocked = stateRepository.update { current ->
            if (current.generation == activated.generation && current.enabled) {
                current.copy(
                    phase = SyncPhase.BLOCKED,
                    currentTrigger = null,
                    followUpRequested = false,
                    problem = problem,
                )
            } else {
                current
            }
        }
        if (blocked.enabled && blocked.generation == activated.generation && blocked.problem == problem) {
            problems.show(activated.generation, problem)
        }
        return SyncLifecycleOutcome.Blocked(activated.generation, problem)
    }

    private suspend fun markCleanupPending(
        disabled: SyncState,
        problem: SyncProblem,
    ) {
        stateRepository.update { current ->
            if (current.generation == disabled.generation && !current.enabled) {
                current.copy(calendarCleanupPending = true, problem = problem)
            } else {
                current
            }
        }
        problems.show(disabled.generation, problem)
    }

    private suspend fun isGenerationCurrent(expected: SyncState): Boolean {
        val current = stateRepository.load()
        return current.enabled && current.generation == expected.generation
    }
}

private fun Long.incremented(): Long {
    check(this < Long.MAX_VALUE) { "Synchronization identity exhausted" }
    return this + 1
}
