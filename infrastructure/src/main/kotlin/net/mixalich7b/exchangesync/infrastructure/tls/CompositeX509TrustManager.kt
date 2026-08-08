package net.mixalich7b.exchangesync.infrastructure.tls

import android.annotation.SuppressLint
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

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
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        system.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val systemFailure =
            try {
                system.checkServerTrusted(chain, authType)
                return
            } catch (failure: CertificateException) {
                failure
            }

        if (local != null) {
            try {
                local.checkServerTrusted(chain, authType)
                return
            } catch (_: CertificateException) {
                // The combined diagnostic below retains the actionable local-trust state.
            }
        }

        throw CombinedTrustException(localTrustStatus, systemFailure)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        system.acceptedIssuers + local?.acceptedIssuers.orEmpty()
}
