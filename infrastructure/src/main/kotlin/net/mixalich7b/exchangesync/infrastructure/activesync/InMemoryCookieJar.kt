package net.mixalich7b.exchangesync.infrastructure.activesync

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

internal class InMemoryCookieJar : CookieJar {
    private val lock = Any()
    private val cookies = mutableListOf<Cookie>()

    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            this.cookies.removeAll { cookie -> cookie.expiresAt < now }
            cookies.forEach { cookie ->
                this.cookies.removeAll { stored -> stored.hasSameIdentityAs(cookie) }
                if (cookie.expiresAt >= now) this.cookies += cookie
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            cookies.removeAll { cookie -> cookie.expiresAt < now }
            cookies.filter { cookie -> cookie.matches(url) }
        }
    }

    private fun Cookie.hasSameIdentityAs(other: Cookie): Boolean =
        name == other.name && domain == other.domain && path == other.path
}
