package net.mixalich7b.exchangesync.infrastructure.tls

import android.content.res.AssetManager
import java.io.IOException
import java.io.InputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

internal fun interface X509CertificateParser {
    fun parse(path: String, input: InputStream): X509Certificate
}

internal interface CertificateAssetSource {
    fun list(directory: String): List<String>

    fun open(path: String): InputStream
}

internal data class LocalCertificateIssue(val fileName: String)

internal data class LocalCertificates(
    val certificates: List<X509Certificate>,
    val issues: List<LocalCertificateIssue>,
    val hadAssets: Boolean,
)

internal class CertificateAssetLoader(
    private val source: CertificateAssetSource,
    private val parser: X509CertificateParser = JcaX509CertificateParser,
) {
    fun load(): LocalCertificates {
        val names = source.list(DIRECTORY).sorted()
        val certificates = mutableListOf<X509Certificate>()
        val issues = mutableListOf<LocalCertificateIssue>()

        names.forEach { fileName ->
            try {
                source.open("$DIRECTORY/$fileName").use { input ->
                    certificates += parser.parse("$DIRECTORY/$fileName", input)
                }
            } catch (_: Exception) {
                issues += LocalCertificateIssue(fileName)
            }
        }

        return LocalCertificates(
            certificates = certificates,
            issues = issues,
            hadAssets = names.isNotEmpty(),
        )
    }

    private companion object {
        const val DIRECTORY = "tls"
    }
}

internal class AndroidCertificateAssetSource(
    private val assetManager: AssetManager,
) : CertificateAssetSource {
    override fun list(directory: String): List<String> =
        try {
            assetManager.list(directory)?.toList().orEmpty()
        } catch (_: IOException) {
            emptyList()
        }

    override fun open(path: String): InputStream = assetManager.open(path)
}

private object JcaX509CertificateParser : X509CertificateParser {
    override fun parse(path: String, input: InputStream): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(input) as? X509Certificate
            ?: throw CertificateException("Asset is not an X.509 certificate")
}
