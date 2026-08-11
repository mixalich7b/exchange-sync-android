package net.mixalich7b.exchangesync.infrastructure.activesync

import java.security.cert.X509Certificate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.sync.ActiveSyncVersion
import net.mixalich7b.exchangesync.core.sync.SyncFailureKind
import net.mixalich7b.exchangesync.core.sync.SyncProblem
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnostics
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticStage
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredential
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredentialResolution
import net.mixalich7b.exchangesync.infrastructure.tls.ClientCredentialResolver
import net.mixalich7b.exchangesync.infrastructure.tls.StubPrivateKey
import net.mixalich7b.exchangesync.infrastructure.tls.StubX509Certificate
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActiveSyncCommandClientTest {
    @Test
    fun `command request percent-encodes account and carries stable device and protocol parameters`() {
        val request =
            ActiveSyncCommandRequestFactory.create(
                endpoint = "https://exchange.example.test/Microsoft-Server-ActiveSync".toHttpUrl(),
                profile = profile(account = "WORK\\calendar+sync"),
                command = ActiveSyncCommand.SYNC,
                deviceId = "A1B2C3D4",
                deviceType = "ExchangeSync",
                version = ActiveSyncVersion.V16_1,
                body = byteArrayOf(0x03, 0x01, 0x6A, 0x00),
            )

        assertEquals("POST", request.method)
        assertEquals("Sync", request.url.queryParameter("Cmd"))
        assertEquals("WORK\\calendar+sync", request.url.queryParameter("User"))
        assertEquals("A1B2C3D4", request.url.queryParameter("DeviceId"))
        assertEquals("ExchangeSync", request.url.queryParameter("DeviceType"))
        assertTrue(request.url.encodedQuery.orEmpty().contains("User=WORK%5Ccalendar%2Bsync"))
        assertEquals("16.1", request.header("MS-ASProtocolVersion"))
        assertEquals(ACTIVE_SYNC_CONTENT_TYPE, request.header("Content-Type"))
        assertArrayEquals(byteArrayOf(0x03, 0x01, 0x6A, 0x00), request.bodyBytes())
    }

    @Test
    fun `version negotiation selects the highest mutually supported response-state version`() {
        assertEquals(
            ActiveSyncVersion.V16_1,
            ActiveSyncVersionNegotiator.select("12.1, 14.0, 16.1, 14.1"),
        )
        assertEquals(ActiveSyncVersion.V14_0, ActiveSyncVersionNegotiator.select("14.0"))
        assertNull(ActiveSyncVersionNegotiator.select("2.5,12.0,12.1"))
        assertNull(ActiveSyncVersionNegotiator.select(null))
    }

    @Test
    fun `12_1-only OPTIONS endpoint is rejected by the existing connection capability policy`() {
        assertEquals(
            net.mixalich7b.exchangesync.core.connection.ConnectionFailure.PROTOCOL_INCOMPATIBLE,
            ActiveSyncResponseEvaluator.evaluate(
                statusCode = 200,
                protocolVersions = "12.1",
                protocolCommands = "FolderSync,Sync",
            ),
        )
    }

    @Test
    fun `command follows permitted HTTPS redirect with POST and the same mTLS identity`() =
        runTest {
            val credential = credential()
            val requestBody = byteArrayOf(1, 2, 3)
            val transport =
                RecordingCommandTransport(
                    listOf(
                        SecureHttpResponse(
                            statusCode = 302,
                            headers = mapOf("Location" to "https://mail.example.test/EAS"),
                        ),
                        SecureHttpResponse(
                            statusCode = 200,
                            headers = emptyMap(),
                            body = byteArrayOf(0x03, 0x01),
                            localCertificates = listOf(credential.leafCertificate),
                        ),
                    ),
                )
            val client = client(credential, transport)

            val result =
                client.execute(
                    profile = profile(),
                    endpoint = "https://exchange.example.test/Microsoft-Server-ActiveSync".toHttpUrl(),
                    command = ActiveSyncCommand.FOLDER_SYNC,
                    deviceId = "DEVICE123",
                    version = ActiveSyncVersion.V14_1,
                    body = requestBody,
                )

            val success = result as ActiveSyncCommandOutcome.Success
            assertEquals("mail.example.test", success.terminalEndpoint.host)
            assertArrayEquals(byteArrayOf(0x03, 0x01), success.body)
            assertEquals(listOf("POST", "POST"), transport.requests.map { request -> request.method })
            assertEquals(listOf("exchange.example.test", "mail.example.test"), transport.requests.map { it.url.host })
            transport.requests.forEach { request ->
                assertEquals("FolderSync", request.url.queryParameter("Cmd"))
                assertEquals("14.1", request.header("MS-ASProtocolVersion"))
                assertArrayEquals(requestBody, request.bodyBytes())
            }
        }

    @Test
    fun `command reports every followed redirect without changing method or body`() =
        runTest {
            val credential = credential()
            val requestBody = byteArrayOf(9, 8, 7)
            val events = mutableListOf<net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnosticEvent>()
            val diagnostics = DeviceDiagnostics { event -> events += event }
            val transport =
                RecordingCommandTransport(
                    listOf(
                        SecureHttpResponse(301, mapOf("Location" to "/first")),
                        SecureHttpResponse(307, mapOf("Location" to "https://mail.example.test/second")),
                        SecureHttpResponse(200, emptyMap(), body = byteArrayOf(0x03, 0x01)),
                    ),
                )

            client(credential, transport, diagnostics).execute(
                profile(),
                endpoint(),
                ActiveSyncCommand.SYNC,
                "DEVICE123",
                ActiveSyncVersion.V16_1,
                requestBody,
            )

            assertEquals(listOf("POST", "POST", "POST"), transport.requests.map { request -> request.method })
            transport.requests.forEach { request -> assertArrayEquals(requestBody, request.bodyBytes()) }
            val redirects = events.filter { event -> event.stage == DiagnosticStage.REDIRECT }
            assertEquals(listOf(301, 307), redirects.map { event -> event.status })
            assertEquals(listOf("exchange.example.test", "mail.example.test"), redirects.map { event -> event.host })
            assertEquals(listOf("follow", "follow"), redirects.map { event -> event.outcome })
        }

    @Test
    fun `successful command response is accepted when local certificate metadata is unavailable`() =
        runTest {
            val credential = credential()
            val responseBody = byteArrayOf(0x03, 0x01)
            val client =
                client(
                    credential,
                    RecordingCommandTransport(
                        listOf(
                            SecureHttpResponse(
                                statusCode = 200,
                                headers = emptyMap(),
                                body = responseBody,
                            ),
                        ),
                    ),
                )

            val result =
                client.execute(
                    profile(),
                    endpoint(),
                    ActiveSyncCommand.SYNC,
                    "DEVICE123",
                    ActiveSyncVersion.V16_1,
                    byteArrayOf(1, 2, 3),
                )

            val success = result as ActiveSyncCommandOutcome.Success
            assertEquals(endpoint(), success.terminalEndpoint)
            assertArrayEquals(responseBody, success.body)
        }

    @Test
    fun `command rejects unsafe redirect and maps terminal access server and certificate failures`() =
        runTest {
            val credential = credential()
            val unsafe = client(credential, RecordingCommandTransport(listOf(SecureHttpResponse(302, mapOf("Location" to "http://mail.example.test/EAS")))))
            assertEquals(
                ActiveSyncCommandOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.REDIRECT),
                unsafe.execute(profile(), endpoint(), ActiveSyncCommand.SYNC, "DEVICE123", ActiveSyncVersion.V16_0, byteArrayOf()),
            )

            val access = client(credential, RecordingCommandTransport(listOf(SecureHttpResponse(403, emptyMap(), localCertificates = listOf(credential.leafCertificate)))))
            assertEquals(
                ActiveSyncCommandOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.ACCESS),
                access.execute(profile(), endpoint(), ActiveSyncCommand.SYNC, "DEVICE123", ActiveSyncVersion.V16_0, byteArrayOf()),
            )

            val server = client(credential, RecordingCommandTransport(listOf(SecureHttpResponse(503, emptyMap(), localCertificates = listOf(credential.leafCertificate)))))
            assertEquals(
                ActiveSyncCommandOutcome.Failure(SyncFailureKind.TRANSIENT, null),
                server.execute(profile(), endpoint(), ActiveSyncCommand.SYNC, "DEVICE123", ActiveSyncVersion.V16_0, byteArrayOf()),
            )

            val wrongCertificate = client(credential, RecordingCommandTransport(listOf(SecureHttpResponse(200, emptyMap(), localCertificates = listOf(StubX509Certificate(byteArrayOf(99)))))))
            assertEquals(
                ActiveSyncCommandOutcome.Failure(SyncFailureKind.CRITICAL, SyncProblem.CLIENT_CERTIFICATE),
                wrongCertificate.execute(profile(), endpoint(), ActiveSyncCommand.SYNC, "DEVICE123", ActiveSyncVersion.V16_0, byteArrayOf()),
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `oversized command response requests a smaller synchronization window`() =
        runTest {
            val credential = credential()
            val client =
                ActiveSyncCommandClient(
                    credentialResolver = ClientCredentialResolver { ClientCredentialResolution.Available(credential) },
                    transportFactory = SecureHttpTransportFactory { _, _, _ ->
                        SecureHttpTransport { throw ActiveSyncResponseTooLargeException() }
                    },
                    transportDispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            val outcome =
                client.execute(
                    profile(),
                    endpoint(),
                    ActiveSyncCommand.SYNC,
                    "DEVICE123",
                    ActiveSyncVersion.V16_1,
                    byteArrayOf(),
                ) as ActiveSyncCommandOutcome.Failure

            assertEquals("WINDOW_TOO_LARGE", outcome.kind.name)
            assertNull(outcome.problem)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `capability client follows HTTPS redirect and returns terminal endpoint with highest version`() =
        runTest {
            val credential = credential()
            val transport =
                RecordingCommandTransport(
                    listOf(
                        SecureHttpResponse(302, mapOf("Location" to "https://mail.example.test/EAS")),
                        SecureHttpResponse(
                            statusCode = 200,
                            headers =
                                mapOf(
                                    "MS-ASProtocolVersions" to "12.1,14.0,16.1",
                                    "MS-ASProtocolCommands" to "FolderSync,Sync",
                                ),
                            localCertificates = listOf(credential.leafCertificate),
                        ),
                    ),
                )
            val client =
                ActiveSyncCapabilityClient(
                    credentialResolver = ClientCredentialResolver { ClientCredentialResolution.Available(credential) },
                    transportFactory = SecureHttpTransportFactory { _, _, _ -> transport },
                    transportDispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            assertEquals(
                ActiveSyncCapabilityOutcome.Success(
                    "https://mail.example.test/EAS".toHttpUrl(),
                    ActiveSyncVersion.V16_1,
                    setOf(ActiveSyncVersion.V14_0, ActiveSyncVersion.V16_1),
                ),
                client.discover(profile()),
            )
            assertEquals(listOf("OPTIONS", "OPTIONS"), transport.requests.map { request -> request.method })
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.client(
        credential: ClientCredential,
        transport: RecordingCommandTransport,
        diagnostics: DeviceDiagnostics = DeviceDiagnostics(),
    ): ActiveSyncCommandClient =
        ActiveSyncCommandClient(
            credentialResolver = ClientCredentialResolver { ClientCredentialResolution.Available(credential) },
            transportFactory = SecureHttpTransportFactory { _, _, _ -> transport },
            transportDispatcher = UnconfinedTestDispatcher(testScheduler),
            diagnostics = diagnostics,
        )

    private class RecordingCommandTransport(responses: List<SecureHttpResponse>) : SecureHttpTransport {
        private val responses = ArrayDeque(responses)
        val requests = mutableListOf<Request>()

        override suspend fun execute(request: Request): SecureHttpResponse {
            requests += request
            return responses.removeFirst()
        }
    }

    private fun endpoint() = "https://exchange.example.test/Microsoft-Server-ActiveSync".toHttpUrl()

    private fun profile(account: String = "WORK\\calendar") =
        ConnectionProfile(
            email = "calendar@example.test",
            account = account,
            serverHost = "exchange.example.test",
            clientCertificateAlias = "work-certificate",
        )

    private fun credential(): ClientCredential {
        val chain: Array<X509Certificate> = arrayOf(StubX509Certificate(byteArrayOf(42)))
        return ClientCredential("work-certificate", StubPrivateKey(), chain)
    }
}

private fun Request.bodyBytes(): ByteArray {
    val buffer = okio.Buffer()
    requireNotNull(body).writeTo(buffer)
    return buffer.readByteArray()
}
