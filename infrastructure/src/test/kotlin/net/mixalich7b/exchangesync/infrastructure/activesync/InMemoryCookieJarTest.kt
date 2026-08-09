package net.mixalich7b.exchangesync.infrastructure.activesync

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InMemoryCookieJarTest {
    @Test
    fun `accepted cookie is returned to a matching request`() {
        val jar = InMemoryCookieJar()
        val url = "https://exchange.example.test/Microsoft-Server-ActiveSync".toHttpUrl()
        val cookie = cookie(name = "session", value = "first")

        jar.saveFromResponse(url, listOf(cookie))

        assertEquals(listOf(cookie), jar.loadForRequest(url))
    }

    @Test
    fun `same name domain and path replaces the previous cookie`() {
        val jar = InMemoryCookieJar()
        val url = "https://exchange.example.test/Microsoft-Server-ActiveSync".toHttpUrl()

        jar.saveFromResponse(url, listOf(cookie(name = "session", value = "first")))
        jar.saveFromResponse(url, listOf(cookie(name = "session", value = "replacement")))

        assertEquals(listOf("replacement"), jar.loadForRequest(url).map { cookie: Cookie -> cookie.value })
    }

    @Test
    fun `server expiry deletes an existing cookie`() {
        val jar = InMemoryCookieJar()
        val url = "https://exchange.example.test/Microsoft-Server-ActiveSync".toHttpUrl()

        jar.saveFromResponse(url, listOf(cookie(name = "session", value = "first")))
        jar.saveFromResponse(
            url,
            listOf(cookie(name = "session", value = "deleted", expiresAt = 0L)),
        )

        assertTrue(jar.loadForRequest(url).isEmpty())
    }

    @Test
    fun `expired cookies are pruned when cookies are saved and loaded`() {
        val jar = InMemoryCookieJar()
        val url = "https://exchange.example.test/Microsoft-Server-ActiveSync".toHttpUrl()

        jar.saveFromResponse(
            url,
            listOf(
                cookie(name = "expired", value = "old", expiresAt = 1L),
                cookie(name = "current", value = "usable"),
            ),
        )

        assertEquals(listOf("current"), jar.loadForRequest(url).map { cookie: Cookie -> cookie.name })
    }

    @Test
    fun `secure host domain and path attributes control request matching`() {
        val jar = InMemoryCookieJar()
        val origin = "https://exchange.example.test/Microsoft-Server-ActiveSync".toHttpUrl()
        jar.saveFromResponse(
            origin,
            listOf(
                cookie(name = "host", value = "exact"),
                cookie(name = "domain", value = "shared", domain = "example.test"),
                cookie(name = "path", value = "scoped", path = "/Microsoft-Server-ActiveSync/child"),
                cookie(name = "secure", value = "https-only", secure = true),
            ),
        )

        assertEquals(
            setOf("host", "domain", "secure"),
            jar.loadForRequest(origin).map { cookie: Cookie -> cookie.name }.toSet(),
        )
        assertEquals(
            setOf("domain"),
            jar.loadForRequest("http://mail.example.test/Microsoft-Server-ActiveSync".toHttpUrl())
                .map { cookie: Cookie -> cookie.name }
                .toSet(),
        )
        assertEquals(
            setOf("host", "domain", "path", "secure"),
            jar.loadForRequest("https://exchange.example.test/Microsoft-Server-ActiveSync/child/page".toHttpUrl())
                .map { cookie: Cookie -> cookie.name }
                .toSet(),
        )
    }

    @Test
    fun `host-only cookie is isolated from redirect and unrelated hosts`() {
        val jar = InMemoryCookieJar()
        val origin = "https://exchange.example.test/Microsoft-Server-ActiveSync".toHttpUrl()
        jar.saveFromResponse(origin, listOf(cookie(name = "session", value = "origin")))

        assertTrue(
            jar.loadForRequest("https://mail.example.test/Microsoft-Server-ActiveSync".toHttpUrl()).isEmpty(),
        )
        assertTrue(
            jar.loadForRequest("https://unrelated.invalid/Microsoft-Server-ActiveSync".toHttpUrl()).isEmpty(),
        )
    }

    @Test
    fun `concurrent saves and loads preserve every distinct cookie`() {
        val jar = InMemoryCookieJar()
        val url = "https://exchange.example.test/Microsoft-Server-ActiveSync".toHttpUrl()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures =
                (0 until 100).map { index ->
                    executor.submit {
                        jar.saveFromResponse(url, listOf(cookie(name = "session-$index", value = index.toString())))
                        jar.loadForRequest(url)
                    }
                }

            futures.forEach { future -> future.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(100, jar.loadForRequest(url).size)
    }

    private fun cookie(
        name: String,
        value: String,
        domain: String = "exchange.example.test",
        path: String = "/Microsoft-Server-ActiveSync",
        secure: Boolean = false,
        expiresAt: Long = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1),
    ): Cookie {
        val builder =
            Cookie.Builder()
                .name(name)
                .value(value)
                .path(path)
                .expiresAt(expiresAt)
        if (domain == "exchange.example.test") {
            builder.hostOnlyDomain(domain)
        } else {
            builder.domain(domain)
        }
        if (secure) builder.secure()
        return builder.build()
    }
}
