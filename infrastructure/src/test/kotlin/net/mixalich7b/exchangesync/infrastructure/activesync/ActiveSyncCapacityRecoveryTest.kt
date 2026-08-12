package net.mixalich7b.exchangesync.infrastructure.activesync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.connection.ConnectionProfileRepository
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarMutation
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncField
import net.mixalich7b.exchangesync.core.sync.ActiveSyncVersion
import net.mixalich7b.exchangesync.core.sync.ExecuteSynchronizationSlice
import net.mixalich7b.exchangesync.core.sync.LocalPageOutcome
import net.mixalich7b.exchangesync.core.sync.OwnedCalendarCleanupOutcome
import net.mixalich7b.exchangesync.core.sync.OwnedCalendarCleanupTrigger
import net.mixalich7b.exchangesync.core.sync.OwnedCalendarPort
import net.mixalich7b.exchangesync.core.sync.RemoteCalendarPage
import net.mixalich7b.exchangesync.core.sync.SyncCheckpoints
import net.mixalich7b.exchangesync.core.sync.SyncClock
import net.mixalich7b.exchangesync.core.sync.SyncFence
import net.mixalich7b.exchangesync.core.sync.SyncPermissionPort
import net.mixalich7b.exchangesync.core.sync.SyncProblem
import net.mixalich7b.exchangesync.core.sync.SyncProblemReporterPort
import net.mixalich7b.exchangesync.core.sync.SyncSchedulerPort
import net.mixalich7b.exchangesync.core.sync.SyncSliceOutcome
import net.mixalich7b.exchangesync.core.sync.SyncState
import net.mixalich7b.exchangesync.core.sync.SyncStateRepository
import net.mixalich7b.exchangesync.core.sync.SyncPhase
import net.mixalich7b.exchangesync.core.sync.SyncTrigger
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.AirSync
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.Calendar
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.FolderHierarchy
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlElement
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlLimits
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlReader
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlWriter
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActiveSyncCapacityRecoveryTest {
    @Test
    fun `high element recurring item at window one reaches local application and commits its key`() =
        runBlocking {
            val response = HighElementCalendarSyncFixture.responseBytes()
            val limits = WbxmlLimits()
            val fixture = RecoveryFixture(initialWindow = 1) { _, _ -> response }

            assertEquals(HighElementCalendarSyncFixture.NEW_MAX_ELEMENTS, limits.maxElements)
            assertTrue(HighElementCalendarSyncFixture.elementCount() > 20_000)
            assertTrue(HighElementCalendarSyncFixture.elementCount() <= limits.maxElements)
            assertTrue(response.size <= limits.maxDocumentBytes)
            assertTrue(HighElementCalendarSyncFixture.maximumDepth() <= limits.maxDepth)
            assertTrue(HighElementCalendarSyncFixture.maximumInlineStringBytes() <= limits.maxInlineStringBytes)

            assertEquals(SyncSliceOutcome.Completed, fixture.execute())
            assertEquals(listOf(RequestedPage("collection-1", 1)), fixture.requestedPages)
            assertEquals(HighElementCalendarSyncFixture.RETURNED_SYNC_KEY, fixture.state.current.checkpoints.collectionSyncKey)
            assertEquals(1, fixture.state.current.checkpoints.windowSize)
            val mutation = fixture.calendar.appliedPages.single().changes.single() as ActiveSyncCalendarMutation.Upsert
            assertEquals(
                HighElementCalendarSyncFixture.ATTENDEE_COUNT,
                (mutation.item.attendees as ActiveSyncField.Value).value.size,
            )
            val exceptions = (mutation.item.exceptions as ActiveSyncField.Value).value
            assertEquals(HighElementCalendarSyncFixture.TOTAL_EXCEPTION_COUNT, exceptions.size)
            assertEquals(HighElementCalendarSyncFixture.CHANGED_EXCEPTION_COUNT, exceptions.count { !it.deleted })
            assertEquals(HighElementCalendarSyncFixture.DELETED_EXCEPTION_COUNT, exceptions.count { it.deleted })
            assertTrue(exceptions.all { exception ->
                (exception.attendees as ActiveSyncField.Value).value.size == HighElementCalendarSyncFixture.ATTENDEE_COUNT
            })
        }

    @Test
    fun `real remote capacity retries the same checkpoint and smaller page resumes pagination`() =
        runBlocking {
            val fixture = RecoveryFixture(initialWindow = 100)

            assertEquals(SyncSliceOutcome.Continued, fixture.execute())
            assertEquals("collection-1", fixture.state.current.checkpoints.collectionSyncKey)
            assertEquals(50, fixture.state.current.checkpoints.windowSize)
            assertTrue(fixture.calendar.appliedPages.isEmpty())

            assertEquals(SyncSliceOutcome.Continued, fixture.execute())
            assertEquals("collection-1", fixture.state.current.checkpoints.collectionSyncKey)
            assertEquals(25, fixture.state.current.checkpoints.windowSize)
            assertTrue(fixture.calendar.appliedPages.isEmpty())

            assertEquals(SyncSliceOutcome.Completed, fixture.execute())
            assertEquals(
                listOf(
                    RequestedPage("collection-1", 100),
                    RequestedPage("collection-1", 50),
                    RequestedPage("collection-1", 25),
                    RequestedPage("collection-2", 25),
                ),
                fixture.requestedPages,
            )
            assertEquals(listOf(1, 1), fixture.calendar.appliedPages.map { page -> page.changes.size })
            assertEquals("collection-3", fixture.state.current.checkpoints.collectionSyncKey)
            assertEquals(25, fixture.state.current.checkpoints.windowSize)
        }

    @Test
    fun `real remote capacity at window one blocks without applying or skipping the checkpoint`() =
        runBlocking {
            val fixture = RecoveryFixture(initialWindow = 1)

            assertEquals(SyncSliceOutcome.Blocked(SyncProblem.PROTOCOL_DATA), fixture.execute())
            assertEquals(listOf(RequestedPage("collection-1", 1)), fixture.requestedPages)
            assertEquals("collection-1", fixture.state.current.checkpoints.collectionSyncKey)
            assertEquals(1, fixture.state.current.checkpoints.windowSize)
            assertTrue(fixture.calendar.appliedPages.isEmpty())
        }

    private class RecoveryFixture(
        initialWindow: Int,
        private val responseFor: (syncKey: String, window: Int) -> ByteArray = { syncKey, window ->
            when {
                window > 25 || window == 1 -> elementCapacityResponse()
                syncKey == "collection-1" -> calendarResponse("collection-2", moreAvailable = true)
                else -> calendarResponse("collection-3", moreAvailable = false)
            }
        },
    ) {
        val state = StateRepository(runningState(initialWindow))
        val calendar = RecordingCalendar()
        val requestedPages = mutableListOf<RequestedPage>()
        private val fence = SyncFence(3, 9)
        private val scheduler = RecordingScheduler()
        private val profile = profile()
        private val sessions =
            ActiveSyncProfileSessionRegistry().also { registry ->
                registry.acquire(profile).recordCapability(
                    ActiveSyncLiveCapability(endpoint(), ActiveSyncVersion.V16_1, setOf(ActiveSyncVersion.V16_1)),
                )
            }
        private val remote =
            ActiveSyncRemoteCalendar(
                capabilities = ActiveSyncCapabilityGateway { error("Live capability must be reused") },
                commands =
                    ActiveSyncCommandGateway { _, endpoint, command, _, _, body ->
                        when (command) {
                            ActiveSyncCommand.FOLDER_SYNC ->
                                ActiveSyncCommandOutcome.Success(endpoint, emptyFolderResponse("folder-2"))
                            ActiveSyncCommand.SYNC -> {
                                val collection =
                                    WbxmlReader().read(body)
                                        .child(AirSync.COLLECTIONS)
                                        ?.child(AirSync.COLLECTION)
                                val syncKey = checkNotNull(collection?.child(AirSync.SYNC_KEY)?.text)
                                val window = checkNotNull(collection.child(AirSync.WINDOW_SIZE)?.text).toInt()
                                requestedPages += RequestedPage(syncKey, window)
                                val response = responseFor(syncKey, window)
                                ActiveSyncCommandOutcome.Success(endpoint, response)
                            }
                        }
                    },
                sessions = sessions,
            )
        private val executor =
            ExecuteSynchronizationSlice(
                stateRepository = state,
                profileRepository = ProfileRepository(profile),
                remoteCalendar = remote,
                ownedCalendar = calendar,
                scheduler = scheduler,
                permissions = AllowPermissions,
                problems = RecordingProblems,
                clock = FixedClock,
            )

        suspend fun execute(): SyncSliceOutcome = executor.execute(fence)
    }

    private data class RequestedPage(val collectionKey: String, val window: Int)

    private class StateRepository(initial: SyncState) : SyncStateRepository {
        private val flow = MutableStateFlow(initial)
        override val states: Flow<SyncState> = flow
        val current: SyncState get() = flow.value

        override suspend fun load(): SyncState = current

        override suspend fun update(transform: (SyncState) -> SyncState): SyncState =
            transform(current).also { updated -> flow.value = updated }
    }

    private class ProfileRepository(private val profile: ConnectionProfile) : ConnectionProfileRepository {
        override suspend fun load(): ConnectionProfile = profile

        override suspend fun replace(profile: ConnectionProfile) = Unit
    }

    private class RecordingCalendar : OwnedCalendarPort {
        val appliedPages = mutableListOf<RemoteCalendarPage>()

        override suspend fun deleteOwnedCalendar(
            fence: SyncFence?,
            trigger: OwnedCalendarCleanupTrigger,
        ): OwnedCalendarCleanupOutcome = OwnedCalendarCleanupOutcome.Completed

        override suspend fun applyPage(fence: SyncFence, page: RemoteCalendarPage): LocalPageOutcome {
            appliedPages += page
            return LocalPageOutcome.Applied
        }
    }

    private class RecordingScheduler : SyncSchedulerPort {
        override suspend fun cancelAll() = Unit
        override suspend fun schedulePeriodic(generation: Long) = Unit
        override suspend fun enqueueExecution(generation: Long, runToken: Long) = Unit
        override suspend fun reconcileExecution(generation: Long, runToken: Long) = Unit
        override suspend fun enqueueContinuation(generation: Long, runToken: Long) = Unit
        override suspend fun cancelExecution() = Unit
    }

    private object AllowPermissions : SyncPermissionPort {
        override fun hasCalendarAccess(): Boolean = true
        override fun hasNotificationAccess(): Boolean = true
    }

    private object RecordingProblems : SyncProblemReporterPort {
        override suspend fun show(generation: Long, problem: SyncProblem) = Unit
        override suspend fun clear(generation: Long) = Unit
    }

    private object FixedClock : SyncClock {
        override fun nowEpochMillis(): Long = 1L
        override fun elapsedRealtimeMillis(): Long = 0L
    }

    private companion object {
        fun runningState(window: Int): SyncState =
            SyncState(
                enabled = true,
                generation = 3,
                runToken = 9,
                fullSyncRequired = false,
                invalidKeyRecoveryUsed = false,
                deviceId = "STABLEDEVICE",
                checkpoints =
                    SyncCheckpoints(
                        terminalCommandUrl = endpoint().toString(),
                        protocolVersion = ActiveSyncVersion.V16_1,
                        folderSyncKey = "folder-1",
                        primaryCalendarId = "primary-calendar",
                        collectionSyncKey = "collection-1",
                        windowSize = window,
                    ),
                phase = SyncPhase.QUEUED,
                currentTrigger = SyncTrigger.CONTINUATION,
                followUpRequested = false,
                consecutiveTransientAttempts = 0,
                lastSuccessfulEpochMillis = null,
                problem = null,
                notificationPermissionDenied = false,
                calendarCleanupPending = false,
            )

        fun profile() =
            ConnectionProfile(
                email = "calendar@example.test",
                account = "WORK\\calendar",
                serverHost = "exchange.example.test",
                clientCertificateAlias = "work-certificate",
            )

        fun endpoint() = "https://exchange.example.test/Microsoft-Server-ActiveSync".toHttpUrl()

        fun elementCapacityResponse(): ByteArray =
            overDefaultElementCapacityResponse()

        fun emptyFolderResponse(syncKey: String): ByteArray =
            wbxml(
                element(
                    FolderHierarchy.FOLDER_SYNC,
                    text(FolderHierarchy.STATUS, "1"),
                    text(FolderHierarchy.SYNC_KEY, syncKey),
                    element(FolderHierarchy.CHANGES, text(FolderHierarchy.COUNT, "0")),
                ),
            )

        fun calendarResponse(syncKey: String, moreAvailable: Boolean): ByteArray {
            val collectionChildren =
                mutableListOf(
                    text(AirSync.SYNC_KEY, syncKey),
                    text(AirSync.COLLECTION_ID, "primary-calendar"),
                    text(AirSync.STATUS, "1"),
                    element(
                        AirSync.COMMANDS,
                        element(
                            AirSync.ADD,
                            text(AirSync.SERVER_ID, "event-$syncKey"),
                            element(
                                AirSync.APPLICATION_DATA,
                                text(Calendar.SUBJECT, "Meeting $syncKey"),
                                text(Calendar.START_TIME, "20260809T090000Z"),
                                text(Calendar.END_TIME, "20260809T100000Z"),
                                text(Calendar.ALL_DAY_EVENT, "0"),
                            ),
                        ),
                    ),
                )
            if (moreAvailable) collectionChildren += WbxmlElement(AirSync.MORE_AVAILABLE)
            return wbxml(
                element(
                    AirSync.SYNC,
                    element(AirSync.COLLECTIONS, WbxmlElement(AirSync.COLLECTION, children = collectionChildren)),
                ),
            )
        }

        fun wbxml(root: WbxmlElement): ByteArray = WbxmlWriter().write(root)

        fun element(tag: net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlTag, vararg children: WbxmlElement) =
            WbxmlElement(tag, children = children.toList())

        fun text(tag: net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlTag, value: String) =
            WbxmlElement(tag, text = value)
    }
}
