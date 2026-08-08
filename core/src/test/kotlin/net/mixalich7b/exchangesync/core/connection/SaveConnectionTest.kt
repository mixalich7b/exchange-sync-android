package net.mixalich7b.exchangesync.core.connection

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SaveConnectionTest {
    @Test
    fun `invalid verification result neither probes nor replaces the saved profile`() =
        runTest {
            val previous = profile(email = "previous@example.test")
            val repository = FakeRepository(previous)
            val verify = RecordingVerifyAction(VerifyConnectionResult.Invalid(allFieldErrors()))

            val result = SaveConnection(repository, verify).execute(ConnectionDraft())

            assertEquals(listOf(ConnectionDraft()), verify.drafts)
            assertEquals(0, repository.replaceAttempts)
            assertEquals(previous, repository.current)
            assertEquals(SaveConnectionResult.Invalid(allFieldErrors()), result)
        }

    @Test
    fun `verification failure is returned without replacing the saved profile`() =
        runTest {
            val previous = profile(email = "previous@example.test")
            val repository = FakeRepository(previous)
            val verify = RecordingVerifyAction(VerifyConnectionResult.Failed(ConnectionFailure.ACCESS_DENIED))

            val result = SaveConnection(repository, verify).execute(validDraft())

            assertEquals(listOf(validDraft()), verify.drafts)
            assertEquals(0, repository.replaceAttempts)
            assertEquals(previous, repository.current)
            assertEquals(SaveConnectionResult.Failed(ConnectionFailure.ACCESS_DENIED), result)
        }

    @Test
    fun `verified profile replaces once and preserves TLS diagnostics`() =
        runTest {
            val diagnostics = diagnostics()
            val repository = FakeRepository(profile(email = "previous@example.test"))
            val verify = RecordingVerifyAction(VerifyConnectionResult.Verified(profile(), diagnostics))

            val result = SaveConnection(repository, verify).execute(validDraft())

            assertEquals(listOf(validDraft()), verify.drafts)
            assertEquals(1, repository.replaceAttempts)
            assertEquals(profile(), repository.current)
            assertEquals(SaveConnectionResult.Saved(profile(), diagnostics), result)
        }

    @Test
    fun `persistence failure is reported after successful verification`() =
        runTest {
            val previous = profile(email = "previous@example.test")
            val repository = FakeRepository(previous, failReplacement = true)
            val verify = RecordingVerifyAction(VerifyConnectionResult.Verified(profile(), diagnostics()))

            val result = SaveConnection(repository, verify).execute(validDraft())

            assertEquals(listOf(validDraft()), verify.drafts)
            assertEquals(1, repository.replaceAttempts)
            assertEquals(previous, repository.current)
            assertEquals(SaveConnectionResult.Failed(ConnectionFailure.PERSISTENCE), result)
        }

    private class FakeRepository(
        initial: ConnectionProfile?,
        private val failReplacement: Boolean = false,
    ) : ConnectionProfileRepository {
        var current: ConnectionProfile? = initial
        var replaceAttempts: Int = 0

        override suspend fun load(): ConnectionProfile? = current

        override suspend fun replace(profile: ConnectionProfile) {
            replaceAttempts += 1
            if (failReplacement) error("simulated atomic write failure")
            current = profile
        }
    }

    private class RecordingVerifyAction(
        private val result: VerifyConnectionResult,
    ) : VerifyConnectionAction {
        val drafts = mutableListOf<ConnectionDraft>()
        override suspend fun execute(draft: ConnectionDraft): VerifyConnectionResult {
            drafts += draft
            return result
        }
    }

    private fun allFieldErrors(): Map<ConnectionField, FieldError> =
        ConnectionField.entries.associateWith { FieldError.REQUIRED }

    private fun validDraft(): ConnectionDraft =
        ConnectionDraft(
            email = "calendar@example.test",
            account = "DOMAIN\\calendar",
            serverHost = "exchange.example.test",
            clientCertificateAlias = "work-certificate",
        )

    private fun profile(email: String = "calendar@example.test"): ConnectionProfile =
        ConnectionProfile(
            email = email,
            account = "DOMAIN\\calendar",
            serverHost = "exchange.example.test",
            clientCertificateAlias = "work-certificate",
        )

    private fun diagnostics(): TlsConnectionDiagnostics =
        TlsConnectionDiagnostics(
            terminalHost = "exchange.example.test",
            certificates =
                listOf(
                    TlsCertificateDiagnostic(
                        subject = "CN=exchange.example.test",
                        issuer = "CN=Example CA",
                        serialNumber = "01",
                        validFrom = Instant.parse("2026-01-01T00:00:00Z"),
                        validUntil = Instant.parse("2027-01-01T00:00:00Z"),
                        sha256Fingerprint = "AA:BB",
                    ),
                ),
        )
}
