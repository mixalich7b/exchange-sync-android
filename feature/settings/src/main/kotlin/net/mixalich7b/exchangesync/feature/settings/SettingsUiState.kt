package net.mixalich7b.exchangesync.feature.settings

import net.mixalich7b.exchangesync.core.connection.ConnectionFailure
import net.mixalich7b.exchangesync.core.connection.ConnectionField
import net.mixalich7b.exchangesync.core.connection.FieldError

public data class SettingsUiState(
    public val email: String = "",
    public val account: String = "",
    public val serverHost: String = "",
    public val clientCertificateAlias: String? = null,
    public val fieldErrors: Map<ConnectionField, FieldError> = emptyMap(),
    public val connectionError: SettingsConnectionError? = null,
    public val status: ConnectionStatus = ConnectionStatus.UNCONFIGURED,
    public val isLoading: Boolean = true,
    public val isSaving: Boolean = false,
) {
    public val areFieldsEnabled: Boolean
        get() = !isLoading

    public val isCertificateSelectionEnabled: Boolean
        get() = !isLoading && !isSaving

    public val isSaveEnabled: Boolean
        get() = !isLoading && !isSaving
}

public enum class ConnectionStatus {
    UNCONFIGURED,
    CONNECTED,
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
        ConnectionFailure.PERSISTENCE -> SettingsConnectionError.PERSISTENCE
        ConnectionFailure.UNKNOWN -> SettingsConnectionError.UNKNOWN
    }
