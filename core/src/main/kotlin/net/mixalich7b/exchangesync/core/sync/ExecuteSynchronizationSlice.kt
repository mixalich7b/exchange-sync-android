package net.mixalich7b.exchangesync.core.sync

import kotlinx.coroutines.CancellationException
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.connection.ConnectionProfileRepository

public fun interface ExecuteSynchronizationAction {
    public suspend fun execute(fence: SyncFence): SyncSliceOutcome
}

public class ExecuteSynchronizationSlice(
    private val stateRepository: SyncStateRepository,
    private val profileRepository: ConnectionProfileRepository,
    private val remoteCalendar: RemoteCalendarPort,
    private val ownedCalendar: OwnedCalendarPort,
    private val scheduler: SyncSchedulerPort,
    private val permissions: SyncPermissionPort,
    private val problems: SyncProblemReporterPort,
    private val clock: SyncClock,
    private val limits: SyncSliceLimits = SyncSliceLimits(),
    private val diagnostics: SyncDiagnosticsPort = NoOpSyncDiagnostics,
) : ExecuteSynchronizationAction {
    override suspend fun execute(fence: SyncFence): SyncSliceOutcome {
        return try {
            executeSlice(fence).also { outcome ->
                recordTerminalOutcome(fence, outcome)
            }
        } catch (cancellation: CancellationException) {
            diagnostics.record(
                SyncDiagnosticEvent(SyncDiagnosticKind.CANCELLATION, fence, outcome = "cancelled"),
                cancellation,
            )
            throw cancellation
        } catch (failure: Exception) {
            diagnostics.record(
                SyncDiagnosticEvent(SyncDiagnosticKind.UNEXPECTED_EXCEPTION, fence, outcome = "recover"),
                failure,
            )
            recoverUnexpectedFailure(fence).also { outcome ->
                recordTerminalOutcome(fence, outcome)
            }
        }
    }

    private fun recordTerminalOutcome(
        fence: SyncFence,
        outcome: SyncSliceOutcome,
    ) {
        val kind =
            when (outcome) {
                is SyncSliceOutcome.Blocked,
                SyncSliceOutcome.PermissionRequired,
                -> SyncDiagnosticKind.BLOCK
                SyncSliceOutcome.Obsolete -> SyncDiagnosticKind.OBSOLETE
                SyncSliceOutcome.Cancelled -> SyncDiagnosticKind.CANCELLATION
                else -> SyncDiagnosticKind.COMPLETE
            }
        val problem =
            when (outcome) {
                is SyncSliceOutcome.Blocked -> outcome.problem
                SyncSliceOutcome.PermissionRequired -> SyncProblem.CALENDAR_PERMISSION
                else -> null
            }
        diagnostics.record(
            SyncDiagnosticEvent(
                kind = kind,
                fence = fence,
                problem = problem,
                outcome = outcome.javaClass.simpleName,
            ),
            null,
        )
    }

    private suspend fun executeSlice(fence: SyncFence): SyncSliceOutcome {
        val initial = stateRepository.load()
        diagnostics.record(
            SyncDiagnosticEvent(
                SyncDiagnosticKind.START,
                fence,
                trigger = initial.currentTrigger,
                phase = initial.phase,
                attempt = initial.consecutiveTransientAttempts,
            ),
            null,
        )
        if (!SyncStateTransitions.mayPerformSideEffect(initial, fence)) return SyncSliceOutcome.Obsolete
        if (!permissions.hasCalendarAccess()) {
            block(fence, SyncProblem.CALENDAR_PERMISSION)
            return SyncSliceOutcome.PermissionRequired
        }
        val profile = profileRepository.load() ?: return SyncSliceOutcome.Obsolete
        if (stateRepository.load().calendarCleanupPending) return resumePendingCalendarReset(fence, profile)
        val startedAt = clock.elapsedRealtimeMillis()
        var completedPages = 0

        while (true) {
            if (!isCurrent(fence)) return SyncSliceOutcome.Obsolete
            val current = stateRepository.load()
            if (!SyncStateTransitions.mayPerformSideEffect(current, fence)) return SyncSliceOutcome.Obsolete
            val deviceId = current.deviceId ?: return block(fence, SyncProblem.PROTOCOL_DATA)
            val remoteOutcome =
                remoteCalendar.fetchPage(
                    SyncPageRequest(
                        profile = profile,
                        fence = fence,
                        deviceId = deviceId,
                        checkpoints = current.checkpoints,
                        fullSyncRequired = current.fullSyncRequired,
                    ),
                ) { phase ->
                    diagnostics.record(
                        SyncDiagnosticEvent(SyncDiagnosticKind.PHASE, fence, phase = phase),
                        null,
                    )
                    transitionPhase(fence, phase)
                }
            if (!isCurrent(fence)) return SyncSliceOutcome.Obsolete

            when (remoteOutcome) {
                is RemotePageOutcome.Failure -> return handleRemoteFailure(fence, profile, remoteOutcome)
                is RemotePageOutcome.Page -> {
                    val outcome = applyPage(fence, profile, remoteOutcome.page)
                    if (outcome != null) return outcome
                    completedPages += 1

                    if (!remoteOutcome.page.moreAvailable) return complete(fence)
                    val elapsed = clock.elapsedRealtimeMillis() - startedAt
                    if (completedPages >= limits.maxPages || elapsed >= limits.maxElapsedMillis) {
                        return continueLater(fence)
                    }
                }
            }
        }
    }

    private suspend fun recoverUnexpectedFailure(fence: SyncFence): SyncSliceOutcome {
        val current = stateRepository.load()
        if (!SyncStateTransitions.mayPerformSideEffect(current, fence)) return SyncSliceOutcome.Obsolete
        val persistedProblem = current.problem
        if (current.phase == SyncPhase.BLOCKED && persistedProblem != null) {
            return SyncSliceOutcome.Blocked(persistedProblem)
        }
        return recordTransientFailure(fence)
    }

    private suspend fun applyPage(
        fence: SyncFence,
        profile: ConnectionProfile,
        page: RemoteCalendarPage,
    ): SyncSliceOutcome? {
        transitionPhase(fence, SyncPhase.APPLYING) ?: return SyncSliceOutcome.Obsolete
        if (!isCurrent(fence)) return SyncSliceOutcome.Obsolete
        val localOutcome = ownedCalendar.applyPage(fence, page)
        if (localOutcome != LocalPageOutcome.Applied) {
            diagnostics.record(
                SyncDiagnosticEvent(
                    SyncDiagnosticKind.LOCAL_FAILURE,
                    fence,
                    problem = (localOutcome as? LocalPageOutcome.Failed)?.problem,
                    outcome = localOutcome.javaClass.simpleName,
                ),
                null,
            )
            recordCheckpoint(fence, SyncCheckpointOutcome.SKIPPED)
        }
        return when (localOutcome) {
            LocalPageOutcome.Applied -> {
                if (!isCurrent(fence)) {
                    recordCheckpoint(fence, SyncCheckpointOutcome.SKIPPED)
                    SyncSliceOutcome.Obsolete
                } else {
                    val committed =
                        try {
                            stateRepository.update { current ->
                                if (SyncStateTransitions.mayPerformSideEffect(current, fence)) {
                                    current.copy(
                                        checkpoints = page.nextCheckpoints,
                                        consecutiveTransientAttempts = 0,
                                    )
                                } else {
                                    current
                                }
                            }
                        } catch (failure: Exception) {
                            diagnostics.record(
                                SyncDiagnosticEvent(
                                    SyncDiagnosticKind.CHECKPOINT_FAILURE,
                                    fence,
                                    checkpointOutcome = SyncCheckpointOutcome.FAILED,
                                ),
                                failure,
                            )
                            throw failure
                        }
                    if (SyncStateTransitions.mayPerformSideEffect(committed, fence)) {
                        recordCheckpoint(fence, SyncCheckpointOutcome.COMMITTED)
                        null
                    } else {
                        recordCheckpoint(fence, SyncCheckpointOutcome.SKIPPED)
                        SyncSliceOutcome.Obsolete
                    }
                }
            }
            LocalPageOutcome.Obsolete -> SyncSliceOutcome.Obsolete
            LocalPageOutcome.FullResetRequired -> requestFullReset(fence, profile)
            LocalPageOutcome.TransactionTooLarge -> reduceWindowAndContinue(fence, SyncProblem.CALENDAR_PROVIDER)
            is LocalPageOutcome.Failed -> block(fence, localOutcome.problem)
        }
    }

    private fun recordCheckpoint(
        fence: SyncFence,
        outcome: SyncCheckpointOutcome,
    ) {
        diagnostics.record(
            SyncDiagnosticEvent(
                kind = SyncDiagnosticKind.CHECKPOINT,
                fence = fence,
                checkpointOutcome = outcome,
            ),
            null,
        )
    }

    private suspend fun requestFullReset(
        fence: SyncFence,
        profile: ConnectionProfile,
    ): SyncSliceOutcome {
        diagnostics.record(SyncDiagnosticEvent(SyncDiagnosticKind.RESET, fence), null)
        val reset =
            stateRepository.update { latest ->
                if (SyncStateTransitions.mayPerformSideEffect(latest, fence)) {
                    latest.copy(
                        fullSyncRequired = true,
                        phase = SyncPhase.QUEUED,
                        calendarCleanupPending = true,
                    )
                } else {
                    latest
                }
            }
        if (!SyncStateTransitions.mayPerformSideEffect(reset, fence)) return SyncSliceOutcome.Obsolete
        return resumePendingCalendarReset(fence, profile)
    }

    private suspend fun handleRemoteFailure(
        fence: SyncFence,
        profile: ConnectionProfile,
        failure: RemotePageOutcome.Failure,
    ): SyncSliceOutcome {
        diagnostics.record(
            SyncDiagnosticEvent(
                SyncDiagnosticKind.REMOTE_FAILURE,
                fence,
                failureKind = failure.kind,
                problem = failure.problem,
            ),
            null,
        )
        return when (failure.kind) {
            SyncFailureKind.TRANSIENT -> recordTransientFailure(fence)
            SyncFailureKind.INVALID_KEY -> resetInvalidKeyOrBlock(fence, profile)
            SyncFailureKind.FULL_RESET_REQUIRED -> requestFullReset(fence, profile)
            SyncFailureKind.WINDOW_TOO_LARGE -> reduceWindowAndContinue(fence, SyncProblem.PROTOCOL_DATA)
            SyncFailureKind.CRITICAL -> block(fence, failure.problem ?: SyncProblem.PROTOCOL_DATA)
        }
    }

    private suspend fun recordTransientFailure(fence: SyncFence): SyncSliceOutcome {
        val current = stateRepository.load()
        if (!SyncStateTransitions.mayPerformSideEffect(current, fence)) return SyncSliceOutcome.Obsolete
        val attempts = current.consecutiveTransientAttempts + 1
        diagnostics.record(
            SyncDiagnosticEvent(
                SyncDiagnosticKind.RETRY,
                fence,
                attempt = attempts,
                outcome = if (attempts < MAX_TRANSIENT_ATTEMPTS) "retry" else "exhausted",
            ),
            null,
        )
        return if (attempts < MAX_TRANSIENT_ATTEMPTS) {
            stateRepository.update { latest ->
                if (SyncStateTransitions.mayPerformSideEffect(latest, fence)) {
                    latest.copy(
                        phase = SyncPhase.QUEUED,
                        consecutiveTransientAttempts = attempts,
                    )
                } else {
                    latest
                }
            }
            SyncSliceOutcome.Retry
        } else {
            stateRepository.update { latest ->
                if (SyncStateTransitions.mayPerformSideEffect(latest, fence)) {
                    latest.copy(consecutiveTransientAttempts = MAX_TRANSIENT_ATTEMPTS)
                } else {
                    latest
                }
            }
            block(fence, SyncProblem.TRANSIENT_EXHAUSTED)
        }
    }

    private suspend fun resetInvalidKeyOrBlock(
        fence: SyncFence,
        profile: ConnectionProfile,
    ): SyncSliceOutcome {
        val current = stateRepository.load()
        if (!SyncStateTransitions.mayPerformSideEffect(current, fence)) return SyncSliceOutcome.Obsolete
        if (current.invalidKeyRecoveryUsed) return block(fence, SyncProblem.REPEATED_INVALID_KEY)

        val reset =
            stateRepository.update { latest ->
                if (SyncStateTransitions.mayPerformSideEffect(latest, fence)) {
                    latest.copy(
                        fullSyncRequired = true,
                        invalidKeyRecoveryUsed = true,
                        phase = SyncPhase.QUEUED,
                        calendarCleanupPending = true,
                    )
                } else {
                    latest
                }
            }
        if (!SyncStateTransitions.mayPerformSideEffect(reset, fence)) return SyncSliceOutcome.Obsolete
        return resumePendingCalendarReset(fence, profile)
    }

    private suspend fun resumePendingCalendarReset(
        fence: SyncFence,
        profile: ConnectionProfile,
    ): SyncSliceOutcome {
        if (!isCurrent(fence)) return SyncSliceOutcome.Obsolete
        remoteCalendar.invalidatePreparedState(profile, fence)
        when (val cleanup = ownedCalendar.deleteOwnedCalendar(fence, OwnedCalendarCleanupTrigger.FULL_RESET)) {
            OwnedCalendarCleanupOutcome.Completed -> Unit
            OwnedCalendarCleanupOutcome.Obsolete -> return SyncSliceOutcome.Obsolete
            is OwnedCalendarCleanupOutcome.Failed -> {
                if (!isCurrent(fence)) return SyncSliceOutcome.Obsolete
                return block(fence, cleanup.problem)
            }
        }
        val reset =
            stateRepository.update { latest ->
                if (SyncStateTransitions.mayPerformSideEffect(latest, fence) && latest.calendarCleanupPending) {
                    latest.copy(
                        checkpoints = SyncCheckpoints.EMPTY,
                        phase = SyncPhase.QUEUED,
                        currentTrigger = SyncTrigger.CONTINUATION,
                        problem = null,
                        calendarCleanupPending = false,
                    )
                } else {
                    latest
                }
            }
        if (!SyncStateTransitions.mayPerformSideEffect(reset, fence) || reset.calendarCleanupPending) {
            return SyncSliceOutcome.Obsolete
        }
        scheduler.enqueueContinuation(fence.generation, fence.runToken)
        return SyncSliceOutcome.Continued
    }

    private suspend fun reduceWindowAndContinue(
        fence: SyncFence,
        minimumWindowProblem: SyncProblem,
    ): SyncSliceOutcome {
        val current = stateRepository.load()
        if (!SyncStateTransitions.mayPerformSideEffect(current, fence)) return SyncSliceOutcome.Obsolete
        if (minimumWindowProblem == SyncProblem.CALENDAR_PROVIDER) {
            val canReduce = current.checkpoints.windowSize > 1
            diagnostics.record(
                SyncDiagnosticEvent(
                    SyncDiagnosticKind.CAPACITY,
                    fence,
                    problem = SyncProblem.CALENDAR_PROVIDER,
                    capacityKind = SyncCapacityKind.CALENDAR_PROVIDER_TRANSACTION,
                    capacityOutcome =
                        if (canReduce) {
                            SyncCapacityOutcome.WINDOW_REDUCTION
                        } else {
                            SyncCapacityOutcome.MINIMUM_WINDOW_BLOCK
                        },
                    windowSize = current.checkpoints.windowSize,
                    reducedWindowSize =
                        (current.checkpoints.windowSize / 2)
                            .coerceAtLeast(1)
                            .takeIf { canReduce },
                ),
                null,
            )
        }
        if (current.checkpoints.windowSize == 1) return block(fence, minimumWindowProblem)
        val reducedWindowSize = (current.checkpoints.windowSize / 2).coerceAtLeast(1)
        diagnostics.record(
            SyncDiagnosticEvent(
                SyncDiagnosticKind.WINDOW_REDUCTION,
                fence,
                problem = minimumWindowProblem,
                windowSize = current.checkpoints.windowSize,
                reducedWindowSize = reducedWindowSize,
            ),
            null,
        )
        stateRepository.update { latest ->
            if (SyncStateTransitions.mayPerformSideEffect(latest, fence)) {
                latest.copy(
                    checkpoints =
                        latest.checkpoints.copy(
                            windowSize = reducedWindowSize,
                        ),
                    phase = SyncPhase.QUEUED,
                )
            } else {
                latest
            }
        }
        if (!isCurrent(fence)) return SyncSliceOutcome.Obsolete
        scheduler.enqueueContinuation(fence.generation, fence.runToken)
        return SyncSliceOutcome.Continued
    }

    private suspend fun continueLater(fence: SyncFence): SyncSliceOutcome {
        val queued = transitionPhase(fence, SyncPhase.QUEUED) ?: return SyncSliceOutcome.Obsolete
        scheduler.enqueueContinuation(queued.generation, queued.runToken)
        return SyncSliceOutcome.Continued
    }

    private suspend fun complete(fence: SyncFence): SyncSliceOutcome {
        var needsFollowUp = false
        val completed =
            stateRepository.update { latest ->
                if (SyncStateTransitions.mayPerformSideEffect(latest, fence)) {
                    needsFollowUp = latest.followUpRequested
                    latest.copy(
                        fullSyncRequired = false,
                        invalidKeyRecoveryUsed = false,
                        phase = if (needsFollowUp) SyncPhase.QUEUED else SyncPhase.IDLE,
                        currentTrigger = if (needsFollowUp) SyncTrigger.CONTINUATION else null,
                        followUpRequested = false,
                        consecutiveTransientAttempts = 0,
                        lastSuccessfulEpochMillis = clock.nowEpochMillis(),
                        problem = null,
                    )
                } else {
                    latest
                }
            }
        if (!SyncStateTransitions.mayPerformSideEffect(completed, fence)) return SyncSliceOutcome.Obsolete
        problems.clear(fence.generation)
        return if (needsFollowUp) {
            scheduler.enqueueContinuation(fence.generation, fence.runToken)
            SyncSliceOutcome.Continued
        } else {
            SyncSliceOutcome.Completed
        }
    }

    private suspend fun block(
        fence: SyncFence,
        problem: SyncProblem,
    ): SyncSliceOutcome.Blocked {
        val blocked =
            stateRepository.update { current ->
                if (SyncStateTransitions.mayPerformSideEffect(current, fence)) {
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
        if (blocked.generation == fence.generation && blocked.problem == problem) {
            problems.show(fence.generation, problem)
        }
        return SyncSliceOutcome.Blocked(problem)
    }

    private suspend fun transitionPhase(
        fence: SyncFence,
        phase: SyncPhase,
    ): SyncState? {
        val updated =
            stateRepository.update { current ->
                if (SyncStateTransitions.mayPerformSideEffect(current, fence)) {
                    current.copy(phase = phase)
                } else {
                    current
                }
            }
        return updated.takeIf { state -> SyncStateTransitions.mayPerformSideEffect(state, fence) }
    }

    private suspend fun isCurrent(fence: SyncFence): Boolean =
        SyncStateTransitions.mayPerformSideEffect(stateRepository.load(), fence)

    private companion object {
        const val MAX_TRANSIENT_ATTEMPTS: Int = 5
    }
}
