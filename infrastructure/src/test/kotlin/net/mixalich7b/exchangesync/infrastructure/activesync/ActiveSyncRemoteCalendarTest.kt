package net.mixalich7b.exchangesync.infrastructure.activesync

import kotlinx.coroutines.runBlocking
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarMutation
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncField
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncResponseType
import net.mixalich7b.exchangesync.core.sync.ActiveSyncVersion
import net.mixalich7b.exchangesync.core.sync.RemotePageOutcome
import net.mixalich7b.exchangesync.core.sync.SyncCheckpoints
import net.mixalich7b.exchangesync.core.sync.SyncFence
import net.mixalich7b.exchangesync.core.sync.SyncFailureKind
import net.mixalich7b.exchangesync.core.sync.SyncPageRequest
import net.mixalich7b.exchangesync.core.sync.SyncPhase
import net.mixalich7b.exchangesync.core.sync.SyncProblem
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.AirSync
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.AirSyncBase
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.Calendar
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.FolderHierarchy
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlElement
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlReader
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlTag
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlWriter
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnosticEvent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnostics
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticStage
import net.mixalich7b.exchangesync.infrastructure.diagnostics.SyncRequestMode
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActiveSyncRemoteCalendarTest {
    @Test
    fun `cold full synchronization discovers capability primary folder and first calendar page in order`() =
        runBlocking {
            val calls = mutableListOf<String>()
            val capabilities =
                ActiveSyncCapabilityGateway {
                    calls += "OPTIONS"
                    ActiveSyncCapabilityOutcome.Success(endpoint(), ActiveSyncVersion.V16_1)
                }
            var syncCalls = 0
            val commands =
                ActiveSyncCommandGateway { _, endpoint, command, _, version, body ->
                    calls += command.wireValue
                    assertEquals(endpoint(), endpoint)
                    assertEquals(ActiveSyncVersion.V16_1, version)
                    when (command) {
                        ActiveSyncCommand.FOLDER_SYNC -> {
                            assertEquals("0", WbxmlReader().read(body).child(FolderHierarchy.SYNC_KEY)?.text)
                            ActiveSyncCommandOutcome.Success(endpoint, folderResponse(syncKey = "folder-key", primaryId = "primary-calendar"))
                        }
                        ActiveSyncCommand.SYNC -> {
                            syncCalls += 1
                            val collection =
                                WbxmlReader().read(body)
                                    .child(AirSync.COLLECTIONS)
                                    ?.child(AirSync.COLLECTION)
                            assertEquals("primary-calendar", collection?.child(AirSync.COLLECTION_ID)?.text)
                            if (syncCalls == 1) {
                                assertEquals("0", collection?.child(AirSync.SYNC_KEY)?.text)
                                assertEquals(null, collection?.child(AirSync.GET_CHANGES))
                                ActiveSyncCommandOutcome.Success(
                                    endpoint,
                                    emptyCalendarResponse("primary-calendar", "primed-key"),
                                )
                            } else {
                                assertEquals("primed-key", collection?.child(AirSync.SYNC_KEY)?.text)
                                assertEquals(WbxmlElement(AirSync.GET_CHANGES), collection?.child(AirSync.GET_CHANGES))
                                assertEquals("100", collection?.child(AirSync.WINDOW_SIZE)?.text)
                                assertFalse(collection?.children.orEmpty().any { element -> element.tag == AirSync.FILTER_TYPE })
                                ActiveSyncCommandOutcome.Success(
                                    endpoint,
                                    calendarResponse(
                                        collectionId = "primary-calendar",
                                        syncKey = "calendar-key",
                                        moreAvailable = true,
                                    ),
                                )
                            }
                        }
                    }
                }
            val remote = ActiveSyncRemoteCalendar(capabilities, commands)
            val phases = mutableListOf<SyncPhase>()

            val outcome =
                remote.fetchPage(request(fullSync = true)) { phase -> phases += phase }
                    as RemotePageOutcome.Page

            assertEquals(listOf("OPTIONS", "FolderSync", "Sync", "Sync"), calls)
            assertEquals(
                listOf(
                    SyncPhase.DISCOVERING_PROTOCOL,
                    SyncPhase.DISCOVERING_FOLDERS,
                    SyncPhase.DOWNLOADING,
                ),
                phases,
            )
            assertTrue(outcome.page.moreAvailable)
            assertEquals(1, outcome.page.changes.size)
            val meeting = outcome.page.changes.single() as ActiveSyncCalendarMutation.Upsert
            assertEquals(ActiveSyncField.Value(ActiveSyncResponseType.NONE), meeting.item.responseType)
            assertEquals(
                SyncCheckpoints(
                    terminalCommandUrl = endpoint().toString(),
                    protocolVersion = ActiveSyncVersion.V16_1,
                    folderSyncKey = "folder-key",
                    primaryCalendarId = "primary-calendar",
                    collectionSyncKey = "calendar-key",
                    windowSize = 100,
                ),
                outcome.page.nextCheckpoints,
            )
        }

    @Test
    fun `priming and multiple unfiltered full pages report bounded command summaries without keys`() =
        runBlocking {
            val events = mutableListOf<DeviceDiagnosticEvent>()
            val diagnostics = DeviceDiagnostics { event -> events += event }
            var folderCalls = 0
            var syncCalls = 0
            val commands =
                ActiveSyncCommandGateway { _, endpoint, command, _, _, body ->
                    when (command) {
                        ActiveSyncCommand.FOLDER_SYNC -> {
                            folderCalls += 1
                            ActiveSyncCommandOutcome.Success(
                                endpoint,
                                if (folderCalls == 1) {
                                    folderResponse("folder-secret-1", "primary-calendar")
                                } else {
                                    emptyFolderResponse("folder-secret-2")
                                },
                            )
                        }
                        ActiveSyncCommand.SYNC -> {
                            syncCalls += 1
                            val collection = WbxmlReader().read(body).child(AirSync.COLLECTIONS)?.child(AirSync.COLLECTION)
                            assertFalse(collection?.children.orEmpty().any { child -> child.tag == AirSync.FILTER_TYPE })
                            ActiveSyncCommandOutcome.Success(
                                endpoint,
                                when (syncCalls) {
                                    1 -> emptyCalendarResponse("primary-calendar", "primed-secret")
                                    2 -> calendarMutationCountsResponse("primary-calendar", "page-secret", moreAvailable = true)
                                    else -> emptyCalendarResponse("primary-calendar", "final-secret")
                                },
                            )
                        }
                    }
                }
            val remote =
                ActiveSyncRemoteCalendar(
                    capabilities =
                        ActiveSyncCapabilityGateway {
                            ActiveSyncCapabilityOutcome.Success(endpoint(), ActiveSyncVersion.V16_1)
                        },
                    commands = commands,
                    diagnostics = diagnostics,
                )

            val first = remote.fetchPage(request(fullSync = true)) as RemotePageOutcome.Page
            val second =
                remote.fetchPage(request(fullSync = true, checkpoints = first.page.nextCheckpoints))
                    as RemotePageOutcome.Page

            assertEquals(3, first.page.changes.size)
            assertTrue(first.page.moreAvailable)
            assertTrue(second.page.changes.isEmpty())
            assertFalse(second.page.moreAvailable)
            val responses = events.filter { event -> event.stage == DiagnosticStage.RESPONSE && event.command == "Sync" }
            assertEquals(
                listOf(SyncRequestMode.PRIMING, SyncRequestMode.FULL, SyncRequestMode.FULL),
                responses.map { event -> event.syncMode },
            )
            assertEquals(listOf(100, 100, 100), responses.map { event -> event.windowSize })
            assertEquals(listOf(false, false, false), responses.map { event -> event.responseEmpty })
            val decoded = events.filter { event -> event.stage == DiagnosticStage.CALENDAR_SYNC }
            assertEquals(listOf(0, 3, 0), decoded.map { event -> event.commandCount })
            assertEquals(listOf(0, 1, 0), decoded.map { event -> event.addCount })
            assertEquals(listOf(0, 1, 0), decoded.map { event -> event.changeCount })
            assertEquals(listOf(0, 1, 0), decoded.map { event -> event.deleteCount })
            assertEquals(listOf(false, true, false), decoded.map { event -> event.moreAvailable })
            assertEquals(listOf(true, true, true), decoded.map { event -> event.keyAdvanced })
            val records = events.joinToString("\n", transform = ::formatDiagnostic)
            listOf("folder-secret-1", "folder-secret-2", "primed-secret", "page-secret", "final-secret")
                .forEach { key -> assertFalse(records.contains(key), key) }
        }

    @Test
    fun `valid empty incremental response reports no commands and no key advancement`() =
        runBlocking {
            val events = mutableListOf<DeviceDiagnosticEvent>()
            val remote =
                ActiveSyncRemoteCalendar(
                    capabilities = ActiveSyncCapabilityGateway { error("Saved capability must be reused") },
                    commands = successfulIncrementalCommands(ActiveSyncVersion.V16_1),
                    sessions = liveSessions(ActiveSyncVersion.V16_1),
                    diagnostics = DeviceDiagnostics { event -> events += event },
                )

            val outcome = remote.fetchPage(request(fullSync = false, checkpoints = persistedCheckpoints()))

            assertTrue(outcome is RemotePageOutcome.Page)
            val response = events.single { event -> event.stage == DiagnosticStage.RESPONSE && event.command == "Sync" }
            assertEquals(SyncRequestMode.INCREMENTAL, response.syncMode)
            assertEquals(0, response.responseBytes)
            assertEquals(true, response.responseEmpty)
            val decoded = events.single { event -> event.stage == DiagnosticStage.CALENDAR_SYNC }
            assertEquals(0, decoded.commandCount)
            assertEquals(false, decoded.moreAvailable)
            assertEquals(false, decoded.keyAdvanced)
            assertFalse(formatDiagnostic(response).contains("known-calendar-key"))
            assertFalse(formatDiagnostic(decoded).contains("known-calendar-key"))
        }

    @Test
    fun `incremental synchronization reuses durable endpoint version folder and collection keys`() =
        runBlocking {
            var optionsCalls = 0
            val capabilities = ActiveSyncCapabilityGateway {
                optionsCalls += 1
                error("Capability discovery must be skipped")
            }
            var commandCalls = 0
            val commands =
                ActiveSyncCommandGateway { _, endpoint, command, deviceId, version, body ->
                    commandCalls += 1
                    assertEquals(endpoint(), endpoint)
                    assertEquals("STABLEDEVICE", deviceId)
                    assertEquals(ActiveSyncVersion.V14_1, version)
                    when (command) {
                        ActiveSyncCommand.FOLDER_SYNC -> {
                            assertEquals("known-folder-key", WbxmlReader().read(body).child(FolderHierarchy.SYNC_KEY)?.text)
                            ActiveSyncCommandOutcome.Success(endpoint, emptyFolderResponse("new-folder-key"))
                        }
                        ActiveSyncCommand.SYNC -> {
                            val collection = WbxmlReader().read(body).child(AirSync.COLLECTIONS)?.child(AirSync.COLLECTION)
                            assertEquals("old-calendar-key", collection?.child(AirSync.SYNC_KEY)?.text)
                            assertEquals("known-primary", collection?.child(AirSync.COLLECTION_ID)?.text)
                            ActiveSyncCommandOutcome.Success(
                                endpoint,
                                calendarResponse("known-primary", "new-calendar-key", moreAvailable = false),
                            )
                        }
                    }
                }
            val remote = ActiveSyncRemoteCalendar(capabilities, commands, liveSessions(ActiveSyncVersion.V14_1))
            val request =
                request(
                    fullSync = false,
                    checkpoints =
                        SyncCheckpoints(
                            terminalCommandUrl = endpoint().toString(),
                            protocolVersion = ActiveSyncVersion.V14_1,
                            folderSyncKey = "known-folder-key",
                            primaryCalendarId = "known-primary",
                            collectionSyncKey = "old-calendar-key",
                        ),
                )

            val outcome = remote.fetchPage(request) as RemotePageOutcome.Page

            assertEquals(0, optionsCalls)
            assertEquals(2, commandCalls)
            assertFalse(outcome.page.moreAvailable)
            assertEquals("new-calendar-key", outcome.page.nextCheckpoints.collectionSyncKey)
            assertEquals("new-folder-key", outcome.page.nextCheckpoints.folderSyncKey)
        }

    @Test
    fun `live profile capability is reused without another OPTIONS request`() =
        runBlocking {
            val sessions = ActiveSyncProfileSessionRegistry()
            sessions.acquire(profile()).recordCapability(
                ActiveSyncLiveCapability(
                    terminalEndpoint = endpoint(),
                    version = ActiveSyncVersion.V16_1,
                    supportedVersions = setOf(ActiveSyncVersion.V16_1),
                ),
            )
            var optionsCalls = 0
            val commands = successfulIncrementalCommands(ActiveSyncVersion.V16_1)
            val remote =
                ActiveSyncRemoteCalendar(
                    capabilities =
                        ActiveSyncCapabilityGateway {
                            optionsCalls += 1
                            error("Live capability must be reused")
                        },
                    commands = commands,
                    sessions = sessions,
                )

            val outcome = remote.fetchPage(request(fullSync = false, checkpoints = persistedCheckpoints()))

            assertTrue(outcome is RemotePageOutcome.Page)
            assertEquals(0, optionsCalls)
        }

    @Test
    fun `cold profile discovers capabilities and retains a persisted version that is still offered`() =
        runBlocking {
            val calls = mutableListOf<String>()
            val sessions = ActiveSyncProfileSessionRegistry()
            val remote =
                ActiveSyncRemoteCalendar(
                    capabilities =
                        ActiveSyncCapabilityGateway {
                            calls += "OPTIONS"
                            ActiveSyncCapabilityOutcome.Success(
                                terminalEndpoint = endpoint(),
                                version = ActiveSyncVersion.V16_1,
                                supportedVersions =
                                    setOf(
                                        ActiveSyncVersion.V14_1,
                                        ActiveSyncVersion.V16_1,
                                    ),
                            )
                        },
                    commands = successfulIncrementalCommands(ActiveSyncVersion.V14_1, calls),
                    sessions = sessions,
                )

            val outcome =
                remote.fetchPage(
                    request(
                        fullSync = false,
                        checkpoints = persistedCheckpoints(version = ActiveSyncVersion.V14_1),
                    ),
                )
            val continuation =
                remote.fetchPage(
                    request(
                        fullSync = false,
                        checkpoints = persistedCheckpoints(version = ActiveSyncVersion.V14_1),
                    ),
                )

            assertTrue(outcome is RemotePageOutcome.Page)
            assertTrue(continuation is RemotePageOutcome.Page)
            assertEquals(listOf("OPTIONS", "FolderSync", "Sync", "FolderSync", "Sync"), calls)
            assertEquals(ActiveSyncVersion.V14_1, sessions.acquire(profile()).liveCapability()?.version)
        }

    @Test
    fun `protocol version change requests full reset before old keys are used`() =
        runBlocking {
            var commandCalls = 0
            val sessions = ActiveSyncProfileSessionRegistry()
            val remote =
                ActiveSyncRemoteCalendar(
                    capabilities =
                        ActiveSyncCapabilityGateway {
                            ActiveSyncCapabilityOutcome.Success(
                                terminalEndpoint = endpoint(),
                                version = ActiveSyncVersion.V16_1,
                                supportedVersions = setOf(ActiveSyncVersion.V16_1),
                            )
                        },
                    commands =
                        ActiveSyncCommandGateway { _, _, _, _, _, _ ->
                            commandCalls += 1
                            error("Old protocol keys must not be used")
                        },
                    sessions = sessions,
                )

            val outcome =
                remote.fetchPage(
                    request(
                        fullSync = false,
                        checkpoints = persistedCheckpoints(version = ActiveSyncVersion.V14_1),
                    ),
                )

            assertEquals(
                RemotePageOutcome.Failure(SyncFailureKind.FULL_RESET_REQUIRED, null),
                outcome,
            )
            assertEquals(0, commandCalls)
            assertEquals(ActiveSyncVersion.V16_1, sessions.acquire(profile()).liveCapability()?.version)
        }

    @Test
    fun `empty successful incremental Sync keeps the collection key and completes with no changes`() =
        runBlocking {
            val commands =
                ActiveSyncCommandGateway { _, endpoint, command, _, _, _ ->
                    ActiveSyncCommandOutcome.Success(
                        endpoint,
                        if (command == ActiveSyncCommand.FOLDER_SYNC) {
                            emptyFolderResponse("new-folder-key")
                        } else {
                            byteArrayOf()
                        },
                    )
                }
            val remote =
                ActiveSyncRemoteCalendar(
                    capabilities = ActiveSyncCapabilityGateway { error("Saved capability must be reused") },
                    commands = commands,
                    sessions = liveSessions(ActiveSyncVersion.V16_1),
                )

            val outcome =
                remote.fetchPage(
                    request(
                        fullSync = false,
                        checkpoints =
                            SyncCheckpoints(
                                terminalCommandUrl = endpoint().toString(),
                                protocolVersion = ActiveSyncVersion.V16_1,
                                folderSyncKey = "old-folder-key",
                                primaryCalendarId = "primary-calendar",
                                collectionSyncKey = "unchanged-calendar-key",
                            ),
                    ),
                ) as RemotePageOutcome.Page

            assertTrue(outcome.page.changes.isEmpty())
            assertFalse(outcome.page.moreAvailable)
            assertEquals("unchanged-calendar-key", outcome.page.nextCheckpoints.collectionSyncKey)
            assertEquals("new-folder-key", outcome.page.nextCheckpoints.folderSyncKey)
        }

    @Test
    fun `full synchronization continuation uses committed page keys without priming again`() =
        runBlocking {
            var capabilityCalls = 0
            val capabilities =
                ActiveSyncCapabilityGateway {
                    capabilityCalls += 1
                    ActiveSyncCapabilityOutcome.Success(endpoint(), ActiveSyncVersion.V16_1)
                }
            val calls = mutableListOf<String>()
            val commands =
                ActiveSyncCommandGateway { _, endpoint, command, _, version, body ->
                    assertEquals(ActiveSyncVersion.V16_1, version)
                    when (command) {
                        ActiveSyncCommand.FOLDER_SYNC -> {
                            val key = WbxmlReader().read(body).child(FolderHierarchy.SYNC_KEY)?.text
                            calls += "FolderSync:$key"
                            ActiveSyncCommandOutcome.Success(endpoint, emptyFolderResponse("folder-key-2"))
                        }
                        ActiveSyncCommand.SYNC -> {
                            val collection =
                                WbxmlReader().read(body)
                                    .child(AirSync.COLLECTIONS)
                                    ?.child(AirSync.COLLECTION)
                            val key = collection?.child(AirSync.SYNC_KEY)?.text
                            calls += "Sync:$key:${collection?.child(AirSync.GET_CHANGES) != null}"
                            ActiveSyncCommandOutcome.Success(
                                endpoint,
                                calendarResponse("primary-calendar", "calendar-key-2", moreAvailable = false),
                            )
                        }
                    }
                }
            val remote = ActiveSyncRemoteCalendar(capabilities, commands, liveSessions(ActiveSyncVersion.V16_1))

            val outcome =
                remote.fetchPage(
                    request(
                        fullSync = true,
                        checkpoints =
                            SyncCheckpoints(
                                terminalCommandUrl = endpoint().toString(),
                                protocolVersion = ActiveSyncVersion.V16_1,
                                folderSyncKey = "folder-key-1",
                                primaryCalendarId = "primary-calendar",
                                collectionSyncKey = "calendar-key-1",
                            ),
                    ),
                ) as RemotePageOutcome.Page

            assertEquals(0, capabilityCalls)
            assertEquals(listOf("FolderSync:folder-key-1", "Sync:calendar-key-1:true"), calls)
            assertFalse(outcome.page.moreAvailable)
            assertEquals("calendar-key-2", outcome.page.nextCheckpoints.collectionSyncKey)
        }

    @Test
    fun `protocol statuses map invalid keys transient server errors and provisioning requirements`() =
        runBlocking {
            suspend fun outcomeFor(commandBody: ByteArray): RemotePageOutcome {
                val remote =
                    ActiveSyncRemoteCalendar(
                        capabilities = ActiveSyncCapabilityGateway { error("Saved capability must be reused") },
                        commands =
                            ActiveSyncCommandGateway { _, endpoint, command, _, _, _ ->
                                ActiveSyncCommandOutcome.Success(
                                    endpoint,
                                    if (command == ActiveSyncCommand.FOLDER_SYNC) {
                                        emptyFolderResponse("next-folder-key")
                                    } else {
                                        commandBody
                                    },
                                )
                            },
                        sessions = liveSessions(ActiveSyncVersion.V16_1),
                    )
                return remote.fetchPage(
                    request(
                        fullSync = false,
                        checkpoints =
                            SyncCheckpoints(
                                terminalCommandUrl = endpoint().toString(),
                                protocolVersion = ActiveSyncVersion.V16_1,
                                folderSyncKey = "folder-key",
                                primaryCalendarId = "primary-calendar",
                                collectionSyncKey = "calendar-key",
                            ),
                    ),
                )
            }

            assertEquals(
                RemotePageOutcome.Failure(SyncFailureKind.INVALID_KEY, null),
                outcomeFor(calendarStatusResponse("primary-calendar", "3")),
            )
            assertEquals(
                RemotePageOutcome.Failure(SyncFailureKind.TRANSIENT, null),
                outcomeFor(calendarStatusResponse("primary-calendar", "5")),
            )
            assertEquals(
                RemotePageOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.UNSUPPORTED_PROVISIONING),
                outcomeFor(calendarStatusResponse("primary-calendar", "141")),
            )
            listOf("110", "111", "114", "133").forEach { status ->
                assertEquals(
                    RemotePageOutcome.Failure(SyncFailureKind.TRANSIENT, null),
                    outcomeFor(calendarStatusResponse("primary-calendar", status)),
                    status,
                )
            }
            listOf("112", "125").forEach { status ->
                assertEquals(
                    RemotePageOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.ACCESS),
                    outcomeFor(calendarStatusResponse("primary-calendar", status)),
                    status,
                )
            }
            listOf("139", "140").forEach { status ->
                assertEquals(
                    RemotePageOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.UNSUPPORTED_PROVISIONING),
                    outcomeFor(calendarStatusResponse("primary-calendar", status)),
                    status,
                )
            }
        }

    @Test
    fun `missing primary Calendar maps to its stable actionable category`() =
        runBlocking {
            val remote =
                ActiveSyncRemoteCalendar(
                    capabilities =
                        ActiveSyncCapabilityGateway {
                            ActiveSyncCapabilityOutcome.Success(endpoint(), ActiveSyncVersion.V16_1)
                        },
                    commands =
                        ActiveSyncCommandGateway { _, endpoint, _, _, _, _ ->
                            ActiveSyncCommandOutcome.Success(endpoint, emptyFolderResponse("folder-key"))
                        },
                )

            val outcome = remote.fetchPage(request(fullSync = true))

            assertEquals(
                RemotePageOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.PRIMARY_CALENDAR),
                outcome,
            )
        }

    @Test
    fun `MoreAvailable with a non-advancing collection key is rejected`() =
        runBlocking {
            val remote =
                ActiveSyncRemoteCalendar(
                    capabilities = ActiveSyncCapabilityGateway { error("Saved capability must be reused") },
                    commands =
                        ActiveSyncCommandGateway { _, endpoint, command, _, _, _ ->
                            ActiveSyncCommandOutcome.Success(
                                endpoint,
                                if (command == ActiveSyncCommand.FOLDER_SYNC) {
                                    emptyFolderResponse("folder-key-2")
                                } else {
                                    calendarResponse("primary-calendar", "stuck-key", moreAvailable = true)
                                },
                            )
                        },
                    sessions = liveSessions(ActiveSyncVersion.V16_1),
                )

            val outcome =
                remote.fetchPage(
                    request(
                        fullSync = false,
                        checkpoints =
                            SyncCheckpoints(
                                terminalCommandUrl = endpoint().toString(),
                                protocolVersion = ActiveSyncVersion.V16_1,
                                folderSyncKey = "folder-key-1",
                                primaryCalendarId = "primary-calendar",
                                collectionSyncKey = "stuck-key",
                            ),
                    ),
                )

            assertEquals(
                RemotePageOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.PROTOCOL_DATA),
                outcome,
            )
        }

    @Test
    fun `malformed Compact InstanceId rejects the page without exposing an advanced checkpoint`() =
        runBlocking {
            val remote =
                ActiveSyncRemoteCalendar(
                    capabilities = ActiveSyncCapabilityGateway { error("Saved capability must be reused") },
                    commands =
                        ActiveSyncCommandGateway { _, endpoint, command, _, _, _ ->
                            ActiveSyncCommandOutcome.Success(
                                endpoint,
                                if (command == ActiveSyncCommand.FOLDER_SYNC) {
                                    emptyFolderResponse("new-folder-key")
                                } else {
                                    calendarResponseWithInstanceId(
                                        collectionId = "known-primary",
                                        syncKey = "advanced-calendar-key",
                                        instanceId = "2026-08-10T09:00:00.000Z",
                                    )
                                },
                            )
                        },
                    sessions = liveSessions(ActiveSyncVersion.V16_1),
                )

            val outcome = remote.fetchPage(request(fullSync = false, checkpoints = persistedCheckpoints()))

            assertEquals(
                RemotePageOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.PROTOCOL_DATA),
                outcome,
            )
        }

    private fun request(
        fullSync: Boolean,
        checkpoints: SyncCheckpoints = SyncCheckpoints.EMPTY,
    ) =
        SyncPageRequest(
            profile = profile(),
            fence = SyncFence(3, 9),
            deviceId = "STABLEDEVICE",
            checkpoints = checkpoints,
            fullSyncRequired = fullSync,
        )

    private fun persistedCheckpoints(
        version: ActiveSyncVersion = ActiveSyncVersion.V16_1,
    ): SyncCheckpoints =
        SyncCheckpoints(
            terminalCommandUrl = endpoint().toString(),
            protocolVersion = version,
            folderSyncKey = "known-folder-key",
            primaryCalendarId = "known-primary",
            collectionSyncKey = "known-calendar-key",
        )

    private fun liveSessions(version: ActiveSyncVersion): ActiveSyncProfileSessionRegistry =
        ActiveSyncProfileSessionRegistry().also { sessions ->
            sessions.acquire(profile()).recordCapability(
                ActiveSyncLiveCapability(
                    terminalEndpoint = endpoint(),
                    version = version,
                    supportedVersions = setOf(version),
                ),
            )
        }

    private fun successfulIncrementalCommands(
        expectedVersion: ActiveSyncVersion,
        calls: MutableList<String> = mutableListOf(),
    ): ActiveSyncCommandGateway =
        ActiveSyncCommandGateway { _, endpoint, command, _, version, _ ->
            calls += command.wireValue
            assertEquals(expectedVersion, version)
            ActiveSyncCommandOutcome.Success(
                endpoint,
                if (command == ActiveSyncCommand.FOLDER_SYNC) {
                    emptyFolderResponse("next-folder-key")
                } else {
                    byteArrayOf()
                },
            )
        }

    private fun folderResponse(syncKey: String, primaryId: String): ByteArray =
        wbxml(
            element(
                FolderHierarchy.FOLDER_SYNC,
                text(FolderHierarchy.STATUS, "1"),
                text(FolderHierarchy.SYNC_KEY, syncKey),
                element(
                    FolderHierarchy.CHANGES,
                    text(FolderHierarchy.COUNT, "1"),
                    element(
                        FolderHierarchy.ADD,
                        text(FolderHierarchy.SERVER_ID, primaryId),
                        text(FolderHierarchy.PARENT_ID, "0"),
                        text(FolderHierarchy.DISPLAY_NAME, "Calendar"),
                        text(FolderHierarchy.TYPE, "8"),
                    ),
                ),
            ),
        )

    private fun emptyFolderResponse(syncKey: String): ByteArray =
        wbxml(
            element(
                FolderHierarchy.FOLDER_SYNC,
                text(FolderHierarchy.STATUS, "1"),
                text(FolderHierarchy.SYNC_KEY, syncKey),
                element(FolderHierarchy.CHANGES, text(FolderHierarchy.COUNT, "0")),
            ),
        )

    private fun calendarResponse(collectionId: String, syncKey: String, moreAvailable: Boolean): ByteArray {
        val collectionChildren =
            mutableListOf(
                text(AirSync.SYNC_KEY, syncKey),
                text(AirSync.COLLECTION_ID, collectionId),
                text(AirSync.STATUS, "1"),
                element(
                    AirSync.COMMANDS,
                    element(
                        AirSync.ADD,
                        text(AirSync.SERVER_ID, "event-1"),
                        element(
                            AirSync.APPLICATION_DATA,
                            text(Calendar.SUBJECT, "Meeting"),
                            text(Calendar.START_TIME, "20260809T090000Z"),
                            text(Calendar.END_TIME, "20260809T100000Z"),
                            text(Calendar.ALL_DAY_EVENT, "0"),
                            text(Calendar.MEETING_STATUS, "3"),
                            text(Calendar.RESPONSE_TYPE, "0"),
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

    private fun calendarMutationCountsResponse(
        collectionId: String,
        syncKey: String,
        moreAvailable: Boolean,
    ): ByteArray {
        val collectionChildren =
            mutableListOf(
                text(AirSync.SYNC_KEY, syncKey),
                text(AirSync.COLLECTION_ID, collectionId),
                text(AirSync.STATUS, "1"),
                element(
                    AirSync.COMMANDS,
                    element(
                        AirSync.ADD,
                        text(AirSync.SERVER_ID, "event-add"),
                        element(
                            AirSync.APPLICATION_DATA,
                            text(Calendar.SUBJECT, "Meeting"),
                            text(Calendar.START_TIME, "20260809T090000Z"),
                            text(Calendar.END_TIME, "20260809T100000Z"),
                            text(Calendar.ALL_DAY_EVENT, "0"),
                        ),
                    ),
                    element(
                        AirSync.CHANGE,
                        text(AirSync.SERVER_ID, "event-change"),
                        element(AirSync.APPLICATION_DATA, text(Calendar.SUBJECT, "Changed")),
                    ),
                    element(AirSync.DELETE, text(AirSync.SERVER_ID, "event-delete")),
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

    private fun emptyCalendarResponse(collectionId: String, syncKey: String): ByteArray =
        wbxml(
            element(
                AirSync.SYNC,
                element(
                    AirSync.COLLECTIONS,
                    element(
                        AirSync.COLLECTION,
                        text(AirSync.SYNC_KEY, syncKey),
                        text(AirSync.COLLECTION_ID, collectionId),
                        text(AirSync.STATUS, "1"),
                    ),
                ),
            ),
        )

    private fun calendarResponseWithInstanceId(
        collectionId: String,
        syncKey: String,
        instanceId: String,
    ): ByteArray =
        wbxml(
            element(
                AirSync.SYNC,
                element(
                    AirSync.COLLECTIONS,
                    element(
                        AirSync.COLLECTION,
                        text(AirSync.SYNC_KEY, syncKey),
                        text(AirSync.COLLECTION_ID, collectionId),
                        text(AirSync.STATUS, "1"),
                        element(
                            AirSync.COMMANDS,
                            element(
                                AirSync.ADD,
                                text(AirSync.SERVER_ID, "series-1"),
                                element(
                                    AirSync.APPLICATION_DATA,
                                    text(Calendar.SUBJECT, "Recurring meeting"),
                                    text(Calendar.START_TIME, "20260809T090000Z"),
                                    text(Calendar.END_TIME, "20260809T100000Z"),
                                    text(Calendar.ALL_DAY_EVENT, "0"),
                                    element(
                                        Calendar.EXCEPTIONS,
                                        element(
                                            Calendar.EXCEPTION,
                                            text(AirSyncBase.INSTANCE_ID, instanceId),
                                            text(Calendar.DELETED, "1"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

    private fun calendarStatusResponse(collectionId: String, status: String): ByteArray =
        wbxml(
            element(
                AirSync.SYNC,
                element(
                    AirSync.COLLECTIONS,
                    element(
                        AirSync.COLLECTION,
                        text(AirSync.COLLECTION_ID, collectionId),
                        text(AirSync.STATUS, status),
                    ),
                ),
            ),
        )

    private fun profile() =
        ConnectionProfile(
            email = "calendar@example.test",
            account = "WORK\\calendar",
            serverHost = "exchange.example.test",
            clientCertificateAlias = "work-certificate",
        )

    private fun endpoint() = "https://exchange.example.test/Microsoft-Server-ActiveSync".toHttpUrl()

    private fun wbxml(root: WbxmlElement): ByteArray = WbxmlWriter().write(root)

    private fun element(tag: WbxmlTag, vararg children: WbxmlElement) = WbxmlElement(tag, children = children.toList())

    private fun text(tag: WbxmlTag, value: String) = WbxmlElement(tag, text = value)

    private fun formatDiagnostic(event: DeviceDiagnosticEvent): String {
        val formatterClass =
            Class.forName("net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnosticFormatter")
        val instance = formatterClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        val method =
            formatterClass.getDeclaredMethod("format", DeviceDiagnosticEvent::class.java).apply {
                isAccessible = true
            }
        return method.invoke(instance, event) as String
    }
}
