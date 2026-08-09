package net.mixalich7b.exchangesync.infrastructure.tls

import android.annotation.SuppressLint
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import java.security.MessageDigest
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnosticEvent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnostics
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticComponent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperation
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperationKind
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticSeverity
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticStage

internal enum class LocalTrustStatus {
    AVAILABLE,
    MISSING,
    INVALID,
}

internal class CombinedTrustException(
    val localTrustStatus: LocalTrustStatus,
    systemFailure: CertificateException,
) : CertificateException("Server certificate is not trusted", systemFailure)

// Both branches delegate to TrustManagerFactory-produced validators; this class never accepts a chain itself.
@SuppressLint("CustomX509TrustManager")
internal class CompositeX509TrustManager(
    private val system: X509TrustManager,
    private val local: X509TrustManager?,
    private val localTrustStatus: LocalTrustStatus,
    private val diagnostics: DeviceDiagnostics = DeviceDiagnostics(),
    private val operation: DiagnosticOperation = diagnostics.operation(DiagnosticOperationKind.LOCAL_OPERATION),
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        system.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val systemFailure =
            try {
                system.checkServerTrusted(chain, authType)
                emitServerChain(chain, "system_trusted", DiagnosticSeverity.INFO)
                return
            } catch (failure: CertificateException) {
                failure
            }

        var localFailure: CertificateException? = null
        if (local != null) {
            try {
                local.checkServerTrusted(chain, authType)
                emitServerChain(chain, "local_trusted", DiagnosticSeverity.INFO)
                return
            } catch (failure: CertificateException) {
                localFailure = failure
            }
        }

        val combined = CombinedTrustException(localTrustStatus, systemFailure)
        localFailure?.let(combined::addSuppressed)
        emitServerChain(chain, localTrustStatus.name, DiagnosticSeverity.ERROR, combined)
        throw combined
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        system.acceptedIssuers + local?.acceptedIssuers.orEmpty()

    private fun emitServerChain(
        chain: Array<out X509Certificate>?,
        outcome: String,
        severity: DiagnosticSeverity,
        throwable: Throwable? = null,
    ) {
        diagnostics.emit(
            DeviceDiagnosticEvent(
                severity = severity,
                component = DiagnosticComponent.TLS,
                stage = DiagnosticStage.SERVER_CHAIN,
                operation = operation,
                chainLength = chain?.size ?: 0,
                fingerprint = chain?.firstOrNull()?.let(::sha256Fingerprint),
                reasonCode = localTrustStatus.name,
                outcome = outcome,
                throwable = throwable,
            ),
        )
    }

}

internal fun sha256Fingerprint(certificate: X509Certificate): String? =
    runCatching {
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString(":") { byte -> "%02X".format(byte) }
    }.getOrNull()
