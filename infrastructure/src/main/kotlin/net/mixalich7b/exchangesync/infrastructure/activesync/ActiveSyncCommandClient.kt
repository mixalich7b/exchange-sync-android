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
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperation
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnosticEvent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnostics
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticComponent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperationKind
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticSeverity
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticStage
import net.mixalich7b.exchangesync.infrastructure.diagnostics.diagnosticHost
import net.mixalich7b.exchangesync.infrastructure.diagnostics.diagnosticPath
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
        operation: DiagnosticOperation? = null,
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
            .apply { operation?.let { tag(DiagnosticOperation::class.java, it) } }
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

    suspend fun execute(
        profile: ConnectionProfile,
        endpoint: HttpUrl,
        command: ActiveSyncCommand,
        deviceId: String,
        version: ActiveSyncVersion,
        body: ByteArray,
        operation: DiagnosticOperation,
    ): ActiveSyncCommandOutcome = execute(profile, endpoint, command, deviceId, version, body)
}

internal class ActiveSyncCommandClient(
    private val credentialResolver: ClientCredentialResolver,
    private val transportFactory: SecureHttpTransportFactory,
    private val totalTimeoutMillis: Long = 30_000,
    private val transportDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: DeviceDiagnostics = DeviceDiagnostics(),
) : ActiveSyncCommandGateway {
    override suspend fun execute(
        profile: ConnectionProfile,
        endpoint: HttpUrl,
        command: ActiveSyncCommand,
        deviceId: String,
        version: ActiveSyncVersion,
        body: ByteArray,
    ): ActiveSyncCommandOutcome =
        execute(
            profile,
            endpoint,
            command,
            deviceId,
            version,
            body,
            diagnostics.operation(DiagnosticOperationKind.ACTIVE_SYNC_COMMAND),
        )

    override suspend fun execute(
        profile: ConnectionProfile,
        endpoint: HttpUrl,
        command: ActiveSyncCommand,
        deviceId: String,
        version: ActiveSyncVersion,
        body: ByteArray,
        operation: DiagnosticOperation,
    ): ActiveSyncCommandOutcome {
        val resolution = credentialResolver.resolve(profile.clientCertificateAlias, operation)
        if (resolution !is ClientCredentialResolution.Available) {
            emitFailure(operation, command, endpoint, SyncProblem.CLIENT_CERTIFICATE, DiagnosticStage.KEYCHAIN_RESOLUTION)
            return ActiveSyncCommandOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.CLIENT_CERTIFICATE)
        }
        return try {
            withTimeout(totalTimeoutMillis) {
                val transport =
                    withContext(transportDispatcher) {
                        transportFactory.create(profile, resolution.credential, operation)
                    }
                executeRedirectChain(
                    profile,
                    endpoint,
                    command,
                    deviceId,
                    version,
                    body,
                    resolution.credential,
                    transport,
                    operation,
                )
            }
        } catch (failure: TimeoutCancellationException) {
            emitFailure(
                operation,
                command,
                endpoint,
                null,
                DiagnosticStage.FAILURE,
                timeoutMillis = totalTimeoutMillis,
                throwable = failure,
            )
            ActiveSyncCommandOutcome.Failure(SyncFailureKind.TRANSIENT, null)
        } catch (cancellation: CancellationException) {
            diagnostics.emit(
                DeviceDiagnosticEvent(
                    DiagnosticSeverity.INFO,
                    DiagnosticComponent.ACTIVE_SYNC,
                    DiagnosticStage.CANCELLATION,
                    operation,
                    command = command.wireValue,
                    host = endpoint.diagnosticHost(),
                    path = endpoint.diagnosticPath(),
                ),
            )
            throw cancellation
        } catch (failure: ActiveSyncResponseTooLargeException) {
            emitFailure(
                operation,
                command,
                endpoint,
                null,
                DiagnosticStage.RESPONSE_BODY,
                reasonCode = "RESPONSE_TOO_LARGE",
                throwable = failure,
            )
            ActiveSyncCommandOutcome.Failure(SyncFailureKind.WINDOW_TOO_LARGE, null)
        } catch (failure: Exception) {
            val category = ConnectionExceptionClassifier.classify(failure)
            val outcome = category.toCommandFailure()
            emitFailure(operation, command, endpoint, outcome.problem, category.diagnosticStage(), throwable = failure)
            outcome
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
        operation: DiagnosticOperation,
    ): ActiveSyncCommandOutcome {
        var currentEndpoint = endpoint
        val redirects = RedirectTracker(endpoint)
        while (true) {
            val request =
                ActiveSyncCommandRequestFactory.create(
                    currentEndpoint,
                    profile,
                    command,
                    deviceId,
                    version = version,
                    body = body,
                    operation = operation,
                )
            val response = transport.execute(request)
            if (ActiveSyncProbePolicy.isRedirect(response.statusCode)) {
                when (val decision = redirects.follow(currentEndpoint, response.header("Location"))) {
                    is RedirectDecision.Follow -> {
                        diagnostics.emit(
                            DeviceDiagnosticEvent(
                                DiagnosticSeverity.INFO,
                                DiagnosticComponent.ACTIVE_SYNC,
                                DiagnosticStage.REDIRECT,
                                operation,
                                method = "POST",
                                command = command.wireValue,
                                host = decision.url.diagnosticHost(),
                                path = decision.url.diagnosticPath(),
                                status = response.statusCode,
                                outcome = "follow",
                            ),
                        )
                        currentEndpoint = decision.url
                    }
                    RedirectDecision.Rejected -> {
                        diagnostics.emit(
                            DeviceDiagnosticEvent(
                                DiagnosticSeverity.WARN,
                                DiagnosticComponent.ACTIVE_SYNC,
                                DiagnosticStage.REDIRECT,
                                operation,
                                method = "POST",
                                command = command.wireValue,
                                host = currentEndpoint.diagnosticHost(),
                                path = currentEndpoint.diagnosticPath(),
                                status = response.statusCode,
                                failureCategory = SyncProblem.REDIRECT.name,
                                outcome = "rejected",
                            ),
                        )
                        return ActiveSyncCommandOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.REDIRECT)
                    }
                }
                continue
            }
            if (!response.used(credential.leafCertificate)) {
                emitFailure(
                    operation,
                    command,
                    currentEndpoint,
                    SyncProblem.CLIENT_CERTIFICATE,
                    DiagnosticStage.CLIENT_CERTIFICATE_VERIFICATION,
                )
                return ActiveSyncCommandOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.CLIENT_CERTIFICATE)
            }
            val outcome = response.toCommandOutcome(currentEndpoint)
            diagnostics.emit(
                DeviceDiagnosticEvent(
                    severity = if (outcome is ActiveSyncCommandOutcome.Success) DiagnosticSeverity.INFO else DiagnosticSeverity.WARN,
                    component = DiagnosticComponent.ACTIVE_SYNC,
                    stage = DiagnosticStage.COMMAND,
                    operation = operation,
                    method = "POST",
                    command = command.wireValue,
                    host = currentEndpoint.diagnosticHost(),
                    path = currentEndpoint.diagnosticPath(),
                    status = response.statusCode,
                    failureCategory = (outcome as? ActiveSyncCommandOutcome.Failure)?.problem?.name,
                    outcome = if (outcome is ActiveSyncCommandOutcome.Success) "success" else "failure",
                ),
            )
            return outcome
        }
    }

    private fun emitFailure(
        operation: DiagnosticOperation,
        command: ActiveSyncCommand,
        endpoint: HttpUrl,
        problem: SyncProblem?,
        stage: DiagnosticStage,
        timeoutMillis: Long? = null,
        reasonCode: String? = null,
        throwable: Throwable? = null,
    ) {
        diagnostics.emit(
            DeviceDiagnosticEvent(
                DiagnosticSeverity.ERROR,
                DiagnosticComponent.ACTIVE_SYNC,
                stage,
                operation,
                method = "POST",
                command = command.wireValue,
                host = endpoint.diagnosticHost(),
                path = endpoint.diagnosticPath(),
                timeoutMillis = timeoutMillis,
                reasonCode = reasonCode,
                failureCategory = problem?.name,
                outcome = "failure",
                throwable = throwable,
            ),
        )
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
