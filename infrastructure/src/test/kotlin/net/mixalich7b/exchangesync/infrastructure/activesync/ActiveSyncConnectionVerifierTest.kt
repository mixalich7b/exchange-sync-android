package net.mixalich7b.exchangesync.infrastructure.activesync

import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.mixalich7b.exchangesync.core.connection.ConnectionCheckResult
import net.mixalich7b.exchangesync.core.connection.ConnectionFailure
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredential
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredentialResolution
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredentialResolver
import net.mixalich7b.exchangesync.infrastructure.tls.StubPrivateKey
import net.mixalich7b.exchangesync.infrastructure.tls.StubX509Certificate
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class ActiveSyncConnectionVerifierTest {
    @Test
    fun `unavailable alias fails before creating an HTTP transport`() =
        runTest {
            val factory = RecordingTransportFactory(emptyList())
            val verifier =
                ActiveSyncConnectionVerifier(
                    credentialResolver = ClientCredentialResolver { ClientCredentialResolution.Unavailable },
                    transportFactory = factory,
                )

            val result = verifier.verify(profile())

            assertEquals(
                ConnectionCheckResult.Failure(ConnectionFailure.CLIENT_CERTIFICATE_UNAVAILABLE),
                result,
            )
            assertEquals(0, factory.createCalls)
        }

    @Test
    fun `terminal response succeeds only when selected certificate and capabilities are present`() =
        runTest {
            val credential = credential()
            val factory =
                RecordingTransportFactory(
                    listOf(
                        ProbeResponse(
                            statusCode = 200,
                            headers =
                                mapOf(
                                    "MS-ASProtocolVersions" to "14.1,16.1",
                                    "MS-ASProtocolCommands" to "FolderSync,Sync",
                                ),
                            localCertificates = listOf(credential.leafCertificate),
                        ),
                    ),
                )
            val verifier = verifier(credential, factory)

            val result = verifier.verify(profile())

            assertEquals(ConnectionCheckResult.Success, result)
            assertEquals(1, factory.createCalls)
            assertEquals(listOf("OPTIONS"), factory.transport.requests.map { request -> request.method })
        }

    @Test
    fun `HTTPS redirect repeats OPTIONS and evaluates terminal response`() =
        runTest {
            val credential = credential()
            val factory =
                RecordingTransportFactory(
                    listOf(
                        ProbeResponse(
                            statusCode = 302,
                            headers = mapOf("Location" to "https://mail.example.test/EAS"),
                        ),
                        ProbeResponse(
                            statusCode = 200,
                            headers =
                                mapOf(
                                    "MS-ASProtocolVersions" to "16.1",
                                    "MS-ASProtocolCommands" to "Sync,FolderSync",
                                ),
                            localCertificates = listOf(credential.leafCertificate),
                        ),
                    ),
                )

            val result = verifier(credential, factory).verify(profile())

            assertEquals(ConnectionCheckResult.Success, result)
            assertEquals(listOf("OPTIONS", "OPTIONS"), factory.transport.requests.map { request -> request.method })
            assertEquals(
                listOf("exchange.example.test", "mail.example.test"),
                factory.transport.requests.map { request -> request.url.host },
            )
        }

    @Test
    fun `terminal handshake without selected leaf is an mTLS failure`() =
        runTest {
            val credential = credential()
            val factory =
                RecordingTransportFactory(
                    listOf(
                        ProbeResponse(
                            statusCode = 200,
                            headers =
                                mapOf(
                                    "MS-ASProtocolVersions" to "16.1",
                                    "MS-ASProtocolCommands" to "FolderSync,Sync",
                                ),
                            localCertificates = listOf(StubX509Certificate(byteArrayOf(99))),
                        ),
                    ),
                )

            val result = verifier(credential, factory).verify(profile())

            assertEquals(
                ConnectionCheckResult.Failure(ConnectionFailure.CLIENT_CERTIFICATE_REJECTED),
                result,
            )
        }

    @Test
    fun `transport construction leaves the caller dispatcher`() {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ui-caller") }
            .asCoroutineDispatcher()
            .use { callerDispatcher ->
                Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "tls-transport") }
                    .asCoroutineDispatcher()
                    .use { transportDispatcher ->
                        val credential = credential()
                        lateinit var callerThread: Thread
                        lateinit var factoryThread: Thread
                        val verifier =
                            ActiveSyncConnectionVerifier(
                                credentialResolver =
                                    ClientCredentialResolver {
                                        ClientCredentialResolution.Available(credential)
                                    },
                                transportFactory =
                                    ProbeTransportFactory {
                                        factoryThread = Thread.currentThread()
                                        ProbeTransport {
                                            ProbeResponse(
                                                statusCode = 200,
                                                headers =
                                                    mapOf(
                                                        "MS-ASProtocolVersions" to "16.1",
                                                        "MS-ASProtocolCommands" to "FolderSync,Sync",
                                                    ),
                                                localCertificates = listOf(credential.leafCertificate),
                                            )
                                        }
                                    },
                                transportDispatcher = transportDispatcher,
                            )

                        val result =
                            runBlocking(callerDispatcher) {
                                callerThread = Thread.currentThread()
                                verifier.verify(profile())
                            }

                        assertEquals(ConnectionCheckResult.Success, result)
                        assertNotSame(callerThread, factoryThread)
                    }
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `timeout bounds the complete redirect chain`() =
        runTest {
            val credential = credential()
            var requestCount = 0
            val verifier =
                ActiveSyncConnectionVerifier(
                    credentialResolver =
                        ClientCredentialResolver {
                            ClientCredentialResolution.Available(credential)
                        },
                    transportFactory =
                        ProbeTransportFactory { _ ->
                            ProbeTransport {
                                requestCount += 1
                                if (requestCount == 1) {
                                    ProbeResponse(
                                        statusCode = 302,
                                        headers = mapOf("Location" to "/redirected"),
                                    )
                                } else {
                                    delay(Long.MAX_VALUE)
                                    error("The whole-chain timeout did not cancel the request")
                                }
                            }
                        },
                    totalTimeoutMillis = 1_000,
                    transportDispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            val result = verifier.verify(profile())

            assertEquals(ConnectionCheckResult.Failure(ConnectionFailure.TIMEOUT), result)
            assertEquals(1_000, testScheduler.currentTime)
            assertEquals(2, requestCount)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.verifier(
        credential: ClientCredential,
        factory: RecordingTransportFactory,
    ): ActiveSyncConnectionVerifier =
        ActiveSyncConnectionVerifier(
            credentialResolver = ClientCredentialResolver { ClientCredentialResolution.Available(credential) },
            transportFactory = factory,
            transportDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

    private class RecordingTransportFactory(
        responses: List<ProbeResponse>,
    ) : ProbeTransportFactory {
        val transport = RecordingTransport(responses)
        var createCalls: Int = 0

        override fun create(credential: ClientCredential): ProbeTransport {
            createCalls += 1
            return transport
        }
    }

    private class RecordingTransport(
        responses: List<ProbeResponse>,
    ) : ProbeTransport {
        private val remaining = ArrayDeque(responses)
        val requests = mutableListOf<Request>()

        override suspend fun execute(request: Request): ProbeResponse {
            requests += request
            return remaining.removeFirst()
        }
    }

    private fun credential(): ClientCredential {
        val chain: Array<X509Certificate> = arrayOf(StubX509Certificate(byteArrayOf(42)))
        return ClientCredential("work-certificate", StubPrivateKey(), chain)
    }

    private fun profile(): ConnectionProfile =
        ConnectionProfile(
            email = "calendar@example.test",
            account = "DOMAIN\\calendar",
            serverHost = "exchange.example.test",
            clientCertificateAlias = "work-certificate",
        )
}
