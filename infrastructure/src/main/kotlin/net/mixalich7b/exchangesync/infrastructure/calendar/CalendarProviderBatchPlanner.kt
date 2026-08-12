package net.mixalich7b.exchangesync.infrastructure.calendar

import java.time.Instant
import java.util.Locale
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncAvailability
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncField
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncTimeZone
import net.mixalich7b.exchangesync.core.calendar.ProviderAccessLevel
import net.mixalich7b.exchangesync.core.calendar.ProviderAttendeeRole
import net.mixalich7b.exchangesync.core.calendar.ProviderAttendeeStatus
import net.mixalich7b.exchangesync.core.calendar.ProviderAvailability
import net.mixalich7b.exchangesync.core.calendar.ProviderCalendarException
import net.mixalich7b.exchangesync.core.calendar.ProviderEvent
import net.mixalich7b.exchangesync.core.calendar.ProviderEventStatus
import net.mixalich7b.exchangesync.core.calendar.ProviderSelfStatus

internal const val MAX_MATERIALIZED_NON_ORGANIZER_ATTENDEES: Int = 100

internal fun interface CalendarProviderTimeZoneResolver {
    fun resolve(timeZone: ActiveSyncTimeZone): String?
}

internal object AndroidCalendarProviderTimeZoneResolver : CalendarProviderTimeZoneResolver {
    override fun resolve(timeZone: ActiveSyncTimeZone): String? {
        if (!timeZone.hasValidBiases()) return null
        val named = timeZone.standardName.trim()
        if (named.isNotEmpty()) {
            android.icu.util.TimeZone.getCanonicalID(named)?.let(::preferredIana)?.let { return it }
            android.icu.util.TimeZone.getIDForWindowsID(named, null)?.let(::preferredIana)?.let { return it }
        }
        if (timeZone.hasDaylightRules()) return null
        val offsetMinutesLong = -(timeZone.biasMinutes.toLong() + timeZone.standardBiasMinutes.toLong())
        if (offsetMinutesLong !in MINIMUM_OFFSET_MINUTES.toLong()..MAXIMUM_OFFSET_MINUTES.toLong()) return null
        val offsetMinutes = offsetMinutesLong.toInt()
        val sign = if (offsetMinutes >= 0) '+' else '-'
        val magnitude = kotlin.math.abs(offsetMinutes)
        return String.format(Locale.ROOT, "GMT%c%02d:%02d", sign, magnitude / 60, magnitude % 60)
    }

    private fun preferredIana(id: String): String? =
        android.icu.util.TimeZone.getIanaID(id).takeUnless { it == android.icu.util.TimeZone.UNKNOWN_ZONE_ID }

    private fun ActiveSyncTimeZone.hasDaylightRules(): Boolean =
        daylightTransition.month != 0 || standardTransition.month != 0 || daylightBiasMinutes != 0

    private fun ActiveSyncTimeZone.hasValidBiases(): Boolean =
        listOf(biasMinutes, standardBiasMinutes, daylightBiasMinutes).all { bias ->
            bias.toLong() in MINIMUM_OFFSET_MINUTES.toLong()..MAXIMUM_OFFSET_MINUTES.toLong()
        }

    private const val MINIMUM_OFFSET_MINUTES: Int = -18 * 60
    private const val MAXIMUM_OFFSET_MINUTES: Int = 18 * 60
}

internal object CalendarProviderField {
    const val SYNC_ID = "_sync_id"
    const val DIRTY = "dirty"
    const val UID = "uid2445"
    const val TITLE = "title"
    const val DESCRIPTION = "description"
    const val LOCATION = "eventLocation"
    const val START = "dtstart"
    const val END = "dtend"
    const val DURATION = "duration"
    const val ALL_DAY = "allDay"
    const val EVENT_TIME_ZONE = "eventTimezone"
    const val EVENT_END_TIME_ZONE = "eventEndTimezone"
    const val RECURRENCE_RULE = "rrule"
    const val ORGANIZER_EMAIL = "organizer"
    const val IS_ORGANIZER = "isOrganizer"
    const val STATUS = "eventStatus"
    const val AVAILABILITY = "availability"
    const val SELF_ATTENDEE_STATUS = "selfAttendeeStatus"
    const val EVENT_COLOR = "eventColor"
    const val ACCESS_LEVEL = "accessLevel"
    const val HAS_ALARM = "hasAlarm"
    const val HAS_ATTENDEE_DATA = "hasAttendeeData"
    const val RESPONSE_TYPE = "sync_data2"
    const val MEETING_STATUS = "sync_data3"
    const val RESPONSE_REQUESTED = "sync_data4"
    const val SERVER_AVAILABILITY = "sync_data5"
    const val EXCEPTION_RESPONSE_OVERRIDE = "sync_data6"
    const val EXCEPTION_DELETED = "sync_data7"
    const val ATTENDEE_EMAIL = "attendeeEmail"
    const val ATTENDEE_NAME = "attendeeName"
    const val ATTENDEE_RELATIONSHIP = "attendeeRelationship"
    const val ATTENDEE_TYPE = "attendeeType"
    const val ATTENDEE_STATUS = "attendeeStatus"
    const val REMINDER_MINUTES = "minutes"
    const val REMINDER_METHOD = "method"
    const val ORIGINAL_SYNC_ID = "original_sync_id"
    const val ORIGINAL_INSTANCE_TIME = "originalInstanceTime"
    const val ORIGINAL_ALL_DAY = "originalAllDay"
}

internal object ProviderInteger {
    const val TENTATIVE_EVENT = 0
    const val CONFIRMED_EVENT = 1
    const val CANCELLED_EVENT = 2
    const val BUSY = 0
    const val FREE = 1
    const val TENTATIVE_AVAILABILITY = 2
    const val ACCEPTED_ATTENDEE = 1
    const val DECLINED_ATTENDEE = 2
    const val INVITED_ATTENDEE = 3
    const val TENTATIVE_ATTENDEE = 4
    const val NONE_ATTENDEE = 0
    const val UNSPECIFIED_ATTENDEE = 0
    const val ATTENDEE_RELATIONSHIP = 1
    const val ORGANIZER_RELATIONSHIP = 2
    const val REQUIRED_ATTENDEE = 1
    const val OPTIONAL_ATTENDEE = 2
    const val RESOURCE_ATTENDEE = 3
    const val ALERT_REMINDER = 1
    const val CONFIDENTIAL_ACCESS = 1
    const val PRIVATE_ACCESS = 2
    const val PUBLIC_ACCESS = 3
}

internal sealed interface EventReference {
    data class Existing(val eventId: Long) : EventReference

    data class Inserted(val operationIndex: Int) : EventReference
}

internal sealed interface CalendarProviderBatchOperation {
    val calendarId: Long

    data class EventInsert(
        override val calendarId: Long,
        val values: Map<String, Any?>,
    ) : CalendarProviderBatchOperation

    data class EventUpdate(
        override val calendarId: Long,
        val eventId: Long,
        val values: Map<String, Any?>,
    ) : CalendarProviderBatchOperation

    data class EventDelete(
        override val calendarId: Long,
        val syncId: String,
    ) : CalendarProviderBatchOperation

    data class AttendeesDelete(
        override val calendarId: Long,
        val eventId: Long,
    ) : CalendarProviderBatchOperation

    data class OrganizerDelete(
        override val calendarId: Long,
        val event: EventReference,
    ) : CalendarProviderBatchOperation

    data class AttendeeInsert(
        override val calendarId: Long,
        val event: EventReference,
        val values: Map<String, Any?>,
    ) : CalendarProviderBatchOperation

    data class RemindersDelete(
        override val calendarId: Long,
        val event: EventReference,
    ) : CalendarProviderBatchOperation

    data class ReminderInsert(
        override val calendarId: Long,
        val event: EventReference,
        val values: Map<String, Any?>,
    ) : CalendarProviderBatchOperation

    data class ExceptionsDelete(
        override val calendarId: Long,
        val seriesId: Long,
    ) : CalendarProviderBatchOperation

    data class ExceptionInsert(
        override val calendarId: Long,
        val series: EventReference,
        val values: Map<String, Any?>,
    ) : CalendarProviderBatchOperation

    data class ExceptionResponseUpdate(
        override val calendarId: Long,
        val seriesId: Long,
        val originalInstance: Instant,
        val values: Map<String, Any?>,
    ) : CalendarProviderBatchOperation
}

internal data class CalendarAttendeeSuppression(
    val inputCount: Int,
    val organizerRetained: Boolean,
)

internal class CalendarProviderBatchPlan private constructor(
    val calendarId: Long,
    val operations: List<CalendarProviderBatchOperation>,
    val attendeeSuppressions: List<CalendarAttendeeSuppression>,
) {
    companion object {
        fun create(
            calendarId: Long,
            operations: List<CalendarProviderBatchOperation>,
            attendeeSuppressions: List<CalendarAttendeeSuppression> = emptyList(),
        ): CalendarProviderBatchPlan {
            if (calendarId < 0 || operations.any { it.calendarId != calendarId }) {
                throw CalendarPlanningException("Calendar Provider batch escaped the owned calendar")
            }
            return CalendarProviderBatchPlan(calendarId, operations, attendeeSuppressions)
        }
    }
}

internal object CalendarProviderBatchPlanner {
    fun plan(
        page: CalendarPagePlan,
        timeZoneResolver: CalendarProviderTimeZoneResolver,
    ): CalendarProviderBatchPlan {
        val operations = mutableListOf<CalendarProviderBatchOperation>()
        val attendeeSuppressions = mutableListOf<CalendarAttendeeSuppression>()
        page.operations.forEach { eventPlan ->
            val serverId =
                when (eventPlan) {
                    is CalendarEventPlan.Delete -> eventPlan.syncId
                    is CalendarEventPlan.Upsert -> eventPlan.event.syncId
                }
            try {
                when (eventPlan) {
                    is CalendarEventPlan.Delete ->
                        operations += CalendarProviderBatchOperation.EventDelete(page.calendarId, eventPlan.syncId)
                    is CalendarEventPlan.Upsert ->
                        addUpsert(eventPlan, operations, attendeeSuppressions, timeZoneResolver)
                }
            } catch (failure: CalendarPlanningException) {
                throw failure.withServerId(serverId)
            }
        }
        return CalendarProviderBatchPlan.create(page.calendarId, operations, attendeeSuppressions)
    }

    private fun addUpsert(
        plan: CalendarEventPlan.Upsert,
        operations: MutableList<CalendarProviderBatchOperation>,
        attendeeSuppressions: MutableList<CalendarAttendeeSuppression>,
        timeZoneResolver: CalendarProviderTimeZoneResolver,
    ) {
        val values = plan.event.toProviderValues(plan.eventId == null, timeZoneResolver)
        val eventReference =
            if (plan.eventId == null) {
                val operationIndex = operations.size
                operations += CalendarProviderBatchOperation.EventInsert(plan.calendarId, values)
                EventReference.Inserted(operationIndex)
            } else {
                if (values.isNotEmpty()) {
                    operations += CalendarProviderBatchOperation.EventUpdate(plan.calendarId, plan.eventId, values)
                }
                EventReference.Existing(plan.eventId)
            }
        addOrganizerOperations(plan, eventReference, operations)
        addAttendeeOperations(plan, eventReference, operations, attendeeSuppressions)
        addReminderOperations(plan, eventReference, operations)
        addExceptionOperations(plan, eventReference, operations, attendeeSuppressions, timeZoneResolver)
    }

    private fun addOrganizerOperations(
        plan: CalendarEventPlan.Upsert,
        eventReference: EventReference,
        operations: MutableList<CalendarProviderBatchOperation>,
    ) {
        if (!plan.replaceOrganizer) return
        operations += CalendarProviderBatchOperation.OrganizerDelete(plan.calendarId, eventReference)
        addOrganizerInsert(plan, eventReference, operations)
    }

    private fun addOrganizerInsert(
        plan: CalendarEventPlan.Upsert,
        eventReference: EventReference,
        operations: MutableList<CalendarProviderBatchOperation>,
    ) {
        val email = (plan.event.organizerEmail as? ActiveSyncField.Value)?.value ?: return
        val name = (plan.event.organizerName as? ActiveSyncField.Value)?.value
        operations +=
            CalendarProviderBatchOperation.AttendeeInsert(
                plan.calendarId,
                eventReference,
                mapOf(
                    CalendarProviderField.ATTENDEE_EMAIL to email,
                    CalendarProviderField.ATTENDEE_NAME to name,
                    CalendarProviderField.ATTENDEE_RELATIONSHIP to ProviderInteger.ORGANIZER_RELATIONSHIP,
                    CalendarProviderField.ATTENDEE_TYPE to ProviderInteger.REQUIRED_ATTENDEE,
                    CalendarProviderField.ATTENDEE_STATUS to ProviderInteger.ACCEPTED_ATTENDEE,
                ),
            )
    }

    private fun addAttendeeOperations(
        plan: CalendarEventPlan.Upsert,
        eventReference: EventReference,
        operations: MutableList<CalendarProviderBatchOperation>,
        attendeeSuppressions: MutableList<CalendarAttendeeSuppression>,
    ) {
        if (!plan.replaceAttendees) return
        val existingId = (eventReference as? EventReference.Existing)?.eventId
        if (existingId != null) {
            operations += CalendarProviderBatchOperation.AttendeesDelete(plan.calendarId, existingId)
        }
        val attendees = (plan.event.attendees as? ActiveSyncField.Value)?.value.orEmpty()
        attendees.suppressionOrNull(plan.event.organizerEmail is ActiveSyncField.Value)
            ?.let(attendeeSuppressions::add)
        attendees.materializedNonOrganizerAttendees().forEach { attendee ->
            operations +=
                CalendarProviderBatchOperation.AttendeeInsert(
                    plan.calendarId,
                    eventReference,
                    mapOf(
                        CalendarProviderField.ATTENDEE_EMAIL to attendee.email,
                        CalendarProviderField.ATTENDEE_NAME to attendee.name,
                        CalendarProviderField.ATTENDEE_RELATIONSHIP to ProviderInteger.ATTENDEE_RELATIONSHIP,
                        CalendarProviderField.ATTENDEE_TYPE to attendee.role.toProviderInteger(),
                        CalendarProviderField.ATTENDEE_STATUS to attendee.status.toProviderInteger(),
                    ),
                )
        }
    }

    private fun addReminderOperations(
        plan: CalendarEventPlan.Upsert,
        eventReference: EventReference,
        operations: MutableList<CalendarProviderBatchOperation>,
    ) {
        if (!plan.replaceReminders) return
        operations += CalendarProviderBatchOperation.RemindersDelete(plan.calendarId, eventReference)
        val minutes = (plan.event.reminderMinutes as? ActiveSyncField.Value)?.value ?: return
        operations +=
            CalendarProviderBatchOperation.ReminderInsert(
                plan.calendarId,
                eventReference,
                mapOf(
                    CalendarProviderField.REMINDER_MINUTES to minutes,
                    CalendarProviderField.REMINDER_METHOD to ProviderInteger.ALERT_REMINDER,
                ),
            )
    }

    private fun addExceptionOperations(
        plan: CalendarEventPlan.Upsert,
        eventReference: EventReference,
        operations: MutableList<CalendarProviderBatchOperation>,
        attendeeSuppressions: MutableList<CalendarAttendeeSuppression>,
        timeZoneResolver: CalendarProviderTimeZoneResolver,
    ) {
        if (plan.refreshExceptionResponses) {
            val seriesId = plan.eventId ?: throw CalendarPlanningException("Cannot refresh exceptions for a new series")
            val exceptions = (plan.event.exceptions as? ActiveSyncField.Value)?.value.orEmpty()
            exceptions
                .filter { exception -> exception.responseTypeOverride == ActiveSyncField.Absent }
                .forEach { exception ->
                    operations +=
                        CalendarProviderBatchOperation.ExceptionResponseUpdate(
                            calendarId = plan.calendarId,
                            seriesId = seriesId,
                            originalInstance = exception.originalInstance,
                            values = exception.toResponseProviderValues(plan.event),
                        )
                }
            return
        }
        if (!plan.replaceExceptions) return
        val existingId = (eventReference as? EventReference.Existing)?.eventId
        if (existingId != null) {
            operations += CalendarProviderBatchOperation.ExceptionsDelete(plan.calendarId, existingId)
        }
        val exceptions = (plan.event.exceptions as? ActiveSyncField.Value)?.value.orEmpty()
        exceptions.forEach { exception ->
            val exceptionIndex = operations.size
            operations +=
                CalendarProviderBatchOperation.ExceptionInsert(
                    calendarId = plan.calendarId,
                    series = eventReference,
                    values = exception.toProviderValues(plan.event, timeZoneResolver, plan.providerTimeZone),
                )
            val exceptionReference = EventReference.Inserted(exceptionIndex)
            val attendees = (exception.attendees as? ActiveSyncField.Value)?.value.orEmpty()
            if (!exception.deleted) {
                addOrganizerInsert(plan, exceptionReference, operations)
                attendees.suppressionOrNull(plan.event.organizerEmail is ActiveSyncField.Value)
                    ?.let(attendeeSuppressions::add)
            }
            attendees
                .materializedNonOrganizerAttendees()
                .takeUnless { exception.deleted }
                .orEmpty()
                .forEach { attendee ->
                operations +=
                    CalendarProviderBatchOperation.AttendeeInsert(
                        plan.calendarId,
                        exceptionReference,
                        mapOf(
                            CalendarProviderField.ATTENDEE_EMAIL to attendee.email,
                            CalendarProviderField.ATTENDEE_NAME to attendee.name,
                            CalendarProviderField.ATTENDEE_RELATIONSHIP to ProviderInteger.ATTENDEE_RELATIONSHIP,
                            CalendarProviderField.ATTENDEE_TYPE to attendee.role.toProviderInteger(),
                            CalendarProviderField.ATTENDEE_STATUS to attendee.status.toProviderInteger(),
                        ),
                    )
            }
            if (exception.deleted) return@forEach
            when (val reminder = exception.reminderMinutes) {
                ActiveSyncField.Absent -> Unit
                ActiveSyncField.Empty ->
                    operations +=
                        CalendarProviderBatchOperation.RemindersDelete(
                            plan.calendarId,
                            EventReference.Inserted(exceptionIndex),
                        )
                is ActiveSyncField.Value -> {
                    val exceptionReference = EventReference.Inserted(exceptionIndex)
                    operations += CalendarProviderBatchOperation.RemindersDelete(plan.calendarId, exceptionReference)
                    operations +=
                        CalendarProviderBatchOperation.ReminderInsert(
                            plan.calendarId,
                            exceptionReference,
                            mapOf(
                                CalendarProviderField.REMINDER_MINUTES to reminder.value,
                                CalendarProviderField.REMINDER_METHOD to ProviderInteger.ALERT_REMINDER,
                            ),
                        )
                }
            }
        }
    }

    private fun ProviderEvent.toProviderValues(
        isInsert: Boolean,
        timeZoneResolver: CalendarProviderTimeZoneResolver,
    ): Map<String, Any?> {
        val values = linkedMapOf<String, Any?>()
        if (isInsert) values[CalendarProviderField.SYNC_ID] = syncId
        values.putField(CalendarProviderField.UID, uid)
        values.putField(CalendarProviderField.TITLE, title)
        values.putField(CalendarProviderField.DESCRIPTION, description)
        values.putField(CalendarProviderField.LOCATION, location)
        values.putField(CalendarProviderField.START, start, Instant::toEpochMilli)
        values.putField(CalendarProviderField.END, end, Instant::toEpochMilli)
        values.putField(CalendarProviderField.ALL_DAY, allDay, Boolean::asInt)
        val providerTimeZone = resolveTimeZone(timeZone, recurrenceRule, allDay, timeZoneResolver)
        if (providerTimeZone.present) {
            values[CalendarProviderField.EVENT_TIME_ZONE] = providerTimeZone.value
            values[CalendarProviderField.EVENT_END_TIME_ZONE] = providerTimeZone.value
        }
        values.putField(CalendarProviderField.RECURRENCE_RULE, recurrenceRule)
        if (recurrenceRule is ActiveSyncField.Value) {
            durationMillis()?.let { duration ->
                values.remove(CalendarProviderField.END)
                values[CalendarProviderField.DURATION] = "PT${duration / 1_000}S"
            }
        }
        if (recurrenceRule == ActiveSyncField.Empty) {
            values[CalendarProviderField.DURATION] = null
        }
        values.putField(CalendarProviderField.ORGANIZER_EMAIL, organizerEmail)
        values.putField(CalendarProviderField.STATUS, status, ProviderEventStatus::toProviderInteger)
        values.putField(CalendarProviderField.AVAILABILITY, availability, ProviderAvailability::toProviderInteger)
        values.putField(CalendarProviderField.SELF_ATTENDEE_STATUS, selfStatus, ProviderSelfStatus::toProviderInteger)
        values.putField(CalendarProviderField.EVENT_COLOR, eventColor)
        values.putField(CalendarProviderField.ACCESS_LEVEL, accessLevel, ProviderAccessLevel::toProviderInteger)
        values.putField(CalendarProviderField.RESPONSE_TYPE, responseType) { it.wireValue }
        values.putField(CalendarProviderField.MEETING_STATUS, meetingStatus) { it.rawValue }
        values.putField(CalendarProviderField.RESPONSE_REQUESTED, responseRequested, Boolean::asInt)
        values.putField(CalendarProviderField.SERVER_AVAILABILITY, serverAvailability) { it.wireValue }
        if (isInsert || reminderMinutes != ActiveSyncField.Absent) {
            values[CalendarProviderField.HAS_ALARM] = (reminderMinutes is ActiveSyncField.Value).asInt()
        }
        if (isInsert || attendees != ActiveSyncField.Absent) {
            values[CalendarProviderField.HAS_ATTENDEE_DATA] = hasMaterializedAttendeeData().asInt()
        }
        if (isInsert) {
            if (start !is ActiveSyncField.Value || end !is ActiveSyncField.Value) {
                throw CalendarPlanningException("Calendar addition has no representable time range")
            }
            if (recurrenceRule is ActiveSyncField.Value && CalendarProviderField.DURATION !in values) {
                throw CalendarPlanningException("Recurring calendar addition has no duration")
            }
            values.putIfAbsent(CalendarProviderField.ALL_DAY, 0)
            values.putIfAbsent(CalendarProviderField.EVENT_TIME_ZONE, "UTC")
            values.putIfAbsent(CalendarProviderField.EVENT_END_TIME_ZONE, values[CalendarProviderField.EVENT_TIME_ZONE])
        }
        return values
    }

    private fun ProviderCalendarException.toProviderValues(
        series: ProviderEvent,
        timeZoneResolver: CalendarProviderTimeZoneResolver,
        fallbackTimeZone: String?,
    ): Map<String, Any?> {
        val values = linkedMapOf<String, Any?>()
        values[CalendarProviderField.SYNC_ID] = exceptionSyncId(series.syncId, originalInstance)
        values[CalendarProviderField.ORIGINAL_SYNC_ID] = series.syncId
        values[CalendarProviderField.ORIGINAL_INSTANCE_TIME] = originalInstance.toEpochMilli()
        val originalAllDay = (series.allDay as? ActiveSyncField.Value)?.value == true
        val effectiveAllDay =
            when (val field = allDay) {
                ActiveSyncField.Absent -> originalAllDay
                ActiveSyncField.Empty -> throw CalendarPlanningException("Calendar exception all-day value is empty")
                is ActiveSyncField.Value -> field.value
            }
        values[CalendarProviderField.ORIGINAL_ALL_DAY] = originalAllDay.asInt()
        values[CalendarProviderField.EXCEPTION_DELETED] = deleted.asInt()
        values[CalendarProviderField.ALL_DAY] = effectiveAllDay.asInt()
        val timeZone =
            resolveTimeZone(
                series.timeZone,
                series.recurrenceRule,
                ActiveSyncField.Value(effectiveAllDay),
                timeZoneResolver,
            )
        values[CalendarProviderField.EVENT_TIME_ZONE] = timeZone.value ?: fallbackTimeZone ?: "UTC"
        values[CalendarProviderField.EVENT_END_TIME_ZONE] = timeZone.value ?: fallbackTimeZone ?: "UTC"
        values.putInheritedField(CalendarProviderField.TITLE, title, series.title)
        values.putInheritedField(CalendarProviderField.DESCRIPTION, description, series.description)
        values.putInheritedField(CalendarProviderField.LOCATION, location, series.location)
        val startInstant = (start as? ActiveSyncField.Value)?.value ?: originalInstance
        val seriesDuration = series.durationMillis()
        val endInstant = (end as? ActiveSyncField.Value)?.value ?: seriesDuration?.let(startInstant::plusMillis)
        values[CalendarProviderField.START] = startInstant.toEpochMilli()
        if (endInstant != null) values[CalendarProviderField.END] = endInstant.toEpochMilli()
        values[CalendarProviderField.STATUS] =
            if (deleted) ProviderInteger.CANCELLED_EVENT else status.valueOrNull()?.toProviderInteger()
                ?: series.status.valueOrNull()?.toProviderInteger()
                ?: ProviderInteger.CONFIRMED_EVENT
        values.putInheritedField(
            CalendarProviderField.AVAILABILITY,
            availability,
            series.availability,
            ProviderAvailability::toProviderInteger,
        )
        values.putInheritedField(
            CalendarProviderField.SELF_ATTENDEE_STATUS,
            selfStatus,
            series.selfStatus,
            ProviderSelfStatus::toProviderInteger,
        )
        values.putInheritedField(CalendarProviderField.EVENT_COLOR, eventColor, series.eventColor)
        values.putInheritedField(CalendarProviderField.RESPONSE_TYPE, responseType, series.responseType) { it.wireValue }
        values.putField(CalendarProviderField.EXCEPTION_RESPONSE_OVERRIDE, responseTypeOverride) { it.wireValue }
        values.putInheritedField(CalendarProviderField.MEETING_STATUS, meetingStatus, series.meetingStatus) { it.rawValue }
        values.putInheritedField(
            CalendarProviderField.RESPONSE_REQUESTED,
            responseRequested,
            series.responseRequested,
            Boolean::asInt,
        )
        values.putInheritedField(
            CalendarProviderField.SERVER_AVAILABILITY,
            serverAvailability,
            series.serverAvailability,
        ) { it.wireValue }
        values.putInheritedField(
            CalendarProviderField.ACCESS_LEVEL,
            accessLevel,
            series.accessLevel,
            ProviderAccessLevel::toProviderInteger,
        )
        values[CalendarProviderField.HAS_ATTENDEE_DATA] =
            (
                series.organizerEmail is ActiveSyncField.Value ||
                    (attendees as? ActiveSyncField.Value)
                        ?.value
                        .orEmpty()
                        .materializedNonOrganizerAttendees()
                        .isNotEmpty()
            ).asInt()
        when (reminderMinutes) {
            ActiveSyncField.Absent -> Unit
            ActiveSyncField.Empty -> values[CalendarProviderField.HAS_ALARM] = 0
            is ActiveSyncField.Value -> values[CalendarProviderField.HAS_ALARM] = 1
        }
        return values
    }

    private fun ProviderCalendarException.toResponseProviderValues(series: ProviderEvent): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            this[CalendarProviderField.STATUS] =
                if (deleted) {
                    ProviderInteger.CANCELLED_EVENT
                } else {
                    status.valueOrNull()?.toProviderInteger()
                        ?: series.status.valueOrNull()?.toProviderInteger()
                        ?: ProviderInteger.CONFIRMED_EVENT
                }
            putInheritedField(
                CalendarProviderField.AVAILABILITY,
                availability,
                series.availability,
                ProviderAvailability::toProviderInteger,
            )
            putInheritedField(
                CalendarProviderField.SELF_ATTENDEE_STATUS,
                selfStatus,
                series.selfStatus,
                ProviderSelfStatus::toProviderInteger,
            )
            putInheritedField(CalendarProviderField.EVENT_COLOR, eventColor, series.eventColor)
            putInheritedField(CalendarProviderField.RESPONSE_TYPE, responseType, series.responseType) { it.wireValue }
        }

    private fun resolveTimeZone(
        timeZone: ActiveSyncField<ActiveSyncTimeZone>,
        recurrence: ActiveSyncField<String>,
        allDay: ActiveSyncField<Boolean>,
        resolver: CalendarProviderTimeZoneResolver,
    ): ResolvedField<String> {
        if ((allDay as? ActiveSyncField.Value)?.value == true) return ResolvedField(true, "UTC")
        return when (timeZone) {
            ActiveSyncField.Absent -> ResolvedField(false, null)
            ActiveSyncField.Empty -> ResolvedField(true, null)
            is ActiveSyncField.Value -> {
                val resolved = resolver.resolve(timeZone.value)
                if (resolved == null && recurrence is ActiveSyncField.Value) {
                    throw CalendarPlanningException("Recurring event time zone cannot be represented")
                }
                if (resolved == null) throw CalendarPlanningException("Event time zone cannot be represented")
                ResolvedField(true, resolved)
            }
        }
    }

    private fun ProviderEvent.durationMillis(): Long? {
        val startValue = (start as? ActiveSyncField.Value)?.value ?: return null
        val endValue = (end as? ActiveSyncField.Value)?.value ?: return null
        val duration = endValue.toEpochMilli() - startValue.toEpochMilli()
        return duration.takeIf { it > 0 }
    }

    private fun exceptionSyncId(seriesSyncId: String, instance: Instant): String =
        "$seriesSyncId#exception#${instance.toEpochMilli()}"

    private data class ResolvedField<T>(val present: Boolean, val value: T?)

    private fun ProviderEvent.hasMaterializedAttendeeData(): Boolean =
        organizerEmail is ActiveSyncField.Value ||
            (attendees as? ActiveSyncField.Value)
                ?.value
                .orEmpty()
                .materializedNonOrganizerAttendees()
                .isNotEmpty()
}

private fun <T> List<T>.materializedNonOrganizerAttendees(): List<T> =
    takeIf { attendees -> attendees.size <= MAX_MATERIALIZED_NON_ORGANIZER_ATTENDEES }.orEmpty()

private fun <T> List<T>.suppressionOrNull(organizerRetained: Boolean): CalendarAttendeeSuppression? =
    takeIf { attendees -> attendees.size > MAX_MATERIALIZED_NON_ORGANIZER_ATTENDEES }
        ?.let { attendees -> CalendarAttendeeSuppression(attendees.size, organizerRetained) }

private fun Boolean.asInt(): Int = if (this) 1 else 0

private fun ProviderEventStatus.toProviderInteger(): Int =
    when (this) {
        ProviderEventStatus.TENTATIVE -> ProviderInteger.TENTATIVE_EVENT
        ProviderEventStatus.CONFIRMED -> ProviderInteger.CONFIRMED_EVENT
        ProviderEventStatus.CANCELLED -> ProviderInteger.CANCELLED_EVENT
    }

private fun ProviderAvailability.toProviderInteger(): Int =
    when (this) {
        ProviderAvailability.FREE,
        ProviderAvailability.WORKING_ELSEWHERE,
        -> ProviderInteger.FREE
        ProviderAvailability.TENTATIVE -> ProviderInteger.TENTATIVE_AVAILABILITY
        ProviderAvailability.BUSY,
        ProviderAvailability.OUT_OF_OFFICE,
        -> ProviderInteger.BUSY
    }

private fun ProviderSelfStatus.toProviderInteger(): Int =
    when (this) {
        ProviderSelfStatus.INVITED -> ProviderInteger.INVITED_ATTENDEE
        ProviderSelfStatus.TENTATIVE -> ProviderInteger.TENTATIVE_ATTENDEE
        ProviderSelfStatus.ACCEPTED,
        ProviderSelfStatus.ORGANIZER,
        -> ProviderInteger.ACCEPTED_ATTENDEE
        ProviderSelfStatus.DECLINED -> ProviderInteger.DECLINED_ATTENDEE
    }

private fun ProviderAccessLevel.toProviderInteger(): Int =
    when (this) {
        ProviderAccessLevel.PUBLIC -> ProviderInteger.PUBLIC_ACCESS
        ProviderAccessLevel.PRIVATE -> ProviderInteger.PRIVATE_ACCESS
        ProviderAccessLevel.CONFIDENTIAL -> ProviderInteger.CONFIDENTIAL_ACCESS
    }

private fun ProviderAttendeeRole.toProviderInteger(): Int =
    when (this) {
        ProviderAttendeeRole.UNSPECIFIED -> ProviderInteger.UNSPECIFIED_ATTENDEE
        ProviderAttendeeRole.REQUIRED -> ProviderInteger.REQUIRED_ATTENDEE
        ProviderAttendeeRole.OPTIONAL -> ProviderInteger.OPTIONAL_ATTENDEE
        ProviderAttendeeRole.RESOURCE -> ProviderInteger.RESOURCE_ATTENDEE
    }

private fun ProviderAttendeeStatus.toProviderInteger(): Int =
    when (this) {
        ProviderAttendeeStatus.NONE -> ProviderInteger.NONE_ATTENDEE
        ProviderAttendeeStatus.INVITED -> ProviderInteger.INVITED_ATTENDEE
        ProviderAttendeeStatus.TENTATIVE -> ProviderInteger.TENTATIVE_ATTENDEE
        ProviderAttendeeStatus.ACCEPTED -> ProviderInteger.ACCEPTED_ATTENDEE
        ProviderAttendeeStatus.DECLINED -> ProviderInteger.DECLINED_ATTENDEE
    }

private fun <T> MutableMap<String, Any?>.putField(
    key: String,
    field: ActiveSyncField<T>,
    transform: (T) -> Any? = { it },
) {
    when (field) {
        ActiveSyncField.Absent -> Unit
        ActiveSyncField.Empty -> this[key] = null
        is ActiveSyncField.Value -> this[key] = transform(field.value)
    }
}

private fun <T> MutableMap<String, Any?>.putInheritedField(
    key: String,
    exception: ActiveSyncField<T>,
    series: ActiveSyncField<T>,
    transform: (T) -> Any? = { it },
) {
    putField(key, if (exception == ActiveSyncField.Absent) series else exception, transform)
}

private fun <T> ActiveSyncField<T>.valueOrNull(): T? = (this as? ActiveSyncField.Value)?.value
