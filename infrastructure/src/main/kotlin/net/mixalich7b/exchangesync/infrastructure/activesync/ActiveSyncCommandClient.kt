package net.mixalich7b.exchangesync.infrastructure.activesync

import java.security.cert.X509Certificate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.mixalich7b.exchangesync.core.connection.ConnectionFailure
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.sync.ActiveSyncVersion
import net.mixalich7b.exchangesync.core.sync.SyncFailureKind
import net.mixalich7b.exchangesync.core.sync.SyncProblem
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredential
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredentialResolution
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredentialResolver
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal const val ACTIVE_SYNC_CONTENT_TYPE = "application/vnd.ms-sync.wbxml"
internal const val ACTIVE_SYNC_DEVICE_TYPE = "ExchangeSync"

internal enum class ActiveSyncCommand(val wireValue: String) {
    FOLDER_SYNC("FolderSync"),
    SYNC("Sync"),
}

internal object ActiveSyncCommandRequestFactory {
    fun create(
        endpoint: HttpUrl,
        profile: ConnectionProfile,
        command: ActiveSyncCommand,
        deviceId: String,
        deviceType: String = ACTIVE_SYNC_DEVICE_TYPE,
        version: ActiveSyncVersion,
        body: ByteArray,
    ): Request {
        require(deviceId.isNotBlank() && deviceId.all(Char::isLetterOrDigit))
        require(deviceType.isNotBlank() && deviceType.all(Char::isLetterOrDigit))
        val commandUrl =
            endpoint
                .newBuilder()
                .query(null)
                .addQueryParameter("Cmd", command.wireValue)
                .addQueryParameter("User", profile.account)
                .addQueryParameter("DeviceId", deviceId)
                .addQueryParameter("DeviceType", deviceType)
                .build()
        return Request.Builder()
            .url(commandUrl)
            .header("MS-ASProtocolVersion", version.wireValue)
            .header("Content-Type", ACTIVE_SYNC_CONTENT_TYPE)
            .post(body.toRequestBody(ACTIVE_SYNC_CONTENT_TYPE.toMediaType()))
            .build()
    }
}

internal sealed interface ActiveSyncCommandOutcome {
    data class Success(
        val terminalEndpoint: HttpUrl,
        val body: ByteArray,
    ) : ActiveSyncCommandOutcome

    data class Failure(
        val kind: SyncFailureKind,
        val problem: SyncProblem?,
    ) : ActiveSyncCommandOutcome
}

internal fun interface ActiveSyncCommandGateway {
    suspend fun execute(
        profile: ConnectionProfile,
        endpoint: HttpUrl,
        command: ActiveSyncCommand,
        deviceId: String,
        version: ActiveSyncVersion,
        body: ByteArray,
    ): ActiveSyncCommandOutcome
}

internal class ActiveSyncCommandClient(
    private val credentialResolver: ClientCredentialResolver,
    private val transportFactory: SecureHttpTransportFactory,
    private val totalTimeoutMillis: Long = 30_000,
    private val transportDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ActiveSyncCommandGateway {
    override suspend fun execute(
        profile: ConnectionProfile,
        endpoint: HttpUrl,
        command: ActiveSyncCommand,
        deviceId: String,
        version: ActiveSyncVersion,
        body: ByteArray,
    ): ActiveSyncCommandOutcome {
        val resolution = credentialResolver.resolve(profile.clientCertificateAlias)
        if (resolution !is ClientCredentialResolution.Available) {
            return ActiveSyncCommandOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.CLIENT_CERTIFICATE)
        }
        return try {
            withTimeout(totalTimeoutMillis) {
                val transport = withContext(transportDispatcher) { transportFactory.create(resolution.credential) }
                executeRedirectChain(profile, endpoint, command, deviceId, version, body, resolution.credential, transport)
            }
        } catch (_: TimeoutCancellationException) {
            ActiveSyncCommandOutcome.Failure(SyncFailureKind.TRANSIENT, null)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: ActiveSyncResponseTooLargeException) {
            ActiveSyncCommandOutcome.Failure(SyncFailureKind.WINDOW_TOO_LARGE, null)
        } catch (failure: Exception) {
            ConnectionExceptionClassifier.classify(failure).toCommandFailure()
        }
    }

    private suspend fun executeRedirectChain(
        profile: ConnectionProfile,
        endpoint: HttpUrl,
        command: ActiveSyncCommand,
        deviceId: String,
        version: ActiveSyncVersion,
        body: ByteArray,
        credential: ClientCredential,
        transport: SecureHttpTransport,
    ): ActiveSyncCommandOutcome {
        var currentEndpoint = endpoint
        val redirects = RedirectTracker(endpoint)
        while (true) {
            val request = ActiveSyncCommandRequestFactory.create(currentEndpoint, profile, command, deviceId, version = version, body = body)
            val response = transport.execute(request)
            if (ActiveSyncProbePolicy.isRedirect(response.statusCode)) {
                when (val decision = redirects.follow(currentEndpoint, response.header("Location"))) {
                    is RedirectDecision.Follow -> currentEndpoint = decision.url
                    RedirectDecision.Rejected ->
                        return ActiveSyncCommandOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.REDIRECT)
                }
                continue
            }
            if (!response.used(credential.leafCertificate)) {
                return ActiveSyncCommandOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.CLIENT_CERTIFICATE)
            }
            return response.toCommandOutcome(currentEndpoint)
        }
    }

    private fun SecureHttpResponse.toCommandOutcome(endpoint: HttpUrl): ActiveSyncCommandOutcome =
        when (statusCode) {
            200 -> ActiveSyncCommandOutcome.Success(endpoint, body)
            401, 403 -> ActiveSyncCommandOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.ACCESS)
            449 -> ActiveSyncCommandOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.UNSUPPORTED_PROVISIONING)
            408, 429, in 500..599 -> ActiveSyncCommandOutcome.Failure(SyncFailureKind.TRANSIENT, null)
            else -> ActiveSyncCommandOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.COMPATIBILITY)
        }

    private fun SecureHttpResponse.used(expected: X509Certificate): Boolean =
        localCertificates.any { certificate ->
            certificate is X509Certificate && certificate.encoded.contentEquals(expected.encoded)
        }

    private fun ConnectionFailure.toCommandFailure(): ActiveSyncCommandOutcome.Failure =
        when (this) {
            ConnectionFailure.CLIENT_CERTIFICATE_UNAVAILABLE,
            ConnectionFailure.CLIENT_CERTIFICATE_REJECTED,
            -> ActiveSyncCommandOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.CLIENT_CERTIFICATE)
            ConnectionFailure.SERVER_TRUST,
            ConnectionFailure.HOSTNAME_MISMATCH,
            ConnectionFailure.LOCAL_CA_MISSING,
            ConnectionFailure.LOCAL_CA_INVALID,
            -> ActiveSyncCommandOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.TLS)
            ConnectionFailure.ACCESS_DENIED -> ActiveSyncCommandOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.ACCESS)
            ConnectionFailure.REDIRECT_POLICY -> ActiveSyncCommandOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.REDIRECT)
            ConnectionFailure.PROTOCOL_INCOMPATIBLE,
            ConnectionFailure.ENDPOINT_MISMATCH,
            -> ActiveSyncCommandOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.COMPATIBILITY)
            ConnectionFailure.SERVER_NOT_FOUND,
            ConnectionFailure.CONNECTION_FAILED,
            ConnectionFailure.TIMEOUT,
            ConnectionFailure.SERVER_ERROR,
            -> ActiveSyncCommandOutcome.Failure(SyncFailureKind.TRANSIENT, null)
            ConnectionFailure.SERVER_CERTIFICATE_DIAGNOSTICS,
            ConnectionFailure.PERSISTENCE,
            ConnectionFailure.UNKNOWN,
            -> ActiveSyncCommandOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.PROTOCOL_DATA)
        }
}
