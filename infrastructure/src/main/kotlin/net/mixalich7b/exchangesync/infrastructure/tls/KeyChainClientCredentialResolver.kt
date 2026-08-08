package net.mixalich7b.exchangesync.infrastructure.tls

import android.content.Context
import android.security.KeyChain
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class KeyChainMaterial(
    val privateKey: PrivateKey?,
    val certificateChain: Array<X509Certificate>?,
)

internal fun interface KeyChainMaterialAccess {
    fun load(alias: String): KeyChainMaterial
}

internal fun interface ClientCredentialResolver {
    suspend fun resolve(alias: String): ClientCredentialResolution
}

internal class KeyChainClientCredentialResolver(
    private val access: KeyChainMaterialAccess,
    private val dispatcher: CoroutineDispatcher,
) : ClientCredentialResolver {
    constructor(context: Context) : this(
        access = AndroidKeyChainMaterialAccess(context.applicationContext),
        dispatcher = Dispatchers.IO,
    )

    override suspend fun resolve(alias: String): ClientCredentialResolution =
        withContext(dispatcher) {
            try {
                val material = access.load(alias)
                ClientCredentialFactory.resolve(alias, material.privateKey, material.certificateChain)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                ClientCredentialResolution.Unavailable
            } catch (_: Exception) {
                ClientCredentialResolution.Unavailable
            }
        }
}

private class AndroidKeyChainMaterialAccess(
    private val context: Context,
) : KeyChainMaterialAccess {
    override fun load(alias: String): KeyChainMaterial =
        KeyChainMaterial(
            privateKey = KeyChain.getPrivateKey(context, alias),
            certificateChain = KeyChain.getCertificateChain(context, alias),
        )
}
