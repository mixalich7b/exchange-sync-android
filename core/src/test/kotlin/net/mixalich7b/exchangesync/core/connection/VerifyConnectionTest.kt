package net.mixalich7b.exchangesync.core.connection

import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VerifyConnectionTest {
    @Test
    fun `invalid draft returns every validation error without probing`() =
        runTest {
            val verifier = RecordingVerifier(ConnectionCheckResult.Failure(ConnectionFailure.TIMEOUT))

            val result = VerifyConnection(verifier).execute(ConnectionDraft())

            assertTrue(result is VerifyConnectionResult.Invalid)
            assertEquals(ConnectionField.entries.toSet(), (result as VerifyConnectionResult.Invalid).errors.keys)
            assertEquals(emptyList<ConnectionProfile>(), verifier.profiles)
        }

    @Test
    fun `typed verifier failure is preserved for a valid draft`() =
        runTest {
            val verifier = RecordingVerifier(ConnectionCheckResult.Failure(ConnectionFailure.ACCESS_DENIED))

            val result = VerifyConnection(verifier).execute(validDraft())

            assertEquals(VerifyConnectionResult.Failed(ConnectionFailure.ACCESS_DENIED), result)
            assertEquals(listOf(profile()), verifier.profiles)
        }

    @Test
    fun `successful verification returns the profile and ordered TLS diagnostics`() =
        runTest {
            val diagnostics = diagnostics()
            val verifier = RecordingVerifier(ConnectionCheckResult.Success(diagnostics))

            val result = VerifyConnection(verifier).execute(validDraft())

            assertEquals(VerifyConnectionResult.Verified(profile(), diagnostics), result)
            assertEquals(listOf(profile()), verifier.profiles)
        }

    private class RecordingVerifier(
        private val result: ConnectionCheckResult,
    ) : ConnectionVerifier {
        val profiles = mutableListOf<ConnectionProfile>()

        override suspend fun verify(profile: ConnectionProfile): ConnectionCheckResult {
            profiles += profile
            return result
        }
    }

    private fun validDraft(): ConnectionDraft =
        ConnectionDraft(
            email = "calendar@example.test",
            account = "DOMAIN\\calendar",
            serverHost = "exchange.example.test",
            clientCertificateAlias = "work-certificate",
        )

    private fun profile(): ConnectionProfile =
        ConnectionProfile(
            email = "calendar@example.test",
            account = "DOMAIN\\calendar",
            serverHost = "exchange.example.test",
            clientCertificateAlias = "work-certificate",
        )

    private fun diagnostics(): TlsConnectionDiagnostics =
        TlsConnectionDiagnostics(
            terminalHost = "mail.example.test",
            certificates =
                listOf(
                    TlsCertificateDiagnostic(
                        subject = "CN=mail.example.test",
                        issuer = "CN=Example Issuing CA",
                        serialNumber = "01",
                        validFrom = Instant.parse("2026-01-01T00:00:00Z"),
                        validUntil = Instant.parse("2027-01-01T00:00:00Z"),
                        sha256Fingerprint = "AA:BB",
                    ),
                    TlsCertificateDiagnostic(
                        subject = "CN=Example Issuing CA",
                        issuer = "CN=Example Root CA",
                        serialNumber = "02",
                        validFrom = Instant.parse("2025-01-01T00:00:00Z"),
                        validUntil = Instant.parse("2030-01-01T00:00:00Z"),
                        sha256Fingerprint = "CC:DD",
                    ),
                ),
        )
}
