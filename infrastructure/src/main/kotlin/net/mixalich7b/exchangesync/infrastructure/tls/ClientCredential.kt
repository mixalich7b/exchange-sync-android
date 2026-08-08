package net.mixalich7b.exchangesync.infrastructure.tls

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

internal class ClientCredential(
    val alias: String,
    val privateKey: PrivateKey,
    certificateChain: Array<X509Certificate>,
) {
    val certificateChain: Array<X509Certificate> = certificateChain.copyOf()

    val leafCertificate: X509Certificate
        get() = certificateChain.first()
}

internal sealed interface ClientCredentialResolution {
    data class Available(val credential: ClientCredential) : ClientCredentialResolution

    data object Unavailable : ClientCredentialResolution
}

internal object ClientCredentialFactory {
    fun resolve(
        alias: String,
        privateKey: PrivateKey?,
        certificateChain: Array<X509Certificate>?,
    ): ClientCredentialResolution {
        if (alias.isBlank() || privateKey == null || certificateChain.isNullOrEmpty()) {
            return ClientCredentialResolution.Unavailable
        }
        return ClientCredentialResolution.Available(ClientCredential(alias, privateKey, certificateChain))
    }
}

internal class FixedAliasKeyManager(
    private val credential: ClientCredential,
) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (keyType != null && supports(keyType)) arrayOf(credential.alias) else null

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = if (keyType != null && keyType.any(::supports)) credential.alias else null

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = if (keyType != null && keyType.any(::supports)) credential.alias else null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = null

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == credential.alias) credential.certificateChain.copyOf() else null

    override fun getPrivateKey(alias: String?): PrivateKey? =
        if (alias == credential.alias) credential.privateKey else null

    private fun supports(keyType: String): Boolean =
        keyType.equals(credential.privateKey.algorithm, ignoreCase = true)
}
