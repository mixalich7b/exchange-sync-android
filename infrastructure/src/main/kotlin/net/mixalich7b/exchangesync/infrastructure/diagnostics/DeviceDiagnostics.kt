package net.mixalich7b.exchangesync.infrastructure.diagnostics

import android.util.Log
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import okhttp3.HttpUrl

internal const val DIAGNOSTIC_LOG_TAG: String = "ExchangeSync"

internal enum class DiagnosticSeverity { INFO, WARN, ERROR }

internal enum class DiagnosticComponent {
    CONNECTION,
    LOCAL_CA,
    TLS,
    KEYCHAIN,
    HTTP,
    ACTIVE_SYNC,
    CALENDAR,
    SYNCHRONIZATION,
    WORKER,
}

internal enum class DiagnosticStage {
    START,
    LOCAL_CA_LIST,
    LOCAL_CA_PARSE,
    TRUST_MANAGER,
    KEYCHAIN_RESOLUTION,
    CLIENT_KEY,
    SERVER_CHAIN,
    HOSTNAME,
    HANDSHAKE,
    CLIENT_CERTIFICATE_VERIFICATION,
    DNS,
    CONNECT,
    SECURE_CONNECT,
    REQUEST,
    RESPONSE,
    RESPONSE_BODY,
    REDIRECT,
    HTTP_CLASSIFICATION,
    CAPABILITY_VALIDATION,
    VERSION_SELECTION,
    COMMAND,
    WBXML,
    FOLDER_SYNC,
    CALENDAR_SYNC,
    EVENT_PARSE,
    EVENT_MAP,
    PROVIDER_QUERY,
    PROVIDER_BATCH,
    CHECKPOINT,
    CLEANUP,
    OWNERSHIP,
    PHASE,
    RETRY,
    RESET,
    WINDOW_REDUCTION,
    BLOCK,
    OBSOLETE,
    CANCELLATION,
    COMPLETE,
    INPUT,
    RESULT,
    FAILURE,
}

internal enum class DiagnosticOperationKind {
    CONNECTION_CHECK,
    CAPABILITY_DISCOVERY,
    ACTIVE_SYNC_COMMAND,
    SYNCHRONIZATION,
    PERIODIC_WORKER,
    EXECUTION_WORKER,
    LOCAL_OPERATION,
}

internal enum class SyncRequestMode {
    PRIMING,
    FULL,
    INCREMENTAL,
}

internal enum class OwnedCalendarAction {
    CREATED,
    REUSED,
    REPAIRED,
    DELETED,
    UNCHANGED,
}

internal enum class CleanupTrigger {
    PROFILE_ACTIVATION,
    FULL_RESET,
    DISABLE,
    STARTUP,
    PERMISSION_RECOVERY,
    USER_RETRY,
}

internal enum class CheckpointOutcome {
    COMMITTED,
    SKIPPED,
    FAILED,
}

internal enum class DiagnosticCapacityKind {
    HTTP_RESPONSE_BYTES,
    WBXML_DOCUMENT_BYTES,
    WBXML_ELEMENT_COUNT,
    WBXML_DEPTH,
    WBXML_INLINE_STRING_BYTES,
    CALENDAR_PROVIDER_TRANSACTION,
}

internal enum class DiagnosticActiveSyncCommand {
    FOLDER_SYNC,
    SYNC,
}

internal enum class DiagnosticCapacityOutcome {
    WINDOW_REDUCTION,
    MINIMUM_WINDOW_BLOCK,
    TERMINAL,
}

internal enum class DiagnosticCapacityProblem {
    PROTOCOL_DATA,
    CALENDAR_PROVIDER,
}

internal enum class FolderPreparationOutcome {
    COLD_REFRESH,
    REFRESH,
    REUSE,
    INVALIDATED,
}

internal data class DiagnosticOperation(
    val id: String,
    val kind: DiagnosticOperationKind,
    val generation: Long? = null,
    val runToken: Long? = null,
)

internal data class DeviceDiagnosticEvent(
    val severity: DiagnosticSeverity,
    val component: DiagnosticComponent,
    val stage: DiagnosticStage,
    val operation: DiagnosticOperation? = null,
    val trigger: String? = null,
    val phase: String? = null,
    val method: String? = null,
    val command: String? = null,
    val host: String? = null,
    val path: String? = null,
    val status: Int? = null,
    val timeoutMillis: Long? = null,
    val protocolVersions: Set<String> = emptySet(),
    val protocolCommands: Set<String> = emptySet(),
    val reasonCode: String? = null,
    val failureCategory: String? = null,
    val outcome: String? = null,
    val attempt: Int? = null,
    val serverId: String? = null,
    val assetFile: String? = null,
    val chainLength: Int? = null,
    val keyAlgorithm: String? = null,
    val fingerprint: String? = null,
    val syncMode: SyncRequestMode? = null,
    val windowSize: Int? = null,
    val reducedWindowSize: Int? = null,
    val capacityKind: DiagnosticCapacityKind? = null,
    val capacityCommand: DiagnosticActiveSyncCommand? = null,
    val capacityOutcome: DiagnosticCapacityOutcome? = null,
    val capacityProblem: DiagnosticCapacityProblem? = null,
    val folderPreparationOutcome: FolderPreparationOutcome? = null,
    val responseBytes: Int? = null,
    val responseEmpty: Boolean? = null,
    val commandCount: Int? = null,
    val addCount: Int? = null,
    val changeCount: Int? = null,
    val deleteCount: Int? = null,
    val moreAvailable: Boolean? = null,
    val keyAdvanced: Boolean? = null,
    val ownershipAction: OwnedCalendarAction? = null,
    val inputCount: Int? = null,
    val acceptedCount: Int? = null,
    val rejectedCount: Int? = null,
    val plannedOperationCount: Int? = null,
    val attemptedOperationCount: Int? = null,
    val appliedOperationCount: Int? = null,
    val cleanupTrigger: CleanupTrigger? = null,
    val checkpointOutcome: CheckpointOutcome? = null,
    val throwable: Throwable? = null,
)

internal fun interface DeviceDiagnosticSink {
    fun emit(event: DeviceDiagnosticEvent)
}

internal object NoOpDeviceDiagnosticSink : DeviceDiagnosticSink {
    override fun emit(event: DeviceDiagnosticEvent) = Unit
}

internal class DeviceDiagnostics(
    private val sink: DeviceDiagnosticSink = NoOpDeviceDiagnosticSink,
) {
    fun operation(
        kind: DiagnosticOperationKind,
        generation: Long? = null,
        runToken: Long? = null,
    ): DiagnosticOperation =
        DiagnosticOperation(
            id = "${kind.name.lowercase()}-${processSequence.incrementAndGet()}",
            kind = kind,
            generation = generation,
            runToken = runToken,
        )

    fun emit(event: DeviceDiagnosticEvent) {
        try {
            sink.emit(event)
        } catch (_: Throwable) {
            // Diagnostics must never change connection or synchronization behavior.
        }
    }

    private companion object {
        val processSequence = AtomicLong()
    }
}

internal class AndroidLogcatDiagnosticSink : DeviceDiagnosticSink {
    override fun emit(event: DeviceDiagnosticEvent) {
        try {
            val record = DeviceDiagnosticFormatter.format(event)
            when (event.severity) {
                DiagnosticSeverity.INFO -> Log.i(DIAGNOSTIC_LOG_TAG, record)
                DiagnosticSeverity.WARN -> Log.w(DIAGNOSTIC_LOG_TAG, record)
                DiagnosticSeverity.ERROR -> Log.e(DIAGNOSTIC_LOG_TAG, record)
            }
        } catch (_: Throwable) {
            // Android logging and formatting are deliberately non-fatal.
        }
    }
}

private object DeviceDiagnosticFormatter {
    private const val MAX_RECORD_LENGTH = 3_000
    private const val MAX_AGGREGATE_COUNT = 1_000_000

    fun format(event: DeviceDiagnosticEvent): String {
        val values = mutableListOf<String>()
        values += "component=${event.component.name.lowercase()}"
        values += "stage=${event.stage.name.lowercase()}"
        event.operation?.let { operation ->
            values += "operation=${DiagnosticTextSanitizer.sanitize(operation.id)}"
            values += "operation_kind=${operation.kind.name.lowercase()}"
            operation.generation?.let { values += "generation=$it" }
            operation.runToken?.let { values += "run_token=$it" }
        }
        if (event.capacityKind != null || event.folderPreparationOutcome != null) {
            event.capacityKind?.let { values += "capacity_kind=${it.name.lowercase()}" }
            event.capacityCommand?.let { values += "capacity_command=${it.name.lowercase()}" }
            event.capacityOutcome?.let { values += "capacity_outcome=${it.name.lowercase()}" }
            event.capacityProblem?.let { values += "capacity_problem=${it.name.lowercase()}" }
            event.folderPreparationOutcome?.let { values += "folder_preparation=${it.name.lowercase()}" }
            appendCount(values, "window_size", event.windowSize)
            appendCount(values, "reduced_window_size", event.reducedWindowSize)
            return values.joinToString(" ").take(MAX_RECORD_LENGTH)
        }
        append(values, "trigger", event.trigger)
        append(values, "phase", event.phase)
        append(values, "method", event.method)
        append(values, "command", event.command)
        append(values, "host", event.host)
        append(values, "path", event.path)
        event.status?.let { values += "status=$it" }
        event.timeoutMillis?.let { values += "timeout_ms=$it" }
        appendSet(values, "protocol_versions", event.protocolVersions)
        appendSet(values, "protocol_commands", event.protocolCommands)
        append(values, "reason", event.reasonCode)
        append(values, "failure", event.failureCategory)
        append(values, "outcome", event.outcome)
        event.attempt?.let { values += "attempt=$it" }
        append(values, "server_id", event.serverId)
        append(values, "asset", event.assetFile)
        event.chainLength?.let { values += "chain_length=$it" }
        append(values, "key_algorithm", event.keyAlgorithm)
        append(values, "fingerprint", event.fingerprint)
        event.syncMode?.let { values += "sync_mode=${it.name.lowercase()}" }
        appendCount(values, "window_size", event.windowSize)
        appendCount(values, "reduced_window_size", event.reducedWindowSize)
        appendCount(values, "response_bytes", event.responseBytes)
        event.responseEmpty?.let { values += "response_empty=$it" }
        appendCount(values, "command_count", event.commandCount)
        appendCount(values, "add_count", event.addCount)
        appendCount(values, "change_count", event.changeCount)
        appendCount(values, "delete_count", event.deleteCount)
        event.moreAvailable?.let { values += "more_available=$it" }
        event.keyAdvanced?.let { values += "key_advanced=$it" }
        event.ownershipAction?.let { values += "ownership_action=${it.name.lowercase()}" }
        appendCount(values, "input_count", event.inputCount)
        appendCount(values, "accepted_count", event.acceptedCount)
        appendCount(values, "rejected_count", event.rejectedCount)
        appendCount(values, "planned_operation_count", event.plannedOperationCount)
        appendCount(values, "attempted_operation_count", event.attemptedOperationCount)
        appendCount(values, "applied_operation_count", event.appliedOperationCount)
        event.cleanupTrigger?.let { values += "cleanup_trigger=${it.name.lowercase()}" }
        event.checkpointOutcome?.let { values += "checkpoint_outcome=${it.name.lowercase()}" }
        event.throwable?.let { throwable ->
            values +=
                "exceptions=${ThrowableDiagnosticFormatter.format(throwable, event.mayIncludeRootMessage())}"
        }
        return values.joinToString(" ").take(MAX_RECORD_LENGTH)
    }

    private fun append(
        values: MutableList<String>,
        name: String,
        value: String?,
    ) {
        value?.let { values += "$name=${DiagnosticTextSanitizer.sanitize(it)}" }
    }

    private fun appendSet(
        values: MutableList<String>,
        name: String,
        source: Set<String>,
    ) {
        if (source.isNotEmpty()) {
            values += "$name=${source.joinToString(",") { value -> DiagnosticTextSanitizer.sanitize(value) }}"
        }
    }

    private fun appendCount(
        values: MutableList<String>,
        name: String,
        value: Int?,
    ) {
        value?.let { values += "$name=${it.coerceIn(0, MAX_AGGREGATE_COUNT)}" }
    }

    private fun DeviceDiagnosticEvent.mayIncludeRootMessage(): Boolean =
        component in messageSafeComponents ||
            (component == DiagnosticComponent.ACTIVE_SYNC && stage !in payloadSensitiveStages)

    private val messageSafeComponents =
        setOf(
            DiagnosticComponent.CONNECTION,
            DiagnosticComponent.LOCAL_CA,
            DiagnosticComponent.TLS,
            DiagnosticComponent.KEYCHAIN,
            DiagnosticComponent.HTTP,
        )

    private val payloadSensitiveStages =
        setOf(
            DiagnosticStage.WBXML,
            DiagnosticStage.FOLDER_SYNC,
            DiagnosticStage.CALENDAR_SYNC,
            DiagnosticStage.EVENT_PARSE,
            DiagnosticStage.EVENT_MAP,
        )
}

private object ThrowableDiagnosticFormatter {
    private const val MAX_THROWABLES = 8
    private const val MAX_FRAMES = 4

    fun format(
        root: Throwable,
        includeRootMessage: Boolean,
    ): String {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Throwable>()
        pending += root
        val records = mutableListOf<String>()
        while (pending.isNotEmpty() && records.size < MAX_THROWABLES) {
            val throwable = pending.removeFirst()
            if (!seen.add(throwable)) {
                records += "cycle"
                continue
            }
            val frames =
                throwable.stackTrace.take(MAX_FRAMES).joinToString(",") { frame ->
                    DiagnosticTextSanitizer.sanitize(
                        "${frame.className}.${frame.methodName}(${frame.fileName.orEmpty()}:${frame.lineNumber})",
                    )
                }
            records +=
                buildString {
                    append(throwable.javaClass.name)
                    if (throwable === root && includeRootMessage) {
                        throwable.message?.let { message ->
                            append(':').append(DiagnosticTextSanitizer.sanitize(message))
                        }
                    }
                    if (frames.isNotEmpty()) append('@').append(frames)
                }
            throwable.cause?.let(pending::addLast)
            throwable.suppressed.forEach(pending::addLast)
        }
        if (pending.isNotEmpty()) records += "truncated"
        return records.joinToString("|")
    }
}

internal object DiagnosticTextSanitizer {
    private const val MAX_VALUE_LENGTH = 256
    private val urlPattern = Regex("https://[^\\s]+", RegexOption.IGNORE_CASE)
    private val headerPattern =
        Regex("(?i)\\b(?:cookie|set-cookie|authorization)\\s*[:=]\\s*[^\\r\\n]*")
    private val aliasPattern =
        Regex("(?i)\\b(?:certificate\\s+|keychain\\s+)?alias\\b\\s*(?::|=|is)?\\s*[^\\r\\n,;]*")
    private val emailPattern = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+")
    private val accountPattern = Regex("[A-Za-z0-9._-]+\\\\[A-Za-z0-9._-]+")

    fun sanitize(raw: String): String {
        var value = headerPattern.replace(raw, "<redacted-header>")
        value = aliasPattern.replace(value, "alias=<redacted>")
        value = value.replace(Regex("[\\p{Cntrl}&&[^\\t]]"), " ")
        value = emailPattern.replace(value, "<email>")
        value = accountPattern.replace(value, "<account>")
        value =
            urlPattern.replace(value) { match ->
                runCatching {
                    val uri = URI(match.value.trimEnd('.', ',', ';', ')', ']'))
                    "https://${uri.host.orEmpty()}${uri.rawPath.orEmpty()}"
                }.getOrDefault("https://<redacted>")
            }
        return value.replace(' ', '_').take(MAX_VALUE_LENGTH)
    }
}

internal fun HttpUrl.diagnosticHost(): String = host

internal fun HttpUrl.diagnosticPath(): String = DiagnosticTextSanitizer.sanitize(encodedPath)

internal fun safeHeaderTokens(header: String?): Set<String> =
    header
        ?.split(',')
        ?.asSequence()
        ?.map(String::trim)
        ?.filter { token -> token.matches(Regex("[A-Za-z0-9._-]{1,32}")) }
        ?.take(16)
        ?.toCollection(linkedSetOf())
        .orEmpty()
