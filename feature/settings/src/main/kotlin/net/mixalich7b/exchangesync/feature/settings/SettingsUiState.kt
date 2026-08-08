package net.mixalich7b.exchangesync.feature.settings

import net.mixalich7b.exchangesync.core.connection.ConnectionFailure
import net.mixalich7b.exchangesync.core.connection.ConnectionField
import net.mixalich7b.exchangesync.core.connection.FieldError
import net.mixalich7b.exchangesync.core.connection.TlsConnectionDiagnostics

public data class SettingsUiState(
    public val email: String = "",
    public val account: String = "",
    public val serverHost: String = "",
    public val clientCertificateAlias: String? = null,
    public val fieldErrors: Map<ConnectionField, FieldError> = emptyMap(),
    public val connectionError: SettingsConnectionError? = null,
    public val tlsDiagnostics: TlsConnectionDiagnostics? = null,
    public val status: ConnectionStatus = ConnectionStatus.UNCONFIGURED,
    public val hasSavedProfile: Boolean = false,
    public val hasUnsavedChanges: Boolean = false,
    public val isLoading: Boolean = true,
    public val operation: ConnectionOperation? = null,
) {
    public val areFieldsEnabled: Boolean
        get() = !isLoading && operation == null

    public val isCertificateSelectionEnabled: Boolean
        get() = !isLoading && operation == null

    public val isSaveEnabled: Boolean
        get() = !isLoading && operation == null

    public val isRecheckVisible: Boolean
        get() = hasSavedProfile

    public val isRecheckEnabled: Boolean
        get() = !isLoading && operation == null && hasSavedProfile && !hasUnsavedChanges

    public val isSaving: Boolean
        get() = operation != null
}

public enum class ConnectionStatus {
    UNCONFIGURED,
    CONNECTED,
}

public enum class ConnectionOperation {
    SAVE,
    RECHECK,
}

public enum class SettingsConnectionError {
    CERTIFICATE_UNAVAILABLE,
    SERVER_NOT_FOUND,
    CONNECTION,
    TIMEOUT,
    SERVER_TRUST,
    HOSTNAME,
    LOCAL_CA_MISSING,
    LOCAL_CA_INVALID,
    MTLS,
    ACCESS,
    ENDPOINT,
    REDIRECT,
    SERVER,
    PROTOCOL,
    SERVER_CERTIFICATE_DIAGNOSTICS,
    PERSISTENCE,
    UNKNOWN,
}

public fun toSettingsConnectionError(failure: ConnectionFailure): SettingsConnectionError =
    when (failure) {
        ConnectionFailure.CLIENT_CERTIFICATE_UNAVAILABLE -> SettingsConnectionError.CERTIFICATE_UNAVAILABLE
        ConnectionFailure.SERVER_NOT_FOUND -> SettingsConnectionError.SERVER_NOT_FOUND
        ConnectionFailure.CONNECTION_FAILED -> SettingsConnectionError.CONNECTION
        ConnectionFailure.TIMEOUT -> SettingsConnectionError.TIMEOUT
        ConnectionFailure.SERVER_TRUST -> SettingsConnectionError.SERVER_TRUST
        ConnectionFailure.HOSTNAME_MISMATCH -> SettingsConnectionError.HOSTNAME
        ConnectionFailure.LOCAL_CA_MISSING -> SettingsConnectionError.LOCAL_CA_MISSING
        ConnectionFailure.LOCAL_CA_INVALID -> SettingsConnectionError.LOCAL_CA_INVALID
        ConnectionFailure.CLIENT_CERTIFICATE_REJECTED -> SettingsConnectionError.MTLS
        ConnectionFailure.ACCESS_DENIED -> SettingsConnectionError.ACCESS
        ConnectionFailure.ENDPOINT_MISMATCH -> SettingsConnectionError.ENDPOINT
        ConnectionFailure.REDIRECT_POLICY -> SettingsConnectionError.REDIRECT
        ConnectionFailure.SERVER_ERROR -> SettingsConnectionError.SERVER
        ConnectionFailure.PROTOCOL_INCOMPATIBLE -> SettingsConnectionError.PROTOCOL
        ConnectionFailure.SERVER_CERTIFICATE_DIAGNOSTICS ->
            SettingsConnectionError.SERVER_CERTIFICATE_DIAGNOSTICS
        ConnectionFailure.PERSISTENCE -> SettingsConnectionError.PERSISTENCE
        ConnectionFailure.UNKNOWN -> SettingsConnectionError.UNKNOWN
    }
