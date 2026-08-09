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
import net.mixalich7b.exchangesync.core.sync.SyncFailureKind
import net.mixalich7b.exchangesync.core.sync.SyncProblem
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredential
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredentialResolution
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredentialResolver

internal class ActiveSyncCapabilityClient(
    private val credentialResolver: ClientCredentialResolver,
    private val transportFactory: SecureHttpTransportFactory,
    private val totalTimeoutMillis: Long = 30_000,
    private val transportDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ActiveSyncCapabilityGateway {
    override suspend fun discover(profile: ConnectionProfile): ActiveSyncCapabilityOutcome {
        val resolution = credentialResolver.resolve(profile.clientCertificateAlias)
        if (resolution !is ClientCredentialResolution.Available) {
            return ActiveSyncCapabilityOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.CLIENT_CERTIFICATE)
        }
        return try {
            withTimeout(totalTimeoutMillis) {
                val transport = withContext(transportDispatcher) { transportFactory.create(resolution.credential) }
                discover(profile, resolution.credential, transport)
            }
        } catch (_: TimeoutCancellationException) {
            ActiveSyncCapabilityOutcome.Failure(SyncFailureKind.TRANSIENT, null)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            ConnectionExceptionClassifier.classify(failure).toCapabilityFailure()
        }
    }

    private suspend fun discover(
        profile: ConnectionProfile,
        credential: ClientCredential,
        transport: SecureHttpTransport,
    ): ActiveSyncCapabilityOutcome {
        var endpoint = ActiveSyncProbePolicy.initialUrl(profile.serverHost)
        val redirects = RedirectTracker(endpoint)
        while (true) {
            val response = transport.execute(ActiveSyncProbePolicy.request(endpoint))
            if (ActiveSyncProbePolicy.isRedirect(response.statusCode)) {
                when (val decision = redirects.follow(endpoint, response.header("Location"))) {
                    is RedirectDecision.Follow -> endpoint = decision.url
                    RedirectDecision.Rejected ->
                        return ActiveSyncCapabilityOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.REDIRECT)
                }
                continue
            }
            if (!response.used(credential.leafCertificate)) {
                return ActiveSyncCapabilityOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.CLIENT_CERTIFICATE)
            }
            val capabilityFailure =
                ActiveSyncResponseEvaluator.evaluate(
                    statusCode = response.statusCode,
                    protocolVersions = response.header("MS-ASProtocolVersions"),
                    protocolCommands = response.header("MS-ASProtocolCommands"),
                )
            if (capabilityFailure != null) return capabilityFailure.toCapabilityFailure()
            val version = ActiveSyncVersionNegotiator.select(response.header("MS-ASProtocolVersions"))
                ?: return ActiveSyncCapabilityOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.COMPATIBILITY)
            return ActiveSyncCapabilityOutcome.Success(endpoint, version)
        }
    }

    private fun SecureHttpResponse.used(expected: X509Certificate): Boolean =
        localCertificates.any { certificate ->
            certificate is X509Certificate && certificate.encoded.contentEquals(expected.encoded)
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
