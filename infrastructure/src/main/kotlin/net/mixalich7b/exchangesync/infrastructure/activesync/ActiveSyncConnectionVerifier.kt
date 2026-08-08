package net.mixalich7b.exchangesync.infrastructure.activesync

import java.security.cert.Certificate
import java.security.cert.X509Certificate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.mixalich7b.exchangesync.core.connection.ConnectionCheckResult
import net.mixalich7b.exchangesync.core.connection.ConnectionFailure
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.connection.ConnectionVerifier
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredential
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredentialResolution
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredentialResolver
import okhttp3.Request

internal data class ProbeResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val localCertificates: List<Certificate> = emptyList(),
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value
}

internal fun interface ProbeTransport {
    suspend fun execute(request: Request): ProbeResponse
}

internal fun interface ProbeTransportFactory {
    fun create(credential: ClientCredential): ProbeTransport
}

internal class ActiveSyncConnectionVerifier(
    private val credentialResolver: ClientCredentialResolver,
    private val transportFactory: ProbeTransportFactory,
    private val totalTimeoutMillis: Long = 30_000,
    private val transportDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConnectionVerifier {
    override suspend fun verify(profile: ConnectionProfile): ConnectionCheckResult {
        val resolution = credentialResolver.resolve(profile.clientCertificateAlias)
        if (resolution !is ClientCredentialResolution.Available) {
            return ConnectionCheckResult.Failure(ConnectionFailure.CLIENT_CERTIFICATE_UNAVAILABLE)
        }

        return try {
            withTimeout(totalTimeoutMillis) {
                val transport =
                    withContext(transportDispatcher) {
                        transportFactory.create(resolution.credential)
                    }
                probe(profile, resolution.credential, transport)
            }
        } catch (_: TimeoutCancellationException) {
            ConnectionCheckResult.Failure(ConnectionFailure.TIMEOUT)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            ConnectionCheckResult.Failure(ConnectionExceptionClassifier.classify(failure))
        }
    }

    private suspend fun probe(
        profile: ConnectionProfile,
        credential: ClientCredential,
        transport: ProbeTransport,
    ): ConnectionCheckResult {
        var currentUrl = ActiveSyncProbePolicy.initialUrl(profile.serverHost)
        val redirects = RedirectTracker(currentUrl)

        while (true) {
            val response = transport.execute(ActiveSyncProbePolicy.request(currentUrl))
            if (ActiveSyncProbePolicy.isRedirect(response.statusCode)) {
                when (val decision = redirects.follow(currentUrl, response.header("Location"))) {
                    is RedirectDecision.Follow -> currentUrl = decision.url
                    RedirectDecision.Rejected ->
                        return ConnectionCheckResult.Failure(ConnectionFailure.REDIRECT_POLICY)
                }
                continue
            }

            if (!response.used(credential.leafCertificate)) {
                return ConnectionCheckResult.Failure(ConnectionFailure.CLIENT_CERTIFICATE_REJECTED)
            }
            return ActiveSyncResponseEvaluator.evaluate(
                statusCode = response.statusCode,
                protocolVersions = response.header("MS-ASProtocolVersions"),
                protocolCommands = response.header("MS-ASProtocolCommands"),
            )
        }
    }

    private fun ProbeResponse.used(expected: X509Certificate): Boolean =
        localCertificates.any { certificate ->
            certificate is X509Certificate && certificate.encoded.contentEquals(expected.encoded)
        }
}
