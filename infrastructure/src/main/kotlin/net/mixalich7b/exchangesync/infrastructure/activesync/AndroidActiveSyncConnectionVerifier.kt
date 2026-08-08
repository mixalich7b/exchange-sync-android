package net.mixalich7b.exchangesync.infrastructure.activesync

import android.content.Context
import java.io.IOException
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import net.mixalich7b.exchangesync.core.connection.ConnectionCheckResult
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.connection.ConnectionVerifier
import net.mixalich7b.exchangesync.infrastructure.tls.AndroidCertificateAssetSource
import net.mixalich7b.exchangesync.infrastructure.tls.CertificateAssetLoader
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredential
import net.mixalich7b.exchangesync.infrastructure.tls.CompositeX509TrustManager
import net.mixalich7b.exchangesync.infrastructure.tls.FixedAliasKeyManager
import net.mixalich7b.exchangesync.infrastructure.tls.KeyChainClientCredentialResolver
import net.mixalich7b.exchangesync.infrastructure.tls.LocalCertificates
import net.mixalich7b.exchangesync.infrastructure.tls.LocalTrustStatus
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

public class AndroidActiveSyncConnectionVerifier(context: Context) : ConnectionVerifier {
    private val delegate: ConnectionVerifier =
        ActiveSyncConnectionVerifier(
            credentialResolver = KeyChainClientCredentialResolver(context.applicationContext),
            transportFactory =
                OkHttpProbeTransportFactory(
                    CertificateAssetLoader(AndroidCertificateAssetSource(context.assets)),
                ),
        )

    override suspend fun verify(profile: ConnectionProfile): ConnectionCheckResult = delegate.verify(profile)
}

private class OkHttpProbeTransportFactory(
    private val certificateLoader: CertificateAssetLoader,
) : ProbeTransportFactory {
    override fun create(credential: ClientCredential): ProbeTransport {
        val trustManager = CombinedTrustManagerFactory.create(certificateLoader.load())
        val sslContext =
            SSLContext.getInstance("TLS").apply {
                init(arrayOf(FixedAliasKeyManager(credential)), arrayOf(trustManager), null)
            }
        val client =
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
        return OkHttpProbeTransport(client)
    }
}

private class OkHttpProbeTransport(
    private val client: OkHttpClient,
) : ProbeTransport {
    override suspend fun execute(request: Request): ProbeResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            continuation.resumeSafely(
                                ProbeResponse(
                                    statusCode = response.code,
                                    headers =
                                        response.headers.names().associateWith { name ->
                                            response.header(name).orEmpty()
                                        },
                                    localCertificates = response.handshake?.localCertificates.orEmpty(),
                                    peerCertificates = response.handshake?.peerCertificates.orEmpty(),
                                ),
                            )
                        }
                    }
                },
            )
        }

    private fun CancellableContinuation<ProbeResponse>.resumeSafely(response: ProbeResponse) {
        if (isActive) resumeWith(Result.success(response))
    }
}

private object CombinedTrustManagerFactory {
    fun create(localCertificates: LocalCertificates): CompositeX509TrustManager {
        val system = trustManager(keyStore = null)
        val local = createLocal(localCertificates.certificates)
        val status =
            when {
                local == null && (localCertificates.hadAssets || localCertificates.issues.isNotEmpty()) ->
                    LocalTrustStatus.INVALID
                local == null -> LocalTrustStatus.MISSING
                localCertificates.issues.isNotEmpty() -> LocalTrustStatus.INVALID
                else -> LocalTrustStatus.AVAILABLE
            }
        return CompositeX509TrustManager(system, local, status)
    }

    private fun createLocal(certificates: List<X509Certificate>): X509TrustManager? {
        if (certificates.isEmpty()) return null
        return try {
            val keyStore =
                KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null, null)
                    certificates.forEachIndexed { index, certificate ->
                        setCertificateEntry("local-ca-$index", certificate)
                    }
                }
            trustManager(keyStore)
        } catch (_: Exception) {
            null
        }
    }

    private fun trustManager(keyStore: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }
}
