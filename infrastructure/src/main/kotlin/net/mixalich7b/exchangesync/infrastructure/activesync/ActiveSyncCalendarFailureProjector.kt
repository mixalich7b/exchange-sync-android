package net.mixalich7b.exchangesync.infrastructure.activesync

import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.AirSyncBase
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.Calendar
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlElement
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarCommandKind
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarFailureSnapshot
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarField
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarFieldEntry
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarFieldPolicy
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarFieldSource
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarPath
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarRule
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticFieldState
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticFieldValue
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticTextSanitizer

internal object ActiveSyncCalendarFailureProjector {
    fun project(
        command: RawCalendarCommand,
        reason: ActiveSyncValidationReason,
        exceptionIndex: Int?,
        failedField: DiagnosticCalendarField?,
        attendeeIndex: Int?,
        currentUserAttendeeCount: Int?,
        profileEmail: String,
    ): DiagnosticCalendarFailureSnapshot {
        val path = exceptionIndex?.let(DiagnosticCalendarPath::Exception) ?: DiagnosticCalendarPath.Event
        val fields = mutableListOf<DiagnosticCalendarFieldEntry>()
        projectSeries(
            command.applicationData,
            fields,
            profileEmail,
            attendeeIndex.takeIf { exceptionIndex == null },
            currentUserAttendeeCount.takeIf { exceptionIndex == null },
        )
        if (exceptionIndex != null) {
            val exception =
                command.applicationData
                    ?.child(Calendar.EXCEPTIONS)
                    ?.children(Calendar.EXCEPTION)
                    ?.getOrNull(exceptionIndex)
            projectException(exception, fields, profileEmail, attendeeIndex, currentUserAttendeeCount)
        }
        return DiagnosticCalendarFailureSnapshot(
            commandKind = command.kind.toDiagnosticCommandKind(),
            serverId = DiagnosticTextSanitizer.sanitize(command.serverId),
            rule = reason.toDiagnosticCalendarRule(),
            path = path,
            failedField = failedField,
            attendeeIndex = attendeeIndex,
            fields = fields,
        )
    }

    private fun projectSeries(
        data: WbxmlElement?,
        fields: MutableList<DiagnosticCalendarFieldEntry>,
        profileEmail: String,
        attendeeIndex: Int?,
        currentUserAttendeeCount: Int?,
    ) {
        fields.addScalar(data?.child(Calendar.UID), DiagnosticCalendarFieldSource.RESPONSE, DiagnosticCalendarField.UID)
        fields.addScalar(
            data?.child(Calendar.SUBJECT),
            DiagnosticCalendarFieldSource.RESPONSE,
            DiagnosticCalendarField.SUBJECT,
        )
        fields.addBody(data, DiagnosticCalendarFieldSource.RESPONSE)
        fields.addLocation(data, DiagnosticCalendarFieldSource.RESPONSE)
        fields.addScalar(data?.child(Calendar.START_TIME), DiagnosticCalendarFieldSource.RESPONSE, DiagnosticCalendarField.START)
        fields.addScalar(data?.child(Calendar.END_TIME), DiagnosticCalendarFieldSource.RESPONSE, DiagnosticCalendarField.END)
        fields.addScalar(
            data?.child(Calendar.ALL_DAY_EVENT),
            DiagnosticCalendarFieldSource.RESPONSE,
            DiagnosticCalendarField.ALL_DAY,
        )
        fields.addScalar(
            data?.child(Calendar.TIMEZONE),
            DiagnosticCalendarFieldSource.RESPONSE,
            DiagnosticCalendarField.TIME_ZONE_RAW,
        )
        fields.addRecurrence(data?.child(Calendar.RECURRENCE), DiagnosticCalendarFieldSource.RESPONSE)
        fields.addExceptionCount(data?.child(Calendar.EXCEPTIONS))
        fields.addScalar(
            data?.child(Calendar.ORGANIZER_EMAIL),
            DiagnosticCalendarFieldSource.RESPONSE,
            DiagnosticCalendarField.ORGANIZER_EMAIL,
        )
        fields.addScalar(
            data?.child(Calendar.ORGANIZER_NAME),
            DiagnosticCalendarFieldSource.RESPONSE,
            DiagnosticCalendarField.ORGANIZER_NAME,
        )
        fields.addAttendees(
            data?.child(Calendar.ATTENDEES),
            DiagnosticCalendarFieldSource.RESPONSE,
            profileEmail,
            attendeeIndex,
            currentUserAttendeeCount,
        )
        fields.addMeetingFields(data, DiagnosticCalendarFieldSource.RESPONSE)
        fields.addScalar(
            data?.child(Calendar.REMINDER),
            DiagnosticCalendarFieldSource.RESPONSE,
            DiagnosticCalendarField.REMINDER_MINUTES,
        )
    }

    private fun projectException(
        exception: WbxmlElement?,
        fields: MutableList<DiagnosticCalendarFieldEntry>,
        profileEmail: String,
        attendeeIndex: Int?,
        currentUserAttendeeCount: Int?,
    ) {
        val identity =
            exception?.child(Calendar.EXCEPTION_START_TIME)
                ?: exception?.child(AirSyncBase.INSTANCE_ID)
        fields.addScalar(identity, DiagnosticCalendarFieldSource.EXCEPTION, DiagnosticCalendarField.EXCEPTION_INSTANCE)
        fields.addScalar(
            exception?.child(Calendar.DELETED),
            DiagnosticCalendarFieldSource.EXCEPTION,
            DiagnosticCalendarField.EXCEPTION_DELETED,
        )
        fields.addScalar(
            exception?.child(Calendar.SUBJECT),
            DiagnosticCalendarFieldSource.EXCEPTION,
            DiagnosticCalendarField.SUBJECT,
        )
        fields.addBody(exception, DiagnosticCalendarFieldSource.EXCEPTION)
        fields.addLocation(exception, DiagnosticCalendarFieldSource.EXCEPTION)
        fields.addScalar(exception?.child(Calendar.START_TIME), DiagnosticCalendarFieldSource.EXCEPTION, DiagnosticCalendarField.START)
        fields.addScalar(exception?.child(Calendar.END_TIME), DiagnosticCalendarFieldSource.EXCEPTION, DiagnosticCalendarField.END)
        fields.addScalar(
            exception?.child(Calendar.ALL_DAY_EVENT),
            DiagnosticCalendarFieldSource.EXCEPTION,
            DiagnosticCalendarField.ALL_DAY,
        )
        fields.addScalar(
            exception?.child(Calendar.REMINDER),
            DiagnosticCalendarFieldSource.EXCEPTION,
            DiagnosticCalendarField.REMINDER_MINUTES,
        )
        fields.addAttendees(
            exception?.child(Calendar.ATTENDEES),
            DiagnosticCalendarFieldSource.EXCEPTION,
            profileEmail,
            attendeeIndex,
            currentUserAttendeeCount,
        )
        fields.addMeetingFields(exception, DiagnosticCalendarFieldSource.EXCEPTION)
    }

    private fun MutableList<DiagnosticCalendarFieldEntry>.addBody(
        parent: WbxmlElement?,
        source: DiagnosticCalendarFieldSource,
    ) {
        val modern = parent?.child(AirSyncBase.BODY)
        if (modern == null) {
            addScalar(parent?.child(Calendar.BODY), source, DiagnosticCalendarField.BODY)
        } else {
            addScalar(modern.child(AirSyncBase.DATA), source, DiagnosticCalendarField.BODY, presentContainer = true)
        }
    }

    private fun MutableList<DiagnosticCalendarFieldEntry>.addLocation(
        parent: WbxmlElement?,
        source: DiagnosticCalendarFieldSource,
    ) {
        val modern = parent?.child(AirSyncBase.LOCATION)
        if (modern == null) {
            addScalar(parent?.child(Calendar.LOCATION), source, DiagnosticCalendarField.LOCATION)
        } else {
            addScalar(
                modern.child(AirSyncBase.DISPLAY_NAME),
                source,
                DiagnosticCalendarField.LOCATION,
                presentContainer = true,
            )
        }
    }

    private fun MutableList<DiagnosticCalendarFieldEntry>.addRecurrence(
        recurrence: WbxmlElement?,
        source: DiagnosticCalendarFieldSource,
    ) {
        addScalar(recurrence?.child(Calendar.TYPE), source, DiagnosticCalendarField.RECURRENCE_TYPE)
        addScalar(recurrence?.child(Calendar.INTERVAL), source, DiagnosticCalendarField.RECURRENCE_INTERVAL)
        addScalar(
            recurrence?.child(Calendar.DAY_OF_WEEK),
            source,
            DiagnosticCalendarField.RECURRENCE_DAY_OF_WEEK_MASK,
        )
        addScalar(recurrence?.child(Calendar.DAY_OF_MONTH), source, DiagnosticCalendarField.RECURRENCE_DAY_OF_MONTH)
        addScalar(recurrence?.child(Calendar.WEEK_OF_MONTH), source, DiagnosticCalendarField.RECURRENCE_WEEK_OF_MONTH)
        addScalar(recurrence?.child(Calendar.MONTH_OF_YEAR), source, DiagnosticCalendarField.RECURRENCE_MONTH_OF_YEAR)
        addScalar(
            recurrence?.child(Calendar.FIRST_DAY_OF_WEEK),
            source,
            DiagnosticCalendarField.RECURRENCE_FIRST_DAY_OF_WEEK,
        )
        addScalar(recurrence?.child(Calendar.CALENDAR_TYPE), source, DiagnosticCalendarField.RECURRENCE_CALENDAR_TYPE)
        addScalar(recurrence?.child(Calendar.IS_LEAP_MONTH), source, DiagnosticCalendarField.RECURRENCE_IS_LEAP_MONTH)
        addScalar(recurrence?.child(Calendar.OCCURRENCES), source, DiagnosticCalendarField.RECURRENCE_OCCURRENCES)
        addScalar(recurrence?.child(Calendar.UNTIL), source, DiagnosticCalendarField.RECURRENCE_UNTIL)
    }

    private fun MutableList<DiagnosticCalendarFieldEntry>.addMeetingFields(
        parent: WbxmlElement?,
        source: DiagnosticCalendarFieldSource,
    ) {
        addScalar(parent?.child(Calendar.MEETING_STATUS), source, DiagnosticCalendarField.MEETING_STATUS_RAW)
        addScalar(parent?.child(Calendar.RESPONSE_TYPE), source, DiagnosticCalendarField.RESPONSE_TYPE)
        addScalar(parent?.child(Calendar.RESPONSE_REQUESTED), source, DiagnosticCalendarField.RESPONSE_REQUESTED)
        addScalar(parent?.child(Calendar.BUSY_STATUS), source, DiagnosticCalendarField.AVAILABILITY)
        addScalar(parent?.child(Calendar.SENSITIVITY), source, DiagnosticCalendarField.SENSITIVITY)
    }

    private fun MutableList<DiagnosticCalendarFieldEntry>.addAttendees(
        container: WbxmlElement?,
        source: DiagnosticCalendarFieldSource,
        profileEmail: String,
        attendeeIndex: Int?,
        currentUserAttendeeCount: Int?,
    ) {
        val attendees = container?.children(Calendar.ATTENDEE).orEmpty()
        add(
            DiagnosticCalendarFieldEntry(
                source = source,
                field = DiagnosticCalendarField.ATTENDEES,
                state =
                    when {
                        container == null -> DiagnosticFieldState.ABSENT
                        attendees.isEmpty() -> DiagnosticFieldState.EMPTY
                        else -> DiagnosticFieldState.PRESENT
                    },
            ),
        )
        add(
            DiagnosticCalendarFieldEntry(
                source = source,
                field = DiagnosticCalendarField.ATTENDEE_COUNT,
                state = if (container == null) DiagnosticFieldState.ABSENT else DiagnosticFieldState.PRESENT,
                value = container?.let { DiagnosticFieldValue.Count(attendees.size) },
            ),
        )
        attendeeIndex?.let { index ->
            val attendee = attendees.getOrNull(index)
            addScalar(attendee?.child(Calendar.EMAIL), source, DiagnosticCalendarField.ATTENDEE_EMAIL)
            addScalar(attendee?.child(Calendar.NAME), source, DiagnosticCalendarField.ATTENDEE_NAME)
            addScalar(attendee?.child(Calendar.ATTENDEE_STATUS), source, DiagnosticCalendarField.ATTENDEE_STATUS)
            addScalar(attendee?.child(Calendar.ATTENDEE_TYPE), source, DiagnosticCalendarField.ATTENDEE_TYPE)
        }
        val matchingCount =
            currentUserAttendeeCount
                ?: container?.let {
                    attendees.count { attendee ->
                        attendee.child(Calendar.EMAIL)?.text.equals(profileEmail, ignoreCase = true)
                    }
                }
        add(
            DiagnosticCalendarFieldEntry(
                source = DiagnosticCalendarFieldSource.DERIVED,
                field = DiagnosticCalendarField.CURRENT_USER_ATTENDEE_COUNT,
                state = if (matchingCount == null) DiagnosticFieldState.ABSENT else DiagnosticFieldState.PRESENT,
                value = matchingCount?.let(DiagnosticFieldValue::Count),
            ),
        )
    }

    private fun MutableList<DiagnosticCalendarFieldEntry>.addExceptionCount(container: WbxmlElement?) {
        val exceptions = container?.children(Calendar.EXCEPTION).orEmpty()
        add(
            DiagnosticCalendarFieldEntry(
                source = DiagnosticCalendarFieldSource.RESPONSE,
                field = DiagnosticCalendarField.EXCEPTION_COUNT,
                state = if (container == null) DiagnosticFieldState.ABSENT else DiagnosticFieldState.PRESENT,
                value = container?.let { DiagnosticFieldValue.Count(exceptions.size) },
            ),
        )
    }

    private fun MutableList<DiagnosticCalendarFieldEntry>.addScalar(
        element: WbxmlElement?,
        source: DiagnosticCalendarFieldSource,
        field: DiagnosticCalendarField,
        presentContainer: Boolean = false,
    ) {
        val state =
            when {
                element == null && !presentContainer -> DiagnosticFieldState.ABSENT
                element?.text.isNullOrEmpty() -> DiagnosticFieldState.EMPTY
                else -> DiagnosticFieldState.PRESENT
            }
        val value =
            element
                ?.text
                ?.takeIf { text -> text.isNotEmpty() && field.policy == DiagnosticCalendarFieldPolicy.FULL_VALUE }
                ?.let(DiagnosticTextSanitizer::sanitize)
                ?.let(DiagnosticFieldValue::Text)
        add(DiagnosticCalendarFieldEntry(source, field, state, value))
    }

    private fun RawCalendarCommandKind.toDiagnosticCommandKind(): DiagnosticCalendarCommandKind =
        when (this) {
            RawCalendarCommandKind.ADD -> DiagnosticCalendarCommandKind.ADD
            RawCalendarCommandKind.CHANGE -> DiagnosticCalendarCommandKind.CHANGE
            RawCalendarCommandKind.DELETE,
            RawCalendarCommandKind.SOFT_DELETE,
            -> error("Delete commands have no calendar application-data snapshot")
        }

    private fun ActiveSyncValidationReason.toDiagnosticCalendarRule(): DiagnosticCalendarRule =
        when (this) {
            ActiveSyncValidationReason.MALFORMED_WBXML -> DiagnosticCalendarRule.MALFORMED_WBXML
            ActiveSyncValidationReason.UNEXPECTED_ROOT -> DiagnosticCalendarRule.UNEXPECTED_ROOT
            ActiveSyncValidationReason.MISSING_REQUIRED_VALUE -> DiagnosticCalendarRule.MISSING_REQUIRED_VALUE
            ActiveSyncValidationReason.EMPTY_VALUE -> DiagnosticCalendarRule.EMPTY_VALUE
            ActiveSyncValidationReason.INVALID_STATUS -> DiagnosticCalendarRule.INVALID_STATUS
            ActiveSyncValidationReason.INVALID_NUMBER -> DiagnosticCalendarRule.INVALID_NUMBER
            ActiveSyncValidationReason.COUNT_MISMATCH -> DiagnosticCalendarRule.COUNT_MISMATCH
            ActiveSyncValidationReason.UNSUPPORTED_COMMAND -> DiagnosticCalendarRule.UNSUPPORTED_COMMAND
            ActiveSyncValidationReason.UNKNOWN_FOLDER -> DiagnosticCalendarRule.UNKNOWN_FOLDER
            ActiveSyncValidationReason.COLLECTION_MISMATCH -> DiagnosticCalendarRule.COLLECTION_MISMATCH
            ActiveSyncValidationReason.MISSING_APPLICATION_DATA -> DiagnosticCalendarRule.MISSING_APPLICATION_DATA
            ActiveSyncValidationReason.INVALID_APPLICATION_DATA -> DiagnosticCalendarRule.INVALID_APPLICATION_DATA
            ActiveSyncValidationReason.MISSING_START -> DiagnosticCalendarRule.MISSING_START
            ActiveSyncValidationReason.MISSING_END -> DiagnosticCalendarRule.MISSING_END
            ActiveSyncValidationReason.INVALID_TIME_RANGE -> DiagnosticCalendarRule.INVALID_TIME_RANGE
            ActiveSyncValidationReason.INVALID_ALL_DAY -> DiagnosticCalendarRule.INVALID_ALL_DAY
            ActiveSyncValidationReason.INVALID_RECURRENCE -> DiagnosticCalendarRule.INVALID_RECURRENCE
            ActiveSyncValidationReason.INVALID_ATTENDEE -> DiagnosticCalendarRule.INVALID_ATTENDEE
            ActiveSyncValidationReason.INVALID_MEETING_RESPONSE -> DiagnosticCalendarRule.INVALID_MEETING_RESPONSE
            ActiveSyncValidationReason.INVALID_TIME_ZONE -> DiagnosticCalendarRule.INVALID_TIME_ZONE
            ActiveSyncValidationReason.INVALID_VALUE -> DiagnosticCalendarRule.INVALID_VALUE
            ActiveSyncValidationReason.NON_ADVANCING_SYNC_KEY -> DiagnosticCalendarRule.NON_ADVANCING_SYNC_KEY
            ActiveSyncValidationReason.INVALID_PRIMING_RESPONSE -> DiagnosticCalendarRule.INVALID_PRIMING_RESPONSE
            ActiveSyncValidationReason.PROTOCOL_STRUCTURE -> DiagnosticCalendarRule.PROTOCOL_STRUCTURE
        }
}
