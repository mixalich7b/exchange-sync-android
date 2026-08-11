package net.mixalich7b.exchangesync.feature.settings

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.mixalich7b.exchangesync.core.connection.ConnectionDraft
import net.mixalich7b.exchangesync.core.connection.ConnectionFailure
import net.mixalich7b.exchangesync.core.connection.ConnectionField
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.connection.ConnectionProfileRepository
import net.mixalich7b.exchangesync.core.connection.FieldError
import net.mixalich7b.exchangesync.core.connection.SaveConnectionAction
import net.mixalich7b.exchangesync.core.connection.SaveConnectionResult
import net.mixalich7b.exchangesync.core.connection.TlsCertificateDiagnostic
import net.mixalich7b.exchangesync.core.connection.TlsConnectionDiagnostics
import net.mixalich7b.exchangesync.core.connection.VerifyConnectionAction
import net.mixalich7b.exchangesync.core.connection.VerifyConnectionResult
import net.mixalich7b.exchangesync.core.sync.RequestSynchronizationAction
import net.mixalich7b.exchangesync.core.sync.SyncCancellationOutcome
import net.mixalich7b.exchangesync.core.sync.SyncDisableOutcome
import net.mixalich7b.exchangesync.core.sync.SyncLifecycleOutcome
import net.mixalich7b.exchangesync.core.sync.SyncPhase
import net.mixalich7b.exchangesync.core.sync.SyncProblem
import net.mixalich7b.exchangesync.core.sync.SyncRunRequest
import net.mixalich7b.exchangesync.core.sync.SyncState
import net.mixalich7b.exchangesync.core.sync.SyncStateRepository
import net.mixalich7b.exchangesync.core.sync.SynchronizationLifecycleActions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Test
    fun `initial profile lookup blocks draft actions until saved profile is applied`() =
        runUiTest {
            val lookup = CompletableDeferred<ConnectionProfile?>()
            val repository =
                object : ConnectionProfileRepository {
                    override suspend fun load(): ConnectionProfile? = lookup.await()

                    override suspend fun replace(profile: ConnectionProfile) = Unit
                }
            val save = RecordingSaveAction()
            val verify = RecordingVerifyAction()
            val viewModel = SettingsViewModel(repository, save, verify)

            assertTrue(viewModel.state.value.isLoading)
            viewModel.onEmailChanged("overwritten@example.test")
            viewModel.onAccountChanged("DOMAIN\\overwritten")
            viewModel.onServerChanged("overwritten.example.test")
            viewModel.onCertificateSelected("overwritten-certificate")
            viewModel.onSave()
            viewModel.onRecheck()

            assertEquals(SettingsUiState(), viewModel.state.value)
            assertEquals(emptyList<ConnectionDraft>(), save.drafts)
            assertEquals(emptyList<ConnectionDraft>(), verify.drafts)

            lookup.complete(profile())
            advanceUntilIdle()

            assertEquals(
                SettingsUiState(
                    email = "calendar@example.test",
                    account = "DOMAIN\\calendar",
                    serverHost = "exchange.example.test",
                    clientCertificateAlias = "work-certificate",
                    status = ConnectionStatus.CONNECTED,
                    hasSavedProfile = true,
                    isLoading = false,
                ),
                viewModel.state.value,
            )
        }

    @Test
    fun `empty repository initializes an editable form without recheck or probing`() =
        runUiTest {
            val save = RecordingSaveAction()
            val verify = RecordingVerifyAction()
            val viewModel = SettingsViewModel(FakeRepository(null), save, verify)
            advanceUntilIdle()

            assertEquals(SettingsUiState(isLoading = false), viewModel.state.value)
            assertFalse(viewModel.state.value.isRecheckVisible)
            assertFalse(viewModel.state.value.isRecheckEnabled)
            assertEquals(emptyList<ConnectionDraft>(), save.drafts)
            assertEquals(emptyList<ConnectionDraft>(), verify.drafts)
        }

    @Test
    fun `saved profile enables recheck only while form matches the saved values`() =
        runUiTest {
            val verify = RecordingVerifyAction()
            val viewModel = SettingsViewModel(FakeRepository(profile()), RecordingSaveAction(), verify)
            advanceUntilIdle()

            assertTrue(viewModel.state.value.isRecheckVisible)
            assertTrue(viewModel.state.value.isRecheckEnabled)
            viewModel.onServerChanged("draft.example.test")
            assertTrue(viewModel.state.value.hasUnsavedChanges)
            assertFalse(viewModel.state.value.isRecheckEnabled)
            viewModel.onServerChanged("exchange.example.test")
            assertFalse(viewModel.state.value.hasUnsavedChanges)
            assertTrue(viewModel.state.value.isRecheckEnabled)
            assertEquals(emptyList<ConnectionDraft>(), verify.drafts)
        }

    @Test
    fun `failed initial lookup unlocks the form with an error`() =
        runUiTest {
            val repository =
                object : ConnectionProfileRepository {
                    override suspend fun load(): ConnectionProfile? = error("disk read failed")

                    override suspend fun replace(profile: ConnectionProfile) = Unit
                }
            val viewModel = SettingsViewModel(repository, RecordingSaveAction(), RecordingVerifyAction())
            advanceUntilIdle()

            assertFalse(viewModel.state.value.isLoading)
            assertEquals(SettingsConnectionError.UNKNOWN, viewModel.state.value.connectionError)
        }

    @Test
    fun `control availability follows initial loading and either connection operation`() {
        val loading = SettingsUiState()
        assertFalse(loading.areFieldsEnabled)
        assertFalse(loading.isCertificateSelectionEnabled)
        assertFalse(loading.isSaveEnabled)
        assertFalse(loading.isRecheckEnabled)

        val ready = SettingsUiState(isLoading = false, hasSavedProfile = true)
        assertTrue(ready.areFieldsEnabled)
        assertTrue(ready.isCertificateSelectionEnabled)
        assertTrue(ready.isSaveEnabled)
        assertTrue(ready.isRecheckEnabled)

        ConnectionOperation.entries.forEach { operation ->
            val inProgress = SettingsUiState(isLoading = false, hasSavedProfile = true, operation = operation)
            assertFalse(inProgress.areFieldsEnabled)
            assertFalse(inProgress.isCertificateSelectionEnabled)
            assertFalse(inProgress.isSaveEnabled)
            assertFalse(inProgress.isRecheckEnabled)
        }
    }

    @Test
    fun `field edits and certificate cancellation do not save or probe`() =
        runUiTest {
            val save = RecordingSaveAction()
            val verify = RecordingVerifyAction()
            val viewModel = SettingsViewModel(FakeRepository(null), save, verify)
            advanceUntilIdle()

            viewModel.onEmailChanged("draft@example.test")
            viewModel.onAccountChanged("DOMAIN\\draft")
            viewModel.onServerChanged("draft.example.test")
            viewModel.onCertificateSelected("selected-alias")
            viewModel.onCertificateSelected(null)

            assertEquals("draft@example.test", viewModel.state.value.email)
            assertEquals("DOMAIN\\draft", viewModel.state.value.account)
            assertEquals("draft.example.test", viewModel.state.value.serverHost)
            assertEquals("selected-alias", viewModel.state.value.clientCertificateAlias)
            assertEquals(emptyList<ConnectionDraft>(), save.drafts)
            assertEquals(emptyList<ConnectionDraft>(), verify.drafts)
        }

    @Test
    fun `failed save retains attempted draft and exposes actionable error`() =
        runUiTest {
            val save = RecordingSaveAction(SaveConnectionResult.Failed(ConnectionFailure.TIMEOUT))
            val viewModel = SettingsViewModel(FakeRepository(profile()), save, RecordingVerifyAction())
            advanceUntilIdle()
            viewModel.onEmailChanged("draft@example.test")
            viewModel.onServerChanged("draft.example.test")

            viewModel.onSave()
            advanceUntilIdle()

            assertEquals("draft@example.test", viewModel.state.value.email)
            assertEquals("draft.example.test", viewModel.state.value.serverHost)
            assertEquals(SettingsConnectionError.TIMEOUT, viewModel.state.value.connectionError)
            assertEquals(ConnectionStatus.CONNECTED, viewModel.state.value.status)
            assertNull(viewModel.state.value.tlsDiagnostics)
        }

    @Test
    fun `invalid save displays every returned field error`() =
        runUiTest {
            val errors = ConnectionField.entries.associateWith { FieldError.REQUIRED }
            val viewModel =
                SettingsViewModel(
                    FakeRepository(null),
                    RecordingSaveAction(SaveConnectionResult.Invalid(errors)),
                    RecordingVerifyAction(),
                )
            advanceUntilIdle()

            viewModel.onSave()
            advanceUntilIdle()

            assertEquals(errors, viewModel.state.value.fieldErrors)
            assertNull(viewModel.state.value.connectionError)
            assertNull(viewModel.state.value.tlsDiagnostics)
        }

    @Test
    fun `every core failure maps to deterministic presentation data`() {
        val expected =
            mapOf(
                ConnectionFailure.CLIENT_CERTIFICATE_UNAVAILABLE to SettingsConnectionError.CERTIFICATE_UNAVAILABLE,
                ConnectionFailure.SERVER_NOT_FOUND to SettingsConnectionError.SERVER_NOT_FOUND,
                ConnectionFailure.CONNECTION_FAILED to SettingsConnectionError.CONNECTION,
                ConnectionFailure.TIMEOUT to SettingsConnectionError.TIMEOUT,
                ConnectionFailure.SERVER_TRUST to SettingsConnectionError.SERVER_TRUST,
                ConnectionFailure.HOSTNAME_MISMATCH to SettingsConnectionError.HOSTNAME,
                ConnectionFailure.LOCAL_CA_MISSING to SettingsConnectionError.LOCAL_CA_MISSING,
                ConnectionFailure.LOCAL_CA_INVALID to SettingsConnectionError.LOCAL_CA_INVALID,
                ConnectionFailure.CLIENT_CERTIFICATE_REJECTED to SettingsConnectionError.MTLS,
                ConnectionFailure.ACCESS_DENIED to SettingsConnectionError.ACCESS,
                ConnectionFailure.ENDPOINT_MISMATCH to SettingsConnectionError.ENDPOINT,
                ConnectionFailure.REDIRECT_POLICY to SettingsConnectionError.REDIRECT,
                ConnectionFailure.SERVER_ERROR to SettingsConnectionError.SERVER,
                ConnectionFailure.PROTOCOL_INCOMPATIBLE to SettingsConnectionError.PROTOCOL,
                ConnectionFailure.SERVER_CERTIFICATE_DIAGNOSTICS to
                    SettingsConnectionError.SERVER_CERTIFICATE_DIAGNOSTICS,
                ConnectionFailure.PERSISTENCE to SettingsConnectionError.PERSISTENCE,
                ConnectionFailure.UNKNOWN to SettingsConnectionError.UNKNOWN,
            )

        assertEquals(expected, ConnectionFailure.entries.associateWith(::toSettingsConnectionError))
    }

    @Test
    fun `active save blocks edits recheck and another save`() =
        runUiTest {
            val gate = CompletableDeferred<SaveConnectionResult>()
            val save = RecordingSaveAction(gate = gate)
            val verify = RecordingVerifyAction()
            val viewModel = SettingsViewModel(FakeRepository(profile()), save, verify)
            advanceUntilIdle()

            viewModel.onSave()
            assertEquals(ConnectionOperation.SAVE, viewModel.state.value.operation)
            viewModel.onEmailChanged("ignored@example.test")
            viewModel.onRecheck()
            viewModel.onSave()

            assertEquals("calendar@example.test", viewModel.state.value.email)
            assertEquals(1, save.drafts.size)
            assertEquals(emptyList<ConnectionDraft>(), verify.drafts)

            gate.complete(SaveConnectionResult.Saved(profile(), diagnostics()))
            advanceUntilIdle()

            assertNull(viewModel.state.value.operation)
            assertEquals(diagnostics(), viewModel.state.value.tlsDiagnostics)
            assertTrue(viewModel.state.value.isRecheckEnabled)
        }

    @Test
    fun `successful recheck returns current diagnostics without persistence`() =
        runUiTest {
            val repository = FakeRepository(profile())
            val diagnostics = diagnostics()
            val verify = RecordingVerifyAction(VerifyConnectionResult.Verified(profile(), diagnostics))
            val viewModel = SettingsViewModel(repository, RecordingSaveAction(), verify)
            advanceUntilIdle()

            viewModel.onRecheck()
            advanceUntilIdle()

            assertEquals(listOf(validDraft()), verify.drafts)
            assertEquals(0, repository.replaceAttempts)
            assertEquals(ConnectionStatus.CONNECTED, viewModel.state.value.status)
            assertEquals(diagnostics, viewModel.state.value.tlsDiagnostics)
            assertNull(viewModel.state.value.connectionError)
        }

    @Test
    fun `failed recheck clears diagnostics and keeps saved profile unchanged`() =
        runUiTest {
            val repository = FakeRepository(profile())
            val save = RecordingSaveAction(SaveConnectionResult.Saved(profile(), diagnostics()))
            val verify = RecordingVerifyAction(VerifyConnectionResult.Failed(ConnectionFailure.SERVER_TRUST))
            val viewModel = SettingsViewModel(repository, save, verify)
            advanceUntilIdle()

            viewModel.onSave()
            advanceUntilIdle()
            assertEquals(diagnostics(), viewModel.state.value.tlsDiagnostics)
            viewModel.onRecheck()
            advanceUntilIdle()

            assertEquals(1, save.drafts.size)
            assertEquals(listOf(validDraft()), verify.drafts)
            assertEquals(0, repository.replaceAttempts)
            assertEquals(profile(), repository.current)
            assertNull(viewModel.state.value.tlsDiagnostics)
            assertEquals(SettingsConnectionError.SERVER_TRUST, viewModel.state.value.connectionError)
        }

    @Test
    fun `recovered synchronization state exposes status controls permissions and persistent problems`() =
        runUiTest {
            val syncRepository = FakeSyncStateRepository(SyncState.initial().copy(generation = 3, runToken = 4))
            val viewModel =
                SettingsViewModel(
                    repository = FakeRepository(profile()),
                    saveConnection = RecordingSaveAction(),
                    verifyConnection = RecordingVerifyAction(),
                    synchronizationStateRepository = syncRepository,
                    requestSynchronization = RecordingSyncRequest(),
                    synchronizationLifecycle = RecordingSyncLifecycle(),
                )
            advanceUntilIdle()

            assertTrue(viewModel.state.value.isEnableSyncVisible)
            assertFalse(viewModel.state.value.isSyncNowVisible)

            syncRepository.replace(
                SyncState.initial().copy(
                    enabled = true,
                    generation = 3,
                    runToken = 4,
                    phase = SyncPhase.IDLE,
                    lastSuccessfulEpochMillis = 1_800_000_000_000,
                ),
            )
            advanceUntilIdle()
            assertTrue(viewModel.state.value.isSyncNowVisible)
            assertTrue(viewModel.state.value.isSyncNowEnabled)
            assertTrue(viewModel.state.value.isDisableSyncVisible)
            assertEquals(1_800_000_000_000, viewModel.state.value.lastSuccessfulSyncEpochMillis)

            syncRepository.replace(syncRepository.current.copy(phase = SyncPhase.DOWNLOADING))
            advanceUntilIdle()
            assertTrue(viewModel.state.value.isCancelSyncVisible)
            assertEquals(SyncPhase.DOWNLOADING, viewModel.state.value.syncPhase)

            syncRepository.replace(
                syncRepository.current.copy(
                    phase = SyncPhase.BLOCKED,
                    problem = SyncProblem.CALENDAR_PERMISSION,
                    notificationPermissionDenied = true,
                ),
            )
            advanceUntilIdle()
            assertEquals(SyncProblem.CALENDAR_PERMISSION, viewModel.state.value.syncProblem)
            assertTrue(viewModel.state.value.isCalendarPermissionActionVisible)
            assertTrue(viewModel.state.value.isNotificationPermissionActionVisible)
        }

    @Test
    fun `saved profile can retry blocked synchronization but active and repeated attempts stay serialized`() =
        runUiTest {
            val syncRepository =
                FakeSyncStateRepository(
                    SyncState.initial().copy(
                        enabled = true,
                        generation = 3,
                        runToken = 4,
                        phase = SyncPhase.BLOCKED,
                        problem = SyncProblem.TLS,
                    ),
                )
            val request = RecordingSyncRequest()
            val viewModel =
                SettingsViewModel(
                    FakeRepository(profile()),
                    RecordingSaveAction(),
                    RecordingVerifyAction(),
                    syncRepository,
                    request,
                    RecordingSyncLifecycle(),
                )
            advanceUntilIdle()

            assertTrue(viewModel.state.value.isSyncNowVisible)
            assertTrue(viewModel.state.value.isSyncNowEnabled)
            viewModel.onSyncNow()
            advanceUntilIdle()
            assertEquals(1, request.attempts)

            syncRepository.replace(syncRepository.current.copy(phase = SyncPhase.QUEUED, problem = null))
            advanceUntilIdle()
            assertFalse(viewModel.state.value.isSyncNowEnabled)
            viewModel.onSyncNow()
            advanceUntilIdle()
            assertEquals(1, request.attempts)

            syncRepository.replace(syncRepository.current.copy(phase = SyncPhase.BLOCKED, problem = SyncProblem.TLS))
            advanceUntilIdle()
            assertTrue(viewModel.state.value.isSyncNowEnabled)
            viewModel.onSyncNow()
            advanceUntilIdle()
            assertEquals(2, request.attempts)
        }

    @Test
    fun `synchronization controls remain unavailable without a saved profile`() =
        runUiTest {
            val request = RecordingSyncRequest()
            val lifecycle = RecordingSyncLifecycle()
            val viewModel =
                SettingsViewModel(
                    FakeRepository(null),
                    RecordingSaveAction(),
                    RecordingVerifyAction(),
                    FakeSyncStateRepository(
                        SyncState.initial().copy(
                            enabled = true,
                            generation = 3,
                            runToken = 4,
                            phase = SyncPhase.BLOCKED,
                            problem = SyncProblem.TLS,
                        ),
                    ),
                    request,
                    lifecycle,
                )
            advanceUntilIdle()

            assertFalse(viewModel.state.value.isSyncNowVisible)
            assertFalse(viewModel.state.value.isSyncNowEnabled)
            assertFalse(viewModel.state.value.isDisableSyncVisible)
            assertFalse(viewModel.state.value.isEnableSyncVisible)
            assertFalse(viewModel.state.value.isCleanupRetryVisible)
            viewModel.onSyncNow()
            viewModel.onDisableSynchronization()
            viewModel.onEnableSynchronization()
            viewModel.onRetryCalendarCleanup()
            advanceUntilIdle()
            assertEquals(0, request.attempts)
            assertEquals(0, lifecycle.disableAttempts)
            assertEquals(0, lifecycle.enableAttempts)
        }

    @Test
    fun `disabled pending cleanup exposes a serialized cleanup retry without enable`() =
        runUiTest {
            val cleanupGate = CompletableDeferred<Unit>()
            val lifecycle = RecordingSyncLifecycle(disableGate = cleanupGate)
            val viewModel =
                SettingsViewModel(
                    FakeRepository(profile()),
                    RecordingSaveAction(),
                    RecordingVerifyAction(),
                    FakeSyncStateRepository(
                        SyncState.initial().copy(
                            generation = 5,
                            runToken = 7,
                            calendarCleanupPending = true,
                            problem = SyncProblem.CALENDAR_PROVIDER,
                        ),
                    ),
                    RecordingSyncRequest(),
                    lifecycle,
                )
            advanceUntilIdle()

            assertTrue(viewModel.state.value.calendarCleanupPending)
            assertTrue(viewModel.state.value.isCleanupRetryVisible)
            assertTrue(viewModel.state.value.isCleanupRetryEnabled)
            assertFalse(viewModel.state.value.isEnableSyncVisible)
            assertFalse(viewModel.state.value.isSyncNowVisible)

            viewModel.onRetryCalendarCleanup()

            assertEquals(SyncControlOperation.CLEANUP, viewModel.state.value.syncControlOperation)
            assertFalse(viewModel.state.value.isCleanupRetryEnabled)
            assertFalse(viewModel.state.value.isEnableSyncEnabled)
            assertEquals(1, lifecycle.disableAttempts)
            cleanupGate.complete(Unit)
            advanceUntilIdle()
            assertNull(viewModel.state.value.syncControlOperation)
        }

    @Test
    fun `synchronization controls delegate run cancel disable and enable actions`() =
        runUiTest {
            val syncRepository =
                FakeSyncStateRepository(
                    SyncState.initial().copy(enabled = true, generation = 3, runToken = 4, phase = SyncPhase.IDLE),
                )
            val request = RecordingSyncRequest()
            val lifecycle = RecordingSyncLifecycle()
            val viewModel =
                SettingsViewModel(
                    FakeRepository(profile()),
                    RecordingSaveAction(),
                    RecordingVerifyAction(),
                    syncRepository,
                    request,
                    lifecycle,
                )
            advanceUntilIdle()

            viewModel.onSyncNow()
            advanceUntilIdle()
            syncRepository.replace(syncRepository.current.copy(phase = SyncPhase.DOWNLOADING))
            advanceUntilIdle()
            viewModel.onCancelSynchronization()
            advanceUntilIdle()
            viewModel.onDisableSynchronization()
            advanceUntilIdle()
            syncRepository.replace(syncRepository.current.copy(enabled = false, phase = SyncPhase.DISABLED))
            advanceUntilIdle()
            viewModel.onEnableSynchronization()
            advanceUntilIdle()

            assertEquals(1, request.attempts)
            assertEquals(1, lifecycle.cancelAttempts)
            assertEquals(1, lifecycle.disableAttempts)
            assertEquals(1, lifecycle.enableAttempts)
        }

    @Test
    fun `synchronization control failure becomes an actionable UI problem`() =
        runUiTest {
            val lifecycle = RecordingSyncLifecycle(disableFailure = IllegalStateException("scheduler failed"))
            val viewModel =
                SettingsViewModel(
                    FakeRepository(profile()),
                    RecordingSaveAction(),
                    RecordingVerifyAction(),
                    FakeSyncStateRepository(
                        SyncState.initial().copy(enabled = true, generation = 1, runToken = 1, phase = SyncPhase.IDLE),
                    ),
                    RecordingSyncRequest(),
                    lifecycle,
                )
            advanceUntilIdle()

            viewModel.onDisableSynchronization()
            advanceUntilIdle()

            assertEquals(1, lifecycle.disableAttempts)
            assertNull(viewModel.state.value.syncControlOperation)
            assertEquals(SyncProblem.BACKGROUND_SCHEDULING, viewModel.state.value.syncProblem)
        }

    @Test
    fun `active save excludes synchronization control actions`() =
        runUiTest {
            val gate = CompletableDeferred<SaveConnectionResult>()
            val request = RecordingSyncRequest()
            val lifecycle = RecordingSyncLifecycle()
            val viewModel =
                SettingsViewModel(
                    FakeRepository(profile()),
                    RecordingSaveAction(gate = gate),
                    RecordingVerifyAction(),
                    FakeSyncStateRepository(
                        SyncState.initial().copy(enabled = true, generation = 1, runToken = 1, phase = SyncPhase.IDLE),
                    ),
                    request,
                    lifecycle,
                )
            advanceUntilIdle()

            viewModel.onSave()
            viewModel.onSyncNow()
            viewModel.onDisableSynchronization()

            assertFalse(viewModel.state.value.isSyncNowEnabled)
            assertEquals(0, request.attempts)
            assertEquals(0, lifecycle.disableAttempts)
            gate.complete(SaveConnectionResult.Saved(profile(), diagnostics()))
            advanceUntilIdle()
        }

    @Test
    fun `active synchronization control excludes save recheck and profile editing`() =
        runUiTest {
            val disableGate = CompletableDeferred<Unit>()
            val save = RecordingSaveAction()
            val verify = RecordingVerifyAction()
            val lifecycle = RecordingSyncLifecycle(disableGate)
            val viewModel =
                SettingsViewModel(
                    FakeRepository(profile()),
                    save,
                    verify,
                    FakeSyncStateRepository(
                        SyncState.initial().copy(enabled = true, generation = 1, runToken = 1, phase = SyncPhase.IDLE),
                    ),
                    RecordingSyncRequest(),
                    lifecycle,
                )
            advanceUntilIdle()
            val email = viewModel.state.value.email

            viewModel.onDisableSynchronization()
            viewModel.onRecheck()
            viewModel.onSave()
            viewModel.onEmailChanged("other@example.test")

            assertEquals(email, viewModel.state.value.email)
            assertTrue(save.drafts.isEmpty())
            assertTrue(verify.drafts.isEmpty())
            disableGate.complete(Unit)
            advanceUntilIdle()
        }

    private class FakeRepository(
        var current: ConnectionProfile?,
    ) : ConnectionProfileRepository {
        var replaceAttempts: Int = 0

        override suspend fun load(): ConnectionProfile? = current

        override suspend fun replace(profile: ConnectionProfile) {
            replaceAttempts += 1
            current = profile
        }
    }

    private class RecordingSaveAction(
        private val result: SaveConnectionResult = SaveConnectionResult.Failed(ConnectionFailure.UNKNOWN),
        private val gate: CompletableDeferred<SaveConnectionResult>? = null,
    ) : SaveConnectionAction {
        val drafts = mutableListOf<ConnectionDraft>()

        override suspend fun execute(draft: ConnectionDraft): SaveConnectionResult {
            drafts += draft
            return gate?.await() ?: result
        }
    }

    private class RecordingVerifyAction(
        private val result: VerifyConnectionResult = VerifyConnectionResult.Failed(ConnectionFailure.UNKNOWN),
    ) : VerifyConnectionAction {
        val drafts = mutableListOf<ConnectionDraft>()

        override suspend fun execute(draft: ConnectionDraft): VerifyConnectionResult {
            drafts += draft
            return result
        }
    }

    private class FakeSyncStateRepository(initial: SyncState) : SyncStateRepository {
        private val mutableStates = MutableStateFlow(initial)
        override val states: Flow<SyncState> = mutableStates
        val current: SyncState
            get() = mutableStates.value

        override suspend fun load(): SyncState = current

        override suspend fun update(transform: (SyncState) -> SyncState): SyncState =
            transform(current).also { mutableStates.value = it }

        fun replace(state: SyncState) {
            mutableStates.value = state
        }
    }

    private class RecordingSyncRequest : RequestSynchronizationAction {
        var attempts = 0

        override suspend fun execute(): SyncRunRequest {
            attempts += 1
            return SyncRunRequest.Ignored(SyncState.initial())
        }
    }

    private class RecordingSyncLifecycle(
        private val disableGate: CompletableDeferred<Unit>? = null,
        private val disableFailure: Exception? = null,
    ) : SynchronizationLifecycleActions {
        var cancelAttempts = 0
        var disableAttempts = 0
        var enableAttempts = 0

        override suspend fun cancel(): SyncCancellationOutcome {
            cancelAttempts += 1
            return SyncCancellationOutcome.Cancelled
        }

        override suspend fun disable(): SyncDisableOutcome {
            disableAttempts += 1
            disableGate?.await()
            disableFailure?.let { failure -> throw failure }
            return SyncDisableOutcome.Disabled
        }

        override suspend fun enable(): SyncLifecycleOutcome {
            enableAttempts += 1
            return SyncLifecycleOutcome.Ignored
        }

        override suspend fun onCalendarPermissionResult(): SyncLifecycleOutcome = SyncLifecycleOutcome.Ignored

        override suspend fun onNotificationPermissionResult() = Unit
    }

    private fun validDraft(): ConnectionDraft =
        ConnectionDraft(
            email = "calendar@example.test",
            account = "DOMAIN\\calendar",
            serverHost = "exchange.example.test",
            clientCertificateAlias = "work-certificate",
        )

    private fun profile(): ConnectionProfile =
        ConnectionProfile(
            email = "calendar@example.test",
            account = "DOMAIN\\calendar",
            serverHost = "exchange.example.test",
            clientCertificateAlias = "work-certificate",
        )

    private fun diagnostics(): TlsConnectionDiagnostics =
        TlsConnectionDiagnostics(
            terminalHost = "exchange.example.test",
            certificates =
                listOf(
                    TlsCertificateDiagnostic(
                        subject = "CN=exchange.example.test",
                        issuer = "CN=Example CA",
                        serialNumber = "01",
                        validFrom = Instant.parse("2026-01-01T00:00:00Z"),
                        validUntil = Instant.parse("2027-01-01T00:00:00Z"),
                        sha256Fingerprint = "AA:BB",
                    ),
                ),
        )

    private fun runUiTest(block: suspend TestScope.() -> Unit) =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                block()
            } finally {
                Dispatchers.resetMain()
            }
        }
}
