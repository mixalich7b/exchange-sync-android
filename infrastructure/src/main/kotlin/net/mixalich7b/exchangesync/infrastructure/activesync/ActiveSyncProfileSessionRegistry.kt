package net.mixalich7b.exchangesync.infrastructure.activesync

import java.util.LinkedHashMap
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.sync.ActiveSyncVersion
import okhttp3.HttpUrl

internal data class ActiveSyncLiveCapability(
    val terminalEndpoint: HttpUrl,
    val version: ActiveSyncVersion,
    val supportedVersions: Set<ActiveSyncVersion> = setOf(version),
) {
    init {
        require(version in supportedVersions)
    }
}

internal class ActiveSyncProfileSession {
    val cookieJar = InMemoryCookieJar()

    @Volatile
    private var capability: ActiveSyncLiveCapability? = null

    fun liveCapability(): ActiveSyncLiveCapability? = capability

    fun recordCapability(value: ActiveSyncLiveCapability) {
        capability = value
    }
}

internal class ActiveSyncProfileSessionRegistry(
    private val maximumProfiles: Int = 4,
) {
    private val lock = Any()
    private val sessions = LinkedHashMap<ConnectionProfile, ActiveSyncProfileSession>(maximumProfiles, 0.75f, true)

    init {
        require(maximumProfiles > 0)
    }

    fun acquire(profile: ConnectionProfile): ActiveSyncProfileSession =
        synchronized(lock) {
            sessions[profile]
                ?: ActiveSyncProfileSession().also { session ->
                    sessions[profile] = session
                    if (sessions.size > maximumProfiles) {
                        val eldest = sessions.entries.iterator()
                        eldest.next()
                        eldest.remove()
                    }
                }
        }
}
