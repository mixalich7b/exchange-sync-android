package net.mixalich7b.exchangesync.infrastructure.tls

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CertificateAssetLoaderTest {
    @Test
    fun `absent asset directory is an empty valid local trust set`() {
        val loader = CertificateAssetLoader(FakeSource(emptyMap()), FakeParser())

        val result = loader.load()

        assertFalse(result.hadAssets)
        assertEquals(emptyList<X509Certificate>(), result.certificates)
        assertEquals(emptyList<LocalCertificateIssue>(), result.issues)
    }

    @Test
    fun `PEM and DER assets are both discovered and parsed`() {
        val pem = "-----BEGIN CERTIFICATE-----\ntest\n-----END CERTIFICATE-----".encodeToByteArray()
        val der = byteArrayOf(0x30, 0x03, 0x01, 0x02, 0x03)
        val first = StubX509Certificate(byteArrayOf(10))
        val second = StubX509Certificate(byteArrayOf(11))
        val parser = FakeParser(mapOf(pem.contentHashCode() to first, der.contentHashCode() to second))
        val loader = CertificateAssetLoader(FakeSource(mapOf("root.pem" to pem, "issuer.der" to der)), parser)

        val result = loader.load()

        assertTrue(result.hadAssets)
        assertEquals(2, result.certificates.size)
        assertSame(second, result.certificates[0])
        assertSame(first, result.certificates[1])
        assertEquals(listOf("tls/issuer.der", "tls/root.pem"), parser.paths)
        assertEquals(emptyList<LocalCertificateIssue>(), result.issues)
    }

    @Test
    fun `malformed asset is reported while another valid certificate is retained`() {
        val valid = byteArrayOf(0x30, 0x01, 0x00)
        val malformed = "not-a-certificate".encodeToByteArray()
        val certificate = StubX509Certificate()
        val parser = FakeParser(mapOf(valid.contentHashCode() to certificate))
        val loader =
            CertificateAssetLoader(
                FakeSource(mapOf("valid.der" to valid, "broken.pem" to malformed)),
                parser,
            )

        val result = loader.load()

        assertEquals(listOf(certificate), result.certificates)
        assertEquals(listOf(LocalCertificateIssue("broken.pem")), result.issues)
    }

    @Test
    fun `unsupported asset read is reported without exposing its exception`() {
        val loader = CertificateAssetLoader(FailingSource("unsupported.cer"), FakeParser())

        val result = loader.load()

        assertEquals(emptyList<X509Certificate>(), result.certificates)
        assertEquals(listOf(LocalCertificateIssue("unsupported.cer")), result.issues)
    }

    private class FakeSource(
        private val assets: Map<String, ByteArray>,
    ) : CertificateAssetSource {
        override fun list(directory: String): List<String> = assets.keys.toList()

        override fun open(path: String): InputStream =
            ByteArrayInputStream(assets.getValue(path.substringAfterLast('/')))
    }

    private class FailingSource(
        private val fileName: String,
    ) : CertificateAssetSource {
        override fun list(directory: String): List<String> = listOf(fileName)

        override fun open(path: String): InputStream = error("unsupported provider")
    }

    private class FakeParser(
        private val certificates: Map<Int, X509Certificate> = emptyMap(),
    ) : X509CertificateParser {
        val paths = mutableListOf<String>()

        override fun parse(path: String, input: InputStream): X509Certificate {
            paths += path
            val bytes = input.readBytes()
            return certificates[bytes.contentHashCode()] ?: throw CertificateException("malformed")
        }
    }
}
