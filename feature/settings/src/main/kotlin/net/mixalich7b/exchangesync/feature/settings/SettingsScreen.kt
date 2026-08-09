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
import androidx.compose.material3.OutlinedButton
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import net.mixalich7b.exchangesync.core.connection.ConnectionField
import net.mixalich7b.exchangesync.core.connection.TlsCertificateDiagnostic
import net.mixalich7b.exchangesync.core.sync.SyncPhase
import net.mixalich7b.exchangesync.core.sync.SyncProblem

@Composable
public fun SettingsRoute(
    viewModel: SettingsViewModel,
    onSelectCertificate: () -> Unit,
    onRequestCalendarPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
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
        onRecheck = viewModel::onRecheck,
        onSyncNow = viewModel::onSyncNow,
        onCancelSynchronization = viewModel::onCancelSynchronization,
        onDisableSynchronization = viewModel::onDisableSynchronization,
        onEnableSynchronization = viewModel::onEnableSynchronization,
        onRequestCalendarPermission = onRequestCalendarPermission,
        onRequestNotificationPermission = onRequestNotificationPermission,
        onOpenNotificationSettings = onOpenNotificationSettings,
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
    onRecheck: () -> Unit,
    onSyncNow: () -> Unit,
    onCancelSynchronization: () -> Unit,
    onDisableSynchronization: () -> Unit,
    onEnableSynchronization: () -> Unit,
    onRequestCalendarPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
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
            ConnectionFeedback(uiState)
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
                    if (uiState.operation == ConnectionOperation.SAVE) {
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
            if (uiState.isRecheckVisible) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onRecheck,
                    enabled = uiState.isRecheckEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (uiState.operation == ConnectionOperation.RECHECK) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                            Text(stringResource(R.string.connection_rechecking))
                        } else {
                            Text(stringResource(R.string.recheck_connection))
                        }
                    }
                }
                if (uiState.hasUnsavedChanges) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.save_changes_before_recheck),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (uiState.hasSavedProfile) {
                Spacer(modifier = Modifier.height(32.dp))
                SynchronizationSection(
                    uiState = uiState,
                    onSyncNow = onSyncNow,
                    onCancelSynchronization = onCancelSynchronization,
                    onDisableSynchronization = onDisableSynchronization,
                    onEnableSynchronization = onEnableSynchronization,
                    onRequestCalendarPermission = onRequestCalendarPermission,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                )
            }
        }
    }
}

@Composable
private fun SynchronizationSection(
    uiState: SettingsUiState,
    onSyncNow: () -> Unit,
    onCancelSynchronization: () -> Unit,
    onDisableSynchronization: () -> Unit,
    onEnableSynchronization: () -> Unit,
    onRequestCalendarPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = stringResource(uiState.syncPhase.messageResource()),
        color =
            if (uiState.syncProblem == null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text =
            uiState.lastSuccessfulSyncEpochMillis?.let { epochMillis ->
                stringResource(R.string.sync_last_success, Instant.ofEpochMilli(epochMillis).localizedDateTime())
            } ?: stringResource(R.string.sync_never_completed),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (uiState.syncEnabled) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            stringResource(R.string.sync_periodic_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    uiState.syncProblem?.let { problem ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(problem.messageResource()),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (uiState.isCalendarPermissionActionVisible) {
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onRequestCalendarPermission,
            enabled = uiState.operation == null && uiState.syncControlOperation == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.sync_grant_calendar_access))
        }
    }
    if (uiState.isNotificationPermissionActionVisible) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.sync_notification_permission_denied),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onRequestNotificationPermission, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sync_grant_notification_access))
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenNotificationSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sync_open_notification_settings))
        }
    }
    if (uiState.isSyncNowVisible) {
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onSyncNow, enabled = uiState.isSyncNowEnabled, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sync_now))
        }
    }
    if (uiState.isCancelSyncVisible) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onCancelSynchronization,
            enabled = uiState.isCancelSyncEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.sync_cancel))
        }
    }
    if (uiState.isDisableSyncVisible) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onDisableSynchronization,
            enabled = uiState.isDisableSyncEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.sync_disable))
        }
    }
    if (uiState.isEnableSyncVisible) {
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onEnableSynchronization,
            enabled = uiState.isEnableSyncEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.sync_enable))
        }
    }
}

@Composable
private fun ConnectionFeedback(uiState: SettingsUiState) {
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
    uiState.tlsDiagnostics?.let { diagnostics ->
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.tls_diagnostics_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.tls_terminal_host, diagnostics.terminalHost),
            style = MaterialTheme.typography.bodyMedium,
        )
        diagnostics.certificates.forEachIndexed { index, certificate ->
            Spacer(modifier = Modifier.height(12.dp))
            TlsCertificateFeedback(index + 1, certificate)
        }
    }
}

@Composable
private fun TlsCertificateFeedback(
    index: Int,
    certificate: TlsCertificateDiagnostic,
) {
    Text(
        text = stringResource(R.string.tls_certificate_number, index),
        style = MaterialTheme.typography.titleSmall,
    )
    Text(stringResource(R.string.tls_subject, certificate.subject), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.tls_issuer, certificate.issuer), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.tls_serial_number, certificate.serialNumber), style = MaterialTheme.typography.bodySmall)
    Text(
        stringResource(R.string.tls_valid_from, certificate.validFrom.localizedDate()),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(R.string.tls_valid_until, certificate.validUntil.localizedDate()),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(R.string.tls_fingerprint, certificate.sha256Fingerprint),
        style = MaterialTheme.typography.bodySmall,
    )
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
        SettingsConnectionError.SERVER_CERTIFICATE_DIAGNOSTICS ->
            R.string.error_server_certificate_diagnostics
        SettingsConnectionError.PERSISTENCE -> R.string.error_persistence
        SettingsConnectionError.UNKNOWN -> R.string.error_unknown
    }

private fun Instant.localizedDate(): String =
    DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(this)

private fun Instant.localizedDateTime(): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(this)

private fun SyncPhase.messageResource(): Int =
    when (this) {
        SyncPhase.DISABLED -> R.string.sync_disabled
        SyncPhase.IDLE -> R.string.sync_idle
        SyncPhase.QUEUED -> R.string.sync_queued
        SyncPhase.DISCOVERING_PROTOCOL -> R.string.sync_discovering_protocol
        SyncPhase.DISCOVERING_FOLDERS -> R.string.sync_discovering_folders
        SyncPhase.DOWNLOADING -> R.string.sync_downloading
        SyncPhase.APPLYING -> R.string.sync_applying
        SyncPhase.CANCELLING -> R.string.sync_cancelling
        SyncPhase.BLOCKED -> R.string.sync_blocked
    }

private fun SyncProblem.messageResource(): Int =
    when (this) {
        SyncProblem.CLIENT_CERTIFICATE -> R.string.sync_problem_certificate
        SyncProblem.TLS -> R.string.sync_problem_tls
        SyncProblem.ACCESS,
        SyncProblem.REDIRECT,
        -> R.string.sync_problem_access
        SyncProblem.COMPATIBILITY,
        SyncProblem.UNSUPPORTED_PROVISIONING,
        -> R.string.sync_problem_compatibility
        SyncProblem.PRIMARY_CALENDAR -> R.string.sync_problem_primary_calendar
        SyncProblem.REPEATED_INVALID_KEY,
        SyncProblem.PROTOCOL_DATA,
        -> R.string.sync_problem_protocol_data
        SyncProblem.CALENDAR_PERMISSION -> R.string.sync_problem_calendar_permission
        SyncProblem.CALENDAR_PROVIDER,
        SyncProblem.BACKGROUND_SCHEDULING,
        -> R.string.sync_problem_calendar_provider
        SyncProblem.TRANSIENT_EXHAUSTED -> R.string.sync_problem_availability
    }
