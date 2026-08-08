package net.mixalich7b.exchangesync.core.connection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ConnectionFailureTest {
    @Test
    fun `connection failures expose stable non-sensitive codes`() {
        val expected =
            mapOf(
                ConnectionFailure.CLIENT_CERTIFICATE_UNAVAILABLE to "client_certificate_unavailable",
                ConnectionFailure.SERVER_NOT_FOUND to "server_not_found",
                ConnectionFailure.CONNECTION_FAILED to "connection_failed",
                ConnectionFailure.TIMEOUT to "timeout",
                ConnectionFailure.SERVER_TRUST to "server_trust",
                ConnectionFailure.HOSTNAME_MISMATCH to "hostname_mismatch",
                ConnectionFailure.LOCAL_CA_MISSING to "local_ca_missing",
                ConnectionFailure.LOCAL_CA_INVALID to "local_ca_invalid",
                ConnectionFailure.CLIENT_CERTIFICATE_REJECTED to "client_certificate_rejected",
                ConnectionFailure.ACCESS_DENIED to "access_denied",
                ConnectionFailure.ENDPOINT_MISMATCH to "endpoint_mismatch",
                ConnectionFailure.REDIRECT_POLICY to "redirect_policy",
                ConnectionFailure.SERVER_ERROR to "server_error",
                ConnectionFailure.PROTOCOL_INCOMPATIBLE to "protocol_incompatible",
                ConnectionFailure.SERVER_CERTIFICATE_DIAGNOSTICS to "server_certificate_diagnostics",
                ConnectionFailure.PERSISTENCE to "persistence",
                ConnectionFailure.UNKNOWN to "unknown",
            )

        assertEquals(expected, ConnectionFailure.entries.associateWith(ConnectionFailure::code))
        assertEquals(expected.keys.size, expected.values.toSet().size)
        assertFalse(expected.values.any { code -> code.contains("exception", ignoreCase = true) })
    }

    @Test
    fun `check failure carries only its stable category`() {
        val result: ConnectionCheckResult =
            ConnectionCheckResult.Failure(ConnectionFailure.SERVER_TRUST)

        assertEquals(ConnectionFailure.SERVER_TRUST, (result as ConnectionCheckResult.Failure).reason)
    }
}
