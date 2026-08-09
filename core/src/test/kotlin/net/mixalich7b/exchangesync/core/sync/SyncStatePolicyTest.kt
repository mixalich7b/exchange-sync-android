package net.mixalich7b.exchangesync.core.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncStatePolicyTest {
    @Test
    fun `profile activation durably requires owned-calendar cleanup`() {
        val activated =
            SyncStateTransitions.activate(
                SyncState.initial().copy(generation = 7, runToken = 9),
                SyncTrigger.PROFILE_ACTIVATION,
            )

        assertTrue(activated.calendarCleanupPending)
        assertTrue(activated.fullSyncRequired)
        assertEquals(SyncCheckpoints.EMPTY, activated.checkpoints)
    }

    @Test
    fun `missing synchronization metadata stays disabled even when an old profile exists`() {
        val state = SyncStateNormalizer.normalize(raw = null, hasSavedProfile = true)

        assertFalse(state.enabled)
        assertEquals(0L, state.generation)
        assertEquals(0L, state.runToken)
        assertEquals(SyncPhase.DISABLED, state.phase)
        assertFalse(state.fullSyncRequired)
        assertFalse(state.invalidKeyRecoveryUsed)
        assertEquals(SyncCheckpoints.EMPTY, state.checkpoints)
        assertNull(state.deviceId)
        assertNull(state.currentTrigger)
        assertFalse(state.followUpRequested)
        assertEquals(0, state.consecutiveTransientAttempts)
        assertNull(state.lastSuccessfulEpochMillis)
        assertNull(state.problem)
    }

    @Test
    fun `state without a saved profile is forced disabled and cannot retain checkpoints`() {
        val raw =
            RawSyncState(
                enabled = true,
                generation = 9,
                runToken = 4,
                fullSyncRequired = false,
                invalidKeyRecoveryUsed = true,
                deviceId = "ABC123",
                phaseCode = "downloading",
                triggerCode = "periodic",
                followUpRequested = true,
                consecutiveTransientAttempts = 3,
                lastSuccessfulEpochMillis = 1_799_000_000_000,
                problemCode = "tls",
                checkpoints =
                    RawSyncCheckpoints(
                        terminalCommandUrl = "https://mail.example.test/Microsoft-Server-ActiveSync",
                        protocolVersion = "16.1",
                        folderSyncKey = "folder-9",
                        primaryCalendarId = "calendar-1",
                        collectionSyncKey = "sync-42",
                        windowSize = 50,
                    ),
            )

        val state = SyncStateNormalizer.normalize(raw, hasSavedProfile = false)

        assertEquals(SyncState.initial(), state)
    }

    @Test
    fun `disabled pending cleanup restores its actionable provider problem after process death`() {
        listOf(SyncProblem.CALENDAR_PERMISSION, SyncProblem.CALENDAR_PROVIDER).forEach { expected ->
            val state =
                SyncStateNormalizer.normalize(
                    RawSyncState(
                        enabled = false,
                        generation = 7,
                        runToken = 9,
                        problemCode = expected.code,
                        calendarCleanupPending = true,
                    ),
                    hasSavedProfile = true,
                )

            assertFalse(state.enabled)
            assertTrue(state.calendarCleanupPending)
            assertEquals(expected, state.problem)
        }

        val unrelated =
            SyncStateNormalizer.normalize(
                RawSyncState(
                    enabled = false,
                    problemCode = SyncProblem.TLS.code,
                    calendarCleanupPending = true,
                ),
                hasSavedProfile = true,
            )
        assertNull(unrelated.problem)
    }

    @Test
    fun `malformed persisted metadata defaults safely instead of restoring an active run`() {
        val raw =
            RawSyncState(
                enabled = true,
                generation = -8,
                runToken = -2,
                fullSyncRequired = false,
                invalidKeyRecoveryUsed = true,
                deviceId = " ",
                phaseCode = "java_net_socket_exception",
                triggerCode = "stack trace",
                followUpRequested = true,
                consecutiveTransientAttempts = 99,
                lastSuccessfulEpochMillis = -1,
                problemCode = "exchange_example_test",
                checkpoints =
                    RawSyncCheckpoints(
                        terminalCommandUrl = "http://unsafe.example.test",
                        protocolVersion = "12.1",
                        folderSyncKey = "",
                        primaryCalendarId = "calendar-1",
                        collectionSyncKey = "sync-42",
                        windowSize = 0,
                    ),
            )

        val state = SyncStateNormalizer.normalize(raw, hasSavedProfile = true)

        assertTrue(state.enabled)
        assertEquals(0L, state.generation)
        assertEquals(0L, state.runToken)
        assertEquals(SyncPhase.IDLE, state.phase)
        assertTrue(state.fullSyncRequired)
        assertFalse(state.invalidKeyRecoveryUsed)
        assertEquals(SyncCheckpoints.EMPTY, state.checkpoints)
        assertNull(state.deviceId)
        assertNull(state.currentTrigger)
        assertFalse(state.followUpRequested)
        assertEquals(0, state.consecutiveTransientAttempts)
        assertNull(state.lastSuccessfulEpochMillis)
        assertNull(state.problem)
    }

    @Test
    fun `known persisted codes restore resumable checkpoints but not an in-process phase`() {
        val state =
            SyncStateNormalizer.normalize(
                raw =
                    RawSyncState(
                        enabled = true,
                        generation = 7,
                        runToken = 11,
                        fullSyncRequired = false,
                        invalidKeyRecoveryUsed = false,
                        deviceId = "A1B2C3D4E5F6",
                        phaseCode = "applying",
                        triggerCode = "manual",
                        followUpRequested = false,
                        consecutiveTransientAttempts = 2,
                        lastSuccessfulEpochMillis = 1_799_000_000_000,
                        problemCode = "tls",
                        checkpoints =
                            RawSyncCheckpoints(
                                terminalCommandUrl =
                                    "https://mail.example.test/Microsoft-Server-ActiveSync",
                                protocolVersion = "16.1",
                                folderSyncKey = "folder-7",
                                primaryCalendarId = "calendar-1",
                                collectionSyncKey = "sync-11",
                                windowSize = 50,
                            ),
                    ),
                hasSavedProfile = true,
            )

        assertEquals(7L, state.generation)
        assertEquals(11L, state.runToken)
        assertEquals(SyncPhase.QUEUED, state.phase)
        assertEquals(SyncTrigger.MANUAL, state.currentTrigger)
        assertEquals(SyncProblem.TLS, state.problem)
        assertEquals(
            SyncCheckpoints(
                terminalCommandUrl = "https://mail.example.test/Microsoft-Server-ActiveSync",
                protocolVersion = ActiveSyncVersion.V16_1,
                folderSyncKey = "folder-7",
                primaryCalendarId = "calendar-1",
                collectionSyncKey = "sync-11",
                windowSize = 50,
            ),
            state.checkpoints,
        )
    }

    @Test
    fun `process death while cancelling recovers as enabled idle`() {
        val state =
            SyncStateNormalizer.normalize(
                raw =
                    RawSyncState(
                        enabled = true,
                        generation = 7,
                        runToken = 12,
                        phaseCode = SyncPhase.CANCELLING.code,
                        triggerCode = SyncTrigger.MANUAL.code,
                    ),
                hasSavedProfile = true,
            )

        assertTrue(state.enabled)
        assertEquals(SyncPhase.IDLE, state.phase)
        assertNull(state.currentTrigger)
        assertFalse(state.followUpRequested)
    }

    @Test
    fun `generation and run-token fencing reject every obsolete side-effect boundary`() {
        val state =
            SyncState.initial().copy(
                enabled = true,
                generation = 12,
                runToken = 5,
                phase = SyncPhase.DOWNLOADING,
            )

        assertTrue(SyncStateTransitions.mayPerformSideEffect(state, SyncFence(12, 5)))
        assertFalse(SyncStateTransitions.mayPerformSideEffect(state, SyncFence(11, 5)))
        assertFalse(SyncStateTransitions.mayPerformSideEffect(state, SyncFence(12, 4)))
        assertFalse(
            SyncStateTransitions.mayPerformSideEffect(
                state.copy(enabled = false, phase = SyncPhase.DISABLED),
                SyncFence(12, 5),
            ),
        )
    }

    @Test
    fun `only queued and executing phases expose active and cancellable state`() {
        val active =
            setOf(
                SyncPhase.QUEUED,
                SyncPhase.DISCOVERING_PROTOCOL,
                SyncPhase.DISCOVERING_FOLDERS,
                SyncPhase.DOWNLOADING,
                SyncPhase.APPLYING,
            )

        SyncPhase.entries.forEach { phase ->
            assertEquals(phase in active, phase.isActive, phase.name)
            assertEquals(phase in active, phase.isCancellable, phase.name)
        }
    }

    @Test
    fun `clearing checkpoints removes every protocol cursor and restores default window`() {
        val checkpoints =
            SyncCheckpoints(
                terminalCommandUrl = "https://mail.example.test/Microsoft-Server-ActiveSync",
                protocolVersion = ActiveSyncVersion.V14_1,
                folderSyncKey = "folder-1",
                primaryCalendarId = "calendar-1",
                collectionSyncKey = "sync-1",
                windowSize = 25,
            )

        assertEquals(SyncCheckpoints.EMPTY, checkpoints.cleared())
        assertEquals(100, checkpoints.cleared().windowSize)
    }

    @Test
    fun `additional triggers during an active run retain one follow-up without replacing its identity`() {
        val running =
            SyncState.initial().copy(
                enabled = true,
                generation = 3,
                runToken = 8,
                phase = SyncPhase.DOWNLOADING,
                currentTrigger = SyncTrigger.PERIODIC,
            )

        val first = SyncStateTransitions.requestRun(running, SyncTrigger.MANUAL)
        val second = SyncStateTransitions.requestRun(first.state, SyncTrigger.PROFILE_ACTIVATION)

        assertTrue(first is SyncRunRequest.Coalesced)
        assertTrue(second is SyncRunRequest.Coalesced)
        assertEquals(8L, second.state.runToken)
        assertEquals(SyncTrigger.PERIODIC, second.state.currentTrigger)
        assertTrue(second.state.followUpRequested)
    }

    @Test
    fun `a newly queued logical run resets invalid-key and transient recovery budgets`() {
        val previousRun =
            SyncState.initial().copy(
                enabled = true,
                generation = 3,
                runToken = 8,
                phase = SyncPhase.IDLE,
                invalidKeyRecoveryUsed = true,
                consecutiveTransientAttempts = 4,
            )

        val queued = SyncStateTransitions.requestRun(previousRun, SyncTrigger.MANUAL)

        assertTrue(queued is SyncRunRequest.Queued)
        assertEquals(9, queued.state.runToken)
        assertFalse(queued.state.invalidKeyRecoveryUsed)
        assertEquals(0, queued.state.consecutiveTransientAttempts)
    }

    @Test
    fun `persisted problems expose only stable localized category codes`() {
        val expected =
            mapOf(
                SyncProblem.CLIENT_CERTIFICATE to "client_certificate",
                SyncProblem.TLS to "tls",
                SyncProblem.ACCESS to "access",
                SyncProblem.REDIRECT to "redirect",
                SyncProblem.COMPATIBILITY to "compatibility",
                SyncProblem.UNSUPPORTED_PROVISIONING to "unsupported_provisioning",
                SyncProblem.PRIMARY_CALENDAR to "primary_calendar",
                SyncProblem.REPEATED_INVALID_KEY to "repeated_invalid_key",
                SyncProblem.PROTOCOL_DATA to "protocol_data",
                SyncProblem.CALENDAR_PERMISSION to "calendar_permission",
                SyncProblem.CALENDAR_PROVIDER to "calendar_provider",
                SyncProblem.BACKGROUND_SCHEDULING to "background_scheduling",
                SyncProblem.TRANSIENT_EXHAUSTED to "transient_exhausted",
            )

        assertEquals(expected, SyncProblem.entries.associateWith(SyncProblem::code))
        assertFalse(expected.values.any { it.contains('.') || it.contains(' ') || it.contains("exception") })
    }
}
