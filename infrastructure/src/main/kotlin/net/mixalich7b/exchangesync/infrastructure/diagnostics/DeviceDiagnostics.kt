package net.mixalich7b.exchangesync.infrastructure.diagnostics

import android.util.Log
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import okhttp3.HttpUrl
import kotlin.collections.plusAssign

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
    ATTENDEE_SUPPRESSION,
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

internal enum class DiagnosticAttendeeRepresentation {
    ORGANIZER_ONLY,
    EMPTY,
}

internal enum class DiagnosticProviderCallOutcome {
    CONFIRMED,
    UNKNOWN,
}

internal enum class DiagnosticProviderFailureCause {
    ACCESS,
    REMOTE,
    OPERATION_APPLICATION,
    OPERATION_CANCELLED,
    INVALID_ARGUMENT,
    INVALID_REQUEST,
    INVALID_RESULT,
    INVALID_REFERENCE,
    UNSUPPORTED_VALUE,
    UNEXPECTED,
    TRANSACTION_TOO_LARGE,
    SECURITY,
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
    val attendeeLimit: Int? = null,
    val attendeeInputCount: Int? = null,
    val attendeeOmittedCount: Int? = null,
    val attendeeRepresentation: DiagnosticAttendeeRepresentation? = null,
    val providerOperationCount: Int? = null,
    val subBatchCount: Int? = null,
    val subBatchOrdinal: Int? = null,
    val subBatchOperationCount: Int? = null,
    val confirmedOperationCount: Int? = null,
    val providerCallOutcome: DiagnosticProviderCallOutcome? = null,
    val providerFailureCause: DiagnosticProviderFailureCause? = null,
    val cleanupTrigger: CleanupTrigger? = null,
    val checkpointOutcome: CheckpointOutcome? = null,
    val calendarFailureSnapshot: DiagnosticCalendarFailureSnapshot? = null,
    val providerOperationSnapshot: DiagnosticProviderOperationSnapshot? = null,
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

    fun emit(eventFactory: () -> DeviceDiagnosticEvent) {
        try {
            emit(eventFactory())
        } catch (_: Throwable) {
            // Diagnostic projection must never change connection or synchronization behavior.
        }
    }

    private companion object {
        val processSequence = AtomicLong()
    }
}

internal class AndroidLogcatDiagnosticSink : DeviceDiagnosticSink {
    override fun emit(event: DeviceDiagnosticEvent) {
        try {
            DeviceDiagnosticFormatter.formatRecords(event).forEach { record ->
                when (event.severity) {
                    DiagnosticSeverity.INFO -> Log.i(DIAGNOSTIC_LOG_TAG, record)
                    DiagnosticSeverity.WARN -> Log.w(DIAGNOSTIC_LOG_TAG, record)
                    DiagnosticSeverity.ERROR -> Log.e(DIAGNOSTIC_LOG_TAG, record)
                }
            }
        } catch (_: Throwable) {
            // Android logging and formatting are deliberately non-fatal.
        }
    }
}

internal object DeviceDiagnosticFormatter {
    private const val MAX_RECORD_LENGTH = 3_000
    private const val MAX_AGGREGATE_COUNT = 1_000_000

    fun formatRecords(event: DeviceDiagnosticEvent): List<String> =
        when {
            event.calendarFailureSnapshot != null ->
                formatCalendarFailure(event, event.calendarFailureSnapshot)
            event.providerOperationSnapshot != null ->
                formatProviderOperation(event, event.providerOperationSnapshot)
            else -> listOf(format(event))
        }

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
            values.appendThrowable(event)
            return values.joinToString(" ").take(MAX_RECORD_LENGTH)
        }
        if (event.hasLargeEntityOrProviderSubBatchFields()) {
            append(values, "failure", event.failureCategory)
            append(values, "outcome", event.outcome)
            appendCount(values, "attempted_operation_count", event.attemptedOperationCount)
            appendCount(values, "applied_operation_count", event.appliedOperationCount)
            appendCount(values, "attendee_limit", event.attendeeLimit)
            appendCount(values, "attendee_input_count", event.attendeeInputCount)
            appendCount(values, "attendee_omitted_count", event.attendeeOmittedCount)
            event.attendeeRepresentation?.let { representation ->
                values += "attendee_representation=${representation.name.lowercase()}"
            }
            appendCount(values, "provider_operation_count", event.providerOperationCount)
            appendCount(values, "sub_batch_count", event.subBatchCount)
            appendCount(values, "sub_batch_ordinal", event.subBatchOrdinal)
            appendCount(values, "sub_batch_operation_count", event.subBatchOperationCount)
            appendCount(values, "confirmed_operation_count", event.confirmedOperationCount)
            event.providerCallOutcome?.let { outcome ->
                values += "provider_call_outcome=${outcome.name.lowercase()}"
            }
            event.providerFailureCause?.let { cause ->
                values += "provider_failure_cause=${cause.name.lowercase()}"
            }
            values.appendThrowable(event)
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
        values.appendThrowable(event)
        return values.joinToString(" ").take(MAX_RECORD_LENGTH)
    }

    private fun formatCalendarFailure(
        event: DeviceDiagnosticEvent,
        snapshot: DiagnosticCalendarFailureSnapshot,
    ): List<String> {
        val header = snapshotHeader(event)
        header += "snapshot=calendar_failure"
        snapshot.commandKind?.let { header += "command_kind=${it.name.lowercase()}" }
        snapshot.serverId?.let { header += "server_id=${DiagnosticTextSanitizer.sanitize(it)}" }
        header += "rule=${snapshot.rule.name.lowercase()}"
        snapshot.failedField?.let { header += "failed_field=${it.name.lowercase()}" }
        appendCount(header, "attendee_index", snapshot.attendeeIndex)
        header +=
            when (val path = snapshot.path) {
                DiagnosticCalendarPath.Event -> "calendar_path=event"
                is DiagnosticCalendarPath.Exception ->
                    "calendar_path=exception exception_index=${path.index.coerceIn(0, MAX_AGGREGATE_COUNT)}"
            }
        header.appendThrowable(event)
        appendCount(header, "field_count", snapshot.fields.size)
        val segments =
            snapshot.fields
                .sortedWith(
                    compareBy<DiagnosticCalendarFieldEntry>(
                        { field -> field.source.ordinal },
                        { field -> field.field.ordinal },
                        { field -> field.state.ordinal },
                        { field -> field.value?.render().orEmpty() },
                    ),
                ).map { field ->
                    val prefix = "${field.source.name.lowercase()}.${field.field.name.lowercase()}"
                    buildList {
                        add("$prefix.state=${field.state.name.lowercase()}")
                        if (
                            field.field.policy == DiagnosticCalendarFieldPolicy.FULL_VALUE &&
                            field.value != null
                        ) {
                            add("$prefix.value=${field.value.render()}")
                        }
                    }.joinToString(" ")
                }
        return chunkedRecords(header, segments)
    }

    private fun formatProviderOperation(
        event: DeviceDiagnosticEvent,
        snapshot: DiagnosticProviderOperationSnapshot,
    ): List<String> {
        val header = snapshotHeader(event)
        header += "snapshot=provider_operation"
        appendCount(header, "global_operation_index", snapshot.globalOperationIndex)
        appendCount(header, "sub_batch_operation_index", snapshot.subBatchOperationIndex)
        header += "provider_operation_kind=${snapshot.operationKind.name.lowercase()}"
        header += "provider_target=${snapshot.target.name.lowercase()}"
        header += "calendar_id=${snapshot.calendarId}"
        when (val reference = snapshot.reference) {
            null -> Unit
            is DiagnosticProviderReference.Existing -> {
                header += "reference_kind=existing"
                header += "reference_value=${reference.rowId}"
            }
            is DiagnosticProviderReference.BackReference -> {
                header += "reference_kind=back_reference"
                appendCount(header, "reference_value", reference.operationIndex)
            }
            is DiagnosticProviderReference.SyncId -> {
                header += "reference_kind=sync_id"
                header += "reference_value=${DiagnosticTextSanitizer.sanitize(reference.value)}"
            }
        }
        appendCount(header, "column_count", snapshot.columns.size)
        val structuralOnly =
            snapshot.target == DiagnosticProviderTarget.ATTENDEE ||
                snapshot.target == DiagnosticProviderTarget.ORGANIZER
        val segments =
            snapshot.columns
                .sortedWith(
                    compareBy<DiagnosticProviderColumnEntry>(
                        { entry -> entry.column.ordinal },
                        { entry -> entry.state.ordinal },
                        { entry -> entry.value?.render().orEmpty() },
                    ),
                ).map { entry ->
                    val prefix = "column.${entry.column.wireName}"
                    buildList {
                        add("$prefix.state=${entry.state.name.lowercase()}")
                        if (
                            !structuralOnly &&
                            entry.column.policy == DiagnosticProviderColumnPolicy.FULL_VALUE &&
                            entry.value != null
                        ) {
                            add("$prefix.value=${entry.value.render()}")
                        }
                    }.joinToString(" ")
                }
        return chunkedRecords(header, segments)
    }

    private fun snapshotHeader(event: DeviceDiagnosticEvent): MutableList<String> =
        mutableListOf(
            "component=${event.component.name.lowercase()}",
            "stage=${event.stage.name.lowercase()}",
        ).apply {
            event.operation?.let { operation ->
                add("operation=${DiagnosticTextSanitizer.sanitize(operation.id)}")
                add("operation_kind=${operation.kind.name.lowercase()}")
                operation.generation?.let { add("generation=$it") }
                operation.runToken?.let { add("run_token=$it") }
            }
            event.failureCategory?.let { add("failure=${DiagnosticTextSanitizer.sanitize(it)}") }
            event.outcome?.let { add("outcome=${DiagnosticTextSanitizer.sanitize(it)}") }
            event.providerCallOutcome?.let { add("provider_call_outcome=${it.name.lowercase()}") }
            event.providerFailureCause?.let { add("provider_failure_cause=${it.name.lowercase()}") }
            appendCount(this, "provider_operation_count", event.providerOperationCount)
            appendCount(this, "sub_batch_count", event.subBatchCount)
            appendCount(this, "sub_batch_ordinal", event.subBatchOrdinal)
            appendCount(this, "sub_batch_operation_count", event.subBatchOperationCount)
            appendCount(this, "confirmed_operation_count", event.confirmedOperationCount)
        }

    private fun chunkedRecords(
        header: List<String>,
        segments: List<String>,
    ): List<String> {
        val headerText = header.joinToString(" ")
        val availableLength = MAX_RECORD_LENGTH - headerText.length - MAX_CHUNK_TOKEN_LENGTH - 2
        check(availableLength > 0) { "Diagnostic snapshot header exceeds record limit" }
        val chunks = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        var currentLength = 0
        segments.forEach { segment ->
            check(segment.length <= availableLength) { "Diagnostic snapshot field exceeds record limit" }
            val addedLength = segment.length + if (current.isEmpty()) 0 else 1
            if (current.isNotEmpty() && currentLength + addedLength > availableLength) {
                chunks += current
                current = mutableListOf()
                currentLength = 0
            }
            current += segment
            currentLength += segment.length + if (current.size == 1) 0 else 1
        }
        if (current.isNotEmpty() || chunks.isEmpty()) chunks += current
        return chunks.mapIndexed { index, chunk ->
            buildList {
                addAll(header)
                add("chunk=${index + 1}/${chunks.size}")
                addAll(chunk)
            }.joinToString(" ").also { record ->
                check(record.length <= MAX_RECORD_LENGTH) { "Diagnostic snapshot record exceeds record limit" }
            }
        }
    }

    private fun DiagnosticFieldValue.render(): String =
        when (this) {
            is DiagnosticFieldValue.Text -> DiagnosticTextSanitizer.sanitize(value)
            is DiagnosticFieldValue.IntegerValue -> value.toString()
            is DiagnosticFieldValue.BooleanValue -> value.toString()
            is DiagnosticFieldValue.Timestamp -> value.toString()
            is DiagnosticFieldValue.EnumName -> DiagnosticTextSanitizer.sanitize(value.lowercase())
            is DiagnosticFieldValue.Count -> value.coerceIn(0, MAX_AGGREGATE_COUNT).toString()
            is DiagnosticFieldValue.Relationship -> value.name.lowercase()
            is DiagnosticFieldValue.TypeName -> DiagnosticTextSanitizer.sanitize(value)
        }

    private fun MutableList<String>.appendThrowable(event: DeviceDiagnosticEvent) {
        event.throwable?.let { throwable ->
            this +=
                "exceptions=${ThrowableDiagnosticFormatter.format(throwable, event.mayIncludeRootMessage())}"
        }
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

    private fun DeviceDiagnosticEvent.hasLargeEntityOrProviderSubBatchFields(): Boolean =
        attendeeLimit != null ||
            attendeeInputCount != null ||
            attendeeOmittedCount != null ||
            attendeeRepresentation != null ||
            providerOperationCount != null ||
            subBatchCount != null ||
            subBatchOrdinal != null ||
            subBatchOperationCount != null ||
            confirmedOperationCount != null ||
            providerCallOutcome != null ||
            providerFailureCause != null

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

    private const val MAX_CHUNK_TOKEN_LENGTH: Int = 15
}

private object ThrowableDiagnosticFormatter {
    private const val MAX_THROWABLES = 8
    private const val MAX_FRAMES = 4
    private const val MAX_TRAVERSAL_ATTEMPTS = 32
    private const val MAX_PENDING_THROWABLES = 32
    private const val MAX_CLASS_NAME_LENGTH = 96
    private const val MAX_FORMAT_LENGTH = 1_024

    fun format(
        root: Throwable,
        includeRootMessage: Boolean,
    ): String {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Throwable>()
        pending += root
        val classes = mutableListOf<String>()
        val frames = mutableListOf<String>()
        var throwableCount = 0
        var traversalAttempts = 0
        var traversalTruncated = false
        while (
            pending.isNotEmpty() &&
            throwableCount < MAX_THROWABLES &&
            traversalAttempts < MAX_TRAVERSAL_ATTEMPTS
        ) {
            val throwable = pending.removeFirst()
            traversalAttempts += 1
            if (!seen.add(throwable)) {
                classes += "cycle"
                continue
            }
            val throwableIndex = throwableCount
            throwableCount += 1
            classes +=
                DiagnosticTextSanitizer
                    .sanitize(throwable.javaClass.name)
                    .take(MAX_CLASS_NAME_LENGTH)
            throwable.stackTrace.take(MAX_FRAMES).forEach { frame ->
                frames +=
                    "$throwableIndex:" +
                    DiagnosticTextSanitizer.sanitize(
                        "${frame.className}.${frame.methodName}(${frame.fileName.orEmpty()}:${frame.lineNumber})",
                    )
            }
            fun enqueue(candidate: Throwable) {
                if (pending.size < MAX_PENDING_THROWABLES) {
                    pending.addLast(candidate)
                } else {
                    traversalTruncated = true
                }
            }
            throwable.cause?.let(::enqueue)
            val suppressed = throwable.suppressed
            val remainingCapacity = (MAX_PENDING_THROWABLES - pending.size).coerceAtLeast(0)
            val retainedSuppressedCount = suppressed.size.coerceAtMost(remainingCapacity)
            repeat(retainedSuppressedCount) { index -> pending.addLast(suppressed[index]) }
            if (retainedSuppressedCount < suppressed.size) traversalTruncated = true
        }
        if (pending.isNotEmpty() || traversalTruncated) classes += "truncated"
        val values = mutableListOf("classes=${classes.joinToString("|")}")
        if (includeRootMessage) {
            root.message?.let { message ->
                values += "root_message=${DiagnosticTextSanitizer.sanitize(message)}"
            }
        }
        if (frames.isNotEmpty()) values += "frames=${frames.joinToString(",")}"
        val formatted = values.joinToString(";")
        if (formatted.length <= MAX_FORMAT_LENGTH) return formatted
        val marker = ";details_truncated"
        return formatted.take(MAX_FORMAT_LENGTH - marker.length) + marker
    }
}

internal object DiagnosticTextSanitizer {
    private const val MAX_VALUE_LENGTH = 256
    private val uriPattern = Regex("(?i)\\b[a-z][a-z0-9+.-]*:[^\\s]+")
    private val fixedOffsetTimeZonePattern = Regex("(?i)^GMT[+-](?:(?:0\\d|1[0-7]):[0-5]\\d|18:00)$")
    private val queryPattern = Regex("\\?[^\\s]+")
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
            uriPattern.replace(value) { match ->
                if (match.value.matches(fixedOffsetTimeZonePattern)) match.value else "<redacted-uri>"
            }
        value = queryPattern.replace(value, "?<redacted-query>")
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
