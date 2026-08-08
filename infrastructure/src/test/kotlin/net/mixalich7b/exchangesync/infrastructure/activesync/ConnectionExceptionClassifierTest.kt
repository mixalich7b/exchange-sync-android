package net.mixalich7b.exchangesync.infrastructure.activesync

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.PKIXReason
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLKeyException
import javax.net.ssl.SSLPeerUnverifiedException
import net.mixalich7b.exchangesync.core.connection.ConnectionFailure
import net.mixalich7b.exchangesync.infrastructure.tls.CombinedTrustException
import net.mixalich7b.exchangesync.infrastructure.tls.LocalTrustStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConnectionExceptionClassifierTest {
    @Test
    fun `network exceptions map to stable categories`() {
        val expected =
            mapOf(
                UnknownHostException() to ConnectionFailure.SERVER_NOT_FOUND,
                ConnectException() to ConnectionFailure.CONNECTION_FAILED,
                NoRouteToHostException() to ConnectionFailure.CONNECTION_FAILED,
                SocketTimeoutException() to ConnectionFailure.TIMEOUT,
                IOException() to ConnectionFailure.CONNECTION_FAILED,
            )

        expected.forEach { (exception, failure) ->
            assertEquals(failure, ConnectionExceptionClassifier.classify(exception), exception.javaClass.name)
        }
    }

    @Test
    fun `hostname and client handshake exceptions map to specific TLS categories`() {
        assertEquals(
            ConnectionFailure.HOSTNAME_MISMATCH,
            ConnectionExceptionClassifier.classify(SSLPeerUnverifiedException("hostname not verified")),
        )
        assertEquals(
            ConnectionFailure.CLIENT_CERTIFICATE_REJECTED,
            ConnectionExceptionClassifier.classify(SSLKeyException("key rejected")),
        )
        assertEquals(
            ConnectionFailure.CLIENT_CERTIFICATE_REJECTED,
            ConnectionExceptionClassifier.classify(SSLHandshakeException("handshake failure")),
        )
    }

    @Test
    fun `server certificate cause takes precedence over generic handshake failure`() {
        val handshake = SSLHandshakeException("handshake failure")
        handshake.initCause(CertificateException("malformed server certificate"))

        assertEquals(
            ConnectionFailure.SERVER_TRUST,
            ConnectionExceptionClassifier.classify(handshake),
        )
    }

    @Test
    fun `missing trust anchor preserves local CA diagnostics`() {
        val expected =
            mapOf(
                LocalTrustStatus.MISSING to ConnectionFailure.LOCAL_CA_MISSING,
                LocalTrustStatus.INVALID to ConnectionFailure.LOCAL_CA_INVALID,
                LocalTrustStatus.AVAILABLE to ConnectionFailure.SERVER_TRUST,
            )

        expected.forEach { (status, failure) ->
            val handshake = SSLHandshakeException("certificate rejected")
            handshake.initCause(CombinedTrustException(status, systemFailure(PKIXReason.NO_TRUST_ANCHOR)))

            assertEquals(failure, ConnectionExceptionClassifier.classify(handshake), status.name)
        }
    }

    @Test
    fun `non anchor trust failures stay server trust regardless of local assets`() {
        val failures =
            listOf(
                systemFailure(CertPathValidatorException.BasicReason.EXPIRED),
                CertificateException("Trust anchor for certification path not found"),
            )

        LocalTrustStatus.entries.forEach { status ->
            failures.forEach { systemFailure ->
                val handshake = SSLHandshakeException("certificate rejected")
                handshake.initCause(CombinedTrustException(status, systemFailure))

                assertEquals(
                    ConnectionFailure.SERVER_TRUST,
                    ConnectionExceptionClassifier.classify(handshake),
                    "$status / ${systemFailure.cause?.javaClass?.simpleName ?: "ambiguous"}",
                )
            }
        }
    }

    @Test
    fun `unknown exception does not expose its message as a category`() {
        val exception = IllegalStateException("private-key-material-must-not-escape")

        assertEquals(ConnectionFailure.UNKNOWN, ConnectionExceptionClassifier.classify(exception))
    }

    private fun systemFailure(reason: CertPathValidatorException.Reason): CertificateException =
        CertificateException(
            "system rejected",
            CertPathValidatorException("path rejected", null, null, -1, reason),
        )
}
