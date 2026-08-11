package net.mixalich7b.exchangesync.infrastructure.activesync

import android.content.Context
import net.mixalich7b.exchangesync.core.connection.ConnectionVerifier
import net.mixalich7b.exchangesync.core.sync.RemoteCalendarPort
import net.mixalich7b.exchangesync.core.sync.SyncFence
import net.mixalich7b.exchangesync.core.sync.SyncStateRepository
import net.mixalich7b.exchangesync.core.sync.SyncStateTransitions
import net.mixalich7b.exchangesync.infrastructure.tls.AndroidCertificateAssetSource
import net.mixalich7b.exchangesync.infrastructure.tls.CertificateAssetLoader
import net.mixalich7b.exchangesync.infrastructure.tls.KeyChainClientCredentialResolver
import net.mixalich7b.exchangesync.infrastructure.diagnostics.AndroidLogcatDiagnosticSink
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnostics

public class AndroidActiveSyncProcessRuntime(
    context: Context,
    stateRepository: SyncStateRepository? = null,
) {
    public val connectionVerifier: ConnectionVerifier
    public val remoteCalendar: RemoteCalendarPort

    init {
        val applicationContext = context.applicationContext
        val diagnostics = DeviceDiagnostics(AndroidLogcatDiagnosticSink())
        val sessions = ActiveSyncProfileSessionRegistry()
        val fenceValidator =
            ActiveSyncSynchronizationFenceValidator { operation ->
                val generation = operation.generation
                val runToken = operation.runToken
                if (stateRepository == null || generation == null || runToken == null) {
                    true
                } else {
                    SyncStateTransitions.mayPerformSideEffect(
                        stateRepository.load(),
                        SyncFence(generation, runToken),
                    )
                }
            }
        val credentials = KeyChainClientCredentialResolver(applicationContext, diagnostics)
        val transportFactory =
            ProfileSessionSecureHttpTransportFactory(
                sessions = sessions,
                delegate =
                    OkHttpSecureHttpTransportFactory(
                        CertificateAssetLoader(
                            AndroidCertificateAssetSource(applicationContext.assets),
                            diagnostics = diagnostics,
                        ),
                        diagnostics,
                    ),
            )
        connectionVerifier =
            ActiveSyncConnectionVerifier(
                credentialResolver = credentials,
                transportFactory = transportFactory,
                sessions = sessions,
                diagnostics = diagnostics,
            )
        remoteCalendar =
            ActiveSyncRemoteCalendar(
                capabilities =
                    ActiveSyncCapabilityClient(
                        credentials,
                        transportFactory,
                        diagnostics = diagnostics,
                        sessions = sessions,
                        fenceValidator = fenceValidator,
                    ),
                commands =
                    ActiveSyncCommandClient(
                        credentials,
                        transportFactory,
                        diagnostics = diagnostics,
                        sessions = sessions,
                        fenceValidator = fenceValidator,
                    ),
                sessions = sessions,
                diagnostics = diagnostics,
            )
    }
}
