package net.mixalich7b.exchangesync.infrastructure.activesync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.mixalich7b.exchangesync.core.connection.ConnectionFailure
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.sync.SyncFailureKind
import net.mixalich7b.exchangesync.core.sync.SyncProblem
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

internal class ActiveSyncCapabilityClient(
    private val credentialResolver: ClientCredentialResolver,
    private val transportFactory: SecureHttpTransportFactory,
    private val totalTimeoutMillis: Long = 30_000,
    private val transportDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: DeviceDiagnostics = DeviceDiagnostics(),
    private val sessions: ActiveSyncProfileSessionRegistry = ActiveSyncProfileSessionRegistry(),
    private val fenceValidator: ActiveSyncSynchronizationFenceValidator = AlwaysCurrentActiveSyncSynchronizationFence,
) : ActiveSyncCapabilityGateway {
    override suspend fun discover(profile: ConnectionProfile): ActiveSyncCapabilityOutcome =
        discover(profile, diagnostics.operation(DiagnosticOperationKind.CAPABILITY_DISCOVERY))

    override suspend fun discover(
        profile: ConnectionProfile,
        operation: DiagnosticOperation,
    ): ActiveSyncCapabilityOutcome {
        val resolution = credentialResolver.resolve(profile.clientCertificateAlias, operation)
        if (resolution !is ClientCredentialResolution.Available) {
            diagnostics.emit(failureEvent(operation, DiagnosticStage.KEYCHAIN_RESOLUTION, SyncProblem.CLIENT_CERTIFICATE))
            return ActiveSyncCapabilityOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.CLIENT_CERTIFICATE)
        }
        return try {
            pacedSynchronizationExchange(profile, operation) {
                withTimeout(totalTimeoutMillis.milliseconds) {
                    val transport =
                        withContext(transportDispatcher) {
                            transportFactory.create(profile, resolution.credential, operation)
                        }
                    ensureSynchronizationFenceIsCurrent(operation)
                    discover(profile, resolution.credential, transport, operation)
                }
            }
        } catch (failure: TimeoutCancellationException) {
            diagnostics.emit(
                failureEvent(operation, DiagnosticStage.FAILURE, null).copy(
                    timeoutMillis = totalTimeoutMillis,
                    throwable = failure,
                ),
            )
            ActiveSyncCapabilityOutcome.Failure(SyncFailureKind.TRANSIENT, null)
        } catch (cancellation: CancellationException) {
            diagnostics.emit(
                DeviceDiagnosticEvent(
                    DiagnosticSeverity.INFO,
                    DiagnosticComponent.ACTIVE_SYNC,
                    DiagnosticStage.CANCELLATION,
                    operation,
                ),
            )
            throw cancellation
        } catch (failure: Exception) {
            val category = ConnectionExceptionClassifier.classify(failure)
            val outcome = category.toCapabilityFailure()
            diagnostics.emit(
                failureEvent(operation, category.diagnosticStage(), outcome.problem).copy(throwable = failure),
            )
            outcome
        }
    }

    private suspend fun <T> pacedSynchronizationExchange(
        profile: ConnectionProfile,
        operation: DiagnosticOperation,
        exchange: suspend () -> T,
    ): T =
        if (operation.kind == DiagnosticOperationKind.SYNCHRONIZATION) {
            sessions.acquire(profile).requestPacer.exchange(
                beforeDispatch = { fenceValidator.isCurrent(operation) },
                block = exchange,
            )
        } else {
            exchange()
        }

    private suspend fun ensureSynchronizationFenceIsCurrent(operation: DiagnosticOperation) {
        if (
            operation.kind == DiagnosticOperationKind.SYNCHRONIZATION &&
            !fenceValidator.isCurrent(operation)
        ) {
            throw ObsoleteActiveSyncSynchronizationException()
        }
    }

    private suspend fun discover(
        profile: ConnectionProfile,
        credential: ClientCredential,
        transport: SecureHttpTransport,
        operation: DiagnosticOperation,
    ): ActiveSyncCapabilityOutcome {
        var endpoint = ActiveSyncProbePolicy.initialUrl(profile.serverHost)
        val redirects = RedirectTracker(endpoint)
        while (true) {
            val response = transport.execute(ActiveSyncProbePolicy.request(endpoint, operation))
            if (ActiveSyncProbePolicy.isRedirect(response.statusCode)) {
                when (val decision = redirects.follow(endpoint, response.header("Location"))) {
                    is RedirectDecision.Follow -> {
                        diagnostics.emit(
                            DeviceDiagnosticEvent(
                                DiagnosticSeverity.INFO,
                                DiagnosticComponent.ACTIVE_SYNC,
                                DiagnosticStage.REDIRECT,
                                operation,
                                method = "OPTIONS",
                                host = decision.url.diagnosticHost(),
                                path = decision.url.diagnosticPath(),
                                status = response.statusCode,
                                outcome = "follow",
                            ),
                        )
                        endpoint = decision.url
                    }
                    RedirectDecision.Rejected -> {
                        diagnostics.emit(
                            DeviceDiagnosticEvent(
                                DiagnosticSeverity.WARN,
                                DiagnosticComponent.ACTIVE_SYNC,
                                DiagnosticStage.REDIRECT,
                                operation,
                                method = "OPTIONS",
                                host = endpoint.diagnosticHost(),
                                path = endpoint.diagnosticPath(),
                                status = response.statusCode,
                                failureCategory = SyncProblem.REDIRECT.name,
                                outcome = "rejected",
                            ),
                        )
                        return ActiveSyncCapabilityOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.REDIRECT)
                    }
                }
                continue
            }
            val identityEvidence = response.clientIdentityParticipation(credential.leafCertificate)
            diagnostics.emit(
                DeviceDiagnosticEvent(
                    severity =
                        if (identityEvidence == ClientIdentityParticipationEvidence.MISMATCHED) {
                            DiagnosticSeverity.ERROR
                        } else {
                            DiagnosticSeverity.INFO
                        },
                    component = DiagnosticComponent.TLS,
                    stage = DiagnosticStage.CLIENT_CERTIFICATE_VERIFICATION,
                    operation = operation,
                    method = "OPTIONS",
                    host = endpoint.diagnosticHost(),
                    path = endpoint.diagnosticPath(),
                    chainLength = response.localCertificates.size,
                    reasonCode = "FIXED_IDENTITY_CONFIGURED",
                    failureCategory =
                        SyncProblem.CLIENT_CERTIFICATE.name
                            .takeIf { identityEvidence == ClientIdentityParticipationEvidence.MISMATCHED },
                    outcome = identityEvidence.diagnosticValue,
                ),
            )
            if (identityEvidence == ClientIdentityParticipationEvidence.MISMATCHED) {
                diagnostics.emit(
                    failureEvent(
                        operation,
                        DiagnosticStage.CLIENT_CERTIFICATE_VERIFICATION,
                        SyncProblem.CLIENT_CERTIFICATE,
                    ).copy(
                        method = "OPTIONS",
                        host = endpoint.diagnosticHost(),
                        path = endpoint.diagnosticPath(),
                        chainLength = response.localCertificates.size,
                        reasonCode = "SELECTED_LEAF_ABSENT",
                    ),
                )
                return ActiveSyncCapabilityOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.CLIENT_CERTIFICATE)
            }
            val capabilityFailure =
                ActiveSyncResponseEvaluator.evaluate(
                    statusCode = response.statusCode,
                    protocolVersions = response.header("MS-ASProtocolVersions"),
                    protocolCommands = response.header("MS-ASProtocolCommands"),
                )
            if (capabilityFailure != null) {
                diagnostics.emit(
                    DeviceDiagnosticEvent(
                        DiagnosticSeverity.WARN,
                        DiagnosticComponent.ACTIVE_SYNC,
                        DiagnosticStage.CAPABILITY_VALIDATION,
                        operation,
                        method = "OPTIONS",
                        host = endpoint.diagnosticHost(),
                        path = endpoint.diagnosticPath(),
                        status = response.statusCode,
                        protocolVersions = safeHeaderTokens(response.header("MS-ASProtocolVersions")),
                        protocolCommands = safeHeaderTokens(response.header("MS-ASProtocolCommands")),
                        reasonCode =
                            capabilityReason(
                                response.statusCode,
                                response.header("MS-ASProtocolVersions"),
                                response.header("MS-ASProtocolCommands"),
                            ),
                        failureCategory = capabilityFailure.name,
                    ),
                )
                return capabilityFailure.toCapabilityFailure()
            }
            val version = ActiveSyncVersionNegotiator.select(response.header("MS-ASProtocolVersions"))
            if (version == null) {
                diagnostics.emit(
                    failureEvent(
                        operation,
                        DiagnosticStage.VERSION_SELECTION,
                        SyncProblem.COMPATIBILITY,
                    ).copy(
                        method = "OPTIONS",
                        host = endpoint.diagnosticHost(),
                        path = endpoint.diagnosticPath(),
                        status = response.statusCode,
                        reasonCode = "NO_MUTUAL_VERSION",
                    ),
                )
                return ActiveSyncCapabilityOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.COMPATIBILITY)
            }
            diagnostics.emit(
                DeviceDiagnosticEvent(
                    DiagnosticSeverity.INFO,
                    DiagnosticComponent.ACTIVE_SYNC,
                    DiagnosticStage.VERSION_SELECTION,
                    operation,
                    host = endpoint.diagnosticHost(),
                    path = endpoint.diagnosticPath(),
                    outcome = version.wireValue,
                ),
            )
            return ActiveSyncCapabilityOutcome.Success(
                terminalEndpoint = endpoint,
                version = version,
                supportedVersions = ActiveSyncVersionNegotiator.supported(response.header("MS-ASProtocolVersions")),
            )
        }
    }

    private fun failureEvent(
        operation: DiagnosticOperation,
        stage: DiagnosticStage,
        problem: SyncProblem?,
    ): DeviceDiagnosticEvent =
        DeviceDiagnosticEvent(
            severity = DiagnosticSeverity.ERROR,
            component = DiagnosticComponent.ACTIVE_SYNC,
            stage = stage,
            operation = operation,
            failureCategory = problem?.name,
            outcome = "failure",
        )

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

    private fun ConnectionFailure.toCapabilityFailure(): ActiveSyncCapabilityOutcome.Failure =
        when (this) {
            ConnectionFailure.CLIENT_CERTIFICATE_UNAVAILABLE,
            ConnectionFailure.CLIENT_CERTIFICATE_REJECTED,
            -> ActiveSyncCapabilityOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.CLIENT_CERTIFICATE)
            ConnectionFailure.SERVER_TRUST,
            ConnectionFailure.HOSTNAME_MISMATCH,
            ConnectionFailure.LOCAL_CA_MISSING,
            ConnectionFailure.LOCAL_CA_INVALID,
            -> ActiveSyncCapabilityOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.TLS)
            ConnectionFailure.ACCESS_DENIED -> ActiveSyncCapabilityOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.ACCESS)
            ConnectionFailure.REDIRECT_POLICY -> ActiveSyncCapabilityOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.REDIRECT)
            ConnectionFailure.PROTOCOL_INCOMPATIBLE,
            ConnectionFailure.ENDPOINT_MISMATCH,
            -> ActiveSyncCapabilityOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.COMPATIBILITY)
            ConnectionFailure.SERVER_NOT_FOUND,
            ConnectionFailure.CONNECTION_FAILED,
            ConnectionFailure.TIMEOUT,
            ConnectionFailure.SERVER_ERROR,
            -> ActiveSyncCapabilityOutcome.Failure(SyncFailureKind.TRANSIENT, null)
            ConnectionFailure.SERVER_CERTIFICATE_DIAGNOSTICS,
            ConnectionFailure.PERSISTENCE,
            ConnectionFailure.UNKNOWN,
            -> ActiveSyncCapabilityOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.PROTOCOL_DATA)
        }
}
