package net.mixalich7b.exchangesync.infrastructure.diagnostics

import java.time.Instant

internal enum class DiagnosticCalendarCommandKind {
    ADD,
    CHANGE,
}

internal enum class DiagnosticCalendarRule {
    MEETING_RESPONSE_EMPTY,
    RECEIVED_MEETING_RESPONSE_MISSING,
    RECEIVED_MEETING_RESPONSE_EMPTY,
    EVENT_TIME_RANGE_INVALID,
    EVENT_ALL_DAY_NOT_UTC_ALIGNED,
    EXCEPTION_TIME_RANGE_INVALID,
    RECURRENCE_INTERVAL_INVALID,
    RECURRENCE_COUNT_INVALID,
    RECURRENCE_WEEKDAYS_MISSING,
    RECURRENCE_WEEKDAY_MASK_INVALID,
    RECURRENCE_FIRST_WEEKDAY_INVALID,
    RECURRENCE_WEEK_POSITION_INVALID,
    RECURRENCE_DAY_OF_MONTH_INVALID,
    RECURRENCE_MONTH_OF_YEAR_INVALID,
    MALFORMED_WBXML,
    UNEXPECTED_ROOT,
    MISSING_REQUIRED_VALUE,
    EMPTY_VALUE,
    INVALID_STATUS,
    INVALID_NUMBER,
    COUNT_MISMATCH,
    UNSUPPORTED_COMMAND,
    UNKNOWN_FOLDER,
    COLLECTION_MISMATCH,
    MISSING_APPLICATION_DATA,
    INVALID_APPLICATION_DATA,
    MISSING_START,
    MISSING_END,
    INVALID_TIME_RANGE,
    INVALID_ALL_DAY,
    INVALID_RECURRENCE,
    INVALID_ATTENDEE,
    INVALID_MEETING_RESPONSE,
    INVALID_TIME_ZONE,
    INVALID_VALUE,
    NON_ADVANCING_SYNC_KEY,
    INVALID_PRIMING_RESPONSE,
    PROTOCOL_STRUCTURE,
    OWNED_CALENDAR_SCOPE,
    DUPLICATE_SERVER_ID,
    UNSUPPORTED_CALENDAR_CHANGE,
    CALENDAR_EVENT_MAPPING,
    PROVIDER_BATCH_SCOPE,
    PROVIDER_REQUIRED_VALUE_NULL,
    REFRESH_EXCEPTIONS_NEW_SERIES,
    ADDITION_TIME_RANGE_MISSING,
    RECURRING_DURATION_MISSING,
    RECURRING_TIME_ZONE_UNREPRESENTABLE,
    TIME_ZONE_UNREPRESENTABLE,
    SUB_BATCH_RESULT_MISSING,
    SUB_BATCH_NOT_PENDING,
    SUB_BATCH_RESULT_COUNT_INVALID,
    INSERT_RESULTS_INVALID,
    ROW_REFERENCE_INVALID,
    FORWARD_INSERT_REFERENCE,
    NON_INSERT_REFERENCE,
    INSERT_RESULT_MISSING,
}

internal sealed interface DiagnosticCalendarPath {
    data object Event : DiagnosticCalendarPath

    data class Exception(val index: Int) : DiagnosticCalendarPath
}

internal enum class DiagnosticCalendarFieldSource {
    RESPONSE,
    PRIOR,
    EFFECTIVE,
    EXCEPTION,
    DERIVED,
}

internal enum class DiagnosticCalendarFieldPolicy {
    FULL_VALUE,
    STRUCTURAL_ONLY,
}

internal enum class DiagnosticCalendarField(
    val policy: DiagnosticCalendarFieldPolicy,
) {
    UID(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    SUBJECT(DiagnosticCalendarFieldPolicy.STRUCTURAL_ONLY),
    BODY(DiagnosticCalendarFieldPolicy.STRUCTURAL_ONLY),
    LOCATION(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    START(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    END(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    ALL_DAY(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    TIME_ZONE_RAW(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    TIME_ZONE_BIAS_MINUTES(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    TIME_ZONE_STANDARD_NAME(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    TIME_ZONE_STANDARD_TRANSITION(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    TIME_ZONE_STANDARD_BIAS_MINUTES(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    TIME_ZONE_DAYLIGHT_NAME(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    TIME_ZONE_DAYLIGHT_TRANSITION(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    TIME_ZONE_DAYLIGHT_BIAS_MINUTES(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    RECURRENCE_TYPE(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    RECURRENCE_INTERVAL(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    RECURRENCE_DAY_OF_WEEK_MASK(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    RECURRENCE_DAY_OF_MONTH(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    RECURRENCE_WEEK_OF_MONTH(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    RECURRENCE_MONTH_OF_YEAR(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    RECURRENCE_FIRST_DAY_OF_WEEK(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    RECURRENCE_CALENDAR_TYPE(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    RECURRENCE_IS_LEAP_MONTH(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    RECURRENCE_END_KIND(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    RECURRENCE_OCCURRENCES(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    RECURRENCE_UNTIL(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    RECURRENCE_RULE(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    EXCEPTION_COUNT(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    EXCEPTION_INSTANCE(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    EXCEPTION_DELETED(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    ORGANIZER_EMAIL(DiagnosticCalendarFieldPolicy.STRUCTURAL_ONLY),
    ORGANIZER_NAME(DiagnosticCalendarFieldPolicy.STRUCTURAL_ONLY),
    ATTENDEES(DiagnosticCalendarFieldPolicy.STRUCTURAL_ONLY),
    ATTENDEE_EMAIL(DiagnosticCalendarFieldPolicy.STRUCTURAL_ONLY),
    ATTENDEE_NAME(DiagnosticCalendarFieldPolicy.STRUCTURAL_ONLY),
    ATTENDEE_STATUS(DiagnosticCalendarFieldPolicy.STRUCTURAL_ONLY),
    ATTENDEE_TYPE(DiagnosticCalendarFieldPolicy.STRUCTURAL_ONLY),
    ATTENDEE_COUNT(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    CURRENT_USER_ATTENDEE_COUNT(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    MEETING_STATUS_RAW(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    MEETING_IS_MEETING(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    MEETING_IS_RECEIVED(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    MEETING_IS_CANCELLED(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    MEETING_IS_FORWARDED(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    RESPONSE_TYPE(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    RESPONSE_REQUESTED(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    AVAILABILITY(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    SENSITIVITY(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    REMINDER_MINUTES(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    PROVIDER_SYNC_ID(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    PROVIDER_TIME_ZONE(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    DURATION_MILLIS(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    STATUS(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    PROVIDER_AVAILABILITY(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    SELF_STATUS(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    EVENT_COLOR(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    ACCESS_LEVEL(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    HAS_ALARM(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    HAS_ATTENDEE_DATA(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    ORIGINAL_SYNC_ID(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    ORIGINAL_INSTANCE_TIME(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    ORIGINAL_ALL_DAY(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    EXCEPTION_RESPONSE_OVERRIDE(DiagnosticCalendarFieldPolicy.FULL_VALUE),
    TIME_RELATIONSHIP(DiagnosticCalendarFieldPolicy.FULL_VALUE),
}

internal enum class DiagnosticFieldState {
    ABSENT,
    EMPTY,
    PRESENT,
}

internal enum class DiagnosticRelationship {
    BEFORE,
    EQUAL,
    AFTER,
    NOT_COMPARABLE,
}

internal sealed interface DiagnosticFieldValue {
    data class Text(val value: String) : DiagnosticFieldValue

    data class IntegerValue(val value: Long) : DiagnosticFieldValue {
        constructor(value: Int) : this(value.toLong())
    }

    data class BooleanValue(val value: Boolean) : DiagnosticFieldValue

    data class Timestamp(val value: Instant) : DiagnosticFieldValue

    data class EnumName(val value: String) : DiagnosticFieldValue

    data class Count(val value: Int) : DiagnosticFieldValue

    data class Relationship(val value: DiagnosticRelationship) : DiagnosticFieldValue

    data class TypeName(val value: String) : DiagnosticFieldValue
}

internal data class DiagnosticCalendarFieldEntry(
    val source: DiagnosticCalendarFieldSource,
    val field: DiagnosticCalendarField,
    val state: DiagnosticFieldState,
    val value: DiagnosticFieldValue? = null,
)

internal data class DiagnosticCalendarFailureSnapshot(
    val commandKind: DiagnosticCalendarCommandKind? = null,
    val serverId: String? = null,
    val rule: DiagnosticCalendarRule,
    val path: DiagnosticCalendarPath = DiagnosticCalendarPath.Event,
    val failedField: DiagnosticCalendarField? = null,
    val attendeeIndex: Int? = null,
    val fields: List<DiagnosticCalendarFieldEntry>,
)

internal enum class DiagnosticProviderOperationKind {
    EVENT_INSERT,
    EVENT_UPDATE,
    EVENT_DELETE,
    ATTENDEES_DELETE,
    ORGANIZER_DELETE,
    ATTENDEE_INSERT,
    REMINDERS_DELETE,
    REMINDER_INSERT,
    EXCEPTIONS_DELETE,
    EXCEPTION_INSERT,
    EXCEPTION_RESPONSE_UPDATE,
}

internal enum class DiagnosticProviderTarget {
    EVENT,
    ATTENDEE,
    ORGANIZER,
    REMINDER,
    EXCEPTION,
}

internal sealed interface DiagnosticProviderReference {
    data class Existing(val rowId: Long) : DiagnosticProviderReference

    data class BackReference(val operationIndex: Int) : DiagnosticProviderReference

    data class SyncId(val value: String) : DiagnosticProviderReference
}

internal enum class DiagnosticProviderColumnPolicy {
    FULL_VALUE,
    STRUCTURAL_ONLY,
}

internal enum class DiagnosticProviderColumn(
    val wireName: String,
    val policy: DiagnosticProviderColumnPolicy,
) {
    SYNC_ID("_sync_id", DiagnosticProviderColumnPolicy.FULL_VALUE),
    DIRTY("dirty", DiagnosticProviderColumnPolicy.FULL_VALUE),
    UID("uid2445", DiagnosticProviderColumnPolicy.FULL_VALUE),
    TITLE("title", DiagnosticProviderColumnPolicy.STRUCTURAL_ONLY),
    DESCRIPTION("description", DiagnosticProviderColumnPolicy.STRUCTURAL_ONLY),
    LOCATION("eventLocation", DiagnosticProviderColumnPolicy.FULL_VALUE),
    START("dtstart", DiagnosticProviderColumnPolicy.FULL_VALUE),
    END("dtend", DiagnosticProviderColumnPolicy.FULL_VALUE),
    DURATION("duration", DiagnosticProviderColumnPolicy.FULL_VALUE),
    ALL_DAY("allDay", DiagnosticProviderColumnPolicy.FULL_VALUE),
    EVENT_TIME_ZONE("eventTimezone", DiagnosticProviderColumnPolicy.FULL_VALUE),
    EVENT_END_TIME_ZONE("eventEndTimezone", DiagnosticProviderColumnPolicy.FULL_VALUE),
    RECURRENCE_RULE("rrule", DiagnosticProviderColumnPolicy.FULL_VALUE),
    ORGANIZER_EMAIL("organizer", DiagnosticProviderColumnPolicy.STRUCTURAL_ONLY),
    IS_ORGANIZER("isOrganizer", DiagnosticProviderColumnPolicy.STRUCTURAL_ONLY),
    STATUS("eventStatus", DiagnosticProviderColumnPolicy.FULL_VALUE),
    AVAILABILITY("availability", DiagnosticProviderColumnPolicy.FULL_VALUE),
    SELF_ATTENDEE_STATUS("selfAttendeeStatus", DiagnosticProviderColumnPolicy.FULL_VALUE),
    EVENT_COLOR("eventColor", DiagnosticProviderColumnPolicy.FULL_VALUE),
    ACCESS_LEVEL("accessLevel", DiagnosticProviderColumnPolicy.FULL_VALUE),
    HAS_ALARM("hasAlarm", DiagnosticProviderColumnPolicy.FULL_VALUE),
    HAS_ATTENDEE_DATA("hasAttendeeData", DiagnosticProviderColumnPolicy.FULL_VALUE),
    RESPONSE_TYPE("sync_data2", DiagnosticProviderColumnPolicy.FULL_VALUE),
    MEETING_STATUS("sync_data3", DiagnosticProviderColumnPolicy.FULL_VALUE),
    RESPONSE_REQUESTED("sync_data4", DiagnosticProviderColumnPolicy.FULL_VALUE),
    SERVER_AVAILABILITY("sync_data5", DiagnosticProviderColumnPolicy.FULL_VALUE),
    EXCEPTION_RESPONSE_OVERRIDE("sync_data6", DiagnosticProviderColumnPolicy.FULL_VALUE),
    EXCEPTION_DELETED("sync_data7", DiagnosticProviderColumnPolicy.FULL_VALUE),
    ATTENDEE_EMAIL("attendeeEmail", DiagnosticProviderColumnPolicy.STRUCTURAL_ONLY),
    ATTENDEE_NAME("attendeeName", DiagnosticProviderColumnPolicy.STRUCTURAL_ONLY),
    ATTENDEE_RELATIONSHIP("attendeeRelationship", DiagnosticProviderColumnPolicy.STRUCTURAL_ONLY),
    ATTENDEE_TYPE("attendeeType", DiagnosticProviderColumnPolicy.STRUCTURAL_ONLY),
    ATTENDEE_STATUS("attendeeStatus", DiagnosticProviderColumnPolicy.STRUCTURAL_ONLY),
    REMINDER_MINUTES("minutes", DiagnosticProviderColumnPolicy.FULL_VALUE),
    REMINDER_METHOD("method", DiagnosticProviderColumnPolicy.FULL_VALUE),
    ORIGINAL_SYNC_ID("original_sync_id", DiagnosticProviderColumnPolicy.FULL_VALUE),
    ORIGINAL_INSTANCE_TIME("originalInstanceTime", DiagnosticProviderColumnPolicy.FULL_VALUE),
    ORIGINAL_ALL_DAY("originalAllDay", DiagnosticProviderColumnPolicy.FULL_VALUE),
    UNKNOWN("<unknown>", DiagnosticProviderColumnPolicy.STRUCTURAL_ONLY),
}

internal data class DiagnosticProviderColumnEntry(
    val column: DiagnosticProviderColumn,
    val state: DiagnosticFieldState,
    val value: DiagnosticFieldValue? = null,
)

internal data class DiagnosticProviderOperationSnapshot(
    val globalOperationIndex: Int,
    val subBatchOperationIndex: Int,
    val operationKind: DiagnosticProviderOperationKind,
    val target: DiagnosticProviderTarget,
    val calendarId: Long,
    val reference: DiagnosticProviderReference? = null,
    val columns: List<DiagnosticProviderColumnEntry> = emptyList(),
)
