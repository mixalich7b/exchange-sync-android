package net.mixalich7b.exchangesync.core.connection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionValidatorTest {
    @Test
    fun `empty draft reports every required field`() {
        val result = ConnectionValidator.validate(ConnectionDraft())

        assertEquals(
            mapOf(
                ConnectionField.EMAIL to FieldError.REQUIRED,
                ConnectionField.ACCOUNT to FieldError.REQUIRED,
                ConnectionField.SERVER_HOST to FieldError.REQUIRED,
                ConnectionField.CLIENT_CERTIFICATE to FieldError.REQUIRED,
            ),
            result.errors,
        )
        assertFalse(result.isValid)
    }

    @Test
    fun `valid draft becomes an unchanged profile`() {
        val draft = validDraft()

        val result = ConnectionValidator.validate(draft)

        assertTrue(result.isValid)
        assertEquals(emptyMap<ConnectionField, FieldError>(), result.errors)
        assertEquals(
            ConnectionProfile(
                email = "calendar@example.test",
                account = "DOMAIN\\calendar",
                serverHost = "exchange.example.test",
                clientCertificateAlias = "work-certificate",
            ),
            ConnectionValidator.toProfile(draft),
        )
    }

    @Test
    fun `email requires one separator and non-empty parts without whitespace`() {
        listOf("calendar", "@example.test", "calendar@", "a@b@example.test", "a b@example.test")
            .forEach { email ->
                assertEquals(
                    FieldError.MALFORMED,
                    ConnectionValidator.validate(validDraft().copy(email = email)).errors[ConnectionField.EMAIL],
                    email,
                )
            }
    }

    @Test
    fun `account requires exactly one backslash and non-empty parts`() {
        listOf("calendar", "\\calendar", "DOMAIN\\", "DOMAIN\\team\\calendar", "DOMAIN \\calendar")
            .forEach { account ->
                assertEquals(
                    FieldError.MALFORMED,
                    ConnectionValidator.validate(validDraft().copy(account = account)).errors[ConnectionField.ACCOUNT],
                    account,
                )
            }
    }

    @Test
    fun `server accepts a hostname and rejects connection syntax or invalid labels`() {
        listOf(
            "https://exchange.example.test",
            "http://exchange.example.test",
            "exchange.example.test/Microsoft-Server-ActiveSync",
            "exchange.example.test:443",
            "exchange.example.test?probe=true",
            "exchange.example.test#fragment",
            "-exchange.example.test",
            "exchange..example.test",
            "exchange example.test",
        ).forEach { server ->
            assertEquals(
                FieldError.MALFORMED,
                ConnectionValidator.validate(validDraft().copy(serverHost = server)).errors[ConnectionField.SERVER_HOST],
                server,
            )
        }
    }

    @Test
    fun `blank certificate alias is rejected`() {
        val result = ConnectionValidator.validate(validDraft().copy(clientCertificateAlias = "  "))

        assertEquals(FieldError.REQUIRED, result.errors[ConnectionField.CLIENT_CERTIFICATE])
        assertEquals(null, ConnectionValidator.toProfile(validDraft().copy(clientCertificateAlias = "  ")))
    }

    private fun validDraft(): ConnectionDraft =
        ConnectionDraft(
            email = "calendar@example.test",
            account = "DOMAIN\\calendar",
            serverHost = "exchange.example.test",
            clientCertificateAlias = "work-certificate",
        )
}
