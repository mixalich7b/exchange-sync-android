package net.mixalich7b.exchangesync.infrastructure.activesync

import net.mixalich7b.exchangesync.core.connection.ConnectionCheckResult
import net.mixalich7b.exchangesync.core.connection.ConnectionFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActiveSyncProbePolicyTest {
    @Test
    fun `initial request targets fixed HTTPS port and ActiveSync path with OPTIONS`() {
        val url = ActiveSyncProbePolicy.initialUrl("exchange.example.test")
        val request = ActiveSyncProbePolicy.request(url)

        assertEquals("https", url.scheme)
        assertEquals("exchange.example.test", url.host)
        assertEquals(443, url.port)
        assertEquals("/Microsoft-Server-ActiveSync", url.encodedPath)
        assertEquals(null, url.query)
        assertEquals("OPTIONS", request.method)
        assertEquals(url, request.url)
    }

    @Test
    fun `relative and cross-host HTTPS redirects preserve OPTIONS`() {
        val initial = ActiveSyncProbePolicy.initialUrl("exchange.example.test")
        val tracker = RedirectTracker(initial)

        val relative = tracker.follow(initial, "/EAS") as RedirectDecision.Follow
        val crossHost = tracker.follow(relative.url, "https://mail.example.test/ActiveSync") as RedirectDecision.Follow

        assertEquals("https://exchange.example.test/EAS", relative.url.toString())
        assertEquals("https://mail.example.test/ActiveSync", crossHost.url.toString())
        assertEquals("OPTIONS", ActiveSyncProbePolicy.request(relative.url).method)
        assertEquals("OPTIONS", ActiveSyncProbePolicy.request(crossHost.url).method)
    }

    @Test
    fun `unsafe malformed cyclic and excessive redirects are rejected`() {
        val initial = ActiveSyncProbePolicy.initialUrl("exchange.example.test")

        listOf(null, "", "http://exchange.example.test/EAS", "::not a uri::").forEach { location ->
            assertEquals(RedirectDecision.Rejected, RedirectTracker(initial).follow(initial, location), location)
        }

        assertEquals(
            RedirectDecision.Rejected,
            RedirectTracker(initial).follow(initial, initial.toString()),
        )

        val tracker = RedirectTracker(initial)
        var current = initial
        repeat(5) { index ->
            val decision = tracker.follow(current, "https://redirect$index.example.test/EAS")
            assertTrue(decision is RedirectDecision.Follow)
            current = (decision as RedirectDecision.Follow).url
        }
        assertEquals(
            RedirectDecision.Rejected,
            tracker.follow(current, "https://redirect6.example.test/EAS"),
        )
    }

    @Test
    fun `only redirect response codes enter redirect handling`() {
        listOf(300, 301, 302, 303, 307, 308).forEach { status ->
            assertTrue(ActiveSyncProbePolicy.isRedirect(status), status.toString())
        }
        listOf(200, 304, 401, 500).forEach { status ->
            assertFalse(ActiveSyncProbePolicy.isRedirect(status), status.toString())
        }
    }

    @Test
    fun `compatible terminal response requires supported version and both commands`() {
        val result =
            ActiveSyncResponseEvaluator.evaluate(
                statusCode = 200,
                protocolVersions = "2.5, 14.1, 16.1",
                protocolCommands = "SendMail, foldersync, SYNC",
            )

        assertEquals(ConnectionCheckResult.Success, result)
    }

    @Test
    fun `missing or incompatible capability tokens report protocol failure`() {
        listOf(
            null to "FolderSync,Sync",
            "" to "FolderSync,Sync",
            "2.5" to "FolderSync,Sync",
            "16.1" to null,
            "16.1" to "Sync",
            "16.1" to "FolderSync",
        ).forEach { (versions, commands) ->
            assertEquals(
                ConnectionCheckResult.Failure(ConnectionFailure.PROTOCOL_INCOMPATIBLE),
                ActiveSyncResponseEvaluator.evaluate(200, versions, commands),
                "$versions / $commands",
            )
        }
    }

    @Test
    fun `terminal HTTP statuses map to actionable categories`() {
        val expected =
            mapOf(
                401 to ConnectionFailure.ACCESS_DENIED,
                403 to ConnectionFailure.ACCESS_DENIED,
                404 to ConnectionFailure.ENDPOINT_MISMATCH,
                405 to ConnectionFailure.ENDPOINT_MISMATCH,
                500 to ConnectionFailure.SERVER_ERROR,
                503 to ConnectionFailure.SERVER_ERROR,
                418 to ConnectionFailure.ENDPOINT_MISMATCH,
            )

        expected.forEach { (status, failure) ->
            assertEquals(
                ConnectionCheckResult.Failure(failure),
                ActiveSyncResponseEvaluator.evaluate(status, null, null),
                status.toString(),
            )
        }
    }

}
