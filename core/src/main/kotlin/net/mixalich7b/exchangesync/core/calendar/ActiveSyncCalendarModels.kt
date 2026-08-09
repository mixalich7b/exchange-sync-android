package net.mixalich7b.exchangesync.core.calendar

import java.time.Instant
import net.mixalich7b.exchangesync.core.sync.CalendarChange

public sealed interface ActiveSyncField<out T> {
    public data object Absent : ActiveSyncField<Nothing>

    public data object Empty : ActiveSyncField<Nothing>

    public data class Value<T>(public val value: T) : ActiveSyncField<T>
}

public enum class ActiveSyncResponseType(public val wireValue: Int) {
    NONE(0),
    ORGANIZER(1),
    TENTATIVE(2),
    ACCEPTED(3),
    DECLINED(4),
    NOT_RESPONDED(5),
}

public enum class ActiveSyncAttendeeStatus(public val wireValue: Int) {
    NONE(0),
    TENTATIVE(2),
    ACCEPTED(3),
    DECLINED(4),
    NOT_RESPONDED(5),
}

public enum class ActiveSyncAttendeeType(public val wireValue: Int) {
    REQUIRED(1),
    OPTIONAL(2),
    RESOURCE(3),
}

public enum class ActiveSyncAvailability(public val wireValue: Int) {
    FREE(0),
    TENTATIVE(1),
    BUSY(2),
    OUT_OF_OFFICE(3),
    WORKING_ELSEWHERE(4),
}

public enum class ActiveSyncSensitivity(public val wireValue: Int) {
    NORMAL(0),
    PERSONAL(1),
    PRIVATE(2),
    CONFIDENTIAL(3),
}

public data class ActiveSyncMeetingStatus(
    public val rawValue: Int,
    public val isMeeting: Boolean,
    public val isReceived: Boolean,
    public val isCancelled: Boolean,
    public val isForwarded: Boolean = false,
)

public data class ActiveSyncAttendee(
    public val email: String,
    public val name: String?,
    public val status: ActiveSyncAttendeeStatus?,
    public val type: ActiveSyncAttendeeType?,
)

public enum class ActiveSyncRecurrenceType(public val wireValue: Int) {
    DAILY(0),
    WEEKLY(1),
    MONTHLY(2),
    MONTHLY_NTH(3),
    YEARLY(5),
    YEARLY_NTH(6),
}

public sealed interface ActiveSyncRecurrenceEnd {
    public data object Infinite : ActiveSyncRecurrenceEnd

    public data class Count(public val occurrences: Int) : ActiveSyncRecurrenceEnd

    public data class Until(public val instant: Instant) : ActiveSyncRecurrenceEnd
}

public data class ActiveSyncRecurrence(
    public val type: ActiveSyncRecurrenceType,
    public val interval: Int,
    public val dayOfWeekMask: Int? = null,
    public val dayOfMonth: Int? = null,
    public val weekOfMonth: Int? = null,
    public val monthOfYear: Int? = null,
    public val firstDayOfWeek: Int? = null,
    public val end: ActiveSyncRecurrenceEnd,
)

public data class ActiveSyncSystemTime(
    public val year: Int,
    public val month: Int,
    public val dayOfWeek: Int,
    public val day: Int,
    public val hour: Int,
    public val minute: Int,
    public val second: Int,
    public val milliseconds: Int,
)

public data class ActiveSyncTimeZone(
    public val biasMinutes: Int,
    public val standardName: String,
    public val standardTransition: ActiveSyncSystemTime,
    public val standardBiasMinutes: Int,
    public val daylightName: String,
    public val daylightTransition: ActiveSyncSystemTime,
    public val daylightBiasMinutes: Int,
)

public data class ActiveSyncCalendarException(
    public val instanceStart: Instant,
    public val deleted: Boolean,
    public val subject: ActiveSyncField<String> = ActiveSyncField.Absent,
    public val body: ActiveSyncField<String> = ActiveSyncField.Absent,
    public val location: ActiveSyncField<String> = ActiveSyncField.Absent,
    public val start: ActiveSyncField<Instant> = ActiveSyncField.Absent,
    public val end: ActiveSyncField<Instant> = ActiveSyncField.Absent,
    public val allDay: ActiveSyncField<Boolean> = ActiveSyncField.Absent,
    public val reminderMinutes: ActiveSyncField<Int> = ActiveSyncField.Absent,
    public val attendees: ActiveSyncField<List<ActiveSyncAttendee>> = ActiveSyncField.Absent,
    public val meetingStatus: ActiveSyncField<ActiveSyncMeetingStatus> = ActiveSyncField.Absent,
    public val responseType: ActiveSyncField<ActiveSyncResponseType> = ActiveSyncField.Absent,
    public val responseRequested: ActiveSyncField<Boolean> = ActiveSyncField.Absent,
    public val availability: ActiveSyncField<ActiveSyncAvailability> = ActiveSyncField.Absent,
    public val sensitivity: ActiveSyncField<ActiveSyncSensitivity> = ActiveSyncField.Absent,
)

public data class ActiveSyncCalendarItem(
    public val serverId: String,
    public val uid: ActiveSyncField<String> = ActiveSyncField.Absent,
    public val subject: ActiveSyncField<String> = ActiveSyncField.Absent,
    public val body: ActiveSyncField<String> = ActiveSyncField.Absent,
    public val location: ActiveSyncField<String> = ActiveSyncField.Absent,
    public val start: ActiveSyncField<Instant> = ActiveSyncField.Absent,
    public val end: ActiveSyncField<Instant> = ActiveSyncField.Absent,
    public val allDay: ActiveSyncField<Boolean> = ActiveSyncField.Absent,
    public val timeZone: ActiveSyncField<ActiveSyncTimeZone> = ActiveSyncField.Absent,
    public val recurrence: ActiveSyncField<ActiveSyncRecurrence> = ActiveSyncField.Absent,
    public val exceptions: ActiveSyncField<List<ActiveSyncCalendarException>> = ActiveSyncField.Absent,
    public val organizerEmail: ActiveSyncField<String> = ActiveSyncField.Absent,
    public val organizerName: ActiveSyncField<String> = ActiveSyncField.Absent,
    public val attendees: ActiveSyncField<List<ActiveSyncAttendee>> = ActiveSyncField.Absent,
    public val meetingStatus: ActiveSyncField<ActiveSyncMeetingStatus> = ActiveSyncField.Absent,
    public val responseType: ActiveSyncField<ActiveSyncResponseType> = ActiveSyncField.Absent,
    public val responseRequested: ActiveSyncField<Boolean> = ActiveSyncField.Absent,
    public val availability: ActiveSyncField<ActiveSyncAvailability> = ActiveSyncField.Absent,
    public val sensitivity: ActiveSyncField<ActiveSyncSensitivity> = ActiveSyncField.Absent,
    public val reminderMinutes: ActiveSyncField<Int> = ActiveSyncField.Absent,
)

public sealed interface ActiveSyncCalendarMutation : CalendarChange {
    public data class Upsert(
        public val item: ActiveSyncCalendarItem,
        public val isAddition: Boolean,
    ) : ActiveSyncCalendarMutation

    public data class Delete(
        public val serverId: String,
        public val soft: Boolean,
    ) : ActiveSyncCalendarMutation
}
