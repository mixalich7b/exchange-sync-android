package net.mixalich7b.exchangesync.infrastructure.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.sync.ProfileActivationCommitter
import net.mixalich7b.exchangesync.core.sync.RawSyncCheckpoints
import net.mixalich7b.exchangesync.core.sync.RawSyncState
import net.mixalich7b.exchangesync.core.sync.SyncCheckpoints
import net.mixalich7b.exchangesync.core.sync.SyncState
import net.mixalich7b.exchangesync.core.sync.SyncStateNormalizer
import net.mixalich7b.exchangesync.core.sync.SyncStateRepository
import net.mixalich7b.exchangesync.core.sync.SyncStateTransitions
import net.mixalich7b.exchangesync.core.sync.SyncTrigger
import net.mixalich7b.exchangesync.core.sync.SynchronizationMutationLock

public fun interface DeviceIdGenerator {
    public fun create(): String
}

public object RandomDeviceIdGenerator : DeviceIdGenerator {
    override fun create(): String = UUID.randomUUID().toString().replace("-", "").uppercase()
}

public class DataStoreSynchronizationStateRepository(
    private val dataStore: DataStore<Preferences>,
    private val deviceIdGenerator: DeviceIdGenerator = RandomDeviceIdGenerator,
    private val mutationLock: SynchronizationMutationLock = SynchronizationMutationLock(),
) : SyncStateRepository,
    ProfileActivationCommitter {
    private val initializationLock = SynchronizationMutationLock()

    @Volatile
    private var initialized: Boolean = false

    override val states: Flow<SyncState> =
        flow {
            ensureInitialized()
            emitAll(dataStore.data.map { preferences -> decode(preferences, recoverInterruptedPhase = false) })
        }

    override suspend fun load(): SyncState {
        ensureInitialized()
        return decode(dataStore.data.first(), recoverInterruptedPhase = false)
    }

    override suspend fun update(transform: (SyncState) -> SyncState): SyncState {
        ensureInitialized()
        var result: SyncState? = null
        dataStore.updateData { preferences ->
            val current = decodeWithDevice(preferences, recoverInterruptedPhase = false)
            val transformed = transform(current)
            val stable = transformed.copy(deviceId = current.deviceId)
            result = stable
            encode(preferences, stable)
        }
        return checkNotNull(result)
    }

    override suspend fun commitActivatedProfile(profile: ConnectionProfile): SyncState {
        ensureInitialized()
        return mutationLock.withLock {
            var result: SyncState? = null
            dataStore.updateData { preferences ->
                val current = decodeWithDevice(preferences, recoverInterruptedPhase = false)
                val activated = SyncStateTransitions.activate(current, SyncTrigger.PROFILE_ACTIVATION)
                result = activated
                val withProfile =
                    mutablePreferencesOf().apply {
                        this += preferences
                        ConnectionProfilePreferences.write(this, profile)
                    }
                encode(withProfile, activated)
            }
            checkNotNull(result)
        }
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        initializationLock.withLock {
            if (!initialized) {
                dataStore.updateData { latest ->
                    encode(latest, decodeWithDevice(latest, recoverInterruptedPhase = true))
                }
                initialized = true
            }
        }
    }

    private fun decodeWithDevice(
        preferences: Preferences,
        recoverInterruptedPhase: Boolean,
    ): SyncState {
        val decoded = decode(preferences, recoverInterruptedPhase)
        val existing = preferences.safeGet(DEVICE_ID)?.takeIf(::isValidDeviceId)
        val deviceId = existing ?: createValidDeviceId()
        return decoded.copy(deviceId = deviceId)
    }

    private fun decode(
        preferences: Preferences,
        recoverInterruptedPhase: Boolean,
    ): SyncState {
        val hasProfile = ConnectionProfilePreferences.hasValidProfile(preferences)
        val raw =
            RawSyncState(
                enabled = preferences.safeGet(ENABLED),
                generation = preferences.safeGet(GENERATION),
                runToken = preferences.safeGet(RUN_TOKEN),
                fullSyncRequired = preferences.safeGet(FULL_SYNC_REQUIRED),
                invalidKeyRecoveryUsed = preferences.safeGet(INVALID_KEY_RECOVERY_USED),
                deviceId = preferences.safeGet(DEVICE_ID),
                phaseCode = preferences.safeGet(PHASE),
                triggerCode = preferences.safeGet(TRIGGER),
                followUpRequested = preferences.safeGet(FOLLOW_UP_REQUESTED),
                consecutiveTransientAttempts = preferences.safeGet(TRANSIENT_ATTEMPTS),
                lastSuccessfulEpochMillis = preferences.safeGet(LAST_SUCCESS_EPOCH_MILLIS),
                problemCode = preferences.safeGet(PROBLEM),
                notificationPermissionDenied = preferences.safeGet(NOTIFICATION_PERMISSION_DENIED),
                calendarCleanupPending = preferences.safeGet(CALENDAR_CLEANUP_PENDING),
                checkpoints =
                    RawSyncCheckpoints(
                        terminalCommandUrl = preferences.safeGet(ENDPOINT),
                        protocolVersion = preferences.safeGet(PROTOCOL_VERSION),
                        folderSyncKey = preferences.safeGet(FOLDER_SYNC_KEY),
                        primaryCalendarId = preferences.safeGet(PRIMARY_CALENDAR_ID),
                        collectionSyncKey = preferences.safeGet(COLLECTION_SYNC_KEY),
                        windowSize = preferences.safeGet(WINDOW_SIZE),
                    ),
            )
        val normalized = SyncStateNormalizer.normalize(raw, hasProfile, recoverInterruptedPhase)
        val deviceId = preferences.safeGet(DEVICE_ID)?.takeIf(::isValidDeviceId)
        return normalized.copy(deviceId = deviceId)
    }

    private fun encode(
        original: Preferences,
        state: SyncState,
    ): Preferences =
        mutablePreferencesOf().apply {
            this += original
            removeSyncKeys()
            this[SCHEMA_VERSION] = CURRENT_SCHEMA_VERSION
            this[ENABLED] = state.enabled
            this[GENERATION] = state.generation
            this[RUN_TOKEN] = state.runToken
            this[FULL_SYNC_REQUIRED] = state.fullSyncRequired
            this[INVALID_KEY_RECOVERY_USED] = state.invalidKeyRecoveryUsed
            state.deviceId?.let { deviceId -> this[DEVICE_ID] = deviceId }
            this[PHASE] = state.phase.code
            state.currentTrigger?.let { trigger -> this[TRIGGER] = trigger.code }
            this[FOLLOW_UP_REQUESTED] = state.followUpRequested
            this[TRANSIENT_ATTEMPTS] = state.consecutiveTransientAttempts
            state.lastSuccessfulEpochMillis?.let { timestamp -> this[LAST_SUCCESS_EPOCH_MILLIS] = timestamp }
            state.problem?.let { problem -> this[PROBLEM] = problem.code }
            this[NOTIFICATION_PERMISSION_DENIED] = state.notificationPermissionDenied
            this[CALENDAR_CLEANUP_PENDING] = state.calendarCleanupPending
            writeCheckpoints(state.checkpoints)
        }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeCheckpoints(
        checkpoints: SyncCheckpoints,
    ) {
        checkpoints.terminalCommandUrl?.let { value -> this[ENDPOINT] = value }
        checkpoints.protocolVersion?.let { value -> this[PROTOCOL_VERSION] = value.wireValue }
        checkpoints.folderSyncKey?.let { value -> this[FOLDER_SYNC_KEY] = value }
        checkpoints.primaryCalendarId?.let { value -> this[PRIMARY_CALENDAR_ID] = value }
        checkpoints.collectionSyncKey?.let { value -> this[COLLECTION_SYNC_KEY] = value }
        this[WINDOW_SIZE] = checkpoints.windowSize
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.removeSyncKeys() {
        ALL_KEYS.forEach { key -> remove(key) }
    }

    private fun createValidDeviceId(): String =
        deviceIdGenerator.create().also { deviceId ->
            require(isValidDeviceId(deviceId)) { "DeviceIdGenerator returned an invalid ActiveSync identifier" }
        }

    private fun isValidDeviceId(value: String?): Boolean =
        value != null &&
            value.length in 1..32 &&
            value.all { character -> character in 'A'..'Z' || character in '0'..'9' }

    private inline fun <reified T> Preferences.safeGet(key: Preferences.Key<T>): T? =
        asMap()
            .entries
            .firstOrNull { (storedKey, _) -> storedKey.name == key.name }
            ?.value as? T

    private companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1

        val SCHEMA_VERSION: Preferences.Key<Int> = intPreferencesKey("sync.schema_version")
        val ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("sync.enabled")
        val GENERATION: Preferences.Key<Long> = longPreferencesKey("sync.generation")
        val RUN_TOKEN: Preferences.Key<Long> = longPreferencesKey("sync.run_token")
        val FULL_SYNC_REQUIRED: Preferences.Key<Boolean> = booleanPreferencesKey("sync.full_sync_required")
        val INVALID_KEY_RECOVERY_USED: Preferences.Key<Boolean> =
            booleanPreferencesKey("sync.invalid_key_recovery_used")
        val DEVICE_ID: Preferences.Key<String> = stringPreferencesKey("sync.device_id")
        val PHASE: Preferences.Key<String> = stringPreferencesKey("sync.phase")
        val TRIGGER: Preferences.Key<String> = stringPreferencesKey("sync.trigger")
        val FOLLOW_UP_REQUESTED: Preferences.Key<Boolean> = booleanPreferencesKey("sync.follow_up_requested")
        val TRANSIENT_ATTEMPTS: Preferences.Key<Int> = intPreferencesKey("sync.transient_attempts")
        val LAST_SUCCESS_EPOCH_MILLIS: Preferences.Key<Long> =
            longPreferencesKey("sync.last_success_epoch_millis")
        val PROBLEM: Preferences.Key<String> = stringPreferencesKey("sync.problem")
        val NOTIFICATION_PERMISSION_DENIED: Preferences.Key<Boolean> =
            booleanPreferencesKey("sync.notification_permission_denied")
        val CALENDAR_CLEANUP_PENDING: Preferences.Key<Boolean> =
            booleanPreferencesKey("sync.calendar_cleanup_pending")
        val ENDPOINT: Preferences.Key<String> = stringPreferencesKey("sync.endpoint")
        val PROTOCOL_VERSION: Preferences.Key<String> = stringPreferencesKey("sync.protocol_version")
        val FOLDER_SYNC_KEY: Preferences.Key<String> = stringPreferencesKey("sync.folder_sync_key")
        val PRIMARY_CALENDAR_ID: Preferences.Key<String> = stringPreferencesKey("sync.primary_calendar_id")
        val COLLECTION_SYNC_KEY: Preferences.Key<String> = stringPreferencesKey("sync.collection_sync_key")
        val WINDOW_SIZE: Preferences.Key<Int> = intPreferencesKey("sync.window_size")

        val ALL_KEYS: List<Preferences.Key<*>> =
            listOf(
                SCHEMA_VERSION,
                ENABLED,
                GENERATION,
                RUN_TOKEN,
                FULL_SYNC_REQUIRED,
                INVALID_KEY_RECOVERY_USED,
                DEVICE_ID,
                PHASE,
                TRIGGER,
                FOLLOW_UP_REQUESTED,
                TRANSIENT_ATTEMPTS,
                LAST_SUCCESS_EPOCH_MILLIS,
                PROBLEM,
                NOTIFICATION_PERMISSION_DENIED,
                CALENDAR_CLEANUP_PENDING,
                ENDPOINT,
                PROTOCOL_VERSION,
                FOLDER_SYNC_KEY,
                PRIMARY_CALENDAR_ID,
                COLLECTION_SYNC_KEY,
                WINDOW_SIZE,
            )
    }
}
