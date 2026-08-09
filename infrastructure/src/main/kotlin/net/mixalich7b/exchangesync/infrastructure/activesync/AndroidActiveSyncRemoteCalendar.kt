package net.mixalich7b.exchangesync.infrastructure.activesync

import android.content.Context
import net.mixalich7b.exchangesync.core.sync.RemoteCalendarPort
import net.mixalich7b.exchangesync.core.sync.RemotePageOutcome
import net.mixalich7b.exchangesync.core.sync.SyncPageRequest
import net.mixalich7b.exchangesync.core.sync.SyncPhase
import net.mixalich7b.exchangesync.infrastructure.tls.AndroidCertificateAssetSource
import net.mixalich7b.exchangesync.infrastructure.tls.CertificateAssetLoader
import net.mixalich7b.exchangesync.infrastructure.tls.KeyChainClientCredentialResolver

public class AndroidActiveSyncRemoteCalendar(context: Context) : RemoteCalendarPort {
    private val delegate: RemoteCalendarPort

    init {
        val applicationContext = context.applicationContext
        val credentials = KeyChainClientCredentialResolver(applicationContext)
        val transportFactory =
            OkHttpSecureHttpTransportFactory(
                CertificateAssetLoader(AndroidCertificateAssetSource(applicationContext.assets)),
            )
        delegate =
            ActiveSyncRemoteCalendar(
                capabilities = ActiveSyncCapabilityClient(credentials, transportFactory),
                commands = ActiveSyncCommandClient(credentials, transportFactory),
            )
    }

    override suspend fun fetchPage(request: SyncPageRequest): RemotePageOutcome = delegate.fetchPage(request)

    override suspend fun fetchPage(
        request: SyncPageRequest,
        reportPhase: suspend (SyncPhase) -> Unit,
    ): RemotePageOutcome = delegate.fetchPage(request, reportPhase)
}
