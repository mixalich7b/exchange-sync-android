package net.mixalich7b.exchangesync.core.connection

import kotlinx.coroutines.CancellationException

public sealed interface SaveConnectionResult {
    public data class Invalid(
        public val errors: Map<ConnectionField, FieldError>,
    ) : SaveConnectionResult

    public data class Failed(public val reason: ConnectionFailure) : SaveConnectionResult

    public data class Saved(
        public val profile: ConnectionProfile,
        public val diagnostics: TlsConnectionDiagnostics,
    ) : SaveConnectionResult
}

public fun interface SaveConnectionAction {
    public suspend fun execute(draft: ConnectionDraft): SaveConnectionResult
}

public class SaveConnection(
    private val repository: ConnectionProfileRepository,
    private val verifyConnection: VerifyConnectionAction,
) : SaveConnectionAction {
    override suspend fun execute(draft: ConnectionDraft): SaveConnectionResult {
        return when (val result = verifyConnection.execute(draft)) {
            is VerifyConnectionResult.Invalid -> SaveConnectionResult.Invalid(result.errors)
            is VerifyConnectionResult.Failed -> SaveConnectionResult.Failed(result.reason)
            is VerifyConnectionResult.Verified -> persist(result.profile, result.diagnostics)
        }
    }

    private suspend fun persist(
        profile: ConnectionProfile,
        diagnostics: TlsConnectionDiagnostics,
    ): SaveConnectionResult =
        try {
            repository.replace(profile)
            SaveConnectionResult.Saved(profile, diagnostics)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            SaveConnectionResult.Failed(ConnectionFailure.PERSISTENCE)
        }
}
