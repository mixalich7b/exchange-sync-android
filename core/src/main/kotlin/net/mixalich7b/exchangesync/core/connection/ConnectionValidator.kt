package net.mixalich7b.exchangesync.core.connection

public object ConnectionValidator {
    private val hostLabel = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

    public fun validate(draft: ConnectionDraft): ConnectionValidation {
        val errors = linkedMapOf<ConnectionField, FieldError>()

        validateRequired(draft.email, ::isEmailValid)?.let { errors[ConnectionField.EMAIL] = it }
        validateRequired(draft.account, ::isAccountValid)?.let { errors[ConnectionField.ACCOUNT] = it }
        validateRequired(draft.serverHost, ::isHostValid)?.let { errors[ConnectionField.SERVER_HOST] = it }
        if (draft.clientCertificateAlias.isNullOrBlank()) {
            errors[ConnectionField.CLIENT_CERTIFICATE] = FieldError.REQUIRED
        }

        return ConnectionValidation(errors)
    }

    public fun toProfile(draft: ConnectionDraft): ConnectionProfile? {
        if (!validate(draft).isValid) return null
        val alias = draft.clientCertificateAlias ?: return null
        return ConnectionProfile(
            email = draft.email,
            account = draft.account,
            serverHost = draft.serverHost,
            clientCertificateAlias = alias,
        )
    }

    private fun validateRequired(value: String, validator: (String) -> Boolean): FieldError? =
        when {
            value.isBlank() -> FieldError.REQUIRED
            !validator(value) -> FieldError.MALFORMED
            else -> null
        }

    private fun isEmailValid(value: String): Boolean {
        if (value.any(Char::isWhitespace)) return false
        val separator = value.indexOf('@')
        return separator > 0 && separator == value.lastIndexOf('@') && separator < value.lastIndex
    }

    private fun isAccountValid(value: String): Boolean {
        if (value.any(Char::isWhitespace)) return false
        val separator = value.indexOf('\\')
        return separator > 0 && separator == value.lastIndexOf('\\') && separator < value.lastIndex
    }

    private fun isHostValid(value: String): Boolean {
        if (value.length > 253 || value.any(Char::isWhitespace)) return false
        if (value.contains("://") || value.any { it in "/?:#" }) return false
        return value.split('.').all { label -> hostLabel.matches(label) }
    }
}
