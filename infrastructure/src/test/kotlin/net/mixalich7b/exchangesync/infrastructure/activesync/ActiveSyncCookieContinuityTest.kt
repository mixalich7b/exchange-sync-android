package net.mixalich7b.exchangesync.infrastructure.activesync

import java.security.cert.X509Certificate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.mixalich7b.exchangesync.core.connection.ConnectionCheckResult
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.sync.ActiveSyncVersion
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredential
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredentialResolution
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredentialResolver
import net.mixalich7b.exchangesync.infrastructure.tls.StubPrivateKey
import net.mixalich7b.exchangesync.infrastructure.tls.StubX509Certificate
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ActiveSyncCookieContinuityTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `OPTIONS cookie reaches an eligible redirect and not a different host`() =
        runTest {
            val credential = credential()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val eligibleScripts =
                ScriptedCookieTransportFactory(
                    listOf(
                        { request ->
                            assertNull(request.header("Cookie"))
                            response(
                                status = 302,
                                headers =
                                    mapOf(
                                        "Location" to "/EAS",
                                        "Set-Cookie" to "redirect=eligible; Path=/EAS; Secure",
                                    ),
                            )
                        },
                        { request ->
                            assertEquals("redirect=eligible", request.header("Cookie"))
                            capabilityResponse(credential)
                        },
                    ),
                )
            val eligibleVerifier =
                ActiveSyncConnectionVerifier(
                    credentialResolver = resolver(credential),
                    transportFactory = profileFactory(eligibleScripts),
                    transportDispatcher = dispatcher,
                )

            assertInstanceOf(ConnectionCheckResult.Success::class.java, eligibleVerifier.verify(profile()))

            val isolatedScripts =
                ScriptedCookieTransportFactory(
                    listOf(
                        { request ->
                            assertNull(request.header("Cookie"))
                            response(
                                status = 302,
                                headers =
                                    mapOf(
                                        "Location" to "https://mail.example.test/EAS",
                                        "Set-Cookie" to "origin=private; Path=/; Secure",
                                    ),
                            )
                        },
                        { request ->
                            assertEquals("mail.example.test", request.url.host)
                            assertNull(request.header("Cookie"))
                            capabilityResponse(credential)
                        },
                    ),
                )
            val isolatedVerifier =
                ActiveSyncConnectionVerifier(
                    credentialResolver = resolver(credential),
                    transportFactory = profileFactory(isolatedScripts),
                    transportDispatcher = dispatcher,
                )

            assertInstanceOf(ConnectionCheckResult.Success::class.java, isolatedVerifier.verify(profile()))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `capability and command responses update cookies used by later commands`() =
        runTest {
            val credential = credential()
            val scripts =
                ScriptedCookieTransportFactory(
                    listOf(
                        { request ->
                            assertEquals("OPTIONS", request.method)
                            assertNull(request.header("Cookie"))
                            capabilityResponse(
                                credential,
                                setCookie = "session=from-options; Path=/Microsoft-Server-ActiveSync; Secure",
                            )
                        },
                        { request ->
                            assertEquals("FolderSync", request.url.queryParameter("Cmd"))
                            assertEquals("session=from-options", request.header("Cookie"))
                            response(
                                status = 200,
                                headers =
                                    mapOf(
                                        "Set-Cookie" to
                                            "session=from-folder; Path=/Microsoft-Server-ActiveSync; Secure",
                                    ),
                                localCertificates = listOf(credential.leafCertificate),
                            )
                        },
                        { request ->
                            assertEquals("Sync", request.url.queryParameter("Cmd"))
                            assertEquals("session=from-folder", request.header("Cookie"))
                            response(status = 200, localCertificates = listOf(credential.leafCertificate))
                        },
                    ),
                )
            val factory = profileFactory(scripts)
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val capabilities = ActiveSyncCapabilityClient(resolver(credential), factory, transportDispatcher = dispatcher)
            val commands = ActiveSyncCommandClient(resolver(credential), factory, transportDispatcher = dispatcher)

            assertInstanceOf(ActiveSyncCapabilityOutcome.Success::class.java, capabilities.discover(profile()))
            assertInstanceOf(
                ActiveSyncCommandOutcome.Success::class.java,
                commands.execute(
                    profile(),
                    endpoint(),
                    ActiveSyncCommand.FOLDER_SYNC,
                    "DEVICE123",
                    ActiveSyncVersion.V16_1,
                    byteArrayOf(),
                ),
            )
            assertInstanceOf(
                ActiveSyncCommandOutcome.Success::class.java,
                commands.execute(
                    profile(),
                    endpoint(),
                    ActiveSyncCommand.SYNC,
                    "DEVICE123",
                    ActiveSyncVersion.V16_1,
                    byteArrayOf(),
                ),
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `redirected command cookie reaches its destination and stays isolated from another host`() =
        runTest {
            val credential = credential()
            val scripts =
                ScriptedCookieTransportFactory(
                    listOf(
                        { request ->
                            assertEquals("exchange.example.test", request.url.host)
                            assertNull(request.header("Cookie"))
                            response(
                                status = 302,
                                headers =
                                    mapOf(
                                        "Location" to "/EAS",
                                        "Set-Cookie" to "redirect=destination; Path=/EAS; Secure",
                                    ),
                            )
                        },
                        { request ->
                            assertEquals("https://exchange.example.test/EAS", request.url.newBuilder().query(null).build().toString())
                            assertEquals("redirect=destination", request.header("Cookie"))
                            response(status = 200, localCertificates = listOf(credential.leafCertificate))
                        },
                        { request ->
                            assertEquals("unrelated.example.test", request.url.host)
                            assertNull(request.header("Cookie"))
                            response(status = 200, localCertificates = listOf(credential.leafCertificate))
                        },
                    ),
                )
            val commands =
                ActiveSyncCommandClient(
                    resolver(credential),
                    profileFactory(scripts),
                    transportDispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            assertInstanceOf(
                ActiveSyncCommandOutcome.Success::class.java,
                commands.execute(
                    profile(),
                    endpoint(),
                    ActiveSyncCommand.FOLDER_SYNC,
                    "DEVICE123",
                    ActiveSyncVersion.V16_1,
                    byteArrayOf(),
                ),
            )
            assertInstanceOf(
                ActiveSyncCommandOutcome.Success::class.java,
                commands.execute(
                    profile(),
                    "https://unrelated.example.test/EAS".toHttpUrl(),
                    ActiveSyncCommand.SYNC,
                    "DEVICE123",
                    ActiveSyncVersion.V16_1,
                    byteArrayOf(),
                ),
            )
        }

    private fun profileFactory(delegate: CookieJarSecureHttpTransportFactory): SecureHttpTransportFactory =
        ProfileSessionSecureHttpTransportFactory(ActiveSyncProfileSessionRegistry(), delegate)

    private class ScriptedCookieTransportFactory(
        scripts: List<(Request) -> SecureHttpResponse>,
    ) : CookieJarSecureHttpTransportFactory {
        private val scripts = ArrayDeque(scripts)

        override fun create(
            credential: ClientCredential,
            cookieJar: CookieJar,
            operation: net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperation,
        ): SecureHttpTransport =
            SecureHttpTransport { request ->
                val matching = cookieJar.loadForRequest(request.url)
                val effective =
                    if (matching.isEmpty()) {
                        request
                    } else {
                        request.newBuilder()
                            .header("Cookie", matching.joinToString("; ") { cookie -> "${cookie.name}=${cookie.value}" })
                            .build()
                    }
                val response = scripts.removeFirst()(effective)
                response.header("Set-Cookie")
                    ?.let { header -> Cookie.parse(request.url, header) }
                    ?.let { cookie -> cookieJar.saveFromResponse(request.url, listOf(cookie)) }
                response
            }
    }

    private fun resolver(credential: ClientCredential): ClientCredentialResolver =
        ClientCredentialResolver { ClientCredentialResolution.Available(credential) }

    private fun credential(): ClientCredential {
        val chain: Array<X509Certificate> = arrayOf(StubX509Certificate(byteArrayOf(42)))
        return ClientCredential("work-certificate", StubPrivateKey(), chain)
    }

    private fun capabilityResponse(
        credential: ClientCredential,
        setCookie: String? = null,
    ): SecureHttpResponse {
        val headers =
            mutableMapOf(
                "MS-ASProtocolVersions" to "14.1,16.1",
                "MS-ASProtocolCommands" to "FolderSync,Sync",
            )
        setCookie?.let { headers["Set-Cookie"] = it }
        return response(
            status = 200,
            headers = headers,
            localCertificates = listOf(credential.leafCertificate),
            peerCertificates = listOf(StubX509Certificate(byteArrayOf())),
        )
    }

    private fun response(
        status: Int,
        headers: Map<String, String> = emptyMap(),
        localCertificates: List<X509Certificate> = emptyList(),
        peerCertificates: List<X509Certificate> = emptyList(),
    ): SecureHttpResponse =
        SecureHttpResponse(
            statusCode = status,
            headers = headers,
            localCertificates = localCertificates,
            peerCertificates = peerCertificates,
        )

    private fun endpoint() = "https://exchange.example.test/Microsoft-Server-ActiveSync".toHttpUrl()

    private fun profile() =
        ConnectionProfile(
            email = "calendar@example.test",
            account = "WORK\\calendar",
            serverHost = "exchange.example.test",
            clientCertificateAlias = "work-certificate",
        )
}
