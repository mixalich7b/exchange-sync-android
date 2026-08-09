package net.mixalich7b.exchangesync.infrastructure.tls

import android.content.Context
import android.security.KeyChain
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnosticEvent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnostics
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticComponent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperation
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperationKind
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticSeverity
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticStage

internal data class KeyChainMaterial(
    val privateKey: PrivateKey?,
    val certificateChain: Array<X509Certificate>?,
)

internal fun interface KeyChainMaterialAccess {
    fun load(alias: String): KeyChainMaterial
}

internal fun interface ClientCredentialResolver {
    suspend fun resolve(alias: String): ClientCredentialResolution

    suspend fun resolve(
        alias: String,
        operation: DiagnosticOperation,
    ): ClientCredentialResolution = resolve(alias)
}

internal class KeyChainClientCredentialResolver(
    private val access: KeyChainMaterialAccess,
    private val dispatcher: CoroutineDispatcher,
    private val diagnostics: DeviceDiagnostics = DeviceDiagnostics(),
) : ClientCredentialResolver {
    constructor(
        context: Context,
        diagnostics: DeviceDiagnostics = DeviceDiagnostics(),
    ) : this(
        access = AndroidKeyChainMaterialAccess(context.applicationContext),
        dispatcher = Dispatchers.IO,
        diagnostics = diagnostics,
    )

    override suspend fun resolve(alias: String): ClientCredentialResolution =
        resolve(alias, diagnostics.operation(DiagnosticOperationKind.LOCAL_OPERATION))

    override suspend fun resolve(
        alias: String,
        operation: DiagnosticOperation,
    ): ClientCredentialResolution =
        withContext(dispatcher) {
            try {
                val material = access.load(alias)
                val resolution = ClientCredentialFactory.resolve(alias, material.privateKey, material.certificateChain)
                diagnostics.emit(
                    DeviceDiagnosticEvent(
                        severity =
                            if (resolution is ClientCredentialResolution.Available) {
                                DiagnosticSeverity.INFO
                            } else {
                                DiagnosticSeverity.ERROR
                            },
                        component = DiagnosticComponent.KEYCHAIN,
                        stage = DiagnosticStage.KEYCHAIN_RESOLUTION,
                        operation = operation,
                        chainLength = material.certificateChain?.size ?: 0,
                        keyAlgorithm = material.privateKey?.algorithm,
                        fingerprint =
                            (resolution as? ClientCredentialResolution.Available)
                                ?.credential
                                ?.leafCertificate
                                ?.let(::sha256Fingerprint),
                        outcome =
                            if (resolution is ClientCredentialResolution.Available) {
                                "available"
                            } else {
                                "unavailable"
                            },
                    ),
                )
                resolution
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                emitFailure(operation, failure)
                ClientCredentialResolution.Unavailable
            } catch (failure: Exception) {
                emitFailure(operation, failure)
                ClientCredentialResolution.Unavailable
            }
        }

    private fun emitFailure(
        operation: DiagnosticOperation,
        failure: Throwable,
    ) {
        diagnostics.emit(
            DeviceDiagnosticEvent(
                DiagnosticSeverity.ERROR,
                DiagnosticComponent.KEYCHAIN,
                DiagnosticStage.KEYCHAIN_RESOLUTION,
                operation,
                outcome = "failure",
                throwable = failure,
            ),
        )
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
