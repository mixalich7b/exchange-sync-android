package net.mixalich7b.exchangesync.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.mixalich7b.exchangesync.core.connection.ConnectionField

@Composable
public fun SettingsRoute(
    viewModel: SettingsViewModel,
    onSelectCertificate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(
        uiState = uiState,
        onEmailChanged = viewModel::onEmailChanged,
        onAccountChanged = viewModel::onAccountChanged,
        onServerChanged = viewModel::onServerChanged,
        onSelectCertificate = onSelectCertificate,
        onSave = viewModel::onSave,
        modifier = modifier,
    )
}

@Composable
public fun SettingsScreen(
    uiState: SettingsUiState,
    onEmailChanged: (String) -> Unit,
    onAccountChanged: (String) -> Unit,
    onServerChanged: (String) -> Unit,
    onSelectCertificate: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            if (uiState.isLoading) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                    Text(stringResource(R.string.connection_loading))
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            ConnectionTextField(
                value = uiState.email,
                onValueChange = onEmailChanged,
                label = stringResource(R.string.email_label),
                error = uiState.fieldErrors.containsKey(ConnectionField.EMAIL),
                errorText = stringResource(R.string.error_email),
                enabled = uiState.areFieldsEnabled,
                keyboardType = KeyboardType.Email,
            )
            Spacer(modifier = Modifier.height(12.dp))
            ConnectionTextField(
                value = uiState.account,
                onValueChange = onAccountChanged,
                label = stringResource(R.string.account_label),
                error = uiState.fieldErrors.containsKey(ConnectionField.ACCOUNT),
                errorText = stringResource(R.string.error_account),
                enabled = uiState.areFieldsEnabled,
            )
            Spacer(modifier = Modifier.height(12.dp))
            ConnectionTextField(
                value = uiState.serverHost,
                onValueChange = onServerChanged,
                label = stringResource(R.string.server_label),
                error = uiState.fieldErrors.containsKey(ConnectionField.SERVER_HOST),
                errorText = stringResource(R.string.error_server),
                enabled = uiState.areFieldsEnabled,
                supportingText = stringResource(R.string.server_supporting_text),
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.client_certificate_label),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text =
                    uiState.clientCertificateAlias
                        ?: stringResource(R.string.no_certificate_selected),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (uiState.fieldErrors.containsKey(ConnectionField.CLIENT_CERTIFICATE)) {
                Text(
                    text = stringResource(R.string.error_certificate_required),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onSelectCertificate, enabled = uiState.isCertificateSelectionEnabled) {
                Text(
                    text =
                        stringResource(
                            if (uiState.clientCertificateAlias == null) {
                                R.string.choose_certificate
                            } else {
                                R.string.replace_certificate
                            },
                        ),
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text =
                    stringResource(
                        if (uiState.status == ConnectionStatus.CONNECTED) {
                            R.string.connection_connected
                        } else {
                            R.string.connection_not_configured
                        },
                    ),
                color =
                    if (uiState.status == ConnectionStatus.CONNECTED) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                style = MaterialTheme.typography.bodyLarge,
            )
            uiState.connectionError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(error.messageResource()),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onSave,
                enabled = uiState.isSaveEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                        Text(stringResource(R.string.connection_checking))
                    } else {
                        Text(stringResource(R.string.save_connection))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: Boolean,
    errorText: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled,
        isError = error,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = {
            when {
                error -> Text(errorText)
                supportingText != null -> Text(supportingText)
            }
        },
    )
}

private fun SettingsConnectionError.messageResource(): Int =
    when (this) {
        SettingsConnectionError.CERTIFICATE_UNAVAILABLE -> R.string.error_certificate_unavailable
        SettingsConnectionError.SERVER_NOT_FOUND -> R.string.error_server_not_found
        SettingsConnectionError.CONNECTION -> R.string.error_connection
        SettingsConnectionError.TIMEOUT -> R.string.error_timeout
        SettingsConnectionError.SERVER_TRUST -> R.string.error_server_trust
        SettingsConnectionError.HOSTNAME -> R.string.error_hostname
        SettingsConnectionError.LOCAL_CA_MISSING -> R.string.error_local_ca_missing
        SettingsConnectionError.LOCAL_CA_INVALID -> R.string.error_local_ca_invalid
        SettingsConnectionError.MTLS -> R.string.error_mtls
        SettingsConnectionError.ACCESS -> R.string.error_access
        SettingsConnectionError.ENDPOINT -> R.string.error_endpoint
        SettingsConnectionError.REDIRECT -> R.string.error_redirect
        SettingsConnectionError.SERVER -> R.string.error_server_response
        SettingsConnectionError.PROTOCOL -> R.string.error_protocol
        SettingsConnectionError.PERSISTENCE -> R.string.error_persistence
        SettingsConnectionError.UNKNOWN -> R.string.error_unknown
    }
