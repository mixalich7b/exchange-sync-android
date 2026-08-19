package net.mixalich7b.exchangesync.core.calendar

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

public enum class CalendarMappingRule {
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
}

public sealed interface CalendarMappingPath {
    public data object Event : CalendarMappingPath

    public data class Exception(public val index: Int) : CalendarMappingPath
}

public class CalendarMappingException(
    public val rule: CalendarMappingRule,
    message: String,
    public val path: CalendarMappingPath = CalendarMappingPath.Event,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

public enum class ProviderEventStatus {
    TENTATIVE,
    CONFIRMED,
    CANCELLED,
}

public enum class ProviderAvailability {
    FREE,
    TENTATIVE,
    BUSY,
    OUT_OF_OFFICE,
    WORKING_ELSEWHERE,
}

public enum class ProviderSelfStatus {
    INVITED,
    TENTATIVE,
    ACCEPTED,
    DECLINED,
    ORGANIZER,
}

public enum class ProviderAccessLevel {
    PUBLIC,
    PRIVATE,
    CONFIDENTIAL,
}

public enum class ProviderAttendeeRole {
    UNSPECIFIED,
    REQUIRED,
    OPTIONAL,
    RESOURCE,
}

public enum class ProviderAttendeeStatus {
    NONE,
    INVITED,
    TENTATIVE,
    ACCEPTED,
    DECLINED,
}

public data class ProviderAttendee(
    public val email: String,
    public val name: String?,
    public val role: ProviderAttendeeRole,
    public val status: ProviderAttendeeStatus,
)

public data class ProviderCalendarException(
    public val originalInstance: Instant,
    public val deleted: Boolean,
    public val title: ActiveSyncField<String>,
    public val description: ActiveSyncField<String>,
    public val location: ActiveSyncField<String>,
    public val start: ActiveSyncField<Instant>,
    public val end: ActiveSyncField<Instant>,
    public val allDay: ActiveSyncField<Boolean> = ActiveSyncField.Absent,
    public val reminderMinutes: ActiveSyncField<Int>,
    public val attendees: ActiveSyncField<List<ProviderAttendee>>,
    public val meetingStatus: ActiveSyncField<ActiveSyncMeetingStatus>,
    public val responseType: ActiveSyncField<ActiveSyncResponseType>,
    public val responseRequested: ActiveSyncField<Boolean>,
    public val serverAvailability: ActiveSyncField<ActiveSyncAvailability>,
    public val accessLevel: ActiveSyncField<ProviderAccessLevel>,
    public val status: ActiveSyncField<ProviderEventStatus>,
    public val availability: ActiveSyncField<ProviderAvailability>,
    public val selfStatus: ActiveSyncField<ProviderSelfStatus>,
    public val eventColor: ActiveSyncField<Int>,
    public val responseTypeOverride: ActiveSyncField<ActiveSyncResponseType> = ActiveSyncField.Absent,
)

public data class ProviderEvent(
    public val syncId: String,
    public val uid: ActiveSyncField<String>,
    public val title: ActiveSyncField<String>,
    public val description: ActiveSyncField<String>,
    public val location: ActiveSyncField<String>,
    public val start: ActiveSyncField<Instant>,
    public val end: ActiveSyncField<Instant>,
    public val allDay: ActiveSyncField<Boolean>,
    public val timeZone: ActiveSyncField<ActiveSyncTimeZone>,
    public val recurrenceRule: ActiveSyncField<String>,
    public val exceptions: ActiveSyncField<List<ProviderCalendarException>>,
    public val organizerEmail: ActiveSyncField<String>,
    public val organizerName: ActiveSyncField<String>,
    public val attendees: ActiveSyncField<List<ProviderAttendee>>,
    public val meetingStatus: ActiveSyncField<ActiveSyncMeetingStatus>,
    public val responseType: ActiveSyncField<ActiveSyncResponseType>,
    public val responseRequested: ActiveSyncField<Boolean>,
    public val serverAvailability: ActiveSyncField<ActiveSyncAvailability>,
    public val status: ActiveSyncField<ProviderEventStatus>,
    public val availability: ActiveSyncField<ProviderAvailability>,
    public val selfStatus: ActiveSyncField<ProviderSelfStatus>,
    public val eventColor: ActiveSyncField<Int>,
    public val accessLevel: ActiveSyncField<ProviderAccessLevel>,
    public val reminderMinutes: ActiveSyncField<Int>,
)

public sealed interface ProviderCalendarMutation {
    public data class Upsert(
        public val event: ProviderEvent,
        public val isAddition: Boolean,
    ) : ProviderCalendarMutation

    public data class Delete(
        public val syncId: String,
        public val soft: Boolean,
    ) : ProviderCalendarMutation
}

public object CalendarEventMapper {
    public fun map(
        mutation: ActiveSyncCalendarMutation,
        ownedCalendarColor: Int,
        previous: ProviderEvent? = null,
    ): ProviderCalendarMutation =
        when (mutation) {
            is ActiveSyncCalendarMutation.Delete ->
                ProviderCalendarMutation.Delete(mutation.serverId, mutation.soft)
            is ActiveSyncCalendarMutation.Upsert ->
                ProviderCalendarMutation.Upsert(
                    event = mapEvent(mutation.item, mutation.isAddition, ownedCalendarColor, previous),
                    isAddition = mutation.isAddition,
                )
        }

    public fun paleColor(opaqueColor: Int): Int {
        require(opaqueColor ushr ALPHA_SHIFT == OPAQUE_ALPHA) { "Calendar color must be opaque" }
        val red = blendChannel(opaqueColor ushr RED_SHIFT and CHANNEL_MASK)
        val green = blendChannel(opaqueColor ushr GREEN_SHIFT and CHANNEL_MASK)
        val blue = blendChannel(opaqueColor and CHANNEL_MASK)
        return (OPAQUE_ALPHA shl ALPHA_SHIFT) or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
    }

    private fun mapEvent(
        item: ActiveSyncCalendarItem,
        isAddition: Boolean,
        ownedCalendarColor: Int,
        previous: ProviderEvent?,
    ): ProviderEvent {
        val effectiveItem =
            item.copy(
                allDay = previous?.allDay.mergeCurrent(item.allDay),
                meetingStatus = previous?.meetingStatus.mergeCurrent(item.meetingStatus),
                responseType = previous?.responseType.mergeCurrent(item.responseType),
                responseRequested = previous?.responseRequested.mergeCurrent(item.responseRequested),
                availability = previous?.serverAvailability.mergeCurrent(item.availability),
            )
        val presentationChanged =
            isAddition ||
                item.meetingStatus != ActiveSyncField.Absent ||
                item.responseType != ActiveSyncField.Absent ||
                item.availability != ActiveSyncField.Absent
        val presentation =
            if (presentationChanged) presentation(effectiveItem, isAddition, ownedCalendarColor)
            else Presentation.absent()
        val mappedExceptions =
            if (item.exceptions != ActiveSyncField.Absent) {
                item.exceptions.mapValues { exceptions ->
                    exceptions.mapIndexed { index, exception ->
                        mapException(
                            effectiveItem,
                            exception,
                            ownedCalendarColor,
                            previous,
                            CalendarMappingPath.Exception(index),
                        )
                    }
                }
            } else if (item.responseType != ActiveSyncField.Absent) {
                previous?.exceptions?.mapValues { exceptions ->
                    exceptions.mapIndexed { index, exception ->
                        refreshInheritedExceptionResponse(
                            effectiveItem,
                            exception,
                            ownedCalendarColor,
                            CalendarMappingPath.Exception(index),
                        )
                    }
                } ?: ActiveSyncField.Absent
            } else {
                ActiveSyncField.Absent
            }
        val mapped = ProviderEvent(
            syncId = item.serverId,
            uid = item.uid,
            title = item.subject,
            description = item.body,
            location = item.location,
            start = item.start,
            end = item.end,
            allDay = item.allDay,
            timeZone = item.timeZone,
            recurrenceRule = item.recurrence.mapValues(ActiveSyncRecurrence::toRecurrenceRule),
            exceptions = mappedExceptions,
            organizerEmail = item.organizerEmail,
            organizerName = item.organizerName,
            attendees =
                item.attendees.mapValues { attendees ->
                    attendees.map { attendee -> attendee.toProviderAttendee() }
                },
            meetingStatus = item.meetingStatus,
            responseType = item.responseType,
            responseRequested = item.responseRequested,
            serverAvailability = item.availability,
            status = presentation.status,
            availability = presentation.availability,
            selfStatus = presentation.selfStatus,
            eventColor = presentation.eventColor,
            accessLevel = item.sensitivity.mapValues(ActiveSyncSensitivity::toProviderAccess),
            reminderMinutes = item.reminderMinutes,
        )
        return (previous?.mergeCurrent(mapped) ?: mapped).also(ProviderEvent::validateTimeConsistency)
    }

    private fun refreshInheritedExceptionResponse(
        series: ActiveSyncCalendarItem,
        exception: ProviderCalendarException,
        ownedCalendarColor: Int,
        path: CalendarMappingPath,
    ): ProviderCalendarException {
        if (exception.responseTypeOverride != ActiveSyncField.Absent) return exception
        val presentation =
            presentation(
                ActiveSyncCalendarItem(
                    serverId = series.serverId,
                    meetingStatus = exception.meetingStatus,
                    responseType = series.responseType,
                    availability = exception.serverAvailability,
                ),
                isAddition = true,
                ownedCalendarColor = ownedCalendarColor,
                path = path,
            )
        return exception.copy(
            responseType = series.responseType,
            status = presentation.status,
            availability = presentation.availability,
            selfStatus = presentation.selfStatus,
            eventColor = presentation.eventColor,
        )
    }

    private fun mapException(
        series: ActiveSyncCalendarItem,
        exception: ActiveSyncCalendarException,
        ownedCalendarColor: Int,
        previous: ProviderEvent?,
        path: CalendarMappingPath,
    ): ProviderCalendarException {
        val previousException =
            (previous?.exceptions as? ActiveSyncField.Value)
                ?.value
                ?.firstOrNull { prior -> prior.originalInstance == exception.instanceStart }
        val responseOverride =
            when (exception.responseType) {
                ActiveSyncField.Absent -> previousException?.responseTypeOverride ?: ActiveSyncField.Absent
                ActiveSyncField.Empty -> ActiveSyncField.Empty
                is ActiveSyncField.Value -> exception.responseType
            }
        val response =
            when (responseOverride) {
                ActiveSyncField.Absent -> series.responseType
                ActiveSyncField.Empty -> ActiveSyncField.Empty
                is ActiveSyncField.Value -> responseOverride
            }
        val meetingStatus = series.meetingStatus.mergeException(exception.meetingStatus)
        val availability = series.availability.mergeException(exception.availability)
        val exceptionItem =
            ActiveSyncCalendarItem(
                serverId = series.serverId,
                meetingStatus = meetingStatus,
                responseType = response,
                availability = availability,
            )
        val presentation = presentation(exceptionItem, isAddition = true, ownedCalendarColor, path)
        return ProviderCalendarException(
            originalInstance = exception.instanceStart,
            deleted = exception.deleted,
            title = exception.subject,
            description = exception.body,
            location = exception.location,
            start = exception.start,
            end = exception.end,
            allDay = series.allDay.mergeException(exception.allDay),
            reminderMinutes =
                previous?.reminderMinutes.mergeCurrent(series.reminderMinutes)
                    .mergeException(exception.reminderMinutes),
            attendees =
                previous?.attendees.mergeCurrent(
                    series.attendees.mapValues { attendees -> attendees.map(ActiveSyncAttendee::toProviderAttendee) },
                ).mergeException(
                    exception.attendees.mapValues { attendees -> attendees.map(ActiveSyncAttendee::toProviderAttendee) },
                ),
            meetingStatus = meetingStatus,
            responseType = response,
            responseRequested =
                previous?.responseRequested.mergeCurrent(series.responseRequested)
                    .mergeException(exception.responseRequested),
            serverAvailability = availability,
            accessLevel =
                previous?.accessLevel.mergeCurrent(
                    series.sensitivity.mapValues(ActiveSyncSensitivity::toProviderAccess),
                ).mergeException(exception.sensitivity.mapValues(ActiveSyncSensitivity::toProviderAccess)),
            status = presentation.status,
            availability = presentation.availability,
            selfStatus = presentation.selfStatus,
            eventColor = presentation.eventColor,
            responseTypeOverride = responseOverride,
        ).also { mappedException -> mappedException.validateTimeConsistency(path) }
    }

    private fun presentation(
        item: ActiveSyncCalendarItem,
        isAddition: Boolean,
        ownedCalendarColor: Int,
        path: CalendarMappingPath = CalendarMappingPath.Event,
    ): Presentation {
        val meeting = (item.meetingStatus as? ActiveSyncField.Value)?.value
        if (meeting == null) {
            when (val response = item.responseType) {
                is ActiveSyncField.Value -> return response.value.toPresentation(item.availability, ownedCalendarColor)
                ActiveSyncField.Empty ->
                    throw CalendarMappingException(
                        CalendarMappingRule.MEETING_RESPONSE_EMPTY,
                        "Meeting response is empty",
                        path,
                    )
                ActiveSyncField.Absent -> Unit
            }
            return if (isAddition) {
                Presentation(
                    ActiveSyncField.Value(ProviderEventStatus.CONFIRMED),
                    item.availability.mapValues(ActiveSyncAvailability::toProviderAvailability),
                    ActiveSyncField.Absent,
                    ActiveSyncField.Empty,
                )
            } else {
                Presentation(
                    ActiveSyncField.Absent,
                    item.availability.mapValues(ActiveSyncAvailability::toProviderAvailability),
                    ActiveSyncField.Absent,
                    ActiveSyncField.Absent,
                )
            }
        }
        if (meeting.isCancelled) {
            return Presentation(
                ActiveSyncField.Value(ProviderEventStatus.CANCELLED),
                item.availability.mapValues(ActiveSyncAvailability::toProviderAvailability),
                item.responseType.toSelfStatus(),
                ActiveSyncField.Empty,
            )
        }
        if (meeting.isMeeting && !meeting.isReceived) {
            return Presentation(
                ActiveSyncField.Value(ProviderEventStatus.CONFIRMED),
                item.availability.mapValues(ActiveSyncAvailability::toProviderAvailability),
                ActiveSyncField.Value(ProviderSelfStatus.ORGANIZER),
                ActiveSyncField.Empty,
            )
        }
        if (!meeting.isMeeting) {
            return Presentation(
                ActiveSyncField.Value(ProviderEventStatus.CONFIRMED),
                item.availability.mapValues(ActiveSyncAvailability::toProviderAvailability),
                ActiveSyncField.Absent,
                ActiveSyncField.Empty,
            )
        }
        val response =
            when (val field = item.responseType) {
                is ActiveSyncField.Value -> field.value
                ActiveSyncField.Absent -> {
                    if (isAddition) {
                        throw CalendarMappingException(
                            CalendarMappingRule.RECEIVED_MEETING_RESPONSE_MISSING,
                            "Received meeting response is missing",
                            path,
                        )
                    }
                    return Presentation.absent()
                }
                ActiveSyncField.Empty ->
                    throw CalendarMappingException(
                        CalendarMappingRule.RECEIVED_MEETING_RESPONSE_EMPTY,
                        "Received meeting response is empty",
                        path,
                    )
            }
        return response.toPresentation(item.availability, ownedCalendarColor)
    }

    private fun ActiveSyncResponseType.toPresentation(
        availability: ActiveSyncField<ActiveSyncAvailability>,
        ownedCalendarColor: Int,
    ): Presentation =
        when (this) {
            ActiveSyncResponseType.NONE,
            ActiveSyncResponseType.NOT_RESPONDED,
            ->
                Presentation(
                    ActiveSyncField.Value(ProviderEventStatus.TENTATIVE),
                    ActiveSyncField.Value(ProviderAvailability.TENTATIVE),
                    ActiveSyncField.Value(ProviderSelfStatus.INVITED),
                    ActiveSyncField.Value(paleColor(ownedCalendarColor)),
                )
            ActiveSyncResponseType.TENTATIVE ->
                Presentation(
                    ActiveSyncField.Value(ProviderEventStatus.TENTATIVE),
                    ActiveSyncField.Value(ProviderAvailability.TENTATIVE),
                    ActiveSyncField.Value(ProviderSelfStatus.TENTATIVE),
                    ActiveSyncField.Value(paleColor(ownedCalendarColor)),
                )
            ActiveSyncResponseType.ACCEPTED ->
                Presentation(
                    ActiveSyncField.Value(ProviderEventStatus.CONFIRMED),
                    availability.mapValues(ActiveSyncAvailability::toProviderAvailability),
                    ActiveSyncField.Value(ProviderSelfStatus.ACCEPTED),
                    ActiveSyncField.Empty,
                )
            ActiveSyncResponseType.ORGANIZER ->
                Presentation(
                    ActiveSyncField.Value(ProviderEventStatus.CONFIRMED),
                    availability.mapValues(ActiveSyncAvailability::toProviderAvailability),
                    ActiveSyncField.Value(ProviderSelfStatus.ORGANIZER),
                    ActiveSyncField.Empty,
                )
            ActiveSyncResponseType.DECLINED ->
                Presentation(
                    ActiveSyncField.Value(ProviderEventStatus.CANCELLED),
                    availability.mapValues(ActiveSyncAvailability::toProviderAvailability),
                    ActiveSyncField.Value(ProviderSelfStatus.DECLINED),
                    ActiveSyncField.Empty,
                )
        }

    private fun blendChannel(channel: Int): Int =
        (channel + (CHANNEL_MASK - channel) * PALE_BLEND).roundToInt()

    private data class Presentation(
        val status: ActiveSyncField<ProviderEventStatus>,
        val availability: ActiveSyncField<ProviderAvailability>,
        val selfStatus: ActiveSyncField<ProviderSelfStatus>,
        val eventColor: ActiveSyncField<Int>,
    ) {
        companion object {
            fun absent(): Presentation =
                Presentation(
                    ActiveSyncField.Absent,
                    ActiveSyncField.Absent,
                    ActiveSyncField.Absent,
                    ActiveSyncField.Absent,
                )
        }
    }

    private const val PALE_BLEND: Double = 0.45
    private const val OPAQUE_ALPHA: Int = 0xFF
    private const val CHANNEL_MASK: Int = 0xFF
    private const val ALPHA_SHIFT: Int = 24
    private const val RED_SHIFT: Int = 16
    private const val GREEN_SHIFT: Int = 8

}

private fun ProviderEvent.mergeCurrent(current: ProviderEvent): ProviderEvent =
    current.copy(
        uid = uid.mergeCurrent(current.uid),
        title = title.mergeCurrent(current.title),
        description = description.mergeCurrent(current.description),
        location = location.mergeCurrent(current.location),
        start = start.mergeCurrent(current.start),
        end = end.mergeCurrent(current.end),
        allDay = allDay.mergeCurrent(current.allDay),
        timeZone = timeZone.mergeCurrent(current.timeZone),
        recurrenceRule = recurrenceRule.mergeCurrent(current.recurrenceRule),
        exceptions = exceptions.mergeCurrent(current.exceptions),
        organizerEmail = organizerEmail.mergeCurrent(current.organizerEmail),
        organizerName = organizerName.mergeCurrent(current.organizerName),
        attendees = attendees.mergeCurrent(current.attendees),
        meetingStatus = meetingStatus.mergeCurrent(current.meetingStatus),
        responseType = responseType.mergeCurrent(current.responseType),
        responseRequested = responseRequested.mergeCurrent(current.responseRequested),
        serverAvailability = serverAvailability.mergeCurrent(current.serverAvailability),
        status = status.mergeCurrent(current.status),
        availability = availability.mergeCurrent(current.availability),
        selfStatus = selfStatus.mergeCurrent(current.selfStatus),
        eventColor = eventColor.mergeCurrent(current.eventColor),
        accessLevel = accessLevel.mergeCurrent(current.accessLevel),
        reminderMinutes = reminderMinutes.mergeCurrent(current.reminderMinutes),
    )

private fun ProviderEvent.validateTimeConsistency() {
    val effectiveStart = (start as? ActiveSyncField.Value)?.value ?: return
    val effectiveEnd = (end as? ActiveSyncField.Value)?.value ?: return
    if (!effectiveEnd.isAfter(effectiveStart)) {
        throw CalendarMappingException(
            CalendarMappingRule.EVENT_TIME_RANGE_INVALID,
            "Calendar event time range is invalid",
        )
    }
    if ((allDay as? ActiveSyncField.Value)?.value == true) {
        val startTime = effectiveStart.atOffset(ZoneOffset.UTC).toLocalTime()
        val endTime = effectiveEnd.atOffset(ZoneOffset.UTC).toLocalTime()
        if (startTime != java.time.LocalTime.MIDNIGHT || endTime != java.time.LocalTime.MIDNIGHT) {
            throw CalendarMappingException(
                CalendarMappingRule.EVENT_ALL_DAY_NOT_UTC_ALIGNED,
                "All-day calendar event is not aligned to UTC dates",
            )
        }
    }
}

private fun ProviderCalendarException.validateTimeConsistency(path: CalendarMappingPath) {
    if (deleted) return
    val explicitEnd = (end as? ActiveSyncField.Value)?.value ?: return
    val effectiveStart = (start as? ActiveSyncField.Value)?.value ?: originalInstance
    if (!explicitEnd.isAfter(effectiveStart)) {
        throw CalendarMappingException(
            CalendarMappingRule.EXCEPTION_TIME_RANGE_INVALID,
            "Calendar exception time range is invalid",
            path,
        )
    }
}

private fun <T> ActiveSyncField<T>?.mergeCurrent(current: ActiveSyncField<T>): ActiveSyncField<T> =
    if (current == ActiveSyncField.Absent) this ?: ActiveSyncField.Absent else current

private fun ActiveSyncRecurrence.toRecurrenceRule(): String {
    if (interval <= 0) {
        throw CalendarMappingException(
            CalendarMappingRule.RECURRENCE_INTERVAL_INVALID,
            "Recurrence interval is invalid",
        )
    }
    val dailyWeekPattern = type == ActiveSyncRecurrenceType.DAILY && dayOfWeekMask != null
    val frequency = if (dailyWeekPattern) "WEEKLY" else type.frequency
    val values = mutableListOf("FREQ=$frequency", "INTERVAL=$interval")
    when (type) {
        ActiveSyncRecurrenceType.DAILY -> {
            if (dailyWeekPattern) {
                values += "BYDAY=${dayOfWeekMask.requiredDays()}"
                firstDayOfWeek?.let { firstDay -> values += "WKST=${firstDay.toWeekday()}" }
            }
        }
        ActiveSyncRecurrenceType.WEEKLY -> {
            values += "BYDAY=${dayOfWeekMask.requiredDays()}"
            firstDayOfWeek?.let { firstDay -> values += "WKST=${firstDay.toWeekday()}" }
        }
        ActiveSyncRecurrenceType.MONTHLY ->
            values +=
                "BYMONTHDAY=${dayOfMonth.requiredIn(
                    1..31,
                    "day of month",
                    CalendarMappingRule.RECURRENCE_DAY_OF_MONTH_INVALID,
                )}"
        ActiveSyncRecurrenceType.MONTHLY_NTH -> {
            values += "BYDAY=${dayOfWeekMask.requiredDays()}"
            values += "BYSETPOS=${weekOfMonth.requiredWeekPosition()}"
        }
        ActiveSyncRecurrenceType.YEARLY -> {
            values +=
                "BYMONTH=${monthOfYear.requiredIn(
                    1..12,
                    "month of year",
                    CalendarMappingRule.RECURRENCE_MONTH_OF_YEAR_INVALID,
                )}"
            values +=
                "BYMONTHDAY=${dayOfMonth.requiredIn(
                    1..31,
                    "day of month",
                    CalendarMappingRule.RECURRENCE_DAY_OF_MONTH_INVALID,
                )}"
        }
        ActiveSyncRecurrenceType.YEARLY_NTH -> {
            values +=
                "BYMONTH=${monthOfYear.requiredIn(
                    1..12,
                    "month of year",
                    CalendarMappingRule.RECURRENCE_MONTH_OF_YEAR_INVALID,
                )}"
            values += "BYDAY=${dayOfWeekMask.requiredDays()}"
            values += "BYSETPOS=${weekOfMonth.requiredWeekPosition()}"
        }
    }
    when (val recurrenceEnd = end) {
        ActiveSyncRecurrenceEnd.Infinite -> Unit
        is ActiveSyncRecurrenceEnd.Count -> {
            if (recurrenceEnd.occurrences <= 0) {
                throw CalendarMappingException(
                    CalendarMappingRule.RECURRENCE_COUNT_INVALID,
                    "Recurrence count is invalid",
                )
            }
            values += "COUNT=${recurrenceEnd.occurrences}"
        }
        is ActiveSyncRecurrenceEnd.Until ->
            values += "UNTIL=${ACTIVE_SYNC_UNTIL_FORMATTER.format(recurrenceEnd.instant)}"
    }
    return values.joinToString(";")
}

private val ActiveSyncRecurrenceType.frequency: String
    get() =
        when (this) {
            ActiveSyncRecurrenceType.DAILY -> "DAILY"
            ActiveSyncRecurrenceType.WEEKLY -> "WEEKLY"
            ActiveSyncRecurrenceType.MONTHLY,
            ActiveSyncRecurrenceType.MONTHLY_NTH,
            -> "MONTHLY"
            ActiveSyncRecurrenceType.YEARLY,
            ActiveSyncRecurrenceType.YEARLY_NTH,
            -> "YEARLY"
        }

private fun Int?.requiredDays(): String {
    val mask =
        this ?: throw CalendarMappingException(
            CalendarMappingRule.RECURRENCE_WEEKDAYS_MISSING,
            "Recurrence weekdays are missing",
        )
    if (mask !in 1..127) {
        throw CalendarMappingException(
            CalendarMappingRule.RECURRENCE_WEEKDAY_MASK_INVALID,
            "Recurrence weekday mask is invalid",
        )
    }
    return WEEKDAYS.filter { (bit, _) -> mask and bit != 0 }.joinToString(",") { (_, name) -> name }
}

private fun Int.toWeekday(): String =
    WEEKDAYS.getOrNull(this)?.second ?: throw CalendarMappingException(
        CalendarMappingRule.RECURRENCE_FIRST_WEEKDAY_INVALID,
        "First recurrence weekday is invalid",
    )

private fun Int?.requiredWeekPosition(): Int =
    when (this) {
        in 1..4 -> checkNotNull(this)
        5 -> -1
        else ->
            throw CalendarMappingException(
                CalendarMappingRule.RECURRENCE_WEEK_POSITION_INVALID,
                "Recurrence week position is invalid",
            )
    }

private fun Int?.requiredIn(
    range: IntRange,
    label: String,
    rule: CalendarMappingRule,
): Int =
    this?.takeIf { value -> value in range } ?: throw CalendarMappingException(
        rule,
        "Recurrence $label is invalid",
    )

private fun <T> ActiveSyncField<T>.mergeException(exception: ActiveSyncField<T>): ActiveSyncField<T> =
    if (exception == ActiveSyncField.Absent) this else exception

private val WEEKDAYS: List<Pair<Int, String>> =
    listOf(
        1 to "SU",
        2 to "MO",
        4 to "TU",
        8 to "WE",
        16 to "TH",
        32 to "FR",
        64 to "SA",
    )

private val ACTIVE_SYNC_UNTIL_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

private fun ActiveSyncAttendee.toProviderAttendee(): ProviderAttendee =
    ProviderAttendee(
        email = email,
        name = name,
        role =
            when (type) {
                null -> ProviderAttendeeRole.UNSPECIFIED
                ActiveSyncAttendeeType.REQUIRED -> ProviderAttendeeRole.REQUIRED
                ActiveSyncAttendeeType.OPTIONAL -> ProviderAttendeeRole.OPTIONAL
                ActiveSyncAttendeeType.RESOURCE -> ProviderAttendeeRole.RESOURCE
            },
        status =
            when (status) {
                null,
                ActiveSyncAttendeeStatus.NONE,
                -> ProviderAttendeeStatus.NONE
                ActiveSyncAttendeeStatus.NOT_RESPONDED -> ProviderAttendeeStatus.INVITED
                ActiveSyncAttendeeStatus.TENTATIVE -> ProviderAttendeeStatus.TENTATIVE
                ActiveSyncAttendeeStatus.ACCEPTED -> ProviderAttendeeStatus.ACCEPTED
                ActiveSyncAttendeeStatus.DECLINED -> ProviderAttendeeStatus.DECLINED
            },
    )

private fun ActiveSyncAvailability.toProviderAvailability(): ProviderAvailability =
    when (this) {
        ActiveSyncAvailability.FREE -> ProviderAvailability.FREE
        ActiveSyncAvailability.TENTATIVE -> ProviderAvailability.TENTATIVE
        ActiveSyncAvailability.BUSY -> ProviderAvailability.BUSY
        ActiveSyncAvailability.OUT_OF_OFFICE -> ProviderAvailability.OUT_OF_OFFICE
        ActiveSyncAvailability.WORKING_ELSEWHERE -> ProviderAvailability.WORKING_ELSEWHERE
    }

private fun ActiveSyncSensitivity.toProviderAccess(): ProviderAccessLevel =
    when (this) {
        ActiveSyncSensitivity.NORMAL -> ProviderAccessLevel.PUBLIC
        ActiveSyncSensitivity.PERSONAL,
        ActiveSyncSensitivity.PRIVATE,
        -> ProviderAccessLevel.PRIVATE
        ActiveSyncSensitivity.CONFIDENTIAL -> ProviderAccessLevel.CONFIDENTIAL
    }

private fun ActiveSyncField<ActiveSyncResponseType>.toSelfStatus(): ActiveSyncField<ProviderSelfStatus> =
    when (this) {
        ActiveSyncField.Absent -> ActiveSyncField.Absent
        ActiveSyncField.Empty -> ActiveSyncField.Empty
        is ActiveSyncField.Value ->
            ActiveSyncField.Value(
                when (value) {
                    ActiveSyncResponseType.NONE,
                    ActiveSyncResponseType.NOT_RESPONDED,
                    -> ProviderSelfStatus.INVITED
                    ActiveSyncResponseType.TENTATIVE -> ProviderSelfStatus.TENTATIVE
                    ActiveSyncResponseType.ACCEPTED -> ProviderSelfStatus.ACCEPTED
                    ActiveSyncResponseType.DECLINED -> ProviderSelfStatus.DECLINED
                    ActiveSyncResponseType.ORGANIZER -> ProviderSelfStatus.ORGANIZER
                },
            )
    }

private fun <T, R> ActiveSyncField<T>.mapValues(transform: (T) -> R): ActiveSyncField<R> =
    when (this) {
        ActiveSyncField.Absent -> ActiveSyncField.Absent
        ActiveSyncField.Empty -> ActiveSyncField.Empty
        is ActiveSyncField.Value -> ActiveSyncField.Value(transform(value))
    }
