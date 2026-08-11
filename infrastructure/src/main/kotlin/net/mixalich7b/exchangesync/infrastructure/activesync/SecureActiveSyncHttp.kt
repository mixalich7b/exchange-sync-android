package net.mixalich7b.exchangesync.infrastructure.activesync

import java.security.cert.Certificate
import java.security.cert.X509Certificate
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperation
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredential
import okhttp3.CookieJar
import okhttp3.Request

internal data class SecureHttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray = byteArrayOf(),
    val localCertificates: List<Certificate> = emptyList(),
    val peerCertificates: List<Certificate> = emptyList(),
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value
}

internal enum class ClientIdentityParticipationEvidence(
    val diagnosticValue: String,
) {
    MATCHED("participation_metadata_matched"),
    UNAVAILABLE("participation_metadata_unavailable"),
    MISMATCHED("participation_metadata_mismatched"),
}

internal fun SecureHttpResponse.clientIdentityParticipation(
    expected: X509Certificate,
): ClientIdentityParticipationEvidence =
    when {
        localCertificates.isEmpty() -> ClientIdentityParticipationEvidence.UNAVAILABLE
        localCertificates.any { certificate ->
            certificate is X509Certificate && certificate.encoded.contentEquals(expected.encoded)
        } -> ClientIdentityParticipationEvidence.MATCHED
        else -> ClientIdentityParticipationEvidence.MISMATCHED
    }

internal fun interface SecureHttpTransport {
    suspend fun execute(request: Request): SecureHttpResponse
}

internal fun interface SecureHttpTransportFactory {
    fun create(
        profile: ConnectionProfile,
        credential: ClientCredential,
        operation: DiagnosticOperation,
    ): SecureHttpTransport
}

internal fun interface CookieJarSecureHttpTransportFactory {
    fun create(
        credential: ClientCredential,
        cookieJar: CookieJar,
        operation: DiagnosticOperation,
    ): SecureHttpTransport
}

internal class ProfileSessionSecureHttpTransportFactory(
    private val sessions: ActiveSyncProfileSessionRegistry,
    private val delegate: CookieJarSecureHttpTransportFactory,
) : SecureHttpTransportFactory {
    override fun create(
        profile: ConnectionProfile,
        credential: ClientCredential,
        operation: DiagnosticOperation,
    ): SecureHttpTransport =
        delegate.create(credential, sessions.acquire(profile).cookieJar, operation)
}
