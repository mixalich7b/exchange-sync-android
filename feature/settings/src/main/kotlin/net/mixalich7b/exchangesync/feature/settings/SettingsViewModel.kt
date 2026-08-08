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

public class SettingsViewModel(
    private val repository: ConnectionProfileRepository,
    private val saveConnection: SaveConnectionAction,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    public val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val profile = repository.load()
                mutableState.value =
                    if (profile == null) {
                        SettingsUiState(isLoading = false)
                    } else {
                        SettingsUiState(
                            email = profile.email,
                            account = profile.account,
                            serverHost = profile.serverHost,
                            clientCertificateAlias = profile.clientCertificateAlias,
                            status = ConnectionStatus.CONNECTED,
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
        if (current.isLoading || current.isSaving) return
        val draft =
            ConnectionDraft(
                email = current.email,
                account = current.account,
                serverHost = current.serverHost,
                clientCertificateAlias = current.clientCertificateAlias,
            )
        mutableState.value = current.copy(isSaving = true, fieldErrors = emptyMap(), connectionError = null)

        viewModelScope.launch {
            val result = saveConnection.execute(draft)
            mutableState.update { state -> state.after(result) }
        }
    }

    private fun updateField(
        field: ConnectionField,
        transform: (SettingsUiState) -> SettingsUiState,
    ) {
        mutableState.update { current ->
            if (current.isLoading) {
                current
            } else {
                transform(current).copy(fieldErrors = current.fieldErrors - field)
            }
        }
    }

    private fun SettingsUiState.after(result: SaveConnectionResult): SettingsUiState =
        when (result) {
            is SaveConnectionResult.Invalid ->
                copy(isSaving = false, fieldErrors = result.errors, connectionError = null)
            is SaveConnectionResult.Failed ->
                copy(
                    isSaving = false,
                    fieldErrors = emptyMap(),
                    connectionError = toSettingsConnectionError(result.reason),
                )
            is SaveConnectionResult.Saved ->
                copy(
                    isSaving = false,
                    fieldErrors = emptyMap(),
                    connectionError = null,
                    status = ConnectionStatus.CONNECTED,
                )
        }
}
