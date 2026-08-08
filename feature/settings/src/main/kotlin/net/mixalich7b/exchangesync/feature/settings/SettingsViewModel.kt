package net.mixalich7b.exchangesync.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.mixalich7b.exchangesync.core.connection.ConnectionDraft
import net.mixalich7b.exchangesync.core.connection.ConnectionField
import net.mixalich7b.exchangesync.core.connection.ConnectionProfileRepository
import net.mixalich7b.exchangesync.core.connection.SaveConnectionAction
import net.mixalich7b.exchangesync.core.connection.SaveConnectionResult
import net.mixalich7b.exchangesync.core.connection.VerifyConnectionAction
import net.mixalich7b.exchangesync.core.connection.VerifyConnectionResult

public class SettingsViewModel(
    private val repository: ConnectionProfileRepository,
    private val saveConnection: SaveConnectionAction,
    private val verifyConnection: VerifyConnectionAction,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    public val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()
    private var savedProfile: net.mixalich7b.exchangesync.core.connection.ConnectionProfile? = null

    init {
        viewModelScope.launch {
            try {
                val profile = repository.load()
                mutableState.value =
                    if (profile == null) {
                        SettingsUiState(isLoading = false)
                    } else {
                        savedProfile = profile
                        SettingsUiState(
                            email = profile.email,
                            account = profile.account,
                            serverHost = profile.serverHost,
                            clientCertificateAlias = profile.clientCertificateAlias,
                            status = ConnectionStatus.CONNECTED,
                            hasSavedProfile = true,
                            isLoading = false,
                        )
                    }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                mutableState.update { current ->
                    current.copy(
                        connectionError = SettingsConnectionError.UNKNOWN,
                        isLoading = false,
                    )
                }
            }
        }
    }

    public fun onEmailChanged(value: String) {
        updateField(ConnectionField.EMAIL) { current -> current.copy(email = value) }
    }

    public fun onAccountChanged(value: String) {
        updateField(ConnectionField.ACCOUNT) { current -> current.copy(account = value) }
    }

    public fun onServerChanged(value: String) {
        updateField(ConnectionField.SERVER_HOST) { current -> current.copy(serverHost = value) }
    }

    public fun onCertificateSelected(alias: String?) {
        if (alias == null) return
        updateField(ConnectionField.CLIENT_CERTIFICATE) { current ->
            current.copy(clientCertificateAlias = alias)
        }
    }

    public fun onSave() {
        val current = mutableState.value
        if (current.isLoading || current.operation != null) return
        val draft = current.toDraft()
        mutableState.value = current.start(ConnectionOperation.SAVE)

        viewModelScope.launch {
            val result = saveConnection.execute(draft)
            mutableState.update { state -> state.afterSave(result) }
        }
    }

    public fun onRecheck() {
        val current = mutableState.value
        val profile = savedProfile
        if (
            current.isLoading ||
                current.operation != null ||
                profile == null ||
                current.hasUnsavedChanges
        ) {
            return
        }
        mutableState.value = current.start(ConnectionOperation.RECHECK)

        viewModelScope.launch {
            val result = verifyConnection.execute(profile.toDraft())
            mutableState.update { state -> state.afterRecheck(result) }
        }
    }

    private fun updateField(
        field: ConnectionField,
        transform: (SettingsUiState) -> SettingsUiState,
    ) {
        mutableState.update { current ->
            if (current.isLoading || current.operation != null) {
                current
            } else {
                val updated = transform(current)
                updated.copy(
                    fieldErrors = current.fieldErrors - field,
                    connectionError = null,
                    tlsDiagnostics = null,
                    hasUnsavedChanges = !updated.matches(savedProfile),
                )
            }
        }
    }

    private fun SettingsUiState.start(operation: ConnectionOperation): SettingsUiState =
        copy(
            operation = operation,
            fieldErrors = emptyMap(),
            connectionError = null,
            tlsDiagnostics = null,
        )

    private fun SettingsUiState.afterSave(result: SaveConnectionResult): SettingsUiState {
        if (operation != ConnectionOperation.SAVE) return this
        return when (result) {
            is SaveConnectionResult.Invalid ->
                copy(operation = null, fieldErrors = result.errors, connectionError = null, tlsDiagnostics = null)
            is SaveConnectionResult.Failed ->
                copy(
                    operation = null,
                    fieldErrors = emptyMap(),
                    connectionError = toSettingsConnectionError(result.reason),
                    tlsDiagnostics = null,
                )
            is SaveConnectionResult.Saved -> {
                savedProfile = result.profile
                copy(
                    operation = null,
                    fieldErrors = emptyMap(),
                    connectionError = null,
                    tlsDiagnostics = result.diagnostics,
                    status = ConnectionStatus.CONNECTED,
                    hasSavedProfile = true,
                    hasUnsavedChanges = false,
                )
            }
        }
    }

    private fun SettingsUiState.afterRecheck(result: VerifyConnectionResult): SettingsUiState {
        if (operation != ConnectionOperation.RECHECK) return this
        return when (result) {
            is VerifyConnectionResult.Invalid ->
                copy(operation = null, fieldErrors = result.errors, connectionError = null, tlsDiagnostics = null)
            is VerifyConnectionResult.Failed ->
                copy(
                    operation = null,
                    fieldErrors = emptyMap(),
                    connectionError = toSettingsConnectionError(result.reason),
                    tlsDiagnostics = null,
                )
            is VerifyConnectionResult.Verified ->
                copy(
                    operation = null,
                    fieldErrors = emptyMap(),
                    connectionError = null,
                    tlsDiagnostics = result.diagnostics,
                    status = ConnectionStatus.CONNECTED,
                )
        }
    }

    private fun SettingsUiState.toDraft(): ConnectionDraft =
        ConnectionDraft(
            email = email,
            account = account,
            serverHost = serverHost,
            clientCertificateAlias = clientCertificateAlias,
        )

    private fun net.mixalich7b.exchangesync.core.connection.ConnectionProfile.toDraft(): ConnectionDraft =
        ConnectionDraft(
            email = email,
            account = account,
            serverHost = serverHost,
            clientCertificateAlias = clientCertificateAlias,
        )

    private fun SettingsUiState.matches(profile: net.mixalich7b.exchangesync.core.connection.ConnectionProfile?): Boolean =
        profile != null &&
            email == profile.email &&
            account == profile.account &&
            serverHost == profile.serverHost &&
            clientCertificateAlias == profile.clientCertificateAlias
}
