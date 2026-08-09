package net.mixalich7b.exchangesync.infrastructure.notification

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.mixalich7b.exchangesync.core.sync.SyncPermissionPort
import net.mixalich7b.exchangesync.core.sync.SyncProblem
import net.mixalich7b.exchangesync.core.sync.SyncPhase
import net.mixalich7b.exchangesync.core.sync.SyncState
import net.mixalich7b.exchangesync.core.sync.SyncStateRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncProblemNotificationReporterTest {
    @Test
    fun `all critical categories map to safe localized content under one stable notification`() = runTest {
        val gateway = RecordingNotificationGateway()
        val stateRepository = MutableSyncStateRepository(state(0, SyncProblem.TLS))
        val reporter = SyncProblemNotificationReporter(AllowedNotifications, stateRepository, gateway, resources())

        SyncProblem.entries.forEachIndexed { index, problem ->
            stateRepository.replace(state(index.toLong(), problem))
            reporter.show(index.toLong(), problem)
        }

        assertEquals(SyncProblem.entries.size, gateway.posts.size)
        assertEquals(setOf(SyncProblemNotificationPolicy.NOTIFICATION_ID), gateway.posts.map { it.id }.toSet())
        assertTrue(gateway.posts.all { it.ongoing && it.title == "attention" && it.body.startsWith("safe:") })
        assertFalse(gateway.posts.any { it.body.contains("mail.example.test") || it.body.contains("DOMAIN") })
    }

    @Test
    fun `repeated generation reports replace one identity and success clears it`() = runTest {
        val gateway = RecordingNotificationGateway()
        val stateRepository = MutableSyncStateRepository(state(4, SyncProblem.TLS))
        val reporter = SyncProblemNotificationReporter(AllowedNotifications, stateRepository, gateway, resources())

        reporter.createChannel()
        reporter.show(4, SyncProblem.TLS)
        reporter.show(4, SyncProblem.TLS)
        stateRepository.replace(state(4, null))
        reporter.clear(4)

        assertEquals(1, gateway.channelCreations)
        assertEquals(listOf(4L, 4L), gateway.posts.map { it.generation })
        assertEquals(1, gateway.clears)
    }

    @Test
    fun `notification denial prevents posting but never prevents clearing`() = runTest {
        val gateway = RecordingNotificationGateway()
        val denied = object : SyncPermissionPort {
            override fun hasCalendarAccess(): Boolean = true

            override fun hasNotificationAccess(): Boolean = false
        }
        val stateRepository = MutableSyncStateRepository(state(2, SyncProblem.CALENDAR_PROVIDER))
        val reporter = SyncProblemNotificationReporter(denied, stateRepository, gateway, resources())

        reporter.show(2, SyncProblem.CALENDAR_PROVIDER)
        assertTrue(stateRepository.load().notificationPermissionDenied)
        stateRepository.replace(state(2, null))
        reporter.clear(2)

        assertTrue(gateway.posts.isEmpty())
        assertEquals(1, gateway.clears)
    }

    @Test
    fun `background reporter persists notification permission recovery before posting`() = runTest {
        val gateway = RecordingNotificationGateway()
        val permissions = MutableNotificationPermission(allowed = true)
        val stateRepository =
            MutableSyncStateRepository(
                state(3, SyncProblem.TLS).copy(notificationPermissionDenied = true),
            )
        val reporter = SyncProblemNotificationReporter(permissions, stateRepository, gateway, resources())

        reporter.show(3, SyncProblem.TLS)

        assertFalse(stateRepository.load().notificationPermissionDenied)
        assertEquals(listOf(3L), gateway.posts.map { it.generation })
    }

    @Test
    fun `background success persists notification permission recovery before clearing`() = runTest {
        val gateway = RecordingNotificationGateway()
        val stateRepository =
            MutableSyncStateRepository(
                state(3, null).copy(notificationPermissionDenied = true),
            )
        val reporter = SyncProblemNotificationReporter(AllowedNotifications, stateRepository, gateway, resources())

        reporter.clear(3)

        assertFalse(stateRepository.load().notificationPermissionDenied)
        assertEquals(1, gateway.clears)
    }

    @Test
    fun `obsolete completion cannot clear a newer generation notification`() = runTest {
        val gateway = RecordingNotificationGateway()
        val stateRepository = MutableSyncStateRepository(state(5, SyncProblem.TLS))
        val reporter = SyncProblemNotificationReporter(AllowedNotifications, stateRepository, gateway, resources())
        reporter.show(5, SyncProblem.TLS)
        stateRepository.replace(state(6, SyncProblem.ACCESS))
        reporter.show(6, SyncProblem.ACCESS)

        reporter.clear(5)

        assertEquals(listOf(5L, 6L), gateway.posts.map { it.generation })
        assertEquals(0, gateway.clears)
    }

    @Test
    fun `late old clear cannot overtake a newer problem post`() = runTest {
        val oldLoadCaptured = CompletableDeferred<Unit>()
        val releaseOldLoad = CompletableDeferred<Unit>()
        val stateRepository =
            SuspendingLoadSyncStateRepository(
                state(5, null),
                oldLoadCaptured,
                releaseOldLoad,
            )
        val gateway = RecordingNotificationGateway()
        val reporter = SyncProblemNotificationReporter(AllowedNotifications, stateRepository, gateway, resources())

        val oldClear = launch { reporter.clear(5) }
        oldLoadCaptured.await()
        stateRepository.replace(state(6, SyncProblem.ACCESS))
        val newShow = launch { reporter.show(6, SyncProblem.ACCESS) }
        yield()
        releaseOldLoad.complete(Unit)
        oldClear.join()
        newShow.join()

        assertEquals(listOf("clear", "post:6"), gateway.actions)
    }

    private fun resources(): SyncProblemNotificationResources =
        SyncProblemNotificationResources(
            channelName = "problems",
            channelDescription = "safe channel",
            title = "attention",
            certificate = "safe:certificate",
            tls = "safe:tls",
            access = "safe:access",
            compatibility = "safe:compatibility",
            primaryCalendar = "safe:primary",
            protocolData = "safe:protocol",
            calendarPermission = "safe:permission",
            calendarProvider = "safe:provider",
            availability = "safe:availability",
            smallIconResourceId = 123,
        )

    private object AllowedNotifications : SyncPermissionPort {
        override fun hasCalendarAccess(): Boolean = true

        override fun hasNotificationAccess(): Boolean = true
    }

    private class MutableNotificationPermission(
        var allowed: Boolean,
    ) : SyncPermissionPort {
        override fun hasCalendarAccess(): Boolean = true

        override fun hasNotificationAccess(): Boolean = allowed
    }

    private fun state(generation: Long, problem: SyncProblem?): SyncState =
        SyncState.initial().copy(
            enabled = true,
            generation = generation,
            phase = if (problem == null) SyncPhase.IDLE else SyncPhase.BLOCKED,
            problem = problem,
        )

    private class MutableSyncStateRepository(initial: SyncState) : SyncStateRepository {
        private val mutable = MutableStateFlow(initial)
        override val states: Flow<SyncState> = mutable

        override suspend fun load(): SyncState = mutable.value

        override suspend fun update(transform: (SyncState) -> SyncState): SyncState =
            transform(mutable.value).also { next -> mutable.value = next }

        fun replace(state: SyncState) {
            mutable.value = state
        }
    }

    private class SuspendingLoadSyncStateRepository(
        initial: SyncState,
        private val captured: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : SyncStateRepository {
        private val mutable = MutableStateFlow(initial)
        private var shouldSuspend = true
        override val states: Flow<SyncState> = mutable

        override suspend fun load(): SyncState {
            val snapshot = mutable.value
            if (shouldSuspend) {
                shouldSuspend = false
                captured.complete(Unit)
                release.await()
            }
            return snapshot
        }

        override suspend fun update(transform: (SyncState) -> SyncState): SyncState =
            transform(mutable.value).also { next -> mutable.value = next }

        fun replace(state: SyncState) {
            mutable.value = state
        }
    }

    private class RecordingNotificationGateway : SyncProblemNotificationGateway {
        val posts = mutableListOf<SyncProblemNotificationSpec>()
        var channelCreations = 0
        var clears = 0
        val actions = mutableListOf<String>()

        override fun createChannel(resources: SyncProblemNotificationResources) {
            channelCreations += 1
        }

        override fun post(spec: SyncProblemNotificationSpec) {
            posts += spec
            actions += "post:${spec.generation}"
        }

        override fun clear(notificationId: Int) {
            assertEquals(SyncProblemNotificationPolicy.NOTIFICATION_ID, notificationId)
            clears += 1
            actions += "clear"
        }
    }
}
