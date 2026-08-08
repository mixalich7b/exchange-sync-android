package net.mixalich7b.exchangesync.infrastructure.tls

import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClientCredentialTest {
    @Test
    fun `missing private key is unavailable`() {
        val result = ClientCredentialFactory.resolve("work", null, arrayOf<X509Certificate>(StubX509Certificate()))

        assertSame(ClientCredentialResolution.Unavailable, result)
    }

    @Test
    fun `missing or empty certificate chain is unavailable`() {
        val key = StubPrivateKey()

        assertSame(ClientCredentialResolution.Unavailable, ClientCredentialFactory.resolve("work", key, null))
        assertSame(
            ClientCredentialResolution.Unavailable,
            ClientCredentialFactory.resolve("work", key, emptyArray()),
        )
    }

    @Test
    fun `fixed key manager exposes only selected alias key and chain`() {
        val key = StubPrivateKey()
        val chain: Array<X509Certificate> = arrayOf(StubX509Certificate())
        val available = ClientCredentialFactory.resolve("work", key, chain)
        assertTrue(available is ClientCredentialResolution.Available)
        val manager = FixedAliasKeyManager((available as ClientCredentialResolution.Available).credential)

        assertEquals("work", manager.chooseClientAlias(arrayOf("RSA"), null, null as Socket?))
        assertEquals("work", manager.chooseEngineClientAlias(arrayOf("RSA"), null, null as SSLEngine?))
        assertArrayEquals(arrayOf("work"), manager.getClientAliases("RSA", null))
        assertSame(key, manager.getPrivateKey("work"))
        assertArrayEquals(chain, manager.getCertificateChain("work"))
        assertNull(manager.getPrivateKey("other"))
        assertNull(manager.getCertificateChain("other"))
        assertNull(manager.chooseClientAlias(arrayOf("EC"), null, null as Socket?))
        assertNull(manager.getServerAliases("RSA", null))
        assertNull(manager.chooseServerAlias("RSA", null, null as Socket?))
    }
}
