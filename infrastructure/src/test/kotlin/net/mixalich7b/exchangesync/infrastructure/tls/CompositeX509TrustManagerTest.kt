package net.mixalich7b.exchangesync.infrastructure.tls

import java.security.cert.CertificateException
import java.security.cert.CertificateEncodingException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CompositeX509TrustManagerTest {
    @Test
    fun `system trust accepts without consulting local trust`() {
        val system = RecordingTrustManager(acceptServer = true)
        val local = RecordingTrustManager(acceptServer = false)
        val manager = CompositeX509TrustManager(system, local, LocalTrustStatus.AVAILABLE)

        manager.checkServerTrusted(chain(), "RSA")

        assertEquals(1, system.serverChecks)
        assertEquals(0, local.serverChecks)
    }

    @Test
    fun `fingerprint diagnostics cannot turn an accepted server chain into a trust failure`() {
        val unencodableCertificate =
            object : StubX509Certificate() {
                override fun getEncoded(): ByteArray = throw CertificateEncodingException("unavailable")
            }
        val manager =
            CompositeX509TrustManager(
                RecordingTrustManager(acceptServer = true),
                local = null,
                localTrustStatus = LocalTrustStatus.MISSING,
            )

        manager.checkServerTrusted(arrayOf(unencodableCertificate), "RSA")

        assertEquals(1, manager.acceptedIssuers.size)
    }

    @Test
    fun `local trust accepts after system trust rejects`() {
        val system = RecordingTrustManager(acceptServer = false)
        val local = RecordingTrustManager(acceptServer = true)
        val manager = CompositeX509TrustManager(system, local, LocalTrustStatus.AVAILABLE)

        manager.checkServerTrusted(chain(), "RSA")

        assertEquals(1, system.serverChecks)
        assertEquals(1, local.serverChecks)
    }

    @Test
    fun `chain rejected by both preserves available local trust status`() {
        val manager =
            CompositeX509TrustManager(
                RecordingTrustManager(acceptServer = false),
                RecordingTrustManager(acceptServer = false),
                LocalTrustStatus.AVAILABLE,
            )

        val failure =
            assertThrows(CombinedTrustException::class.java) {
                manager.checkServerTrusted(chain(), "RSA")
            }

        assertEquals(LocalTrustStatus.AVAILABLE, failure.localTrustStatus)
    }

    @Test
    fun `missing and invalid local trust diagnostics survive rejection`() {
        listOf(LocalTrustStatus.MISSING, LocalTrustStatus.INVALID).forEach { status ->
            val manager =
                CompositeX509TrustManager(
                    RecordingTrustManager(acceptServer = false),
                    local = null,
                    localTrustStatus = status,
                )

            val failure =
                assertThrows(CombinedTrustException::class.java) {
                    manager.checkServerTrusted(chain(), "RSA")
                }

            assertEquals(status, failure.localTrustStatus)
        }
    }

    @Test
    fun `accepted issuers include system and local anchors`() {
        val systemCertificate = StubX509Certificate(byteArrayOf(1))
        val localCertificate = StubX509Certificate(byteArrayOf(2))
        val manager =
            CompositeX509TrustManager(
                RecordingTrustManager(true, systemCertificate),
                RecordingTrustManager(true, localCertificate),
                LocalTrustStatus.AVAILABLE,
            )

        val issuers = manager.acceptedIssuers

        assertEquals(2, issuers.size)
        assertSame(systemCertificate, issuers[0])
        assertSame(localCertificate, issuers[1])
    }

    private class RecordingTrustManager(
        private val acceptServer: Boolean,
        private val issuer: X509Certificate = StubX509Certificate(),
    ) : X509TrustManager {
        var serverChecks: Int = 0

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            serverChecks += 1
            if (!acceptServer) throw CertificateException("untrusted in test")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(issuer)
    }

    private fun chain(): Array<X509Certificate> = arrayOf(StubX509Certificate())
}
