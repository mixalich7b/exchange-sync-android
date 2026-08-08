package net.mixalich7b.exchangesync.infrastructure.tls

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeyChainClientCredentialResolverTest {
    @Test
    fun `missing key material asks for certificate reselection`() =
        runTest {
            val resolver =
                KeyChainClientCredentialResolver(
                    access = KeyChainMaterialAccess { KeyChainMaterial(privateKey = null, certificateChain = null) },
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            assertSame(ClientCredentialResolution.Unavailable, resolver.resolve("removed-alias"))
        }

    @Test
    fun `keychain access failure asks for certificate reselection`() =
        runTest {
            val resolver =
                KeyChainClientCredentialResolver(
                    access = KeyChainMaterialAccess { error("authorization revoked") },
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            assertSame(ClientCredentialResolution.Unavailable, resolver.resolve("revoked-alias"))
        }
}
