package net.mixalich7b.exchangesync.infrastructure.activesync

import net.mixalich7b.exchangesync.core.connection.ConnectionCheckResult
import net.mixalich7b.exchangesync.core.connection.ConnectionFailure
import okhttp3.HttpUrl
import okhttp3.Request

internal object ActiveSyncProbePolicy {
    private val redirectCodes = setOf(300, 301, 302, 303, 307, 308)

    fun initialUrl(serverHost: String): HttpUrl =
        HttpUrl.Builder()
            .scheme("https")
            .host(serverHost)
            .port(443)
            .addPathSegment("Microsoft-Server-ActiveSync")
            .build()

    fun request(url: HttpUrl): Request =
        Request.Builder()
            .url(url)
            .method("OPTIONS", null)
            .build()

    fun isRedirect(statusCode: Int): Boolean = statusCode in redirectCodes
}

internal sealed interface RedirectDecision {
    data class Follow(val url: HttpUrl) : RedirectDecision

    data object Rejected : RedirectDecision
}

internal class RedirectTracker(
    initialUrl: HttpUrl,
    private val maximumRedirects: Int = 5,
) {
    private val visited = mutableSetOf(initialUrl)
    private var redirectCount = 0

    fun follow(currentUrl: HttpUrl, location: String?): RedirectDecision {
        if (redirectCount >= maximumRedirects) return RedirectDecision.Rejected
        val value = location?.takeIf { candidate -> candidate.isNotBlank() && candidate.none(Char::isWhitespace) }
            ?: return RedirectDecision.Rejected
        val destination = currentUrl.resolve(value) ?: return RedirectDecision.Rejected
        if (destination.scheme != "https") return RedirectDecision.Rejected
        if (destination.username.isNotEmpty() || destination.password.isNotEmpty()) return RedirectDecision.Rejected
        if (!visited.add(destination)) return RedirectDecision.Rejected

        redirectCount += 1
        return RedirectDecision.Follow(destination)
    }
}

internal object ActiveSyncResponseEvaluator {
    private val supportedVersions = setOf("12.1", "14.0", "14.1", "16.0", "16.1")
    private val requiredCommands = setOf("foldersync", "sync")

    fun evaluate(
        statusCode: Int,
        protocolVersions: String?,
        protocolCommands: String?,
    ): ConnectionCheckResult {
        if (statusCode != 200) return ConnectionCheckResult.Failure(classifyStatus(statusCode))

        val versions = tokens(protocolVersions)
        val commands = tokens(protocolCommands).map(String::lowercase).toSet()
        return if (versions.any(supportedVersions::contains) && commands.containsAll(requiredCommands)) {
            ConnectionCheckResult.Success
        } else {
            ConnectionCheckResult.Failure(ConnectionFailure.PROTOCOL_INCOMPATIBLE)
        }
    }

    private fun tokens(header: String?): Set<String> =
        header
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty()

    private fun classifyStatus(statusCode: Int): ConnectionFailure =
        when (statusCode) {
            401, 403 -> ConnectionFailure.ACCESS_DENIED
            404, 405 -> ConnectionFailure.ENDPOINT_MISMATCH
            in 500..599 -> ConnectionFailure.SERVER_ERROR
            in 300..399 -> ConnectionFailure.REDIRECT_POLICY
            else -> ConnectionFailure.ENDPOINT_MISMATCH
        }
}
