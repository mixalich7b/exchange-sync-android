package net.mixalich7b.exchangesync.infrastructure.activesync

import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Locale
import javax.security.auth.x500.X500Principal
import net.mixalich7b.exchangesync.core.connection.TlsCertificateDiagnostic
import net.mixalich7b.exchangesync.core.connection.TlsConnectionDiagnostics

internal object TlsCertificateDiagnosticsFactory {
    fun create(
        terminalHost: String,
        certificates: List<Certificate>,
    ): TlsConnectionDiagnostics? =
        runCatching {
            val x509Certificates = certificates.map { certificate -> certificate as? X509Certificate ?: return null }
            if (x509Certificates.isEmpty()) return null
            TlsConnectionDiagnostics(
                terminalHost = terminalHost,
                certificates = x509Certificates.map(::toDiagnostic),
            )
        }.getOrNull()

    private fun toDiagnostic(certificate: X509Certificate): TlsCertificateDiagnostic =
        TlsCertificateDiagnostic(
            subject = certificate.subjectX500Principal.getName(X500Principal.RFC2253),
            issuer = certificate.issuerX500Principal.getName(X500Principal.RFC2253),
            serialNumber = certificate.serialNumber.toString(16).uppercase(Locale.ROOT),
            validFrom = certificate.notBefore.toInstant(),
            validUntil = certificate.notAfter.toInstant(),
            sha256Fingerprint = sha256Fingerprint(certificate.encoded),
        )

    private fun sha256Fingerprint(encoded: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(encoded)
            .joinToString(separator = ":") { byte -> "%02X".format(Locale.ROOT, byte.toInt() and 0xff) }
}
