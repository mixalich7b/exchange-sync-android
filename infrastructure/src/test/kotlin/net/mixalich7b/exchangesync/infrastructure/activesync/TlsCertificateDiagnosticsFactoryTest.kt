package net.mixalich7b.exchangesync.infrastructure.activesync

import java.math.BigInteger
import java.time.Instant
import java.util.Date
import javax.security.auth.x500.X500Principal
import net.mixalich7b.exchangesync.core.connection.TlsCertificateDiagnostic
import net.mixalich7b.exchangesync.core.connection.TlsConnectionDiagnostics
import net.mixalich7b.exchangesync.infrastructure.tls.StubCertificate
import net.mixalich7b.exchangesync.infrastructure.tls.StubX509Certificate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TlsCertificateDiagnosticsFactoryTest {
    @Test
    fun `converts the peer chain in leaf to issuer order with public certificate metadata`() {
        val leaf =
            StubX509Certificate(
                encoded = byteArrayOf(),
                serialNumber = BigInteger("255"),
                subject = X500Principal("CN=mail.example.test,O=Example"),
                issuer = X500Principal("CN=Example Issuing CA,O=Example"),
                notBefore = Date.from(Instant.parse("2026-01-01T00:00:00Z")),
                notAfter = Date.from(Instant.parse("2027-01-01T00:00:00Z")),
            )
        val issuer =
            StubX509Certificate(
                encoded = byteArrayOf(1),
                serialNumber = BigInteger("16"),
                subject = X500Principal("CN=Example Issuing CA,O=Example"),
                issuer = X500Principal("CN=Example Root CA,O=Example"),
                notBefore = Date.from(Instant.parse("2025-01-01T00:00:00Z")),
                notAfter = Date.from(Instant.parse("2030-01-01T00:00:00Z")),
            )

        val result = TlsCertificateDiagnosticsFactory.create("mail.example.test", listOf(leaf, issuer))

        assertEquals(
            TlsConnectionDiagnostics(
                terminalHost = "mail.example.test",
                certificates =
                    listOf(
                        TlsCertificateDiagnostic(
                            subject = "CN=mail.example.test,O=Example",
                            issuer = "CN=Example Issuing CA,O=Example",
                            serialNumber = "FF",
                            validFrom = Instant.parse("2026-01-01T00:00:00Z"),
                            validUntil = Instant.parse("2027-01-01T00:00:00Z"),
                            sha256Fingerprint =
                                "E3:B0:C4:42:98:FC:1C:14:9A:FB:F4:C8:99:6F:B9:24:27:AE:41:E4:64:9B:93:4C:A4:95:99:1B:78:52:B8:55",
                        ),
                        TlsCertificateDiagnostic(
                            subject = "CN=Example Issuing CA,O=Example",
                            issuer = "CN=Example Root CA,O=Example",
                            serialNumber = "10",
                            validFrom = Instant.parse("2025-01-01T00:00:00Z"),
                            validUntil = Instant.parse("2030-01-01T00:00:00Z"),
                            sha256Fingerprint =
                                "4B:F5:12:2F:34:45:54:C5:3B:DE:2E:BB:8C:D2:B7:E3:D1:60:0A:D6:31:C3:85:A5:D7:CC:E2:3C:77:85:45:9A",
                        ),
                    ),
            ),
            result,
        )
    }

    @Test
    fun `rejects empty or non X509 peer chains instead of producing partial diagnostics`() {
        assertNull(TlsCertificateDiagnosticsFactory.create("mail.example.test", emptyList()))
        assertNull(
            TlsCertificateDiagnosticsFactory.create(
                "mail.example.test",
                listOf(StubX509Certificate(), StubCertificate()),
            ),
        )
    }
}
