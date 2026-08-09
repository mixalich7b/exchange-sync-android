package net.mixalich7b.exchangesync.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.connection.ConnectionProfileRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncUseCasesTest {
    @Test
    fun `startup reconciliation restores periodic and missing queued execution after process death`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository =
                FakeStateRepository(
                    idleState().copy(phase = SyncPhase.QUEUED, currentTrigger = SyncTrigger.PROFILE_ACTIVATION),
                    trace,
                )
            val scheduler = FakeScheduler(trace)

            ReconcileSynchronizationScheduling(stateRepository, scheduler, FakeProblems(trace)).execute()

            assertEquals(listOf("scheduler:periodic:2", "scheduler:recover:2:3"), trace.filter { it.startsWith("scheduler:") })
            assertEquals(listOf(SyncFence(2, 3)), scheduler.executions)
        }

    @Test
    fun `startup reconciliation repairs periodic and queued work when generation changes during scheduling`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(idleState(), trace)
            val replacement =
                idleState().copy(
                    generation = 3,
                    runToken = 4,
                    phase = SyncPhase.QUEUED,
                    currentTrigger = SyncTrigger.PROFILE_ACTIVATION,
                )
            var changed = false
            val scheduler =
                FakeScheduler(
                    trace = trace,
                    onSchedulePeriodic = {
                        if (!changed) {
                            changed = true
                            stateRepository.replace(replacement)
                        }
                    },
                )

            ReconcileSynchronizationScheduling(stateRepository, scheduler, FakeProblems(trace)).execute()

            assertEquals(
                listOf("scheduler:periodic:2", "scheduler:periodic:3", "scheduler:recover:3:4"),
                trace.filter { it.startsWith("scheduler:") },
            )
            assertEquals(listOf(SyncFence(3, 4)), scheduler.executions)
        }

    @Test
    fun `disabled startup reconciliation restores replacement generation after stale cancellation`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(disabledState(2, 3), trace)
            val replacement =
                idleState().copy(
                    generation = 3,
                    runToken = 4,
                    phase = SyncPhase.QUEUED,
                    currentTrigger = SyncTrigger.PROFILE_ACTIVATION,
                )
            val scheduler =
                FakeScheduler(
                    trace = trace,
                    onCancelAll = { stateRepository.replace(replacement) },
                )

            ReconcileSynchronizationScheduling(stateRepository, scheduler, FakeProblems(trace)).execute()

            assertEquals(
                listOf("scheduler:cancel-all", "scheduler:periodic:3", "scheduler:recover:3:4"),
                trace.filter { it.startsWith("scheduler:") },
            )
            assertEquals(listOf(SyncFence(3, 4)), scheduler.executions)
        }

    @Test
    fun `startup reconciliation cancels stale disabled work and persists enabled scheduling failure`() =
        runTest {
            val disabledTrace = mutableListOf<String>()
            ReconcileSynchronizationScheduling(
                FakeStateRepository(disabledState(3, 4), disabledTrace),
                FakeScheduler(disabledTrace),
                FakeProblems(disabledTrace),
            ).execute()
            assertEquals(listOf("scheduler:cancel-all"), disabledTrace.filter { it.startsWith("scheduler:") })

            val failureTrace = mutableListOf<String>()
            val enabled = FakeStateRepository(idleState(), failureTrace)
            ReconcileSynchronizationScheduling(
                enabled,
                FakeScheduler(failureTrace, scheduleFailure = IllegalStateException("WorkManager unavailable")),
                FakeProblems(failureTrace),
            ).execute()

            assertEquals(SyncPhase.BLOCKED, enabled.current.phase)
            assertEquals(SyncProblem.BACKGROUND_SCHEDULING, enabled.current.problem)
            assertEquals(listOf("problem:show:2:background_scheduling"), problemsShown(failureTrace))
        }

    @Test
    fun `startup reconciliation restores current blocked problem notification`() =
        runTest {
            val trace = mutableListOf<String>()
            val blocked =
                idleState().copy(
                    phase = SyncPhase.BLOCKED,
                    problem = SyncProblem.TLS,
                )

            ReconcileSynchronizationScheduling(
                FakeStateRepository(blocked, trace),
                FakeScheduler(trace),
                FakeProblems(trace),
            ).execute()

            assertEquals(listOf("scheduler:periodic:2"), trace.filter { it.startsWith("scheduler:") })
            assertEquals(listOf("problem:show:2:tls"), problemsShown(trace))
        }

    @Test
    fun `startup reconciliation resumes durable disabled calendar cleanup`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository =
                FakeStateRepository(
                    disabledState(3, 4).copy(calendarCleanupPending = true),
                    trace,
                )
            var cleanupResumptions = 0

            ReconcileSynchronizationScheduling(
                stateRepository,
                FakeScheduler(trace),
                FakeProblems(trace),
                resumePendingCalendarCleanup = { cleanupResumptions += 1 },
            ).execute()

            assertEquals(listOf("scheduler:cancel-all"), trace.filter { it.startsWith("scheduler:") })
            assertEquals(1, cleanupResumptions)
        }

    @Test
    fun `disabled startup clears stale notification even when work cancellation fails`() =
        runTest {
            val trace = mutableListOf<String>()

            ReconcileSynchronizationScheduling(
                FakeStateRepository(disabledState(3, 4), trace),
                FakeScheduler(trace, cancelAllFailure = IllegalStateException("WorkManager unavailable")),
                FakeProblems(trace),
            ).execute()

            assertEquals(
                listOf("problem:clear", "scheduler:cancel-all"),
                trace.filter { it == "problem:clear" || it.startsWith("scheduler:") },
            )
        }

    @Test
    fun `cancellation after atomic profile commit cannot abort cleanup and durable scheduling handoff`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(disabledState(4, 6), trace)
            val scheduler = FakeScheduler(trace)
            val lifecycle =
                SynchronizationLifecycle(
                    stateRepository = stateRepository,
                    scheduler = scheduler,
                    permissions = FakePermissions(calendar = true, notifications = true, trace),
                    ownedCalendar = FakeCalendar(trace),
                    problems = FakeProblems(trace),
                    profileActivationCommitter =
                        ProfileActivationCommitter {
                            val activated =
                                stateRepository.update { current ->
                                    SyncStateTransitions.activate(current, SyncTrigger.PROFILE_ACTIVATION)
                                }
                            currentCoroutineContext().cancel()
                            activated
                        },
                )

            val activation = launch { lifecycle.activateProfile(profile()) }
            activation.join()

            assertEquals(5, stateRepository.current.generation)
            assertEquals(SyncPhase.QUEUED, stateRepository.current.phase)
            assertEquals(listOf(SyncFence(5, 7)), scheduler.executions)
            assertTrue(trace.contains("calendar:delete"))
        }

    @Test
    fun `profile activation fences old work before cleanup and queues a full synchronization`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(disabledState(generation = 4, runToken = 6), trace)
            val scheduler = FakeScheduler(trace)
            val calendar = FakeCalendar(trace)
            val problems = FakeProblems(trace)
            val lifecycle =
                SynchronizationLifecycle(
                    stateRepository,
                    scheduler,
                    FakePermissions(calendar = true, notifications = true, trace),
                    calendar,
                    problems,
                )

            val outcome = lifecycle.activateProfile(profile())

            assertEquals(SyncLifecycleOutcome.Scheduled(5), outcome)
            assertEquals(listOf(SyncFence(5, 7)), calendar.deleteFences)
            assertEquals(
                listOf(
                    "state:queued",
                    "scheduler:cancel-all",
                    "problem:clear",
                    "permission:notification",
                    "permission:calendar",
                    "calendar:delete",
                    "state:queued",
                    "scheduler:periodic:5",
                    "scheduler:execute:5:7",
                ),
                trace,
            )
            assertEquals(
                SyncState.initial().copy(
                    enabled = true,
                    generation = 5,
                    runToken = 7,
                    fullSyncRequired = true,
                    phase = SyncPhase.QUEUED,
                    currentTrigger = SyncTrigger.PROFILE_ACTIVATION,
                ),
                stateRepository.current,
            )
        }

    @Test
    fun `notification denial is persisted but does not block profile synchronization`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(disabledState(4, 6), trace)
            val lifecycle =
                SynchronizationLifecycle(
                    stateRepository,
                    FakeScheduler(trace),
                    FakePermissions(calendar = true, notifications = false, trace),
                    FakeCalendar(trace),
                    FakeProblems(trace),
                )

            val outcome = lifecycle.activateProfile(profile())

            assertEquals(SyncLifecycleOutcome.Scheduled(5), outcome)
            assertTrue(stateRepository.current.notificationPermissionDenied)
            assertNull(stateRepository.current.problem)
        }

    @Test
    fun `calendar permission grant resumes pending full synchronization without another profile save`() =
        runTest {
            val trace = mutableListOf<String>()
            val blocked =
                disabledState(5, 7).copy(
                    enabled = true,
                    fullSyncRequired = true,
                    phase = SyncPhase.BLOCKED,
                    currentTrigger = SyncTrigger.PROFILE_ACTIVATION,
                    problem = SyncProblem.CALENDAR_PERMISSION,
                )
            val stateRepository = FakeStateRepository(blocked, trace)
            val scheduler = FakeScheduler(trace)
            val lifecycle =
                SynchronizationLifecycle(
                    stateRepository,
                    scheduler,
                    FakePermissions(calendar = true, notifications = true, trace),
                    FakeCalendar(trace),
                    FakeProblems(trace),
                )

            val outcome = lifecycle.onCalendarPermissionResult()

            assertEquals(SyncLifecycleOutcome.Scheduled(5), outcome)
            assertEquals(5, stateRepository.current.generation)
            assertEquals(7, stateRepository.current.runToken)
            assertEquals(SyncPhase.QUEUED, stateRepository.current.phase)
            assertTrue(stateRepository.current.fullSyncRequired)
            assertNull(stateRepository.current.problem)
            assertEquals(listOf(SyncFence(5, 7)), scheduler.executions)
        }

    @Test
    fun `calendar permission recovery clears incremental checkpoints before deleting the mirror`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository =
                FakeStateRepository(
                    idleState().copy(
                        phase = SyncPhase.BLOCKED,
                        problem = SyncProblem.CALENDAR_PERMISSION,
                        checkpoints = checkpoints("sync-42"),
                    ),
                    trace,
                )
            var checkpointsAtDelete: SyncCheckpoints? = null
            val calendar =
                FakeCalendar(trace, onDelete = { checkpointsAtDelete = stateRepository.current.checkpoints })
            val lifecycle =
                SynchronizationLifecycle(
                    stateRepository,
                    FakeScheduler(trace),
                    FakePermissions(calendar = true, notifications = true, trace),
                    calendar,
                    FakeProblems(trace),
                )

            val outcome = lifecycle.onCalendarPermissionResult()

            assertEquals(SyncLifecycleOutcome.Scheduled(2), outcome)
            assertEquals(SyncCheckpoints.EMPTY, checkpointsAtDelete)
            assertEquals(SyncCheckpoints.EMPTY, stateRepository.current.checkpoints)
            assertTrue(stateRepository.current.fullSyncRequired)
        }

    @Test
    fun `run now queues one execution while an active run only retains one follow-up`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(idleState(), trace)
            val scheduler = FakeScheduler(trace)
            val runNow = RequestSynchronizationNow(stateRepository, scheduler)

            val queued = runNow.execute()
            val coalesced = runNow.execute()

            assertTrue(queued is SyncRunRequest.Queued)
            assertTrue(coalesced is SyncRunRequest.Coalesced)
            assertEquals(4L, stateRepository.current.runToken)
            assertTrue(stateRepository.current.followUpRequested)
            assertEquals(
                listOf(
                    "state:queued",
                    "scheduler:execute:2:4",
                    "state:queued",
                ),
                trace,
            )
        }

    @Test
    fun `periodic trigger queues only its matching generation and reconciles queued unique work`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(idleState(), trace)
            val scheduler = FakeScheduler(trace)
            val periodic = RequestPeriodicSynchronization(stateRepository, scheduler)

            val obsolete = periodic.execute(expectedGeneration = 1)
            val queued = periodic.execute(expectedGeneration = 2)
            val reconciled = periodic.execute(expectedGeneration = 2)

            assertTrue(obsolete is SyncRunRequest.Ignored)
            assertTrue(queued is SyncRunRequest.Queued)
            assertEquals(SyncTrigger.PERIODIC, queued.state.currentTrigger)
            assertTrue(reconciled is SyncRunRequest.Queued)
            assertFalse(stateRepository.current.followUpRequested)
            assertEquals(listOf(SyncFence(2, 4), SyncFence(2, 4)), scheduler.executions)
        }

    @Test
    fun `periodic trigger reconciles persisted queued state but ignores non-retryable block`() =
        runTest {
            val trace = mutableListOf<String>()
            val scheduler = FakeScheduler(trace)
            val queuedRepository =
                FakeStateRepository(
                    idleState().copy(phase = SyncPhase.QUEUED, currentTrigger = SyncTrigger.MANUAL),
                    trace,
                )

            val reconciled = RequestPeriodicSynchronization(queuedRepository, scheduler).execute(2)

            assertTrue(reconciled is SyncRunRequest.Queued)
            assertEquals(listOf(SyncFence(2, 3)), scheduler.executions)
            val blockedRepository =
                FakeStateRepository(
                    idleState().copy(phase = SyncPhase.BLOCKED, problem = SyncProblem.ACCESS),
                    mutableListOf(),
                )
            val blockedScheduler = FakeScheduler(mutableListOf())

            val ignored = RequestPeriodicSynchronization(blockedRepository, blockedScheduler).execute(2)

            assertTrue(ignored is SyncRunRequest.Ignored)
            assertTrue(blockedScheduler.executions.isEmpty())
        }

    @Test
    fun `periodic trigger retries transient-exhausted state with a new run token`() =
        runTest {
            val scheduler = FakeScheduler(mutableListOf())
            val repository =
                FakeStateRepository(
                    idleState().copy(
                        phase = SyncPhase.BLOCKED,
                        problem = SyncProblem.TRANSIENT_EXHAUSTED,
                        consecutiveTransientAttempts = 5,
                    ),
                    mutableListOf(),
                )

            val result = RequestPeriodicSynchronization(repository, scheduler).execute(2)

            assertTrue(result is SyncRunRequest.Queued)
            assertEquals(SyncFence(2, 4), scheduler.executions.single())
            assertEquals(0, repository.current.consecutiveTransientAttempts)
        }

    @Test
    fun `cancellation invalidates the run token and preserves the last committed checkpoint`() =
        runTest {
            val trace = mutableListOf<String>()
            val committed = checkpoints(collectionKey = "sync-17")
            val stateRepository =
                FakeStateRepository(
                    runningState(runToken = 9).copy(checkpoints = committed),
                    trace,
                )
            val lifecycle = lifecycle(stateRepository, trace)

            val outcome = lifecycle.cancel()

            assertEquals(SyncCancellationOutcome.Cancelled, outcome)
            assertEquals(
                listOf("state:cancelling", "scheduler:cancel-execution", "state:idle"),
                trace,
            )
            assertTrue(stateRepository.current.enabled)
            assertEquals(2L, stateRepository.current.generation)
            assertEquals(10L, stateRepository.current.runToken)
            assertEquals(committed, stateRepository.current.checkpoints)
            assertEquals(SyncPhase.IDLE, stateRepository.current.phase)
        }

    @Test
    fun `cancellation fence transition cannot interleave with a provider mutation`() =
        runTest {
            val trace = mutableListOf<String>()
            val mutationLock = SynchronizationMutationLock()
            val providerStarted = CompletableDeferred<Unit>()
            val releaseProvider = CompletableDeferred<Unit>()
            val stateRepository = FakeStateRepository(runningState(), trace)
            val lifecycle =
                SynchronizationLifecycle(
                    stateRepository,
                    FakeScheduler(trace),
                    FakePermissions(calendar = true, notifications = true, trace),
                    FakeCalendar(trace),
                    FakeProblems(trace),
                    mutationLock = mutationLock,
                )
            val provider =
                launch {
                    mutationLock.withLock {
                        providerStarted.complete(Unit)
                        releaseProvider.await()
                        trace += "provider:commit"
                    }
                }
            providerStarted.await()
            val cancellation = launch { lifecycle.cancel() }
            yield()

            assertEquals(3, stateRepository.current.runToken)
            releaseProvider.complete(Unit)
            provider.join()
            cancellation.join()

            assertTrue(trace.indexOf("provider:commit") < trace.indexOf("state:cancelling"))
            assertEquals(4, stateRepository.current.runToken)
        }

    @Test
    fun `disable invalidates state before cancelling work and deleting only the owned calendar`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(runningState(), trace)
            val lifecycle = lifecycle(stateRepository, trace)

            val outcome = lifecycle.disable()

            assertEquals(SyncDisableOutcome.Disabled, outcome)
            assertEquals(
                listOf(
                    "state:disabled",
                    "scheduler:cancel-all",
                    "problem:clear",
                    "permission:calendar",
                    "calendar:delete",
                    "state:disabled",
                ),
                trace,
            )
            assertFalse(stateRepository.current.enabled)
            assertEquals(3L, stateRepository.current.generation)
            assertEquals(SyncCheckpoints.EMPTY, stateRepository.current.checkpoints)
            assertFalse(stateRepository.current.calendarCleanupPending)
            assertNull(stateRepository.current.problem)
        }

    @Test
    fun `disable persists cleanup intent before scheduler cancellation can fail`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(runningState(), trace)
            val lifecycle =
                SynchronizationLifecycle(
                    stateRepository,
                    FakeScheduler(trace, cancelAllFailure = IllegalStateException("WorkManager unavailable")),
                    FakePermissions(calendar = true, notifications = true, trace),
                    FakeCalendar(trace),
                    FakeProblems(trace),
                )

            val failure = runCatching { lifecycle.disable() }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertFalse(stateRepository.current.enabled)
            assertTrue(stateRepository.current.calendarCleanupPending)
            assertEquals(SyncCheckpoints.EMPTY, stateRepository.current.checkpoints)
            assertTrue(trace.none { it == "calendar:delete" })
        }

    @Test
    fun `enable creates a new generation clears remnants and restores both schedules`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(disabledState(generation = 8, runToken = 12), trace)
            val lifecycle = lifecycle(stateRepository, trace)

            val outcome = lifecycle.enable()

            assertEquals(SyncLifecycleOutcome.Scheduled(9), outcome)
            assertEquals(
                listOf(
                    "state:queued",
                    "scheduler:cancel-all",
                    "problem:clear",
                    "permission:notification",
                    "permission:calendar",
                    "calendar:delete",
                    "state:queued",
                    "scheduler:periodic:9",
                    "scheduler:execute:9:13",
                ),
                trace,
            )
            assertTrue(stateRepository.current.enabled)
            assertEquals(9L, stateRepository.current.generation)
            assertTrue(stateRepository.current.fullSyncRequired)
            assertEquals(SyncCheckpoints.EMPTY, stateRepository.current.checkpoints)
        }

    @Test
    fun `re-enable denied calendar permission keeps a new full generation pending`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(disabledState(8, 12), trace)
            val lifecycle =
                SynchronizationLifecycle(
                    stateRepository,
                    FakeScheduler(trace),
                    FakePermissions(calendar = false, notifications = true, trace),
                    FakeCalendar(trace),
                    FakeProblems(trace),
                )

            val outcome = lifecycle.enable()

            assertEquals(SyncLifecycleOutcome.PermissionRequired(9), outcome)
            assertTrue(stateRepository.current.enabled)
            assertEquals(9, stateRepository.current.generation)
            assertTrue(stateRepository.current.fullSyncRequired)
            assertEquals(SyncPhase.BLOCKED, stateRepository.current.phase)
            assertEquals(SyncProblem.CALENDAR_PERMISSION, stateRepository.current.problem)
        }

    @Test
    fun `notification permission result only updates presentation state`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(idleState(), trace)
            val lifecycle =
                SynchronizationLifecycle(
                    stateRepository,
                    FakeScheduler(trace),
                    FakePermissions(calendar = true, notifications = false, trace),
                    FakeCalendar(trace),
                    FakeProblems(trace),
                )

            lifecycle.onNotificationPermissionResult()

            assertTrue(stateRepository.current.notificationPermissionDenied)
            assertEquals(SyncPhase.IDLE, stateRepository.current.phase)
            assertTrue(trace.none { it.startsWith("scheduler:") || it == "calendar:delete" })
        }

    @Test
    fun `notification permission grant posts the persisted current-generation problem`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository =
                FakeStateRepository(
                    idleState().copy(phase = SyncPhase.BLOCKED, problem = SyncProblem.TLS),
                    trace,
                )
            val lifecycle =
                SynchronizationLifecycle(
                    stateRepository,
                    FakeScheduler(trace),
                    FakePermissions(calendar = true, notifications = true, trace),
                    FakeCalendar(trace),
                    FakeProblems(trace),
                )

            lifecycle.onNotificationPermissionResult()

            assertEquals(listOf("problem:show:2:tls"), problemsShown(trace))
        }

    @Test
    fun `late activation failure cannot report a problem for an obsolete generation`() =
        runTest {
            val trace = mutableListOf<String>()
            lateinit var stateRepository: FakeStateRepository
            stateRepository =
                FakeStateRepository(
                    disabledState(4, 6),
                    trace,
                    beforeUpdate = { updateNumber ->
                        if (updateNumber == 2) {
                            stateRepository.replace(
                                stateRepository.current.copy(generation = 6, runToken = 8, phase = SyncPhase.QUEUED),
                            )
                        }
                    },
                )
            val lifecycle =
                SynchronizationLifecycle(
                    stateRepository,
                    FakeScheduler(trace, scheduleFailure = IllegalStateException("late failure")),
                    FakePermissions(calendar = true, notifications = true, trace),
                    FakeCalendar(trace),
                    FakeProblems(trace),
                )

            lifecycle.activateProfile(profile())

            assertTrue(problemsShown(trace).isEmpty())
            assertEquals(6, stateRepository.current.generation)
        }

    @Test
    fun `revoked permission leaves disabled cleanup pending and retry keeps the profile`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(runningState(), trace)
            val profileRepository = FakeProfileRepository(profile())
            val calendar = FakeCalendar(trace)
            val deniedLifecycle =
                SynchronizationLifecycle(
                    stateRepository,
                    FakeScheduler(trace),
                    FakePermissions(calendar = false, notifications = true, trace),
                    calendar,
                    FakeProblems(trace),
                )

            val pending = deniedLifecycle.disable()

            assertEquals(SyncDisableOutcome.CleanupPending(SyncProblem.CALENDAR_PERMISSION), pending)
            assertFalse(stateRepository.current.enabled)
            assertTrue(stateRepository.current.calendarCleanupPending)
            assertEquals(SyncProblem.CALENDAR_PERMISSION, stateRepository.current.problem)
            assertEquals(0, calendar.deleteAttempts)
            assertEquals(profile(), profileRepository.load())

            val resumedLifecycle =
                SynchronizationLifecycle(
                    stateRepository,
                    FakeScheduler(trace),
                    FakePermissions(calendar = true, notifications = true, trace),
                    calendar,
                    FakeProblems(trace),
                )

            resumedLifecycle.onCalendarPermissionResult()

            assertEquals(listOf(stateRepository.current.fence), calendar.deleteFences)
            assertFalse(stateRepository.current.calendarCleanupPending)
            assertNull(stateRepository.current.problem)
            assertEquals(1, calendar.deleteAttempts)
            assertEquals(profile(), profileRepository.load())
        }

    @Test
    fun `startup cleanup without calendar access persists the corrective permission problem`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository =
                FakeStateRepository(
                    disabledState(8, 12).copy(calendarCleanupPending = true),
                    trace,
                )
            val lifecycle =
                SynchronizationLifecycle(
                    stateRepository,
                    FakeScheduler(trace),
                    FakePermissions(calendar = false, notifications = true, trace),
                    FakeCalendar(trace),
                    FakeProblems(trace),
                )

            val outcome = lifecycle.onCalendarPermissionResult()

            assertEquals(SyncLifecycleOutcome.PermissionRequired(8), outcome)
            assertTrue(stateRepository.current.calendarCleanupPending)
            assertEquals(SyncProblem.CALENDAR_PERMISSION, stateRepository.current.problem)
            assertEquals(listOf("problem:show:8:calendar_permission"), problemsShown(trace))
        }

    @Test
    fun `invalid-key cleanup failure preserves checkpoints and gates the next periodic run before network`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(runningState(), trace)
            val remote = FakeRemote(mutableListOf(RemotePageOutcome.Failure(SyncFailureKind.INVALID_KEY, null)), trace)
            val failedCalendar = FakeCalendar(trace, deleteResult = false)
            val first = executor(stateRepository, remote, failedCalendar, FakeScheduler(trace), trace)

            val failed = first.execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Blocked(SyncProblem.CALENDAR_PROVIDER), failed)
            assertEquals("sync-16", stateRepository.current.checkpoints.collectionSyncKey)
            assertTrue(stateRepository.current.calendarCleanupPending)

            val scheduler = FakeScheduler(trace)
            val queued = RequestPeriodicSynchronization(stateRepository, scheduler).execute(2)
            assertTrue(queued is SyncRunRequest.Queued)
            val noNetwork = FakeRemote(mutableListOf(), trace)
            val cleanup =
                executor(
                    stateRepository,
                    noNetwork,
                    FakeCalendar(trace),
                    scheduler,
                    trace,
                ).execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Continued, cleanup)
            assertTrue(noNetwork.requests.isEmpty())
            assertEquals(SyncCheckpoints.EMPTY, stateRepository.current.checkpoints)
            assertFalse(stateRepository.current.calendarCleanupPending)
        }

    @Test
    fun `missing local representation requests fenced full reset without consuming invalid-key recovery`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(runningState(), trace)
            val calendar = FakeCalendar(trace, applyOutcome = LocalPageOutcome.FullResetRequired)
            val scheduler = FakeScheduler(trace)

            val outcome =
                executor(
                    stateRepository,
                    FakeRemote(mutableListOf(page(checkpoints("sync-18"), false)), trace),
                    calendar,
                    scheduler,
                    trace,
                ).execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Continued, outcome)
            assertTrue(stateRepository.current.fullSyncRequired)
            assertFalse(stateRepository.current.invalidKeyRecoveryUsed)
            assertEquals(SyncCheckpoints.EMPTY, stateRepository.current.checkpoints)
            assertEquals(1, calendar.deleteAttempts)
            assertEquals(listOf(SyncFence(2, 3)), scheduler.continuations)
        }

    @Test
    fun `remote protocol change requests full reset without consuming invalid-key recovery`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(runningState(), trace)
            val calendar = FakeCalendar(trace)
            val scheduler = FakeScheduler(trace)

            val outcome =
                executor(
                    stateRepository,
                    FakeRemote(
                        mutableListOf(
                            RemotePageOutcome.Failure(SyncFailureKind.FULL_RESET_REQUIRED, null),
                        ),
                        trace,
                    ),
                    calendar,
                    scheduler,
                    trace,
                ).execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Continued, outcome)
            assertTrue(stateRepository.current.fullSyncRequired)
            assertFalse(stateRepository.current.invalidKeyRecoveryUsed)
            assertEquals(SyncCheckpoints.EMPTY, stateRepository.current.checkpoints)
            assertEquals(1, calendar.deleteAttempts)
            assertEquals(listOf(SyncFence(2, 3)), scheduler.continuations)
        }

    @Test
    fun `restart while disabled cannot enqueue a manual synchronization`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(disabledState(generation = 8, runToken = 12), trace)
            val scheduler = FakeScheduler(trace)

            val outcome = RequestSynchronizationNow(stateRepository, scheduler).execute()

            assertTrue(outcome is SyncRunRequest.Ignored)
            assertTrue(trace.none { it.startsWith("scheduler:") })
            assertEquals(SyncPhase.DISABLED, stateRepository.current.phase)
        }

    @Test
    fun `late calendar completion from an old generation cannot commit its returned key`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(runningState(), trace)
            val next = checkpoints("sync-18")
            val calendar =
                FakeCalendar(
                    trace,
                    onApply = {
                        stateRepository.replace(
                            stateRepository.current.copy(
                                generation = 3,
                                runToken = 4,
                                phase = SyncPhase.QUEUED,
                            ),
                        )
                    },
                )
            val executor =
                executor(
                    stateRepository,
                    FakeRemote(mutableListOf(page(next, false)), trace),
                    calendar,
                    FakeScheduler(trace),
                    trace,
                )

            val outcome = executor.execute(SyncFence(2, 3))

            assertEquals(SyncSliceOutcome.Obsolete, outcome)
            assertEquals("sync-16", stateRepository.current.checkpoints.collectionSyncKey)
        }

    @Test
    fun `first invalid key resets once and continues from an empty checkpoint`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(runningState(), trace)
            val remote = FakeRemote(mutableListOf(RemotePageOutcome.Failure(SyncFailureKind.INVALID_KEY, null)), trace)
            val calendar = FakeCalendar(trace)
            val scheduler = FakeScheduler(trace)
            val executor = executor(stateRepository, remote, calendar, scheduler, trace)

            val outcome = executor.execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Continued, outcome)
            assertTrue(stateRepository.current.invalidKeyRecoveryUsed)
            assertTrue(stateRepository.current.fullSyncRequired)
            assertEquals(SyncCheckpoints.EMPTY, stateRepository.current.checkpoints)
            assertEquals(SyncPhase.QUEUED, stateRepository.current.phase)
            assertEquals(1, calendar.deleteAttempts)
            assertEquals(listOf(SyncFence(2, 3)), scheduler.continuations)
            assertTrue(problemsShown(trace).isEmpty())
        }

    @Test
    fun `second invalid key blocks the run and reports a stable problem`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository =
                FakeStateRepository(runningState().copy(invalidKeyRecoveryUsed = true), trace)
            val remote = FakeRemote(mutableListOf(RemotePageOutcome.Failure(SyncFailureKind.INVALID_KEY, null)), trace)
            val calendar = FakeCalendar(trace)
            val executor = executor(stateRepository, remote, calendar, FakeScheduler(trace), trace)

            val outcome = executor.execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Blocked(SyncProblem.REPEATED_INVALID_KEY), outcome)
            assertEquals(SyncPhase.BLOCKED, stateRepository.current.phase)
            assertEquals(SyncProblem.REPEATED_INVALID_KEY, stateRepository.current.problem)
            assertEquals(0, calendar.deleteAttempts)
            assertEquals(listOf("problem:show:2:repeated_invalid_key"), problemsShown(trace))
        }

    @Test
    fun `page budget commits its page then queues continuation with the returned key`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(runningState(), trace)
            val next = checkpoints(collectionKey = "sync-18")
            val remote = FakeRemote(mutableListOf(page(next, moreAvailable = true)), trace)
            val calendar = FakeCalendar(trace)
            val scheduler = FakeScheduler(trace)
            val executor =
                executor(
                    stateRepository,
                    remote,
                    calendar,
                    scheduler,
                    trace,
                    limits = SyncSliceLimits(maxPages = 1, maxElapsedMillis = 60_000),
                )

            val outcome = executor.execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Continued, outcome)
            assertEquals(next, stateRepository.current.checkpoints)
            assertEquals(SyncPhase.QUEUED, stateRepository.current.phase)
            assertEquals(1, calendar.appliedPages.size)
            assertEquals("STABLEDEVICE", remote.requests.single().deviceId)
            assertEquals(listOf(SyncFence(2, 3)), scheduler.continuations)
        }

    @Test
    fun `cold synchronization durably publishes protocol folder download and apply phases`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository =
                FakeStateRepository(
                    runningState().copy(
                        fullSyncRequired = true,
                        checkpoints = SyncCheckpoints.EMPTY,
                        phase = SyncPhase.QUEUED,
                    ),
                    trace,
                )

            val outcome =
                executor(
                    stateRepository,
                    FakeRemote(
                        mutableListOf(page(checkpoints("sync-1"), false)),
                        trace,
                        phases =
                            listOf(
                                SyncPhase.DISCOVERING_PROTOCOL,
                                SyncPhase.DISCOVERING_FOLDERS,
                                SyncPhase.DOWNLOADING,
                            ),
                    ),
                    FakeCalendar(trace),
                    FakeScheduler(trace),
                    trace,
                ).execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Completed, outcome)
            assertEquals(
                listOf(
                    "state:discovering_protocol",
                    "state:discovering_folders",
                    "state:downloading",
                    "state:applying",
                    "state:applying",
                    "state:idle",
                ),
                trace.filter { entry -> entry.startsWith("state:") },
            )
        }

    @Test
    fun `soft elapsed-time budget commits the current page then continues later`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(runningState(), trace)
            val next = checkpoints("sync-18")
            val scheduler = FakeScheduler(trace)
            val executor =
                executor(
                    stateRepository,
                    FakeRemote(mutableListOf(page(next, moreAvailable = true)), trace),
                    FakeCalendar(trace),
                    scheduler,
                    trace,
                    limits = SyncSliceLimits(maxPages = 10, maxElapsedMillis = 60_000),
                    clock = SequenceClock(mutableListOf(10_000, 70_001)),
                )

            val outcome = executor.execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Continued, outcome)
            assertEquals(next, stateRepository.current.checkpoints)
            assertEquals(listOf(SyncFence(2, 3)), scheduler.continuations)
        }

    @Test
    fun `critical execution result persists its safe problem before worker success mapping`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(runningState(), trace)
            val calendar = FakeCalendar(trace)
            val executor =
                executor(
                    stateRepository,
                    FakeRemote(
                        mutableListOf(RemotePageOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.TLS)),
                        trace,
                    ),
                    calendar,
                    FakeScheduler(trace),
                    trace,
                )

            val outcome = executor.execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Blocked(SyncProblem.TLS), outcome)
            assertEquals(SyncPhase.BLOCKED, stateRepository.current.phase)
            assertEquals(SyncProblem.TLS, stateRepository.current.problem)
            assertTrue(calendar.appliedPages.isEmpty())
            assertEquals(listOf("problem:show:2:tls"), problemsShown(trace))
        }

    @Test
    fun `calendar commits before key and replay after a crash converges without duplicates`() =
        runTest {
            val trace = mutableListOf<String>()
            var crashBeforeCheckpoint = true
            val stateRepository =
                FakeStateRepository(
                    runningState(),
                    trace,
                    beforeCommit = { current, next ->
                        if (current.checkpoints != next.checkpoints && crashBeforeCheckpoint) {
                            crashBeforeCheckpoint = false
                            trace += "checkpoint:crash"
                            throw SimulatedProcessCrash()
                        }
                    },
                )
            val next = checkpoints("sync-18")
            val remotePage =
                RemotePageOutcome.Page(
                    RemoteCalendarPage(listOf(TestCalendarChange("event-1")), next, moreAvailable = false),
                )
            val remote = FakeRemote(mutableListOf(remotePage, remotePage), trace)
            val identities = linkedSetOf<String>()
            var providerApplyCount = 0
            val calendar =
                FakeCalendar(
                    trace,
                    onApply = {
                        providerApplyCount += 1
                        identities += "event-1"
                        trace += "provider:committed"
                    },
                )
            val executor = executor(stateRepository, remote, calendar, FakeScheduler(trace), trace)

            var crashed = false
            try {
                executor.execute(stateRepository.current.fence)
            } catch (_: SimulatedProcessCrash) {
                crashed = true
            }

            assertTrue(crashed)
            assertEquals("sync-16", stateRepository.current.checkpoints.collectionSyncKey)
            assertTrue(trace.indexOf("provider:committed") < trace.indexOf("checkpoint:crash"))

            val outcome = executor.execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Completed, outcome)
            assertEquals("sync-18", stateRepository.current.checkpoints.collectionSyncKey)
            assertEquals(2, providerApplyCount)
            assertEquals(setOf("event-1"), identities)
            assertEquals("sync-16", remote.requests[1].checkpoints.collectionSyncKey)
        }

    @Test
    fun `provider failure never advances the returned synchronization key`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(runningState(), trace)
            val next = checkpoints("sync-18")
            val executor =
                executor(
                    stateRepository,
                    FakeRemote(mutableListOf(page(next, false)), trace),
                    FakeCalendar(trace, LocalPageOutcome.Failed()),
                    FakeScheduler(trace),
                    trace,
                )

            val outcome = executor.execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Blocked(SyncProblem.CALENDAR_PROVIDER), outcome)
            assertEquals("sync-16", stateRepository.current.checkpoints.collectionSyncKey)
        }

    @Test
    fun `oversized provider transaction retries the unchanged key with a halved window`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(runningState(), trace)
            val next = checkpoints("sync-18")
            val remote = FakeRemote(mutableListOf(page(next, false), page(next, false)), trace)
            val scheduler = FakeScheduler(trace)
            val tooLargeExecutor =
                executor(
                    stateRepository,
                    remote,
                    FakeCalendar(trace, LocalPageOutcome.TransactionTooLarge),
                    scheduler,
                    trace,
                )

            val retryOutcome = tooLargeExecutor.execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Continued, retryOutcome)
            assertEquals("sync-16", stateRepository.current.checkpoints.collectionSyncKey)
            assertEquals(50, stateRepository.current.checkpoints.windowSize)
            assertEquals(listOf(SyncFence(2, 3)), scheduler.continuations)

            val completedOutcome =
                executor(
                    stateRepository,
                    remote,
                    FakeCalendar(trace),
                    scheduler,
                    trace,
                ).execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Completed, completedOutcome)
            assertEquals("sync-16", remote.requests[1].checkpoints.collectionSyncKey)
            assertEquals(50, remote.requests[1].checkpoints.windowSize)
            assertEquals("sync-18", stateRepository.current.checkpoints.collectionSyncKey)
        }

    @Test
    fun `oversized remote page retries the unchanged key with a halved window`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(runningState(), trace)
            val scheduler = FakeScheduler(trace)
            val remote =
                FakeRemote(
                    mutableListOf(
                        RemotePageOutcome.Failure(
                            SyncFailureKind.valueOf("WINDOW_TOO_LARGE"),
                            null,
                        ),
                    ),
                    trace,
                )
            val calendar = FakeCalendar(trace)

            val outcome = executor(stateRepository, remote, calendar, scheduler, trace)
                .execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Continued, outcome)
            assertEquals("sync-16", stateRepository.current.checkpoints.collectionSyncKey)
            assertEquals(50, stateRepository.current.checkpoints.windowSize)
            assertTrue(calendar.appliedPages.isEmpty())
            assertEquals(listOf(SyncFence(2, 3)), scheduler.continuations)
        }

    @Test
    fun `single-item oversized remote page becomes a protocol-data problem`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository =
                FakeStateRepository(
                    runningState().copy(checkpoints = checkpoints("sync-16").copy(windowSize = 1)),
                    trace,
                )
            val remote =
                FakeRemote(
                    mutableListOf(
                        RemotePageOutcome.Failure(SyncFailureKind.WINDOW_TOO_LARGE, null),
                    ),
                    trace,
                )

            val outcome =
                executor(
                    stateRepository,
                    remote,
                    FakeCalendar(trace),
                    FakeScheduler(trace),
                    trace,
                ).execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Blocked(SyncProblem.PROTOCOL_DATA), outcome)
            assertEquals("sync-16", stateRepository.current.checkpoints.collectionSyncKey)
            assertEquals(1, stateRepository.current.checkpoints.windowSize)
        }

    @Test
    fun `single-item provider transaction failure becomes a persistent provider problem`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository =
                FakeStateRepository(
                    runningState().copy(checkpoints = checkpoints("sync-16").copy(windowSize = 1)),
                    trace,
                )
            val executor =
                executor(
                    stateRepository,
                    FakeRemote(mutableListOf(page(checkpoints("sync-18"), false)), trace),
                    FakeCalendar(trace, LocalPageOutcome.TransactionTooLarge),
                    FakeScheduler(trace),
                    trace,
                )

            val outcome = executor.execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Blocked(SyncProblem.CALENDAR_PROVIDER), outcome)
            assertEquals("sync-16", stateRepository.current.checkpoints.collectionSyncKey)
            assertEquals(1, stateRepository.current.checkpoints.windowSize)
        }

    @Test
    fun `final page records success and clears prior attempts and problem`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository =
                FakeStateRepository(
                    runningState().copy(
                        consecutiveTransientAttempts = 4,
                        problem = SyncProblem.TRANSIENT_EXHAUSTED,
                    ),
                    trace,
                )
            val next = checkpoints(collectionKey = "sync-19")
            val executor =
                executor(
                    stateRepository,
                    FakeRemote(mutableListOf(page(next, moreAvailable = false)), trace),
                    FakeCalendar(trace),
                    FakeScheduler(trace),
                    trace,
                )

            val outcome = executor.execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Completed, outcome)
            assertEquals(SyncPhase.IDLE, stateRepository.current.phase)
            assertFalse(stateRepository.current.fullSyncRequired)
            assertFalse(stateRepository.current.invalidKeyRecoveryUsed)
            assertEquals(0, stateRepository.current.consecutiveTransientAttempts)
            assertEquals(1_800_000_000_000, stateRepository.current.lastSuccessfulEpochMillis)
            assertNull(stateRepository.current.problem)
            assertTrue(trace.contains("problem:clear"))
        }

    @Test
    fun `follow-up requested between completion read and commit is retained atomically`() =
        runTest {
            val trace = mutableListOf<String>()
            lateinit var stateRepository: FakeStateRepository
            stateRepository =
                FakeStateRepository(
                    runningState(),
                    trace,
                    beforeUpdate = { updateNumber ->
                        if (updateNumber == 4) {
                            stateRepository.replace(stateRepository.current.copy(followUpRequested = true))
                        }
                    },
                )
            val scheduler = FakeScheduler(trace)
            val outcome =
                executor(
                    stateRepository,
                    FakeRemote(mutableListOf(page(checkpoints("sync-19"), false)), trace),
                    FakeCalendar(trace),
                    scheduler,
                    trace,
                ).execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Continued, outcome)
            assertEquals(SyncPhase.QUEUED, stateRepository.current.phase)
            assertEquals(listOf(SyncFence(2, 3)), scheduler.continuations)
        }

    @Test
    fun `obsolete generation exits before network or calendar side effects`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(runningState(), trace)
            val remote = FakeRemote(mutableListOf(page(checkpoints("sync-20"), false)), trace)
            val calendar = FakeCalendar(trace)
            val scheduler = FakeScheduler(trace)
            val executor = executor(stateRepository, remote, calendar, scheduler, trace)

            val outcome = executor.execute(SyncFence(generation = 1, runToken = 3))

            assertEquals(SyncSliceOutcome.Obsolete, outcome)
            assertTrue(remote.requests.isEmpty())
            assertTrue(calendar.appliedPages.isEmpty())
            assertTrue(scheduler.continuations.isEmpty())
        }

    @Test
    fun `generation changed after download prevents the calendar batch`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(runningState(), trace)
            val remote =
                FakeRemote(
                    mutableListOf(page(checkpoints("sync-20"), false)),
                    trace,
                    onFetch = {
                        stateRepository.replace(
                            stateRepository.current.copy(generation = 3, runToken = 4),
                        )
                    },
                )
            val calendar = FakeCalendar(trace)
            val executor = executor(stateRepository, remote, calendar, FakeScheduler(trace), trace)

            val outcome = executor.execute(SyncFence(2, 3))

            assertEquals(SyncSliceOutcome.Obsolete, outcome)
            assertEquals(1, remote.requests.size)
            assertTrue(calendar.appliedPages.isEmpty())
        }

    @Test
    fun `transient failures retry four times and the fifth records a persistent problem`() =
        runTest {
            val retryTrace = mutableListOf<String>()
            val retryState = FakeStateRepository(runningState().copy(consecutiveTransientAttempts = 3), retryTrace)
            val retryExecutor =
                executor(
                    retryState,
                    FakeRemote(mutableListOf(RemotePageOutcome.Failure(SyncFailureKind.TRANSIENT, null)), retryTrace),
                    FakeCalendar(retryTrace),
                    FakeScheduler(retryTrace),
                    retryTrace,
                )

            assertEquals(SyncSliceOutcome.Retry, retryExecutor.execute(retryState.current.fence))
            assertEquals(4, retryState.current.consecutiveTransientAttempts)
            assertNull(retryState.current.problem)

            val exhaustedTrace = mutableListOf<String>()
            val exhaustedState =
                FakeStateRepository(runningState().copy(consecutiveTransientAttempts = 4), exhaustedTrace)
            val exhaustedExecutor =
                executor(
                    exhaustedState,
                    FakeRemote(
                        mutableListOf(RemotePageOutcome.Failure(SyncFailureKind.TRANSIENT, null)),
                        exhaustedTrace,
                    ),
                    FakeCalendar(exhaustedTrace),
                    FakeScheduler(exhaustedTrace),
                    exhaustedTrace,
                )

            assertEquals(
                SyncSliceOutcome.Blocked(SyncProblem.TRANSIENT_EXHAUSTED),
                exhaustedExecutor.execute(exhaustedState.current.fence),
            )
            assertEquals(5, exhaustedState.current.consecutiveTransientAttempts)
            assertEquals(SyncProblem.TRANSIENT_EXHAUSTED, exhaustedState.current.problem)
            assertEquals(listOf("problem:show:2:transient_exhausted"), problemsShown(exhaustedTrace))
        }

    @Test
    fun `unexpected execution failure returns to durable queued retry state`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(runningState(), trace)
            val diagnostics = RecordingSyncDiagnostics()
            val executor =
                executor(
                    stateRepository,
                    FakeRemote(
                        outcomes = mutableListOf(page(checkpoints("sync-20"), false)),
                        trace = trace,
                        onFetch = { throw IllegalStateException("unexpected adapter failure") },
                    ),
                    FakeCalendar(trace),
                    FakeScheduler(trace),
                    trace,
                    diagnostics = diagnostics,
                )

            val outcome = executor.execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Retry, outcome)
            assertEquals(SyncPhase.QUEUED, stateRepository.current.phase)
            assertEquals(1, stateRepository.current.consecutiveTransientAttempts)
            assertNull(stateRepository.current.problem)
            assertEquals(
                listOf(
                    SyncDiagnosticKind.START,
                    SyncDiagnosticKind.PHASE,
                    SyncDiagnosticKind.UNEXPECTED_EXCEPTION,
                    SyncDiagnosticKind.RETRY,
                    SyncDiagnosticKind.COMPLETE,
                ),
                diagnostics.events.map(SyncDiagnosticEvent::kind),
            )
        }

    @Test
    fun `successful page commit starts a new consecutive transient failure sequence`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository =
                FakeStateRepository(
                    runningState().copy(consecutiveTransientAttempts = 4),
                    trace,
                )
            val remote =
                FakeRemote(
                    mutableListOf(
                        page(checkpoints("sync-17"), moreAvailable = true),
                        RemotePageOutcome.Failure(SyncFailureKind.TRANSIENT, null),
                    ),
                    trace,
                )

            val outcome =
                executor(
                    stateRepository,
                    remote,
                    FakeCalendar(trace),
                    FakeScheduler(trace),
                    trace,
                ).execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.Retry, outcome)
            assertEquals("sync-17", stateRepository.current.checkpoints.collectionSyncKey)
            assertEquals(1, stateRepository.current.consecutiveTransientAttempts)
            assertNull(stateRepository.current.problem)
        }

    @Test
    fun `calendar permission is checked before a synchronization network request`() =
        runTest {
            val trace = mutableListOf<String>()
            val stateRepository = FakeStateRepository(runningState(), trace)
            val remote = FakeRemote(mutableListOf(page(checkpoints("sync-20"), false)), trace)
            val executor =
                ExecuteSynchronizationSlice(
                    stateRepository = stateRepository,
                    profileRepository = FakeProfileRepository(profile()),
                    remoteCalendar = remote,
                    ownedCalendar = FakeCalendar(trace),
                    scheduler = FakeScheduler(trace),
                    permissions = FakePermissions(calendar = false, notifications = true, trace),
                    problems = FakeProblems(trace),
                    clock = FakeClock(),
                )

            val outcome = executor.execute(stateRepository.current.fence)

            assertEquals(SyncSliceOutcome.PermissionRequired, outcome)
            assertTrue(remote.requests.isEmpty())
            assertEquals(SyncPhase.BLOCKED, stateRepository.current.phase)
            assertEquals(SyncProblem.CALENDAR_PERMISSION, stateRepository.current.problem)
        }

    private fun lifecycle(
        stateRepository: FakeStateRepository,
        trace: MutableList<String>,
    ): SynchronizationLifecycle =
        SynchronizationLifecycle(
            stateRepository,
            FakeScheduler(trace),
            FakePermissions(calendar = true, notifications = true, trace),
            FakeCalendar(trace),
            FakeProblems(trace),
        )

    private fun executor(
        stateRepository: FakeStateRepository,
        remote: FakeRemote,
        calendar: FakeCalendar,
        scheduler: FakeScheduler,
        trace: MutableList<String>,
        limits: SyncSliceLimits = SyncSliceLimits(),
        clock: SyncClock = FakeClock(),
        diagnostics: SyncDiagnosticsPort = NoOpSyncDiagnostics,
    ): ExecuteSynchronizationSlice =
        ExecuteSynchronizationSlice(
            stateRepository = stateRepository,
            profileRepository = FakeProfileRepository(profile()),
            remoteCalendar = remote,
            ownedCalendar = calendar,
            scheduler = scheduler,
            permissions = FakePermissions(calendar = true, notifications = true, trace),
            problems = FakeProblems(trace),
            clock = clock,
            limits = limits,
            diagnostics = diagnostics,
        )

    private fun problemsShown(trace: List<String>): List<String> = trace.filter { it.startsWith("problem:show") }

    private fun page(
        checkpoints: SyncCheckpoints,
        moreAvailable: Boolean,
    ): RemotePageOutcome =
        RemotePageOutcome.Page(
            RemoteCalendarPage(
                changes = emptyList(),
                nextCheckpoints = checkpoints,
                moreAvailable = moreAvailable,
            ),
        )

    private fun disabledState(
        generation: Long,
        runToken: Long,
    ): SyncState = SyncState.initial().copy(generation = generation, runToken = runToken)

    private fun idleState(): SyncState =
        SyncState.initial().copy(
            enabled = true,
            generation = 2,
            runToken = 3,
            phase = SyncPhase.IDLE,
            deviceId = "STABLEDEVICE",
            checkpoints = checkpoints("sync-16"),
        )

    private fun runningState(runToken: Long = 3): SyncState =
        idleState().copy(
            runToken = runToken,
            phase = SyncPhase.DOWNLOADING,
            currentTrigger = SyncTrigger.MANUAL,
        )

    private fun checkpoints(collectionKey: String): SyncCheckpoints =
        SyncCheckpoints(
            terminalCommandUrl = "https://mail.example.test/Microsoft-Server-ActiveSync",
            protocolVersion = ActiveSyncVersion.V16_1,
            folderSyncKey = "folder-2",
            primaryCalendarId = "calendar-1",
            collectionSyncKey = collectionKey,
            windowSize = 100,
        )

    private fun profile(): ConnectionProfile =
        ConnectionProfile(
            email = "calendar@example.test",
            account = "DOMAIN\\calendar",
            serverHost = "mail.example.test",
            clientCertificateAlias = "work-certificate",
        )

    private class FakeStateRepository(
        initial: SyncState,
        private val trace: MutableList<String>,
        private val beforeCommit: (SyncState, SyncState) -> Unit = { _, _ -> },
        private val beforeUpdate: (Int) -> Unit = {},
    ) : SyncStateRepository {
        private val mutableStates = MutableStateFlow(initial)
        override val states: Flow<SyncState> = mutableStates
        val current: SyncState
            get() = mutableStates.value
        private var updateNumber: Int = 0

        override suspend fun load(): SyncState = current

        override suspend fun update(transform: (SyncState) -> SyncState): SyncState {
            updateNumber += 1
            beforeUpdate(updateNumber)
            val next = transform(current)
            beforeCommit(current, next)
            mutableStates.value = next
            trace += "state:${next.phase.code}"
            return next
        }

        fun replace(state: SyncState) {
            mutableStates.value = state
        }
    }

    private class RecordingSyncDiagnostics : SyncDiagnosticsPort {
        val events = mutableListOf<SyncDiagnosticEvent>()

        override fun record(
            event: SyncDiagnosticEvent,
            throwable: Throwable?,
        ) {
            events += event
        }
    }

    private class FakeProfileRepository(
        private val profile: ConnectionProfile?,
    ) : ConnectionProfileRepository {
        override suspend fun load(): ConnectionProfile? = profile

        override suspend fun replace(profile: ConnectionProfile) = error("not used by synchronization")
    }

    private class FakeScheduler(
        private val trace: MutableList<String>,
        private val scheduleFailure: RuntimeException? = null,
        private val cancelAllFailure: RuntimeException? = null,
        private val onCancelAll: () -> Unit = {},
        private val onSchedulePeriodic: () -> Unit = {},
    ) : SyncSchedulerPort {
        val continuations = mutableListOf<SyncFence>()
        val executions = mutableListOf<SyncFence>()

        override suspend fun cancelAll() {
            trace += "scheduler:cancel-all"
            onCancelAll()
            cancelAllFailure?.let { throw it }
        }

        override suspend fun schedulePeriodic(generation: Long) {
            scheduleFailure?.let { throw it }
            trace += "scheduler:periodic:$generation"
            onSchedulePeriodic()
        }

        override suspend fun enqueueExecution(generation: Long, runToken: Long) {
            executions += SyncFence(generation, runToken)
            trace += "scheduler:execute:$generation:$runToken"
        }

        override suspend fun reconcileExecution(generation: Long, runToken: Long) {
            executions += SyncFence(generation, runToken)
            trace += "scheduler:recover:$generation:$runToken"
        }

        override suspend fun enqueueContinuation(generation: Long, runToken: Long) {
            continuations += SyncFence(generation, runToken)
            trace += "scheduler:continue:$generation:$runToken"
        }

        override suspend fun cancelExecution() {
            trace += "scheduler:cancel-execution"
        }
    }

    private class FakePermissions(
        private val calendar: Boolean,
        private val notifications: Boolean,
        private val trace: MutableList<String>,
    ) : SyncPermissionPort {
        override fun hasCalendarAccess(): Boolean {
            trace += "permission:calendar"
            return calendar
        }

        override fun hasNotificationAccess(): Boolean {
            trace += "permission:notification"
            return notifications
        }
    }

    private class FakeProblems(
        private val trace: MutableList<String>,
    ) : SyncProblemReporterPort {
        override suspend fun show(generation: Long, problem: SyncProblem) {
            trace += "problem:show:$generation:${problem.code}"
        }

        override suspend fun clear(generation: Long) {
            trace += "problem:clear"
        }
    }

    private class FakeCalendar(
        private val trace: MutableList<String>,
        private val applyOutcome: LocalPageOutcome = LocalPageOutcome.Applied,
        private val onApply: () -> Unit = {},
        private val deleteResult: Boolean = true,
        private val onDelete: () -> Unit = {},
    ) : OwnedCalendarPort {
        var deleteAttempts: Int = 0
        val deleteFences = mutableListOf<SyncFence?>()
        val appliedPages = mutableListOf<RemoteCalendarPage>()

        override suspend fun deleteOwnedCalendar(fence: SyncFence?): Boolean {
            deleteAttempts += 1
            deleteFences += fence
            trace += "calendar:delete"
            onDelete()
            return deleteResult
        }

        override suspend fun applyPage(fence: SyncFence, page: RemoteCalendarPage): LocalPageOutcome {
            appliedPages += page
            trace += "calendar:apply:${fence.generation}:${fence.runToken}"
            onApply()
            return applyOutcome
        }
    }

    private class FakeRemote(
        private val outcomes: MutableList<RemotePageOutcome>,
        private val trace: MutableList<String>,
        private val phases: List<SyncPhase> = listOf(SyncPhase.DOWNLOADING),
        private val onFetch: () -> Unit = {},
    ) : RemoteCalendarPort {
        val requests = mutableListOf<SyncPageRequest>()

        override suspend fun fetchPage(request: SyncPageRequest): RemotePageOutcome {
            requests += request
            trace += "remote:fetch"
            onFetch()
            return outcomes.removeFirst()
        }

        override suspend fun fetchPage(
            request: SyncPageRequest,
            reportPhase: suspend (SyncPhase) -> Unit,
        ): RemotePageOutcome {
            phases.forEach { phase -> reportPhase(phase) }
            return fetchPage(request)
        }
    }

    private class FakeClock : SyncClock {
        override fun nowEpochMillis(): Long = 1_800_000_000_000

        override fun elapsedRealtimeMillis(): Long = 10_000
    }

    private class SequenceClock(private val elapsedValues: MutableList<Long>) : SyncClock {
        override fun nowEpochMillis(): Long = 1_800_000_000_000

        override fun elapsedRealtimeMillis(): Long = elapsedValues.removeFirst()
    }

    private data class TestCalendarChange(val id: String) : CalendarChange

    private class SimulatedProcessCrash : Error()
}
