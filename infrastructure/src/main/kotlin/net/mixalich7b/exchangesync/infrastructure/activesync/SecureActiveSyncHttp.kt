package net.mixalich7b.exchangesync.infrastructure.activesync

import java.security.cert.Certificate
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredential
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

internal fun interface SecureHttpTransport {
    suspend fun execute(request: Request): SecureHttpResponse
}

internal fun interface SecureHttpTransportFactory {
    fun create(credential: ClientCredential): SecureHttpTransport
}
