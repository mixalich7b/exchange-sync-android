package net.mixalich7b.exchangesync.infrastructure.tls

import android.content.res.AssetManager
import java.io.InputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnosticEvent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnostics
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticComponent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperation
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperationKind
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticSeverity
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticStage

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
    private val diagnostics: DeviceDiagnostics = DeviceDiagnostics(),
) {
    fun load(
        operation: DiagnosticOperation = diagnostics.operation(DiagnosticOperationKind.LOCAL_OPERATION),
    ): LocalCertificates {
        val names =
            try {
                source.list(DIRECTORY).sorted()
            } catch (failure: Exception) {
                diagnostics.emit(
                    DeviceDiagnosticEvent(
                        DiagnosticSeverity.WARN,
                        DiagnosticComponent.LOCAL_CA,
                        DiagnosticStage.LOCAL_CA_LIST,
                        operation,
                        outcome = "failed",
                        throwable = failure,
                    ),
                )
                return LocalCertificates(emptyList(), emptyList(), hadAssets = false)
            }
        diagnostics.emit(
            DeviceDiagnosticEvent(
                DiagnosticSeverity.INFO,
                DiagnosticComponent.LOCAL_CA,
                DiagnosticStage.LOCAL_CA_LIST,
                operation,
                chainLength = names.size,
                outcome = if (names.isEmpty()) "empty" else "available",
            ),
        )
        val certificates = mutableListOf<X509Certificate>()
        val issues = mutableListOf<LocalCertificateIssue>()

        names.forEach { fileName ->
            try {
                source.open("$DIRECTORY/$fileName").use { input ->
                    certificates += parser.parse("$DIRECTORY/$fileName", input)
                }
                diagnostics.emit(
                    DeviceDiagnosticEvent(
                        DiagnosticSeverity.INFO,
                        DiagnosticComponent.LOCAL_CA,
                        DiagnosticStage.LOCAL_CA_PARSE,
                        operation,
                        assetFile = fileName,
                        outcome = "parsed",
                    ),
                )
            } catch (failure: Exception) {
                issues += LocalCertificateIssue(fileName)
                diagnostics.emit(
                    DeviceDiagnosticEvent(
                        DiagnosticSeverity.WARN,
                        DiagnosticComponent.LOCAL_CA,
                        DiagnosticStage.LOCAL_CA_PARSE,
                        operation,
                        assetFile = fileName,
                        outcome = "invalid",
                        throwable = failure,
                    ),
                )
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
        assetManager.list(directory)?.toList().orEmpty()

    override fun open(path: String): InputStream = assetManager.open(path)
}

private object JcaX509CertificateParser : X509CertificateParser {
    override fun parse(path: String, input: InputStream): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(input) as? X509Certificate
            ?: throw CertificateException("Asset is not an X.509 certificate")
}
