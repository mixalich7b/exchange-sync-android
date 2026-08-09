package net.mixalich7b.exchangesync.infrastructure.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.mixalich7b.exchangesync.core.sync.ActiveSyncVersion
import net.mixalich7b.exchangesync.core.sync.SyncCheckpoints
import net.mixalich7b.exchangesync.core.sync.SyncPhase
import net.mixalich7b.exchangesync.core.sync.SyncProblem
import net.mixalich7b.exchangesync.core.sync.SyncTrigger
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DataStoreSynchronizationStateRepositoryTest {
    @Test
    fun `live phases are preserved until a new repository performs process recovery`() =
        runTest {
            val store = RecordingDataStore(profilePreferences())
            val repository =
                DataStoreSynchronizationStateRepository(
                    store,
                    RecordingDeviceIdGenerator("A1B2C3D4E5F60708"),
                )
            repository.load()

            repository.update { current ->
                current.copy(
                    enabled = true,
                    phase = SyncPhase.DOWNLOADING,
                    currentTrigger = SyncTrigger.PERIODIC,
                    followUpRequested = true,
                )
            }

            assertEquals(SyncPhase.DOWNLOADING, repository.load().phase)
            assertEquals(SyncPhase.DOWNLOADING, repository.states.first().phase)
            assertEquals(SyncTrigger.PERIODIC, repository.load().currentTrigger)
            assertTrue(repository.load().followUpRequested)

            val restarted =
                DataStoreSynchronizationStateRepository(
                    store,
                    RecordingDeviceIdGenerator("0011223344556677"),
                )
            assertEquals(SyncPhase.QUEUED, restarted.load().phase)

            restarted.update { current ->
                current.copy(
                    phase = SyncPhase.CANCELLING,
                    currentTrigger = null,
                    followUpRequested = false,
                )
            }
            assertEquals(SyncPhase.CANCELLING, restarted.load().phase)
            assertEquals(SyncPhase.CANCELLING, restarted.states.first().phase)

            val restartedAgain =
                DataStoreSynchronizationStateRepository(
                    store,
                    RecordingDeviceIdGenerator("8899AABBCCDDEEFF"),
                )
            assertEquals(SyncPhase.IDLE, restartedAgain.load().phase)
        }

    @Test
    fun `profile replacement and generation fence commit in one datastore transaction`() =
        runTest {
            val store = RecordingDataStore(profilePreferences())
            val repository =
                DataStoreSynchronizationStateRepository(
                    store,
                    RecordingDeviceIdGenerator("A1B2C3D4E5F60708"),
                )
            repository.load()
            repository.update { current ->
                current.copy(
                    enabled = true,
                    generation = 7,
                    runToken = 9,
                    phase = SyncPhase.IDLE,
                    checkpoints =
                        SyncCheckpoints(
                            collectionSyncKey = "old-key",
                        ),
                )
            }
            store.updateCalls = 0
            val replacement =
                ConnectionProfile(
                    "new@example.test",
                    "DOMAIN\\new",
                    "new.example.test",
                    "new-certificate",
                )

            val activated = repository.commitActivatedProfile(replacement)

            assertEquals(1, store.updateCalls)
            assertEquals("new@example.test", store.value("connection_email"))
            assertEquals(8L, store.value("sync.generation"))
            assertEquals(10L, store.value("sync.run_token"))
            assertEquals(SyncPhase.QUEUED, activated.phase)
            assertTrue(activated.fullSyncRequired)
            assertTrue(activated.calendarCleanupPending)
            assertEquals(SyncCheckpoints.EMPTY, activated.checkpoints)
        }

    @Test
    fun `pre-change saved profile migrates to disabled state with one stable device id`() =
        runTest {
            val store = RecordingDataStore(profilePreferences())
            val generator = RecordingDeviceIdGenerator("A1B2C3D4E5F60708")
            val repository = DataStoreSynchronizationStateRepository(store, generator)

            val first = repository.load()
            val second = repository.load()

            assertFalse(first.enabled)
            assertEquals(SyncPhase.DISABLED, first.phase)
            assertFalse(first.fullSyncRequired)
            assertEquals("A1B2C3D4E5F60708", first.deviceId)
            assertEquals(first, second)
            assertEquals(1, generator.calls)
            assertEquals(1, store.updateCalls)
            assertEquals("calendar@example.test", store.value("connection_email"))
        }

    @Test
    fun `multi-field transition is encoded in one datastore transaction`() =
        runTest {
            val store = RecordingDataStore(profilePreferences())
            val repository =
                DataStoreSynchronizationStateRepository(
                    store,
                    RecordingDeviceIdGenerator("A1B2C3D4E5F60708"),
                )
            repository.load()
            store.updateCalls = 0

            val updated =
                repository.update { current ->
                    current.copy(
                        enabled = true,
                        generation = 3,
                        runToken = 2,
                        fullSyncRequired = false,
                        invalidKeyRecoveryUsed = true,
                        phase = SyncPhase.QUEUED,
                        currentTrigger = SyncTrigger.MANUAL,
                        followUpRequested = true,
                        consecutiveTransientAttempts = 4,
                        lastSuccessfulEpochMillis = 1_800_000_000_000,
                        problem = SyncProblem.TLS,
                        notificationPermissionDenied = true,
                        calendarCleanupPending = true,
                        checkpoints =
                            SyncCheckpoints(
                                terminalCommandUrl =
                                    "https://mail.example.test/Microsoft-Server-ActiveSync",
                                protocolVersion = ActiveSyncVersion.V16_1,
                                folderSyncKey = "folder-3",
                                primaryCalendarId = "calendar-1",
                                collectionSyncKey = "sync-8",
                                windowSize = 50,
                            ),
                    )
                }

            assertEquals(1, store.updateCalls)
            assertEquals(updated, repository.load())
            assertEquals(3L, store.value("sync.generation"))
            assertEquals(2L, store.value("sync.run_token"))
            assertEquals("sync-8", store.value("sync.collection_sync_key"))
            assertEquals("tls", store.value("sync.problem"))
        }

    @Test
    fun `all synchronization metadata uses its namespace and preserves profile fields`() =
        runTest {
            val store = RecordingDataStore(profilePreferences())
            val repository =
                DataStoreSynchronizationStateRepository(
                    store,
                    RecordingDeviceIdGenerator("A1B2C3D4E5F60708"),
                )

            repository.load()

            val keys = store.current.asMap().keys.map { key -> key.name }.toSet()
            val connectionKeys = keys.filter { key -> key.startsWith("connection_") }
            val synchronizationKeys = keys - connectionKeys.toSet()
            assertEquals(
                setOf(
                    "connection_email",
                    "connection_account",
                    "connection_server_host",
                    "connection_client_certificate_alias",
                ),
                connectionKeys.toSet(),
            )
            assertTrue(synchronizationKeys.isNotEmpty())
            assertTrue(synchronizationKeys.all { key -> key.startsWith("sync.") })
            assertEquals(1, store.value("sync.schema_version"))
        }

    @Test
    fun `malformed metadata is defaulted without restoring unsafe values or an active phase`() =
        runTest {
            val malformed =
                mutablePreferencesOf(
                    stringPreferencesKey("connection_email") to "calendar@example.test",
                    stringPreferencesKey("connection_account") to "DOMAIN\\calendar",
                    stringPreferencesKey("connection_server_host") to "mail.example.test",
                    stringPreferencesKey("connection_client_certificate_alias") to "work-certificate",
                    intPreferencesKey("sync.schema_version") to 1,
                    stringPreferencesKey("sync.enabled") to "yes",
                    longPreferencesKey("sync.generation") to -2,
                    longPreferencesKey("sync.run_token") to -9,
                    stringPreferencesKey("sync.device_id") to "bad-device-id",
                    stringPreferencesKey("sync.phase") to "exception text",
                    stringPreferencesKey("sync.problem") to "mail.example.test",
                    stringPreferencesKey("sync.endpoint") to "http://mail.example.test",
                    stringPreferencesKey("sync.protocol_version") to "12.1",
                    intPreferencesKey("sync.window_size") to 0,
                )
            val generator = RecordingDeviceIdGenerator("0011223344556677")
            val repository = DataStoreSynchronizationStateRepository(RecordingDataStore(malformed), generator)

            val state = repository.load()

            assertFalse(state.enabled)
            assertEquals(SyncPhase.DISABLED, state.phase)
            assertEquals(0L, state.generation)
            assertEquals(0L, state.runToken)
            assertEquals("0011223344556677", state.deviceId)
            assertEquals(SyncCheckpoints.EMPTY, state.checkpoints)
            assertNull(state.problem)
            assertEquals(1, generator.calls)
        }

    @Test
    fun `no profile keeps synchronization disabled while retaining no protocol cursor`() =
        runTest {
            val store =
                RecordingDataStore(
                    mutablePreferencesOf(
                        intPreferencesKey("sync.schema_version") to 1,
                        booleanPreferencesKey("sync.enabled") to true,
                        longPreferencesKey("sync.generation") to 4,
                        stringPreferencesKey("sync.device_id") to "A1B2C3D4E5F60708",
                        stringPreferencesKey("sync.phase") to "queued",
                        stringPreferencesKey("sync.collection_sync_key") to "sync-secret",
                    ),
                )
            val repository =
                DataStoreSynchronizationStateRepository(
                    store,
                    RecordingDeviceIdGenerator("0011223344556677"),
                )

            val state = repository.load()

            assertFalse(state.enabled)
            assertEquals(SyncCheckpoints.EMPTY, state.checkpoints)
        }

    @Test
    fun `persisted payload contains only profile fields and non-secret synchronization metadata`() =
        runTest {
            val store = RecordingDataStore(emptyPreferences())
            val profileRepository = DataStoreConnectionProfileRepository(store)
            val stateRepository =
                DataStoreSynchronizationStateRepository(
                    store,
                    RecordingDeviceIdGenerator("A1B2C3D4E5F60708"),
                )
            profileRepository.replace(
                net.mixalich7b.exchangesync.core.connection.ConnectionProfile(
                    email = "calendar@example.test",
                    account = "DOMAIN\\calendar",
                    serverHost = "mail.example.test",
                    clientCertificateAlias = "work-certificate",
                ),
            )
            stateRepository.update { current ->
                current.copy(
                    enabled = true,
                    generation = 21,
                    runToken = 8,
                    fullSyncRequired = false,
                    phase = SyncPhase.IDLE,
                    problem = SyncProblem.PROTOCOL_DATA,
                    checkpoints =
                        SyncCheckpoints(
                            terminalCommandUrl = "https://mail.example.test/Microsoft-Server-ActiveSync",
                            protocolVersion = ActiveSyncVersion.V16_1,
                            folderSyncKey = "folder-checkpoint",
                            primaryCalendarId = "calendar-1",
                            collectionSyncKey = "collection-checkpoint",
                        ),
                )
            }

            val persisted = store.current.asMap().mapKeys { (key, _) -> key.name }
            val allowedKeys =
                setOf(
                    "connection_email",
                    "connection_account",
                    "connection_server_host",
                    "connection_client_certificate_alias",
                    "sync.schema_version",
                    "sync.enabled",
                    "sync.generation",
                    "sync.run_token",
                    "sync.full_sync_required",
                    "sync.invalid_key_recovery_used",
                    "sync.device_id",
                    "sync.phase",
                    "sync.follow_up_requested",
                    "sync.transient_attempts",
                    "sync.problem",
                    "sync.notification_permission_denied",
                    "sync.calendar_cleanup_pending",
                    "sync.endpoint",
                    "sync.protocol_version",
                    "sync.folder_sync_key",
                    "sync.primary_calendar_id",
                    "sync.collection_sync_key",
                    "sync.window_size",
                )
            val forbiddenFragments =
                listOf(
                    "password",
                    "private_key",
                    "certificate_bytes",
                    "raw_response",
                    "event_payload",
                    "exception",
                    "stack_trace",
                )

            assertEquals(allowedKeys, persisted.keys)
            assertTrue(forbiddenFragments.none { fragment -> persisted.keys.any { key -> fragment in key } })
            assertTrue(forbiddenFragments.none { fragment -> persisted.values.any { value -> fragment in value.toString() } })
            assertEquals(21L, persisted["sync.generation"])
            assertEquals("folder-checkpoint", persisted["sync.folder_sync_key"])
            assertEquals("collection-checkpoint", persisted["sync.collection_sync_key"])
            assertEquals("protocol_data", persisted["sync.problem"])
        }

    private class RecordingDataStore(initial: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        var updateCalls: Int = 0

        val current: Preferences
            get() = state.value

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            updateCalls += 1
            return transform(state.value).also { updated -> state.value = updated }
        }

        fun value(name: String): Any? =
            current.asMap().entries.firstOrNull { (key, _) -> key.name == name }?.value
    }

    private class RecordingDeviceIdGenerator(
        private val value: String,
    ) : DeviceIdGenerator {
        var calls: Int = 0

        override fun create(): String {
            calls += 1
            return value
        }
    }

    private fun profilePreferences(): Preferences =
        mutablePreferencesOf(
            stringPreferencesKey("connection_email") to "calendar@example.test",
            stringPreferencesKey("connection_account") to "DOMAIN\\calendar",
            stringPreferencesKey("connection_server_host") to "mail.example.test",
            stringPreferencesKey("connection_client_certificate_alias") to "work-certificate",
        )
}
