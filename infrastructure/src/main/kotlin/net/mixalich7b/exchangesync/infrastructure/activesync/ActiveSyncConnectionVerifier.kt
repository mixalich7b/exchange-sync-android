package net.mixalich7b.exchangesync.infrastructure.activesync

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
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnosticEvent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnostics
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticComponent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperation
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperationKind
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticSeverity
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticStage
import net.mixalich7b.exchangesync.infrastructure.diagnostics.diagnosticHost
import net.mixalich7b.exchangesync.infrastructure.diagnostics.diagnosticPath
import net.mixalich7b.exchangesync.infrastructure.diagnostics.safeHeaderTokens
import kotlin.time.Duration.Companion.milliseconds

internal typealias ProbeResponse = SecureHttpResponse
internal typealias ProbeTransport = SecureHttpTransport
internal typealias ProbeTransportFactory = SecureHttpTransportFactory

internal class ActiveSyncConnectionVerifier(
    private val credentialResolver: ClientCredentialResolver,
    private val transportFactory: ProbeTransportFactory,
    private val totalTimeoutMillis: Long = 30_000,
    private val transportDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sessions: ActiveSyncProfileSessionRegistry = ActiveSyncProfileSessionRegistry(),
    private val diagnostics: DeviceDiagnostics = DeviceDiagnostics(),
) : ConnectionVerifier {
    override suspend fun verify(profile: ConnectionProfile): ConnectionCheckResult {
        val operation = diagnostics.operation(DiagnosticOperationKind.CONNECTION_CHECK)
        val initialUrl = ActiveSyncProbePolicy.initialUrl(profile.serverHost)
        diagnostics.emit(
            DeviceDiagnosticEvent(
                severity = DiagnosticSeverity.INFO,
                component = DiagnosticComponent.CONNECTION,
                stage = DiagnosticStage.START,
                operation = operation,
                method = "OPTIONS",
                host = initialUrl.diagnosticHost(),
                path = initialUrl.diagnosticPath(),
            ),
        )
        val resolution = credentialResolver.resolve(profile.clientCertificateAlias, operation)
        if (resolution !is ClientCredentialResolution.Available) {
            return finishFailure(operation, ConnectionFailure.CLIENT_CERTIFICATE_UNAVAILABLE, DiagnosticStage.KEYCHAIN_RESOLUTION)
        }

        return try {
            val result = withTimeout(totalTimeoutMillis.milliseconds) {
                val transport =
                    withContext(transportDispatcher) {
                        transportFactory.create(profile, resolution.credential, operation)
                    }
                probe(profile, resolution.credential, transport, operation)
            }
            finish(operation, result)
        } catch (failure: TimeoutCancellationException) {
            finishFailure(
                operation,
                ConnectionFailure.TIMEOUT,
                DiagnosticStage.FAILURE,
                timeoutMillis = totalTimeoutMillis,
                throwable = failure,
            )
        } catch (cancellation: CancellationException) {
            diagnostics.emit(
                DeviceDiagnosticEvent(
                    DiagnosticSeverity.INFO,
                    DiagnosticComponent.CONNECTION,
                    DiagnosticStage.CANCELLATION,
                    operation,
                ),
            )
            throw cancellation
        } catch (failure: Exception) {
            val category = ConnectionExceptionClassifier.classify(failure)
            finishFailure(
                operation,
                category,
                category.diagnosticStage(),
                throwable = failure,
            )
        }
    }

    private suspend fun probe(
        profile: ConnectionProfile,
        credential: ClientCredential,
        transport: ProbeTransport,
        operation: DiagnosticOperation,
    ): ConnectionCheckResult {
        var currentUrl = ActiveSyncProbePolicy.initialUrl(profile.serverHost)
        val redirects = RedirectTracker(currentUrl)

        while (true) {
            val response = transport.execute(ActiveSyncProbePolicy.request(currentUrl, operation))
            if (ActiveSyncProbePolicy.isRedirect(response.statusCode)) {
                when (val decision = redirects.follow(currentUrl, response.header("Location"))) {
                    is RedirectDecision.Follow -> {
                        diagnostics.emit(
                            DeviceDiagnosticEvent(
                                DiagnosticSeverity.INFO,
                                DiagnosticComponent.CONNECTION,
                                DiagnosticStage.REDIRECT,
                                operation,
                                method = "OPTIONS",
                                host = decision.url.diagnosticHost(),
                                path = decision.url.diagnosticPath(),
                                status = response.statusCode,
                                outcome = "follow",
                            ),
                        )
                        currentUrl = decision.url
                    }
                    RedirectDecision.Rejected -> {
                        diagnostics.emit(
                            DeviceDiagnosticEvent(
                                DiagnosticSeverity.WARN,
                                DiagnosticComponent.CONNECTION,
                                DiagnosticStage.REDIRECT,
                                operation,
                                method = "OPTIONS",
                                host = currentUrl.diagnosticHost(),
                                path = currentUrl.diagnosticPath(),
                                status = response.statusCode,
                                outcome = "rejected",
                            ),
                        )
                        return ConnectionCheckResult.Failure(ConnectionFailure.REDIRECT_POLICY)
                    }
                }
                continue
            }

            if (!response.used(credential.leafCertificate)) {
                diagnostics.emit(
                    DeviceDiagnosticEvent(
                        DiagnosticSeverity.ERROR,
                        DiagnosticComponent.TLS,
                        DiagnosticStage.CLIENT_CERTIFICATE_VERIFICATION,
                        operation,
                        host = currentUrl.diagnosticHost(),
                        path = currentUrl.diagnosticPath(),
                        chainLength = response.localCertificates.size,
                        outcome = "selected_leaf_absent",
                    ),
                )
                return ConnectionCheckResult.Failure(ConnectionFailure.CLIENT_CERTIFICATE_REJECTED)
            }
            val protocolVersions = response.header("MS-ASProtocolVersions")
            val protocolCommands = response.header("MS-ASProtocolCommands")
            val capabilityFailure =
                ActiveSyncResponseEvaluator.evaluate(
                    statusCode = response.statusCode,
                    protocolVersions = protocolVersions,
                    protocolCommands = protocolCommands,
                )
            if (capabilityFailure != null) {
                diagnostics.emit(
                    DeviceDiagnosticEvent(
                        DiagnosticSeverity.WARN,
                        DiagnosticComponent.ACTIVE_SYNC,
                        DiagnosticStage.CAPABILITY_VALIDATION,
                        operation,
                        method = "OPTIONS",
                        host = currentUrl.diagnosticHost(),
                        path = currentUrl.diagnosticPath(),
                        status = response.statusCode,
                        protocolVersions = safeHeaderTokens(protocolVersions),
                        protocolCommands = safeHeaderTokens(protocolCommands),
                        reasonCode = capabilityReason(response.statusCode, protocolVersions, protocolCommands),
                        failureCategory = capabilityFailure.name,
                    ),
                )
                return ConnectionCheckResult.Failure(capabilityFailure)
            }
            val tlsDiagnostics =
                TlsCertificateDiagnosticsFactory.create(currentUrl.host, response.peerCertificates)
                    ?: return ConnectionCheckResult.Failure(ConnectionFailure.SERVER_CERTIFICATE_DIAGNOSTICS)
            val supportedVersions =
                ActiveSyncVersionNegotiator.supported(protocolVersions)
            val version =
                ActiveSyncVersionNegotiator.select(protocolVersions)
                    ?: return ConnectionCheckResult.Failure(ConnectionFailure.PROTOCOL_INCOMPATIBLE)
            sessions.acquire(profile).recordCapability(
                ActiveSyncLiveCapability(
                    terminalEndpoint = currentUrl,
                    version = version,
                    supportedVersions = supportedVersions,
                ),
            )
            diagnostics.emit(
                DeviceDiagnosticEvent(
                    DiagnosticSeverity.INFO,
                    DiagnosticComponent.ACTIVE_SYNC,
                    DiagnosticStage.VERSION_SELECTION,
                    operation,
                    host = currentUrl.diagnosticHost(),
                    path = currentUrl.diagnosticPath(),
                    protocolVersions = supportedVersions.mapTo(linkedSetOf()) { it.wireValue },
                    outcome = version.wireValue,
                ),
            )
            return ConnectionCheckResult.Success(tlsDiagnostics)
        }
    }

    private fun finish(
        operation: DiagnosticOperation,
        result: ConnectionCheckResult,
    ): ConnectionCheckResult {
        val failure = (result as? ConnectionCheckResult.Failure)?.reason
        diagnostics.emit(
            DeviceDiagnosticEvent(
                severity = if (failure == null) DiagnosticSeverity.INFO else DiagnosticSeverity.WARN,
                component = DiagnosticComponent.CONNECTION,
                stage = DiagnosticStage.COMPLETE,
                operation = operation,
                failureCategory = failure?.name,
                outcome = if (failure == null) "success" else "failure",
            ),
        )
        return result
    }

    private fun finishFailure(
        operation: DiagnosticOperation,
        failure: ConnectionFailure,
        stage: DiagnosticStage,
        timeoutMillis: Long? = null,
        throwable: Throwable? = null,
    ): ConnectionCheckResult =
        ConnectionCheckResult.Failure(failure).also {
            diagnostics.emit(
                DeviceDiagnosticEvent(
                    severity = DiagnosticSeverity.ERROR,
                    component = DiagnosticComponent.CONNECTION,
                    stage = stage,
                    operation = operation,
                    timeoutMillis = timeoutMillis,
                    failureCategory = failure.name,
                    outcome = "failure",
                    throwable = throwable,
                ),
            )
        }

    private fun capabilityReason(
        status: Int,
        versions: String?,
        commands: String?,
    ): String =
        when {
            status != 200 -> "HTTP_STATUS"
            ActiveSyncVersionNegotiator.select(versions) == null -> "NO_MUTUAL_VERSION"
            !safeHeaderTokens(commands).map(String::lowercase).containsAll(setOf("foldersync", "sync")) ->
                "MISSING_REQUIRED_COMMAND"
            else -> "INVALID_CAPABILITY"
        }

    private fun ProbeResponse.used(expected: X509Certificate): Boolean =
        localCertificates.any { certificate ->
            certificate is X509Certificate && certificate.encoded.contentEquals(expected.encoded)
        }
}
