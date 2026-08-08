package net.mixalich7b.exchangesync.core.connection

import kotlinx.coroutines.CancellationException

public sealed interface SaveConnectionResult {
    public data class Invalid(
        public val errors: Map<ConnectionField, FieldError>,
    ) : SaveConnectionResult

    public data class Failed(public val reason: ConnectionFailure) : SaveConnectionResult

    public data class Saved(public val profile: ConnectionProfile) : SaveConnectionResult
}

public fun interface SaveConnectionAction {
    public suspend fun execute(draft: ConnectionDraft): SaveConnectionResult
}

public class SaveConnection(
    private val repository: ConnectionProfileRepository,
    private val verifier: ConnectionVerifier,
) : SaveConnectionAction {
    override suspend fun execute(draft: ConnectionDraft): SaveConnectionResult {
        val validation = ConnectionValidator.validate(draft)
        if (!validation.isValid) return SaveConnectionResult.Invalid(validation.errors)
        val profile =
            ConnectionValidator.toProfile(draft)
                ?: return SaveConnectionResult.Invalid(validation.errors)

        return when (val check = verifier.verify(profile)) {
            ConnectionCheckResult.Success -> persist(profile)
            is ConnectionCheckResult.Failure -> SaveConnectionResult.Failed(check.reason)
        }
    }

    private suspend fun persist(profile: ConnectionProfile): SaveConnectionResult =
        try {
            repository.replace(profile)
            SaveConnectionResult.Saved(profile)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            SaveConnectionResult.Failed(ConnectionFailure.PERSISTENCE)
        }
}
