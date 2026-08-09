package net.mixalich7b.exchangesync.infrastructure.activesync

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.PKIXReason
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLKeyException
import javax.net.ssl.SSLPeerUnverifiedException
import net.mixalich7b.exchangesync.core.connection.ConnectionFailure
import net.mixalich7b.exchangesync.infrastructure.tls.CombinedTrustException
import net.mixalich7b.exchangesync.infrastructure.tls.LocalTrustStatus
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticStage

internal object ConnectionExceptionClassifier {
    fun classify(failure: Throwable): ConnectionFailure {
        val causes = causeChain(failure)
        causes.filterIsInstance<CombinedTrustException>().firstOrNull()?.let { trustFailure ->
            val missingTrustAnchor =
                causes.filterIsInstance<CertPathValidatorException>().any { pathFailure ->
                    pathFailure.reason == PKIXReason.NO_TRUST_ANCHOR
                }
            if (!missingTrustAnchor) return ConnectionFailure.SERVER_TRUST

            return when (trustFailure.localTrustStatus) {
                LocalTrustStatus.MISSING -> ConnectionFailure.LOCAL_CA_MISSING
                LocalTrustStatus.INVALID -> ConnectionFailure.LOCAL_CA_INVALID
                LocalTrustStatus.AVAILABLE -> ConnectionFailure.SERVER_TRUST
            }
        }

        return when {
            causes.any { it is UnknownHostException } -> ConnectionFailure.SERVER_NOT_FOUND
            causes.any { it is SocketTimeoutException } -> ConnectionFailure.TIMEOUT
            causes.any { it is SSLPeerUnverifiedException } -> ConnectionFailure.HOSTNAME_MISMATCH
            causes.any { it is SSLKeyException } -> ConnectionFailure.CLIENT_CERTIFICATE_REJECTED
            causes.any { it is CertificateException } -> ConnectionFailure.SERVER_TRUST
            causes.any { it is SSLHandshakeException } -> ConnectionFailure.CLIENT_CERTIFICATE_REJECTED
            causes.any { it is ConnectException || it is NoRouteToHostException } ->
                ConnectionFailure.CONNECTION_FAILED
            causes.any { it is IOException } -> ConnectionFailure.CONNECTION_FAILED
            else -> ConnectionFailure.UNKNOWN
        }
    }

    private fun causeChain(failure: Throwable): List<Throwable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val causes = mutableListOf<Throwable>()
        var current: Throwable? = failure
        while (current != null && seen.add(current)) {
            causes += current
            current = current.cause
        }
        return causes
    }
}

internal fun ConnectionFailure.diagnosticStage(): DiagnosticStage =
    when (this) {
        ConnectionFailure.CLIENT_CERTIFICATE_UNAVAILABLE -> DiagnosticStage.KEYCHAIN_RESOLUTION
        ConnectionFailure.CLIENT_CERTIFICATE_REJECTED -> DiagnosticStage.HANDSHAKE
        ConnectionFailure.SERVER_TRUST,
        ConnectionFailure.LOCAL_CA_MISSING,
        ConnectionFailure.LOCAL_CA_INVALID,
        ConnectionFailure.SERVER_CERTIFICATE_DIAGNOSTICS,
        -> DiagnosticStage.SERVER_CHAIN
        ConnectionFailure.HOSTNAME_MISMATCH -> DiagnosticStage.HOSTNAME
        ConnectionFailure.REDIRECT_POLICY -> DiagnosticStage.REDIRECT
        ConnectionFailure.PROTOCOL_INCOMPATIBLE,
        ConnectionFailure.ENDPOINT_MISMATCH,
        -> DiagnosticStage.CAPABILITY_VALIDATION
        else -> DiagnosticStage.FAILURE
    }
