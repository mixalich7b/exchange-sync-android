package net.mixalich7b.exchangesync.infrastructure.calendar

import java.time.Instant
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticFieldState
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticFieldValue
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticProviderColumn
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticProviderColumnEntry
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticProviderColumnPolicy
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticProviderOperationKind
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticProviderOperationSnapshot
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticProviderReference
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticProviderTarget
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticTextSanitizer

internal object CalendarProviderFailureProjector {
    fun project(subBatch: CalendarProviderSubBatch): List<DiagnosticProviderOperationSnapshot> =
        subBatch.operations.mapIndexed { localIndex, operation ->
            project(
                operation = operation,
                globalIndex = subBatch.startOperationIndex + localIndex,
                localIndex = localIndex,
            )
        }

    private fun project(
        operation: CalendarProviderBatchOperation,
        globalIndex: Int,
        localIndex: Int,
    ): DiagnosticProviderOperationSnapshot =
        when (operation) {
            is CalendarProviderBatchOperation.EventInsert ->
                snapshot(
                    operation,
                    globalIndex,
                    localIndex,
                    DiagnosticProviderOperationKind.EVENT_INSERT,
                    DiagnosticProviderTarget.EVENT,
                    values = operation.values,
                )
            is CalendarProviderBatchOperation.EventUpdate ->
                snapshot(
                    operation,
                    globalIndex,
                    localIndex,
                    DiagnosticProviderOperationKind.EVENT_UPDATE,
                    DiagnosticProviderTarget.EVENT,
                    DiagnosticProviderReference.Existing(operation.eventId),
                    operation.values,
                )
            is CalendarProviderBatchOperation.EventDelete ->
                snapshot(
                    operation,
                    globalIndex,
                    localIndex,
                    DiagnosticProviderOperationKind.EVENT_DELETE,
                    DiagnosticProviderTarget.EVENT,
                    DiagnosticProviderReference.SyncId(DiagnosticTextSanitizer.sanitize(operation.syncId)),
                )
            is CalendarProviderBatchOperation.AttendeesDelete ->
                snapshot(
                    operation,
                    globalIndex,
                    localIndex,
                    DiagnosticProviderOperationKind.ATTENDEES_DELETE,
                    DiagnosticProviderTarget.ATTENDEE,
                    DiagnosticProviderReference.Existing(operation.eventId),
                )
            is CalendarProviderBatchOperation.OrganizerDelete ->
                snapshot(
                    operation,
                    globalIndex,
                    localIndex,
                    DiagnosticProviderOperationKind.ORGANIZER_DELETE,
                    DiagnosticProviderTarget.ORGANIZER,
                    operation.event.toDiagnosticReference(),
                )
            is CalendarProviderBatchOperation.AttendeeInsert -> {
                val organizer =
                    (operation.values[CalendarProviderField.ATTENDEE_RELATIONSHIP] as? Number)?.toInt() ==
                        ProviderInteger.ORGANIZER_RELATIONSHIP
                snapshot(
                    operation,
                    globalIndex,
                    localIndex,
                    DiagnosticProviderOperationKind.ATTENDEE_INSERT,
                    if (organizer) DiagnosticProviderTarget.ORGANIZER else DiagnosticProviderTarget.ATTENDEE,
                    operation.event.toDiagnosticReference(),
                    operation.values,
                )
            }
            is CalendarProviderBatchOperation.RemindersDelete ->
                snapshot(
                    operation,
                    globalIndex,
                    localIndex,
                    DiagnosticProviderOperationKind.REMINDERS_DELETE,
                    DiagnosticProviderTarget.REMINDER,
                    operation.event.toDiagnosticReference(),
                )
            is CalendarProviderBatchOperation.ReminderInsert ->
                snapshot(
                    operation,
                    globalIndex,
                    localIndex,
                    DiagnosticProviderOperationKind.REMINDER_INSERT,
                    DiagnosticProviderTarget.REMINDER,
                    operation.event.toDiagnosticReference(),
                    operation.values,
                )
            is CalendarProviderBatchOperation.ExceptionsDelete ->
                snapshot(
                    operation,
                    globalIndex,
                    localIndex,
                    DiagnosticProviderOperationKind.EXCEPTIONS_DELETE,
                    DiagnosticProviderTarget.EXCEPTION,
                    DiagnosticProviderReference.Existing(operation.seriesId),
                )
            is CalendarProviderBatchOperation.ExceptionInsert ->
                snapshot(
                    operation,
                    globalIndex,
                    localIndex,
                    DiagnosticProviderOperationKind.EXCEPTION_INSERT,
                    DiagnosticProviderTarget.EXCEPTION,
                    operation.series.toDiagnosticReference(),
                    operation.values,
                )
            is CalendarProviderBatchOperation.ExceptionResponseUpdate -> {
                val values =
                    operation.values +
                        (CalendarProviderField.ORIGINAL_INSTANCE_TIME to operation.originalInstance.toEpochMilli())
                snapshot(
                    operation,
                    globalIndex,
                    localIndex,
                    DiagnosticProviderOperationKind.EXCEPTION_RESPONSE_UPDATE,
                    DiagnosticProviderTarget.EXCEPTION,
                    DiagnosticProviderReference.Existing(operation.seriesId),
                    values,
                )
            }
        }

    private fun snapshot(
        operation: CalendarProviderBatchOperation,
        globalIndex: Int,
        localIndex: Int,
        kind: DiagnosticProviderOperationKind,
        target: DiagnosticProviderTarget,
        reference: DiagnosticProviderReference? = null,
        values: Map<String, Any?> = emptyMap(),
    ): DiagnosticProviderOperationSnapshot =
        DiagnosticProviderOperationSnapshot(
            globalOperationIndex = globalIndex,
            subBatchOperationIndex = localIndex,
            operationKind = kind,
            target = target,
            calendarId = operation.calendarId,
            reference = reference,
            columns = values.projectColumns(target),
        )

    private fun Map<String, Any?>.projectColumns(
        target: DiagnosticProviderTarget,
    ): List<DiagnosticProviderColumnEntry> =
        map { (wireName, rawValue) ->
            val column = providerColumnsByWireName[wireName] ?: DiagnosticProviderColumn.UNKNOWN
            val structuralOnly =
                target == DiagnosticProviderTarget.ATTENDEE ||
                    target == DiagnosticProviderTarget.ORGANIZER ||
                    column.policy == DiagnosticProviderColumnPolicy.STRUCTURAL_ONLY
            DiagnosticProviderColumnEntry(
                column = column,
                state = if (rawValue == null) DiagnosticFieldState.EMPTY else DiagnosticFieldState.PRESENT,
                value = rawValue?.takeUnless { structuralOnly }?.toDiagnosticValue(),
            )
        }.sortedBy { entry -> entry.column.ordinal }

    private fun Any.toDiagnosticValue(): DiagnosticFieldValue =
        when (this) {
            is String -> DiagnosticFieldValue.Text(DiagnosticTextSanitizer.sanitize(this))
            is Byte -> DiagnosticFieldValue.IntegerValue(toLong())
            is Short -> DiagnosticFieldValue.IntegerValue(toLong())
            is Int -> DiagnosticFieldValue.IntegerValue(this)
            is Long -> DiagnosticFieldValue.IntegerValue(this)
            is Boolean -> DiagnosticFieldValue.BooleanValue(this)
            is Instant -> DiagnosticFieldValue.Timestamp(this)
            is Enum<*> -> DiagnosticFieldValue.EnumName(name)
            else ->
                DiagnosticFieldValue.TypeName(
                    DiagnosticTextSanitizer.sanitize(javaClass.simpleName.ifEmpty { "unknown" }),
                )
        }

    private fun EventReference.toDiagnosticReference(): DiagnosticProviderReference =
        when (this) {
            is EventReference.Existing -> DiagnosticProviderReference.Existing(eventId)
            is EventReference.Inserted -> DiagnosticProviderReference.BackReference(operationIndex)
        }

    private val providerColumnsByWireName: Map<String, DiagnosticProviderColumn> =
        DiagnosticProviderColumn.entries
            .filterNot { column -> column == DiagnosticProviderColumn.UNKNOWN }
            .associateBy(DiagnosticProviderColumn::wireName)
}
