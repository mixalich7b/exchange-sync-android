package net.mixalich7b.exchangesync.infrastructure.calendar

import java.time.Instant
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarException
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarItem
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarMutation
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncField
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncMeetingStatus
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncRecurrence
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncRecurrenceEnd
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncSystemTime
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncTimeZone
import net.mixalich7b.exchangesync.core.calendar.CalendarMappingException
import net.mixalich7b.exchangesync.core.calendar.CalendarMappingPath
import net.mixalich7b.exchangesync.core.calendar.CalendarMappingRule
import net.mixalich7b.exchangesync.core.calendar.ProviderCalendarException
import net.mixalich7b.exchangesync.core.calendar.ProviderEvent
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
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticRelationship
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticTextSanitizer

internal object CalendarMappingFailureProjector {
    fun project(
        mutation: ActiveSyncCalendarMutation,
        previous: ProviderEvent?,
        failure: CalendarMappingException,
    ): DiagnosticCalendarFailureSnapshot {
        val upsert = mutation as? ActiveSyncCalendarMutation.Upsert
            ?: error("Delete mutations do not pass calendar mapping validation")
        val response = upsert.item
        val fields = mutableListOf<DiagnosticCalendarFieldEntry>()

        fields.addResponseSeries(DiagnosticCalendarFieldSource.RESPONSE, response)
        fields.addProviderSeries(DiagnosticCalendarFieldSource.PRIOR, previous)
        fields.addEffectiveSeries(response, previous)
        fields.addSelectedException(response, previous, failure.path)
        val effectiveStart = effective(response.start, previous?.start)
        val effectiveEnd = effective(response.end, previous?.end)
        val failedRange = response.failedRange(failure.path, effectiveStart, effectiveEnd)
        fields +=
            DiagnosticCalendarFieldEntry(
                source = DiagnosticCalendarFieldSource.DERIVED,
                field = DiagnosticCalendarField.TIME_RELATIONSHIP,
                state = DiagnosticFieldState.PRESENT,
                value = DiagnosticFieldValue.Relationship(relationship(failedRange.first, failedRange.second)),
            )

        return DiagnosticCalendarFailureSnapshot(
            commandKind =
                if (upsert.isAddition) {
                    DiagnosticCalendarCommandKind.ADD
                } else {
                    DiagnosticCalendarCommandKind.CHANGE
                },
            serverId = DiagnosticTextSanitizer.sanitize(response.serverId),
            rule = failure.rule.toDiagnosticRule(),
            path = failure.path.toDiagnosticPath(),
            fields = fields,
        )
    }

    fun project(
        plan: CalendarEventPlan.Upsert,
        failure: CalendarPlanningException,
    ): DiagnosticCalendarFailureSnapshot {
        val fields = mutableListOf<DiagnosticCalendarFieldEntry>()
        fields.addProviderSeries(DiagnosticCalendarFieldSource.EFFECTIVE, plan.event)
        fields.addSelectedProviderException(plan.event, failure.calendarPath)
        val selectedException = plan.event.selectedException(failure.calendarPath)
        val duration =
            when (failure.calendarPath) {
                DiagnosticCalendarPath.Event -> durationMillis(plan.event.start, plan.event.end)
                is DiagnosticCalendarPath.Exception -> selectedException?.durationMillis(plan.event)
            }
        fields +=
            DiagnosticCalendarFieldEntry(
                DiagnosticCalendarFieldSource.DERIVED,
                DiagnosticCalendarField.DURATION_MILLIS,
                if (duration == null) DiagnosticFieldState.ABSENT else DiagnosticFieldState.PRESENT,
                duration?.let(DiagnosticFieldValue::IntegerValue),
            )
        val timeZoneUnrepresentable =
            failure.planningRule == CalendarPlanningRule.RECURRING_TIME_ZONE_UNREPRESENTABLE ||
                failure.planningRule == CalendarPlanningRule.TIME_ZONE_UNREPRESENTABLE
        fields +=
            DiagnosticCalendarFieldEntry(
                DiagnosticCalendarFieldSource.DERIVED,
                DiagnosticCalendarField.PROVIDER_TIME_ZONE,
                if (timeZoneUnrepresentable) DiagnosticFieldState.EMPTY else DiagnosticFieldState.ABSENT,
            )
        fields +=
            DiagnosticCalendarFieldEntry(
                DiagnosticCalendarFieldSource.DERIVED,
                DiagnosticCalendarField.HAS_ALARM,
                DiagnosticFieldState.PRESENT,
                DiagnosticFieldValue.BooleanValue(
                    plan.event.failedReminder(failure.calendarPath, selectedException) is ActiveSyncField.Value,
                ),
            )
        val attendeeCount =
            (plan.event.failedAttendees(failure.calendarPath, selectedException) as? ActiveSyncField.Value)
                ?.value
                ?.size
                ?: 0
        fields +=
            DiagnosticCalendarFieldEntry(
                DiagnosticCalendarFieldSource.DERIVED,
                DiagnosticCalendarField.HAS_ATTENDEE_DATA,
                DiagnosticFieldState.PRESENT,
                DiagnosticFieldValue.BooleanValue(
                    plan.event.organizerEmail is ActiveSyncField.Value ||
                        attendeeCount in 1..MAX_MATERIALIZED_NON_ORGANIZER_ATTENDEES,
                ),
            )
        return DiagnosticCalendarFailureSnapshot(
            commandKind =
                if (plan.isAddition) {
                    DiagnosticCalendarCommandKind.ADD
                } else {
                    DiagnosticCalendarCommandKind.CHANGE
                },
            serverId = DiagnosticTextSanitizer.sanitize(plan.event.syncId),
            rule = failure.planningRule.toDiagnosticRule(),
            path = failure.calendarPath,
            fields = fields,
        )
    }

    private fun MutableList<DiagnosticCalendarFieldEntry>.addResponseSeries(
        source: DiagnosticCalendarFieldSource,
        item: ActiveSyncCalendarItem,
    ) {
        addTyped(source, DiagnosticCalendarField.UID, item.uid, ::text)
        addTyped(source, DiagnosticCalendarField.SUBJECT, item.subject, ::text)
        addTyped(source, DiagnosticCalendarField.BODY, item.body, ::text)
        addTyped(source, DiagnosticCalendarField.LOCATION, item.location, ::text)
        addTyped(source, DiagnosticCalendarField.START, item.start, DiagnosticFieldValue::Timestamp)
        addTyped(source, DiagnosticCalendarField.END, item.end, DiagnosticFieldValue::Timestamp)
        addTyped(source, DiagnosticCalendarField.ALL_DAY, item.allDay, DiagnosticFieldValue::BooleanValue)
        addTimeZone(source, item.timeZone)
        addRecurrence(source, item.recurrence)
        addCount(source, DiagnosticCalendarField.EXCEPTION_COUNT, item.exceptions)
        addTyped(source, DiagnosticCalendarField.ORGANIZER_EMAIL, item.organizerEmail, ::text)
        addTyped(source, DiagnosticCalendarField.ORGANIZER_NAME, item.organizerName, ::text)
        addCollection(source, DiagnosticCalendarField.ATTENDEES, DiagnosticCalendarField.ATTENDEE_COUNT, item.attendees)
        addMeeting(source, item.meetingStatus)
        addTyped(source, DiagnosticCalendarField.RESPONSE_TYPE, item.responseType, ::enumValue)
        addTyped(
            source,
            DiagnosticCalendarField.RESPONSE_REQUESTED,
            item.responseRequested,
            DiagnosticFieldValue::BooleanValue,
        )
        addTyped(source, DiagnosticCalendarField.AVAILABILITY, item.availability, ::enumValue)
        addTyped(source, DiagnosticCalendarField.SENSITIVITY, item.sensitivity, ::enumValue)
        addTyped(
            source,
            DiagnosticCalendarField.REMINDER_MINUTES,
            item.reminderMinutes,
            DiagnosticFieldValue::IntegerValue,
        )
    }

    private fun MutableList<DiagnosticCalendarFieldEntry>.addProviderSeries(
        source: DiagnosticCalendarFieldSource,
        event: ProviderEvent?,
    ) {
        addOptionalText(source, DiagnosticCalendarField.PROVIDER_SYNC_ID, event?.syncId)
        addTyped(source, DiagnosticCalendarField.UID, event?.uid ?: ActiveSyncField.Absent, ::text)
        addTyped(source, DiagnosticCalendarField.SUBJECT, event?.title ?: ActiveSyncField.Absent, ::text)
        addTyped(source, DiagnosticCalendarField.BODY, event?.description ?: ActiveSyncField.Absent, ::text)
        addTyped(source, DiagnosticCalendarField.LOCATION, event?.location ?: ActiveSyncField.Absent, ::text)
        addTyped(
            source,
            DiagnosticCalendarField.START,
            event?.start ?: ActiveSyncField.Absent,
            DiagnosticFieldValue::Timestamp,
        )
        addTyped(
            source,
            DiagnosticCalendarField.END,
            event?.end ?: ActiveSyncField.Absent,
            DiagnosticFieldValue::Timestamp,
        )
        addTyped(
            source,
            DiagnosticCalendarField.ALL_DAY,
            event?.allDay ?: ActiveSyncField.Absent,
            DiagnosticFieldValue::BooleanValue,
        )
        addTimeZone(source, event?.timeZone ?: ActiveSyncField.Absent)
        addTyped(
            source,
            DiagnosticCalendarField.RECURRENCE_RULE,
            event?.recurrenceRule ?: ActiveSyncField.Absent,
            ::text,
        )
        addCount(source, DiagnosticCalendarField.EXCEPTION_COUNT, event?.exceptions ?: ActiveSyncField.Absent)
        addTyped(
            source,
            DiagnosticCalendarField.ORGANIZER_EMAIL,
            event?.organizerEmail ?: ActiveSyncField.Absent,
            ::text,
        )
        addTyped(
            source,
            DiagnosticCalendarField.ORGANIZER_NAME,
            event?.organizerName ?: ActiveSyncField.Absent,
            ::text,
        )
        addCollection(
            source,
            DiagnosticCalendarField.ATTENDEES,
            DiagnosticCalendarField.ATTENDEE_COUNT,
            event?.attendees ?: ActiveSyncField.Absent,
        )
        addMeeting(source, event?.meetingStatus ?: ActiveSyncField.Absent)
        addTyped(source, DiagnosticCalendarField.RESPONSE_TYPE, event?.responseType ?: ActiveSyncField.Absent, ::enumValue)
        addTyped(
            source,
            DiagnosticCalendarField.RESPONSE_REQUESTED,
            event?.responseRequested ?: ActiveSyncField.Absent,
            DiagnosticFieldValue::BooleanValue,
        )
        addTyped(
            source,
            DiagnosticCalendarField.AVAILABILITY,
            event?.serverAvailability ?: ActiveSyncField.Absent,
            ::enumValue,
        )
        addTyped(source, DiagnosticCalendarField.STATUS, event?.status ?: ActiveSyncField.Absent, ::enumValue)
        addTyped(
            source,
            DiagnosticCalendarField.PROVIDER_AVAILABILITY,
            event?.availability ?: ActiveSyncField.Absent,
            ::enumValue,
        )
        addTyped(source, DiagnosticCalendarField.SELF_STATUS, event?.selfStatus ?: ActiveSyncField.Absent, ::enumValue)
        addTyped(
            source,
            DiagnosticCalendarField.EVENT_COLOR,
            event?.eventColor ?: ActiveSyncField.Absent,
            DiagnosticFieldValue::IntegerValue,
        )
        addTyped(source, DiagnosticCalendarField.ACCESS_LEVEL, event?.accessLevel ?: ActiveSyncField.Absent, ::enumValue)
        addTyped(
            source,
            DiagnosticCalendarField.REMINDER_MINUTES,
            event?.reminderMinutes ?: ActiveSyncField.Absent,
            DiagnosticFieldValue::IntegerValue,
        )
    }

    private fun MutableList<DiagnosticCalendarFieldEntry>.addEffectiveSeries(
        response: ActiveSyncCalendarItem,
        previous: ProviderEvent?,
    ) {
        val source = DiagnosticCalendarFieldSource.EFFECTIVE
        addTyped(source, DiagnosticCalendarField.UID, effective(response.uid, previous?.uid), ::text)
        addTyped(source, DiagnosticCalendarField.SUBJECT, effective(response.subject, previous?.title), ::text)
        addTyped(source, DiagnosticCalendarField.BODY, effective(response.body, previous?.description), ::text)
        addTyped(source, DiagnosticCalendarField.LOCATION, effective(response.location, previous?.location), ::text)
        addTyped(source, DiagnosticCalendarField.START, effective(response.start, previous?.start), DiagnosticFieldValue::Timestamp)
        addTyped(source, DiagnosticCalendarField.END, effective(response.end, previous?.end), DiagnosticFieldValue::Timestamp)
        addTyped(
            source,
            DiagnosticCalendarField.ALL_DAY,
            effective(response.allDay, previous?.allDay),
            DiagnosticFieldValue::BooleanValue,
        )
        addTimeZone(source, effective(response.timeZone, previous?.timeZone))
        if (response.recurrence == ActiveSyncField.Absent) {
            addTyped(
                source,
                DiagnosticCalendarField.RECURRENCE_RULE,
                previous?.recurrenceRule ?: ActiveSyncField.Absent,
                ::text,
            )
        } else {
            addRecurrence(source, response.recurrence)
        }
        addCount(source, DiagnosticCalendarField.EXCEPTION_COUNT, effectiveCount(response.exceptions, previous?.exceptions))
        addTyped(
            source,
            DiagnosticCalendarField.ORGANIZER_EMAIL,
            effective(response.organizerEmail, previous?.organizerEmail),
            ::text,
        )
        addTyped(
            source,
            DiagnosticCalendarField.ORGANIZER_NAME,
            effective(response.organizerName, previous?.organizerName),
            ::text,
        )
        addCollection(
            source,
            DiagnosticCalendarField.ATTENDEES,
            DiagnosticCalendarField.ATTENDEE_COUNT,
            effectiveCount(response.attendees, previous?.attendees),
        )
        addMeeting(source, effective(response.meetingStatus, previous?.meetingStatus))
        addTyped(
            source,
            DiagnosticCalendarField.RESPONSE_TYPE,
            effective(response.responseType, previous?.responseType),
            ::enumValue,
        )
        addTyped(
            source,
            DiagnosticCalendarField.RESPONSE_REQUESTED,
            effective(response.responseRequested, previous?.responseRequested),
            DiagnosticFieldValue::BooleanValue,
        )
        addTyped(
            source,
            DiagnosticCalendarField.AVAILABILITY,
            effective(response.availability, previous?.serverAvailability),
            ::enumValue,
        )
        addTyped(
            source,
            DiagnosticCalendarField.REMINDER_MINUTES,
            effective(response.reminderMinutes, previous?.reminderMinutes),
            DiagnosticFieldValue::IntegerValue,
        )
    }

    private fun MutableList<DiagnosticCalendarFieldEntry>.addSelectedException(
        response: ActiveSyncCalendarItem,
        previous: ProviderEvent?,
        path: CalendarMappingPath,
    ) {
        val index = (path as? CalendarMappingPath.Exception)?.index ?: return
        val responseException =
            (response.exceptions as? ActiveSyncField.Value)
                ?.value
                ?.getOrNull(index)
        if (responseException != null) {
            addException(DiagnosticCalendarFieldSource.EXCEPTION, responseException)
            return
        }
        if (response.exceptions != ActiveSyncField.Absent) return
        val priorSeries = previous ?: return
        val priorException =
            (priorSeries.exceptions as? ActiveSyncField.Value)
                ?.value
                ?.getOrNull(index)
                ?: return
        addProviderException(priorSeries, priorException)
    }

    private fun MutableList<DiagnosticCalendarFieldEntry>.addSelectedProviderException(
        event: ProviderEvent,
        path: DiagnosticCalendarPath,
    ) {
        val index = (path as? DiagnosticCalendarPath.Exception)?.index ?: return
        val exception = (event.exceptions as? ActiveSyncField.Value)?.value?.getOrNull(index) ?: return
        addProviderException(event, exception)
    }

    private fun ProviderEvent.selectedException(path: DiagnosticCalendarPath): ProviderCalendarException? {
        val index = (path as? DiagnosticCalendarPath.Exception)?.index ?: return null
        return (exceptions as? ActiveSyncField.Value)?.value?.getOrNull(index)
    }

    private fun ProviderCalendarException.durationMillis(series: ProviderEvent): Long? {
        val startValue = (start as? ActiveSyncField.Value)?.value ?: originalInstance
        val endValue =
            (end as? ActiveSyncField.Value)?.value
                ?: durationMillis(series.start, series.end)?.let(startValue::plusMillis)
                ?: return null
        return endValue.toEpochMilli() - startValue.toEpochMilli()
    }

    private fun ProviderEvent.failedReminder(
        path: DiagnosticCalendarPath,
        exception: ProviderCalendarException?,
    ): ActiveSyncField<Int> =
        when (path) {
            DiagnosticCalendarPath.Event -> reminderMinutes
            is DiagnosticCalendarPath.Exception ->
                exception?.let { effective(it.reminderMinutes, reminderMinutes) } ?: ActiveSyncField.Absent
        }

    private fun ProviderEvent.failedAttendees(
        path: DiagnosticCalendarPath,
        exception: ProviderCalendarException?,
    ) =
        when (path) {
            DiagnosticCalendarPath.Event -> attendees
            is DiagnosticCalendarPath.Exception ->
                exception?.let { effective(it.attendees, attendees) } ?: ActiveSyncField.Absent
        }

    private fun MutableList<DiagnosticCalendarFieldEntry>.addProviderException(
        series: ProviderEvent,
        exception: ProviderCalendarException,
    ) {
        val source = DiagnosticCalendarFieldSource.EXCEPTION
        addTyped(
            source,
            DiagnosticCalendarField.EXCEPTION_INSTANCE,
            ActiveSyncField.Value(exception.originalInstance),
            DiagnosticFieldValue::Timestamp,
        )
        addTyped(
            source,
            DiagnosticCalendarField.EXCEPTION_DELETED,
            ActiveSyncField.Value(exception.deleted),
            DiagnosticFieldValue::BooleanValue,
        )
        addTyped(source, DiagnosticCalendarField.SUBJECT, exception.title, ::text)
        addTyped(source, DiagnosticCalendarField.BODY, exception.description, ::text)
        addTyped(source, DiagnosticCalendarField.LOCATION, exception.location, ::text)
        addTyped(source, DiagnosticCalendarField.START, exception.start, DiagnosticFieldValue::Timestamp)
        addTyped(source, DiagnosticCalendarField.END, exception.end, DiagnosticFieldValue::Timestamp)
        addTyped(source, DiagnosticCalendarField.ALL_DAY, exception.allDay, DiagnosticFieldValue::BooleanValue)
        addTyped(
            source,
            DiagnosticCalendarField.REMINDER_MINUTES,
            exception.reminderMinutes,
            DiagnosticFieldValue::IntegerValue,
        )
        addCollection(
            source,
            DiagnosticCalendarField.ATTENDEES,
            DiagnosticCalendarField.ATTENDEE_COUNT,
            exception.attendees,
        )
        addMeeting(source, exception.meetingStatus)
        addTyped(source, DiagnosticCalendarField.RESPONSE_TYPE, exception.responseType, ::enumValue)
        addTyped(
            source,
            DiagnosticCalendarField.RESPONSE_REQUESTED,
            exception.responseRequested,
            DiagnosticFieldValue::BooleanValue,
        )
        addTyped(source, DiagnosticCalendarField.AVAILABILITY, exception.serverAvailability, ::enumValue)
        addTyped(source, DiagnosticCalendarField.ACCESS_LEVEL, exception.accessLevel, ::enumValue)
        addTyped(source, DiagnosticCalendarField.STATUS, exception.status, ::enumValue)
        addTyped(
            source,
            DiagnosticCalendarField.PROVIDER_AVAILABILITY,
            exception.availability,
            ::enumValue,
        )
        addTyped(source, DiagnosticCalendarField.SELF_STATUS, exception.selfStatus, ::enumValue)
        addTyped(
            source,
            DiagnosticCalendarField.EVENT_COLOR,
            exception.eventColor,
            DiagnosticFieldValue::IntegerValue,
        )
        addTyped(
            source,
            DiagnosticCalendarField.EXCEPTION_RESPONSE_OVERRIDE,
            exception.responseTypeOverride,
            ::enumValue,
        )
        addOptionalText(source, DiagnosticCalendarField.ORIGINAL_SYNC_ID, series.syncId)
        addTyped(
            source,
            DiagnosticCalendarField.ORIGINAL_INSTANCE_TIME,
            ActiveSyncField.Value(exception.originalInstance),
            DiagnosticFieldValue::Timestamp,
        )
        addTyped(
            source,
            DiagnosticCalendarField.ORIGINAL_ALL_DAY,
            series.allDay,
            DiagnosticFieldValue::BooleanValue,
        )
    }

    private fun MutableList<DiagnosticCalendarFieldEntry>.addException(
        source: DiagnosticCalendarFieldSource,
        exception: ActiveSyncCalendarException,
    ) {
        addTyped(
            source,
            DiagnosticCalendarField.EXCEPTION_INSTANCE,
            ActiveSyncField.Value(exception.instanceStart),
            DiagnosticFieldValue::Timestamp,
        )
        addTyped(
            source,
            DiagnosticCalendarField.EXCEPTION_DELETED,
            ActiveSyncField.Value(exception.deleted),
            DiagnosticFieldValue::BooleanValue,
        )
        addTyped(source, DiagnosticCalendarField.SUBJECT, exception.subject, ::text)
        addTyped(source, DiagnosticCalendarField.BODY, exception.body, ::text)
        addTyped(source, DiagnosticCalendarField.LOCATION, exception.location, ::text)
        addTyped(source, DiagnosticCalendarField.START, exception.start, DiagnosticFieldValue::Timestamp)
        addTyped(source, DiagnosticCalendarField.END, exception.end, DiagnosticFieldValue::Timestamp)
        addTyped(source, DiagnosticCalendarField.ALL_DAY, exception.allDay, DiagnosticFieldValue::BooleanValue)
        addTyped(
            source,
            DiagnosticCalendarField.REMINDER_MINUTES,
            exception.reminderMinutes,
            DiagnosticFieldValue::IntegerValue,
        )
        addCollection(
            source,
            DiagnosticCalendarField.ATTENDEES,
            DiagnosticCalendarField.ATTENDEE_COUNT,
            exception.attendees,
        )
        addMeeting(source, exception.meetingStatus)
        addTyped(source, DiagnosticCalendarField.RESPONSE_TYPE, exception.responseType, ::enumValue)
        addTyped(
            source,
            DiagnosticCalendarField.RESPONSE_REQUESTED,
            exception.responseRequested,
            DiagnosticFieldValue::BooleanValue,
        )
        addTyped(source, DiagnosticCalendarField.AVAILABILITY, exception.availability, ::enumValue)
        addTyped(source, DiagnosticCalendarField.SENSITIVITY, exception.sensitivity, ::enumValue)
    }

    private fun MutableList<DiagnosticCalendarFieldEntry>.addMeeting(
        source: DiagnosticCalendarFieldSource,
        meeting: ActiveSyncField<ActiveSyncMeetingStatus>,
    ) {
        addNested(source, DiagnosticCalendarField.MEETING_STATUS_RAW, meeting) {
            DiagnosticFieldValue.IntegerValue(it.rawValue)
        }
        addNested(source, DiagnosticCalendarField.MEETING_IS_MEETING, meeting) {
            DiagnosticFieldValue.BooleanValue(it.isMeeting)
        }
        addNested(source, DiagnosticCalendarField.MEETING_IS_RECEIVED, meeting) {
            DiagnosticFieldValue.BooleanValue(it.isReceived)
        }
        addNested(source, DiagnosticCalendarField.MEETING_IS_CANCELLED, meeting) {
            DiagnosticFieldValue.BooleanValue(it.isCancelled)
        }
        addNested(source, DiagnosticCalendarField.MEETING_IS_FORWARDED, meeting) {
            DiagnosticFieldValue.BooleanValue(it.isForwarded)
        }
    }

    private fun MutableList<DiagnosticCalendarFieldEntry>.addTimeZone(
        source: DiagnosticCalendarFieldSource,
        timeZone: ActiveSyncField<ActiveSyncTimeZone>,
    ) {
        addNested(source, DiagnosticCalendarField.TIME_ZONE_BIAS_MINUTES, timeZone) {
            DiagnosticFieldValue.IntegerValue(it.biasMinutes)
        }
        addNested(source, DiagnosticCalendarField.TIME_ZONE_STANDARD_NAME, timeZone) { text(it.standardName) }
        addNested(source, DiagnosticCalendarField.TIME_ZONE_STANDARD_TRANSITION, timeZone) {
            text(it.standardTransition.stableValue())
        }
        addNested(source, DiagnosticCalendarField.TIME_ZONE_STANDARD_BIAS_MINUTES, timeZone) {
            DiagnosticFieldValue.IntegerValue(it.standardBiasMinutes)
        }
        addNested(source, DiagnosticCalendarField.TIME_ZONE_DAYLIGHT_NAME, timeZone) { text(it.daylightName) }
        addNested(source, DiagnosticCalendarField.TIME_ZONE_DAYLIGHT_TRANSITION, timeZone) {
            text(it.daylightTransition.stableValue())
        }
        addNested(source, DiagnosticCalendarField.TIME_ZONE_DAYLIGHT_BIAS_MINUTES, timeZone) {
            DiagnosticFieldValue.IntegerValue(it.daylightBiasMinutes)
        }
    }

    private fun MutableList<DiagnosticCalendarFieldEntry>.addRecurrence(
        source: DiagnosticCalendarFieldSource,
        recurrence: ActiveSyncField<ActiveSyncRecurrence>,
    ) {
        addNested(source, DiagnosticCalendarField.RECURRENCE_TYPE, recurrence) { enumValue(it.type) }
        addNested(source, DiagnosticCalendarField.RECURRENCE_INTERVAL, recurrence) {
            DiagnosticFieldValue.IntegerValue(it.interval)
        }
        addNullableNested(source, DiagnosticCalendarField.RECURRENCE_DAY_OF_WEEK_MASK, recurrence) {
            it.dayOfWeekMask?.let(DiagnosticFieldValue::IntegerValue)
        }
        addNullableNested(source, DiagnosticCalendarField.RECURRENCE_DAY_OF_MONTH, recurrence) {
            it.dayOfMonth?.let(DiagnosticFieldValue::IntegerValue)
        }
        addNullableNested(source, DiagnosticCalendarField.RECURRENCE_WEEK_OF_MONTH, recurrence) {
            it.weekOfMonth?.let(DiagnosticFieldValue::IntegerValue)
        }
        addNullableNested(source, DiagnosticCalendarField.RECURRENCE_MONTH_OF_YEAR, recurrence) {
            it.monthOfYear?.let(DiagnosticFieldValue::IntegerValue)
        }
        addNullableNested(source, DiagnosticCalendarField.RECURRENCE_FIRST_DAY_OF_WEEK, recurrence) {
            it.firstDayOfWeek?.let(DiagnosticFieldValue::IntegerValue)
        }
        addNested(source, DiagnosticCalendarField.RECURRENCE_END_KIND, recurrence) {
            enumValue(
                when (it.end) {
                    ActiveSyncRecurrenceEnd.Infinite -> "INFINITE"
                    is ActiveSyncRecurrenceEnd.Count -> "COUNT"
                    is ActiveSyncRecurrenceEnd.Until -> "UNTIL"
                },
            )
        }
        addNullableNested(source, DiagnosticCalendarField.RECURRENCE_OCCURRENCES, recurrence) {
            (it.end as? ActiveSyncRecurrenceEnd.Count)?.occurrences?.let(DiagnosticFieldValue::IntegerValue)
        }
        addNullableNested(source, DiagnosticCalendarField.RECURRENCE_UNTIL, recurrence) {
            (it.end as? ActiveSyncRecurrenceEnd.Until)?.instant?.let(DiagnosticFieldValue::Timestamp)
        }
    }

    private fun <T> MutableList<DiagnosticCalendarFieldEntry>.addTyped(
        source: DiagnosticCalendarFieldSource,
        field: DiagnosticCalendarField,
        value: ActiveSyncField<T>,
        transform: (T) -> DiagnosticFieldValue,
    ) {
        val state = value.state()
        val projected =
            (value as? ActiveSyncField.Value)
                ?.value
                ?.takeIf { field.policy == DiagnosticCalendarFieldPolicy.FULL_VALUE }
                ?.let(transform)
        add(DiagnosticCalendarFieldEntry(source, field, state, projected))
    }

    private fun <T> MutableList<DiagnosticCalendarFieldEntry>.addNested(
        source: DiagnosticCalendarFieldSource,
        field: DiagnosticCalendarField,
        container: ActiveSyncField<T>,
        transform: (T) -> DiagnosticFieldValue,
    ) {
        val value = (container as? ActiveSyncField.Value)?.value?.let(transform)
        add(DiagnosticCalendarFieldEntry(source, field, container.state(), value))
    }

    private fun <T> MutableList<DiagnosticCalendarFieldEntry>.addNullableNested(
        source: DiagnosticCalendarFieldSource,
        field: DiagnosticCalendarField,
        container: ActiveSyncField<T>,
        transform: (T) -> DiagnosticFieldValue?,
    ) {
        val projected = (container as? ActiveSyncField.Value)?.value?.let(transform)
        val state =
            when {
                container == ActiveSyncField.Absent -> DiagnosticFieldState.ABSENT
                container == ActiveSyncField.Empty -> DiagnosticFieldState.EMPTY
                projected == null -> DiagnosticFieldState.ABSENT
                else -> DiagnosticFieldState.PRESENT
            }
        add(DiagnosticCalendarFieldEntry(source, field, state, projected))
    }

    private fun MutableList<DiagnosticCalendarFieldEntry>.addOptionalText(
        source: DiagnosticCalendarFieldSource,
        field: DiagnosticCalendarField,
        value: String?,
    ) {
        add(
            DiagnosticCalendarFieldEntry(
                source,
                field,
                if (value == null) DiagnosticFieldState.ABSENT else DiagnosticFieldState.PRESENT,
                value?.let(::text),
            ),
        )
    }

    private fun <T> MutableList<DiagnosticCalendarFieldEntry>.addCollection(
        source: DiagnosticCalendarFieldSource,
        field: DiagnosticCalendarField,
        countField: DiagnosticCalendarField,
        value: ActiveSyncField<List<T>>,
    ) {
        add(DiagnosticCalendarFieldEntry(source, field, value.state()))
        addCount(source, countField, value)
    }

    private fun <T> MutableList<DiagnosticCalendarFieldEntry>.addCount(
        source: DiagnosticCalendarFieldSource,
        field: DiagnosticCalendarField,
        value: ActiveSyncField<List<T>>,
    ) {
        val count = (value as? ActiveSyncField.Value)?.value?.size?.coerceAtMost(MAX_DIAGNOSTIC_COLLECTION_COUNT)
        add(
            DiagnosticCalendarFieldEntry(
                source,
                field,
                if (value == ActiveSyncField.Absent) DiagnosticFieldState.ABSENT else DiagnosticFieldState.PRESENT,
                count?.let(DiagnosticFieldValue::Count),
            ),
        )
    }

    private fun <T> effective(
        response: ActiveSyncField<T>,
        previous: ActiveSyncField<T>?,
    ): ActiveSyncField<T> =
        if (response == ActiveSyncField.Absent) previous ?: ActiveSyncField.Absent else response

    private fun effectiveCount(
        response: ActiveSyncField<List<*>>,
        previous: ActiveSyncField<List<*>>?,
    ): ActiveSyncField<List<*>> =
        when {
            response != ActiveSyncField.Absent -> response
            previous != null -> previous
            else -> ActiveSyncField.Absent
        }

    private fun ActiveSyncCalendarItem.failedRange(
        path: CalendarMappingPath,
        eventStart: ActiveSyncField<Instant>,
        eventEnd: ActiveSyncField<Instant>,
    ): Pair<ActiveSyncField<Instant>, ActiveSyncField<Instant>> {
        val index = (path as? CalendarMappingPath.Exception)?.index ?: return eventStart to eventEnd
        val exception = (exceptions as? ActiveSyncField.Value)?.value?.getOrNull(index)
            ?: return ActiveSyncField.Absent to ActiveSyncField.Absent
        val start =
            if (exception.start == ActiveSyncField.Absent) {
                ActiveSyncField.Value(exception.instanceStart)
            } else {
                exception.start
            }
        return start to exception.end
    }

    private fun durationMillis(
        start: ActiveSyncField<Instant>,
        end: ActiveSyncField<Instant>,
    ): Long? {
        val startValue = (start as? ActiveSyncField.Value)?.value ?: return null
        val endValue = (end as? ActiveSyncField.Value)?.value ?: return null
        return endValue.toEpochMilli() - startValue.toEpochMilli()
    }

    private fun ActiveSyncSystemTime.stableValue(): String =
        listOf(year, month, dayOfWeek, day, hour, minute, second, milliseconds).joinToString(",")

    private fun <T> ActiveSyncField<T>.state(): DiagnosticFieldState =
        when (this) {
            ActiveSyncField.Absent -> DiagnosticFieldState.ABSENT
            ActiveSyncField.Empty -> DiagnosticFieldState.EMPTY
            is ActiveSyncField.Value -> DiagnosticFieldState.PRESENT
        }

    private fun text(value: String): DiagnosticFieldValue =
        DiagnosticFieldValue.Text(DiagnosticTextSanitizer.sanitize(value))

    private fun enumValue(value: Enum<*>): DiagnosticFieldValue =
        DiagnosticFieldValue.EnumName(value.name)

    private fun enumValue(value: String): DiagnosticFieldValue =
        DiagnosticFieldValue.EnumName(value)

    private fun relationship(
        start: ActiveSyncField<Instant>,
        end: ActiveSyncField<Instant>,
    ): DiagnosticRelationship {
        val startValue = (start as? ActiveSyncField.Value)?.value ?: return DiagnosticRelationship.NOT_COMPARABLE
        val endValue = (end as? ActiveSyncField.Value)?.value ?: return DiagnosticRelationship.NOT_COMPARABLE
        return when {
            startValue < endValue -> DiagnosticRelationship.BEFORE
            startValue == endValue -> DiagnosticRelationship.EQUAL
            else -> DiagnosticRelationship.AFTER
        }
    }

    private fun CalendarMappingPath.toDiagnosticPath(): DiagnosticCalendarPath =
        when (this) {
            CalendarMappingPath.Event -> DiagnosticCalendarPath.Event
            is CalendarMappingPath.Exception -> DiagnosticCalendarPath.Exception(index)
        }

    private fun CalendarMappingRule.toDiagnosticRule(): DiagnosticCalendarRule =
        when (this) {
            CalendarMappingRule.MEETING_RESPONSE_EMPTY -> DiagnosticCalendarRule.MEETING_RESPONSE_EMPTY
            CalendarMappingRule.RECEIVED_MEETING_RESPONSE_MISSING ->
                DiagnosticCalendarRule.RECEIVED_MEETING_RESPONSE_MISSING
            CalendarMappingRule.RECEIVED_MEETING_RESPONSE_EMPTY ->
                DiagnosticCalendarRule.RECEIVED_MEETING_RESPONSE_EMPTY
            CalendarMappingRule.EVENT_TIME_RANGE_INVALID -> DiagnosticCalendarRule.EVENT_TIME_RANGE_INVALID
            CalendarMappingRule.EVENT_ALL_DAY_NOT_UTC_ALIGNED ->
                DiagnosticCalendarRule.EVENT_ALL_DAY_NOT_UTC_ALIGNED
            CalendarMappingRule.EXCEPTION_TIME_RANGE_INVALID ->
                DiagnosticCalendarRule.EXCEPTION_TIME_RANGE_INVALID
            CalendarMappingRule.RECURRENCE_INTERVAL_INVALID ->
                DiagnosticCalendarRule.RECURRENCE_INTERVAL_INVALID
            CalendarMappingRule.RECURRENCE_COUNT_INVALID -> DiagnosticCalendarRule.RECURRENCE_COUNT_INVALID
            CalendarMappingRule.RECURRENCE_WEEKDAYS_MISSING ->
                DiagnosticCalendarRule.RECURRENCE_WEEKDAYS_MISSING
            CalendarMappingRule.RECURRENCE_WEEKDAY_MASK_INVALID ->
                DiagnosticCalendarRule.RECURRENCE_WEEKDAY_MASK_INVALID
            CalendarMappingRule.RECURRENCE_FIRST_WEEKDAY_INVALID ->
                DiagnosticCalendarRule.RECURRENCE_FIRST_WEEKDAY_INVALID
            CalendarMappingRule.RECURRENCE_WEEK_POSITION_INVALID ->
                DiagnosticCalendarRule.RECURRENCE_WEEK_POSITION_INVALID
            CalendarMappingRule.RECURRENCE_DAY_OF_MONTH_INVALID ->
                DiagnosticCalendarRule.RECURRENCE_DAY_OF_MONTH_INVALID
            CalendarMappingRule.RECURRENCE_MONTH_OF_YEAR_INVALID ->
                DiagnosticCalendarRule.RECURRENCE_MONTH_OF_YEAR_INVALID
        }

    private fun CalendarPlanningRule.toDiagnosticRule(): DiagnosticCalendarRule =
        when (this) {
            CalendarPlanningRule.OWNED_CALENDAR_SCOPE -> DiagnosticCalendarRule.OWNED_CALENDAR_SCOPE
            CalendarPlanningRule.DUPLICATE_SERVER_ID -> DiagnosticCalendarRule.DUPLICATE_SERVER_ID
            CalendarPlanningRule.UNSUPPORTED_CALENDAR_CHANGE ->
                DiagnosticCalendarRule.UNSUPPORTED_CALENDAR_CHANGE
            CalendarPlanningRule.CALENDAR_EVENT_MAPPING -> DiagnosticCalendarRule.CALENDAR_EVENT_MAPPING
            CalendarPlanningRule.PROVIDER_BATCH_SCOPE -> DiagnosticCalendarRule.PROVIDER_BATCH_SCOPE
            CalendarPlanningRule.PROVIDER_REQUIRED_VALUE_NULL ->
                DiagnosticCalendarRule.PROVIDER_REQUIRED_VALUE_NULL
            CalendarPlanningRule.REFRESH_EXCEPTIONS_NEW_SERIES ->
                DiagnosticCalendarRule.REFRESH_EXCEPTIONS_NEW_SERIES
            CalendarPlanningRule.ADDITION_TIME_RANGE_MISSING ->
                DiagnosticCalendarRule.ADDITION_TIME_RANGE_MISSING
            CalendarPlanningRule.RECURRING_DURATION_MISSING ->
                DiagnosticCalendarRule.RECURRING_DURATION_MISSING
            CalendarPlanningRule.RECURRING_TIME_ZONE_UNREPRESENTABLE ->
                DiagnosticCalendarRule.RECURRING_TIME_ZONE_UNREPRESENTABLE
            CalendarPlanningRule.TIME_ZONE_UNREPRESENTABLE ->
                DiagnosticCalendarRule.TIME_ZONE_UNREPRESENTABLE
            CalendarPlanningRule.SUB_BATCH_RESULT_MISSING ->
                DiagnosticCalendarRule.SUB_BATCH_RESULT_MISSING
            CalendarPlanningRule.SUB_BATCH_NOT_PENDING -> DiagnosticCalendarRule.SUB_BATCH_NOT_PENDING
            CalendarPlanningRule.SUB_BATCH_RESULT_COUNT_INVALID ->
                DiagnosticCalendarRule.SUB_BATCH_RESULT_COUNT_INVALID
            CalendarPlanningRule.INSERT_RESULTS_INVALID -> DiagnosticCalendarRule.INSERT_RESULTS_INVALID
            CalendarPlanningRule.ROW_REFERENCE_INVALID -> DiagnosticCalendarRule.ROW_REFERENCE_INVALID
            CalendarPlanningRule.FORWARD_INSERT_REFERENCE ->
                DiagnosticCalendarRule.FORWARD_INSERT_REFERENCE
            CalendarPlanningRule.NON_INSERT_REFERENCE -> DiagnosticCalendarRule.NON_INSERT_REFERENCE
            CalendarPlanningRule.INSERT_RESULT_MISSING -> DiagnosticCalendarRule.INSERT_RESULT_MISSING
        }

    private const val MAX_DIAGNOSTIC_COLLECTION_COUNT: Int = 10_000
}
