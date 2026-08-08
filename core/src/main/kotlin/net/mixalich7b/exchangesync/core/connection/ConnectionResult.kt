package net.mixalich7b.exchangesync.core.connection

public enum class ConnectionFailure(public val code: String) {
    CLIENT_CERTIFICATE_UNAVAILABLE("client_certificate_unavailable"),
    SERVER_NOT_FOUND("server_not_found"),
    CONNECTION_FAILED("connection_failed"),
    TIMEOUT("timeout"),
    SERVER_TRUST("server_trust"),
    HOSTNAME_MISMATCH("hostname_mismatch"),
    LOCAL_CA_MISSING("local_ca_missing"),
    LOCAL_CA_INVALID("local_ca_invalid"),
    CLIENT_CERTIFICATE_REJECTED("client_certificate_rejected"),
    ACCESS_DENIED("access_denied"),
    ENDPOINT_MISMATCH("endpoint_mismatch"),
    REDIRECT_POLICY("redirect_policy"),
    SERVER_ERROR("server_error"),
    PROTOCOL_INCOMPATIBLE("protocol_incompatible"),
    PERSISTENCE("persistence"),
    UNKNOWN("unknown"),
}

public sealed interface ConnectionCheckResult {
    public data object Success : ConnectionCheckResult

    public data class Failure(public val reason: ConnectionFailure) : ConnectionCheckResult
}
