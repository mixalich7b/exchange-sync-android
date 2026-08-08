package net.mixalich7b.exchangesync.feature.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.mixalich7b.exchangesync.core.connection.ConnectionDraft
import net.mixalich7b.exchangesync.core.connection.ConnectionFailure
import net.mixalich7b.exchangesync.core.connection.ConnectionField
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.connection.ConnectionProfileRepository
import net.mixalich7b.exchangesync.core.connection.FieldError
import net.mixalich7b.exchangesync.core.connection.SaveConnectionAction
import net.mixalich7b.exchangesync.core.connection.SaveConnectionResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
            val viewModel = SettingsViewModel(repository, save)

            assertTrue(viewModel.state.value.isLoading)
            viewModel.onEmailChanged("overwritten@example.test")
            viewModel.onAccountChanged("DOMAIN\\overwritten")
            viewModel.onServerChanged("overwritten.example.test")
            viewModel.onCertificateSelected("overwritten-certificate")
            viewModel.onSave()

            assertEquals(SettingsUiState(), viewModel.state.value)
            assertEquals(emptyList<ConnectionDraft>(), save.drafts)

            lookup.complete(profile())
            advanceUntilIdle()

            assertEquals(
                SettingsUiState(
                    email = "calendar@example.test",
                    account = "DOMAIN\\calendar",
                    serverHost = "exchange.example.test",
                    clientCertificateAlias = "work-certificate",
                    status = ConnectionStatus.CONNECTED,
                    isLoading = false,
                ),
                viewModel.state.value,
            )
        }

    @Test
    fun `empty repository initializes an editable unconfigured form without probing`() =
        runUiTest {
            val save = RecordingSaveAction()
            val viewModel = SettingsViewModel(FakeRepository(null), save)
            advanceUntilIdle()

            assertEquals(SettingsUiState(isLoading = false), viewModel.state.value)
            assertEquals(emptyList<ConnectionDraft>(), save.drafts)
        }

    @Test
    fun `saved profile initializes populated connected form`() =
        runUiTest {
            val viewModel = SettingsViewModel(FakeRepository(profile()), RecordingSaveAction())
            advanceUntilIdle()

            assertEquals(
                SettingsUiState(
                    email = "calendar@example.test",
                    account = "DOMAIN\\calendar",
                    serverHost = "exchange.example.test",
                    clientCertificateAlias = "work-certificate",
                    status = ConnectionStatus.CONNECTED,
                    isLoading = false,
                ),
                viewModel.state.value,
            )
        }

    @Test
    fun `failed initial lookup unlocks the form with an error`() =
        runUiTest {
            val repository =
                object : ConnectionProfileRepository {
                    override suspend fun load(): ConnectionProfile? = error("disk read failed")

                    override suspend fun replace(profile: ConnectionProfile) = Unit
                }
            val viewModel = SettingsViewModel(repository, RecordingSaveAction())
            advanceUntilIdle()

            assertFalse(viewModel.state.value.isLoading)
            assertEquals(SettingsConnectionError.UNKNOWN, viewModel.state.value.connectionError)
        }

    @Test
    fun `control availability follows initial loading and save progress`() {
        val loading = SettingsUiState()
        assertFalse(loading.areFieldsEnabled)
        assertFalse(loading.isCertificateSelectionEnabled)
        assertFalse(loading.isSaveEnabled)

        val ready = SettingsUiState(isLoading = false)
        assertTrue(ready.areFieldsEnabled)
        assertTrue(ready.isCertificateSelectionEnabled)
        assertTrue(ready.isSaveEnabled)

        val saving = SettingsUiState(isLoading = false, isSaving = true)
        assertTrue(saving.areFieldsEnabled)
        assertFalse(saving.isCertificateSelectionEnabled)
        assertFalse(saving.isSaveEnabled)
    }

    @Test
    fun `field edits and certificate cancellation do not save or probe`() =
        runUiTest {
            val save = RecordingSaveAction()
            val viewModel = SettingsViewModel(FakeRepository(null), save)
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
        }

    @Test
    fun `failed save retains attempted draft and exposes actionable error`() =
        runUiTest {
            val save = RecordingSaveAction(SaveConnectionResult.Failed(ConnectionFailure.TIMEOUT))
            val viewModel = SettingsViewModel(FakeRepository(profile()), save)
            advanceUntilIdle()
            viewModel.onEmailChanged("draft@example.test")
            viewModel.onServerChanged("draft.example.test")

            viewModel.onSave()
            advanceUntilIdle()

            assertEquals("draft@example.test", viewModel.state.value.email)
            assertEquals("draft.example.test", viewModel.state.value.serverHost)
            assertEquals(SettingsConnectionError.TIMEOUT, viewModel.state.value.connectionError)
            assertEquals(ConnectionStatus.CONNECTED, viewModel.state.value.status)
        }

    @Test
    fun `invalid save displays every returned field error`() =
        runUiTest {
            val errors = ConnectionField.entries.associateWith { FieldError.REQUIRED }
            val viewModel =
                SettingsViewModel(
                    FakeRepository(null),
                    RecordingSaveAction(SaveConnectionResult.Invalid(errors)),
                )
            advanceUntilIdle()

            viewModel.onSave()
            advanceUntilIdle()

            assertEquals(errors, viewModel.state.value.fieldErrors)
            assertEquals(null, viewModel.state.value.connectionError)
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
                ConnectionFailure.PERSISTENCE to SettingsConnectionError.PERSISTENCE,
                ConnectionFailure.UNKNOWN to SettingsConnectionError.UNKNOWN,
            )

        assertEquals(expected, ConnectionFailure.entries.associateWith(::toSettingsConnectionError))
    }

    @Test
    fun `save progress prevents a second simultaneous attempt and success connects`() =
        runUiTest {
            val gate = CompletableDeferred<SaveConnectionResult>()
            val save = RecordingSaveAction(gate = gate)
            val viewModel = SettingsViewModel(FakeRepository(null), save)
            advanceUntilIdle()
            enterValidDraft(viewModel)

            viewModel.onSave()
            assertTrue(viewModel.state.value.isSaving)
            viewModel.onSave()
            assertEquals(1, save.drafts.size)

            gate.complete(SaveConnectionResult.Saved(profile()))
            advanceUntilIdle()

            assertFalse(viewModel.state.value.isSaving)
            assertEquals(ConnectionStatus.CONNECTED, viewModel.state.value.status)
            assertEquals(null, viewModel.state.value.connectionError)
            assertEquals(emptyMap<ConnectionField, FieldError>(), viewModel.state.value.fieldErrors)
        }

    private class FakeRepository(
        private var profile: ConnectionProfile?,
    ) : ConnectionProfileRepository {
        override suspend fun load(): ConnectionProfile? = profile

        override suspend fun replace(profile: ConnectionProfile) {
            this.profile = profile
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

    private fun enterValidDraft(viewModel: SettingsViewModel) {
        viewModel.onEmailChanged("calendar@example.test")
        viewModel.onAccountChanged("DOMAIN\\calendar")
        viewModel.onServerChanged("exchange.example.test")
        viewModel.onCertificateSelected("work-certificate")
    }

    private fun profile(): ConnectionProfile =
        ConnectionProfile(
            email = "calendar@example.test",
            account = "DOMAIN\\calendar",
            serverHost = "exchange.example.test",
            clientCertificateAlias = "work-certificate",
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
