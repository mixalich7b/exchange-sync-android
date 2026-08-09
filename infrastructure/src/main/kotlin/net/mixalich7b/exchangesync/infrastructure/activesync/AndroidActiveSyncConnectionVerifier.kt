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
import net.mixalich7b.exchangesync.infrastructure.tls.CertificateAssetLoader
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredential
import net.mixalich7b.exchangesync.infrastructure.tls.CompositeX509TrustManager
import net.mixalich7b.exchangesync.infrastructure.tls.FixedAliasKeyManager
import net.mixalich7b.exchangesync.infrastructure.tls.LocalCertificates
import net.mixalich7b.exchangesync.infrastructure.tls.LocalTrustStatus
import net.mixalich7b.exchangesync.infrastructure.tls.sha256Fingerprint
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnosticEvent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnostics
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticComponent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperation
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticSeverity
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticStage
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer

internal const val MAX_ACTIVE_SYNC_RESPONSE_BYTES: Int = 2 * 1024 * 1024

internal class ActiveSyncResponseTooLargeException : IllegalArgumentException("ActiveSync response is too large")

internal fun readBoundedActiveSyncBody(body: ResponseBody): ByteArray {
    if (body.contentLength() > MAX_ACTIVE_SYNC_RESPONSE_BYTES) throw ActiveSyncResponseTooLargeException()
    val source = body.source()
    val buffer = Buffer()
    var remaining = MAX_ACTIVE_SYNC_RESPONSE_BYTES.toLong() + 1
    while (remaining > 0) {
        val read = source.read(buffer, minOf(remaining, RESPONSE_READ_CHUNK_BYTES))
        if (read == -1L) break
        remaining -= read
    }
    if (buffer.size > MAX_ACTIVE_SYNC_RESPONSE_BYTES) throw ActiveSyncResponseTooLargeException()
    return buffer.readByteArray()
}

internal fun readActiveSyncResponseBody(
    requestMethod: String,
    body: ResponseBody,
): ByteArray =
    if (requestMethod.equals("OPTIONS", ignoreCase = true)) byteArrayOf() else readBoundedActiveSyncBody(body)

private const val RESPONSE_READ_CHUNK_BYTES: Long = 8 * 1024

public class AndroidActiveSyncConnectionVerifier(context: Context) : ConnectionVerifier {
    private val delegate: ConnectionVerifier = AndroidActiveSyncProcessRuntime(context).connectionVerifier

    override suspend fun verify(profile: ConnectionProfile): ConnectionCheckResult = delegate.verify(profile)
}

internal class OkHttpSecureHttpTransportFactory(
    private val certificateLoader: CertificateAssetLoader,
    private val diagnostics: DeviceDiagnostics = DeviceDiagnostics(),
) : CookieJarSecureHttpTransportFactory {
    override fun create(
        credential: ClientCredential,
        cookieJar: CookieJar,
        operation: DiagnosticOperation,
    ): ProbeTransport {
        val localCertificates = certificateLoader.load(operation)
        val trustManager = CombinedTrustManagerFactory.create(localCertificates, diagnostics, operation)
        diagnostics.emit(
            DeviceDiagnosticEvent(
                DiagnosticSeverity.INFO,
                DiagnosticComponent.TLS,
                DiagnosticStage.CLIENT_KEY,
                operation,
                chainLength = credential.certificateChain.size,
                keyAlgorithm = credential.privateKey.algorithm,
                fingerprint = sha256Fingerprint(credential.leafCertificate),
                outcome = "available",
            ),
        )
        val sslContext =
            SSLContext.getInstance("TLS").apply {
                init(arrayOf(FixedAliasKeyManager(credential)), arrayOf(trustManager), null)
            }
        val client =
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .cookieJar(cookieJar)
                .eventListenerFactory { ActiveSyncNetworkEventListener(diagnostics, operation) }
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
        return OkHttpSecureHttpTransport(client, diagnostics, operation)
    }
}

private class OkHttpSecureHttpTransport(
    private val client: OkHttpClient,
    private val diagnostics: DeviceDiagnostics,
    private val operation: DiagnosticOperation,
) : ProbeTransport {
    override suspend fun execute(request: Request): ProbeResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!call.isCanceled()) {
                            diagnostics.emit(
                                DeviceDiagnosticEvent(
                                    DiagnosticSeverity.ERROR,
                                    DiagnosticComponent.HTTP,
                                    DiagnosticStage.FAILURE,
                                    operation,
                                    method = request.method,
                                    command = request.url.queryParameter("Cmd"),
                                    host = request.url.host,
                                    path = request.url.encodedPath,
                                    throwable = e,
                                ),
                            )
                        }
                        continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            try {
                                diagnostics.emit(
                                    DeviceDiagnosticEvent(
                                        DiagnosticSeverity.INFO,
                                        DiagnosticComponent.HTTP,
                                        DiagnosticStage.RESPONSE_BODY,
                                        operation,
                                        method = request.method,
                                        command = request.url.queryParameter("Cmd"),
                                        host = request.url.host,
                                        path = request.url.encodedPath,
                                        status = response.code,
                                        outcome = "bounded_read",
                                    ),
                                )
                                continuation.resumeSafely(
                                    SecureHttpResponse(
                                        statusCode = response.code,
                                        headers =
                                            response.headers.names().associateWith { name ->
                                                response.header(name).orEmpty()
                                            },
                                        body = readActiveSyncResponseBody(request.method, response.body),
                                        localCertificates = response.handshake?.localCertificates.orEmpty(),
                                        peerCertificates = response.handshake?.peerCertificates.orEmpty(),
                                    ),
                                )
                            } catch (failure: Exception) {
                                if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                            }
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
    fun create(
        localCertificates: LocalCertificates,
        diagnostics: DeviceDiagnostics,
        operation: DiagnosticOperation,
    ): CompositeX509TrustManager {
        val system = trustManager(keyStore = null)
        val localResult = createLocal(localCertificates.certificates)
        val local = localResult.manager
        val status =
            when {
                local == null && (localCertificates.hadAssets || localCertificates.issues.isNotEmpty()) ->
                    LocalTrustStatus.INVALID
                local == null -> LocalTrustStatus.MISSING
                localCertificates.issues.isNotEmpty() -> LocalTrustStatus.INVALID
                else -> LocalTrustStatus.AVAILABLE
            }
        diagnostics.emit(
            DeviceDiagnosticEvent(
                severity = if (status == LocalTrustStatus.INVALID) DiagnosticSeverity.WARN else DiagnosticSeverity.INFO,
                component = DiagnosticComponent.TLS,
                stage = DiagnosticStage.TRUST_MANAGER,
                operation = operation,
                chainLength = localCertificates.certificates.size,
                reasonCode = status.name,
                outcome = "constructed",
                throwable = localResult.failure,
            ),
        )
        return CompositeX509TrustManager(system, local, status, diagnostics, operation)
    }

    private fun createLocal(certificates: List<X509Certificate>): LocalTrustManagerResult {
        if (certificates.isEmpty()) return LocalTrustManagerResult(null, null)
        return try {
            val keyStore =
                KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null, null)
                    certificates.forEachIndexed { index, certificate ->
                        setCertificateEntry("local-ca-$index", certificate)
                    }
                }
            LocalTrustManagerResult(trustManager(keyStore), null)
        } catch (failure: Exception) {
            LocalTrustManagerResult(null, failure)
        }
    }

    private data class LocalTrustManagerResult(
        val manager: X509TrustManager?,
        val failure: Throwable?,
    )

    private fun trustManager(keyStore: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }
}
