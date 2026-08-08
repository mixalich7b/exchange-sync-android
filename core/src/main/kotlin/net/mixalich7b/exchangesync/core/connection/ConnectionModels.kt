package net.mixalich7b.exchangesync.core.connection

public data class ConnectionDraft(
    public val email: String = "",
    public val account: String = "",
    public val serverHost: String = "",
    public val clientCertificateAlias: String? = null,
)

public data class ConnectionProfile(
    public val email: String,
    public val account: String,
    public val serverHost: String,
    public val clientCertificateAlias: String,
)

public enum class ConnectionField {
    EMAIL,
    ACCOUNT,
    SERVER_HOST,
    CLIENT_CERTIFICATE,
}

public enum class FieldError {
    REQUIRED,
    MALFORMED,
}

public data class ConnectionValidation(
    public val errors: Map<ConnectionField, FieldError>,
) {
    public val isValid: Boolean
        get() = errors.isEmpty()
}
