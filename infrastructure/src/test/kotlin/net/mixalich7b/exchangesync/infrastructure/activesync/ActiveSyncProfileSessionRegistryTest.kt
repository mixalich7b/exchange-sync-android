package net.mixalich7b.exchangesync.infrastructure.activesync

import java.util.concurrent.TimeUnit
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.sync.ActiveSyncVersion
import net.mixalich7b.exchangesync.core.sync.SyncFence
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActiveSyncProfileSessionRegistryTest {
    @Test
    fun `exact profile reuses cookies across newly acquired sessions`() {
        val registry = ActiveSyncProfileSessionRegistry(maximumProfiles = 4)
        val profile = profile()
        val url = endpoint()
        val first = registry.acquire(profile)
        first.cookieJar.saveFromResponse(url, listOf(cookie("session", "retained")))

        val newlyAcquired = registry.acquire(profile.copy())

        assertSame(first, newlyAcquired)
        assertEquals(
            listOf("retained"),
            newlyAcquired.cookieJar.loadForRequest(url).map { cookie: Cookie -> cookie.value },
        )
    }

    @Test
    fun `different profile identity fields receive no prior cookies`() {
        val registry = ActiveSyncProfileSessionRegistry(maximumProfiles = 8)
        val original = profile()
        val originalSession = registry.acquire(original)
        originalSession.cookieJar.saveFromResponse(endpoint(), listOf(cookie("session", "private")))

        listOf(
            original.copy(email = "other@example.test"),
            original.copy(account = "WORK\\other"),
            original.copy(serverHost = "mail.example.test"),
            original.copy(clientCertificateAlias = "other-certificate"),
        ).forEach { different ->
            val session = registry.acquire(different)
            assertNotSame(originalSession, session)
            assertTrue(session.cookieJar.loadForRequest(endpoint(different.serverHost)).isEmpty())
        }
    }

    @Test
    fun `least recently used profile session is evicted at the bound`() {
        val registry = ActiveSyncProfileSessionRegistry(maximumProfiles = 2)
        val firstProfile = profile(email = "first@example.test")
        val secondProfile = profile(email = "second@example.test")
        val secondSession = registry.acquire(secondProfile)
        secondSession.cookieJar.saveFromResponse(endpoint(), listOf(cookie("second", "evicted")))
        val firstSession = registry.acquire(firstProfile)

        assertSame(firstSession, registry.acquire(firstProfile))
        registry.acquire(profile(email = "third@example.test"))

        val reacquiredSecond = registry.acquire(secondProfile)
        assertNotSame(secondSession, reacquiredSecond)
        assertTrue(reacquiredSecond.cookieJar.loadForRequest(endpoint()).isEmpty())
    }

    @Test
    fun `live capability state remains in its exact process session`() {
        val registry = ActiveSyncProfileSessionRegistry(maximumProfiles = 2)
        val session = registry.acquire(profile())
        val capability =
            ActiveSyncLiveCapability(
                terminalEndpoint = endpoint(),
                version = ActiveSyncVersion.V16_1,
            )

        assertNull(session.liveCapability())
        session.recordCapability(capability)

        assertEquals(capability, registry.acquire(profile()).liveCapability())
        assertNull(registry.acquire(profile(email = "other@example.test")).liveCapability())
    }

    @Test
    fun `prepared folder state requires the exact fence version profile and process session`() {
        val registry = ActiveSyncProfileSessionRegistry(maximumProfiles = 2)
        val session = registry.acquire(profile())
        val prepared =
            ActiveSyncPreparedFolder(
                fence = SyncFence(3, 9),
                version = ActiveSyncVersion.V16_1,
                terminalEndpoint = endpoint(),
                folderSyncKey = "folder-key",
                primaryCalendarId = "primary-calendar",
            )

        session.recordPreparedFolder(prepared)

        assertEquals(prepared, session.preparedFolder(SyncFence(3, 9), ActiveSyncVersion.V16_1))
        assertNull(session.preparedFolder(SyncFence(3, 10), ActiveSyncVersion.V16_1))
        assertNull(session.preparedFolder(SyncFence(4, 9), ActiveSyncVersion.V16_1))
        assertNull(session.preparedFolder(SyncFence(3, 9), ActiveSyncVersion.V14_1))
        assertNull(registry.acquire(profile(email = "other@example.test")).preparedFolder(SyncFence(3, 9), ActiveSyncVersion.V16_1))
        assertNull(
            ActiveSyncProfileSessionRegistry()
                .acquire(profile())
                .preparedFolder(SyncFence(3, 9), ActiveSyncVersion.V16_1),
        )

        session.clearPreparedFolder()
        assertNull(session.preparedFolder(SyncFence(3, 9), ActiveSyncVersion.V16_1))
    }

    private fun profile(
        email: String = "calendar@example.test",
        account: String = "WORK\\calendar",
        serverHost: String = "exchange.example.test",
        alias: String = "work-certificate",
    ): ConnectionProfile =
        ConnectionProfile(
            email = email,
            account = account,
            serverHost = serverHost,
            clientCertificateAlias = alias,
        )

    private fun endpoint(host: String = "exchange.example.test") =
        "https://$host/Microsoft-Server-ActiveSync".toHttpUrl()

    private fun cookie(
        name: String,
        value: String,
    ): Cookie =
        Cookie.Builder()
            .name(name)
            .value(value)
            .hostOnlyDomain("exchange.example.test")
            .path("/Microsoft-Server-ActiveSync")
            .expiresAt(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1))
            .build()
}
