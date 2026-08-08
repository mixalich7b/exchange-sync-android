package net.mixalich7b.exchangesync.core.connection

public sealed interface VerifyConnectionResult {
    public data class Invalid(
        public val errors: Map<ConnectionField, FieldError>,
    ) : VerifyConnectionResult

    public data class Failed(public val reason: ConnectionFailure) : VerifyConnectionResult

    public data class Verified(
        public val profile: ConnectionProfile,
        public val diagnostics: TlsConnectionDiagnostics,
    ) : VerifyConnectionResult
}

public fun interface VerifyConnectionAction {
    public suspend fun execute(draft: ConnectionDraft): VerifyConnectionResult
}

public class VerifyConnection(
    private val verifier: ConnectionVerifier,
) : VerifyConnectionAction {
    override suspend fun execute(draft: ConnectionDraft): VerifyConnectionResult {
        val validation = ConnectionValidator.validate(draft)
        if (!validation.isValid) return VerifyConnectionResult.Invalid(validation.errors)
        val profile =
            ConnectionValidator.toProfile(draft)
                ?: return VerifyConnectionResult.Invalid(validation.errors)

        return when (val result = verifier.verify(profile)) {
            is ConnectionCheckResult.Success -> VerifyConnectionResult.Verified(profile, result.diagnostics)
            is ConnectionCheckResult.Failure -> VerifyConnectionResult.Failed(result.reason)
        }
    }
}
