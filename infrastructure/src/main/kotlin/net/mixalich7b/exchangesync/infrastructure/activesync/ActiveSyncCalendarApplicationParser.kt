package net.mixalich7b.exchangesync.infrastructure.activesync

import java.time.LocalTime
import java.time.ZoneOffset
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncAttendee
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarException
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarItem
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarMutation
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncField
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncRecurrence
import net.mixalich7b.exchangesync.core.sync.ActiveSyncVersion
import net.mixalich7b.exchangesync.infrastructure.activesync.calendar.ActiveSyncCalendarValueException
import net.mixalich7b.exchangesync.infrastructure.activesync.calendar.ActiveSyncCalendarValueParsers
import net.mixalich7b.exchangesync.infrastructure.activesync.calendar.ActiveSyncRecurrenceParser
import net.mixalich7b.exchangesync.infrastructure.activesync.calendar.ActiveSyncTimeZoneParser
import net.mixalich7b.exchangesync.infrastructure.activesync.calendar.CurrentUserResponseResolver
import net.mixalich7b.exchangesync.infrastructure.activesync.calendar.RawActiveSyncRecurrence
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.AirSync
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.AirSyncBase
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.Calendar
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlElement
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlTag
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarField

internal object ActiveSyncCalendarApplicationParser {
    fun parse(
        command: RawCalendarCommand,
        profileEmail: String,
        version: ActiveSyncVersion = ActiveSyncVersion.V14_0,
    ): ActiveSyncCalendarMutation =
        try {
            when (command.kind) {
                RawCalendarCommandKind.DELETE -> ActiveSyncCalendarMutation.Delete(command.serverId, soft = false)
                RawCalendarCommandKind.SOFT_DELETE -> ActiveSyncCalendarMutation.Delete(command.serverId, soft = true)
                RawCalendarCommandKind.ADD,
                RawCalendarCommandKind.CHANGE,
                -> parseUpsert(command, profileEmail, version)
            }
        } catch (error: ActiveSyncProtocolDataException) {
            throw error.withCalendarContext(command, profileEmail)
        } catch (error: ActiveSyncCalendarValueException) {
            throw ActiveSyncProtocolDataException(
                message = "Calendar application data is malformed",
                reason = error.reason,
                failedField = error.field,
                attendeeIndex = error.attendeeIndex,
                currentUserAttendeeCount = error.currentUserAttendeeCount,
                cause = error,
            ).withCalendarContext(command, profileEmail)
        } catch (error: IllegalArgumentException) {
            throw ActiveSyncProtocolDataException(
                message = "Calendar application data is malformed",
                reason = ActiveSyncValidationReason.INVALID_APPLICATION_DATA,
                cause = error,
            ).withCalendarContext(command, profileEmail)
        }

    private fun ActiveSyncProtocolDataException.withCalendarContext(
        command: RawCalendarCommand,
        profileEmail: String,
    ): ActiveSyncProtocolDataException {
        val contextual = withContext(command.kind.name, command.serverId)
        val snapshot =
            runCatching {
                ActiveSyncCalendarFailureProjector.project(
                    command = command,
                    reason = contextual.reason,
                    exceptionIndex = contextual.exceptionIndex,
                    failedField = contextual.failedField,
                    attendeeIndex = contextual.attendeeIndex,
                    currentUserAttendeeCount = contextual.currentUserAttendeeCount,
                    profileEmail = profileEmail,
                )
            }.getOrNull()
        return if (snapshot == null) contextual else contextual.withCalendarFailureSnapshot(snapshot)
    }

    private fun parseUpsert(
        command: RawCalendarCommand,
        profileEmail: String,
        version: ActiveSyncVersion,
    ): ActiveSyncCalendarMutation.Upsert {
        val data = command.applicationData
            ?: throw ActiveSyncProtocolDataException("Calendar upsert has no application data")
        if (data.tag != AirSync.APPLICATION_DATA) {
            throw ActiveSyncProtocolDataException("Calendar upsert application data has an invalid root")
        }
        var item =
            ActiveSyncCalendarItem(
                serverId = command.serverId,
                uid = data.textField(Calendar.UID),
                subject = data.textField(Calendar.SUBJECT),
                body = data.bodyField(),
                location = data.locationField(version),
                start = data.valueField(Calendar.START_TIME, ActiveSyncCalendarValueParsers::parseDateTime),
                end = data.valueField(Calendar.END_TIME, ActiveSyncCalendarValueParsers::parseDateTime),
                allDay = data.valueField(Calendar.ALL_DAY_EVENT, ::parseProtocolBoolean),
                timeZone = data.valueField(Calendar.TIMEZONE, ActiveSyncTimeZoneParser::parse),
                recurrence = data.recurrenceField(),
                exceptions = data.exceptionsField(version, profileEmail),
                organizerEmail = data.textField(Calendar.ORGANIZER_EMAIL),
                organizerName = data.textField(Calendar.ORGANIZER_NAME),
                attendees = data.attendeesField(),
                meetingStatus = data.valueField(Calendar.MEETING_STATUS, ActiveSyncCalendarValueParsers::parseMeetingStatus),
                responseType = data.valueField(Calendar.RESPONSE_TYPE, ActiveSyncCalendarValueParsers::parseResponseType),
                responseRequested =
                    data.valueField(Calendar.RESPONSE_REQUESTED, ActiveSyncCalendarValueParsers::parseResponseRequested),
                availability = data.valueField(Calendar.BUSY_STATUS, ActiveSyncCalendarValueParsers::parseAvailability),
                sensitivity = data.valueField(Calendar.SENSITIVITY, ActiveSyncCalendarValueParsers::parseSensitivity),
                reminderMinutes = data.valueField(Calendar.REMINDER, ActiveSyncCalendarValueParsers::parseReminder),
            )
        val isAddition = command.kind == RawCalendarCommandKind.ADD
        if (isAddition) {
            validateRequiredAddition(item)
        }
        item = item.withResolvedReceivedMeetingResponse(profileEmail, requireResponse = isAddition)
        return ActiveSyncCalendarMutation.Upsert(item, isAddition)
    }

    private fun validateRequiredAddition(item: ActiveSyncCalendarItem) {
        val start = (item.start as? ActiveSyncField.Value)?.value
            ?: throw ActiveSyncProtocolDataException("Calendar Add start is missing")
        val end = (item.end as? ActiveSyncField.Value)?.value
            ?: throw ActiveSyncProtocolDataException("Calendar Add end is missing")
        if (!end.isAfter(start)) throw ActiveSyncProtocolDataException("Calendar Add time range is invalid")
        if ((item.allDay as? ActiveSyncField.Value)?.value == true) {
            if (
                start.atOffset(ZoneOffset.UTC).toLocalTime() != LocalTime.MIDNIGHT ||
                end.atOffset(ZoneOffset.UTC).toLocalTime() != LocalTime.MIDNIGHT
            ) {
                throw ActiveSyncProtocolDataException("All-day Calendar Add is not aligned to UTC dates")
            }
        }
    }

    private fun ActiveSyncCalendarItem.withResolvedReceivedMeetingResponse(
        profileEmail: String,
        requireResponse: Boolean,
    ): ActiveSyncCalendarItem {
        val status = when (val field = meetingStatus) {
            is ActiveSyncField.Value -> field.value
            ActiveSyncField.Absent ->
                return if (requireResponse) this else withAttendeeOnlyResponse(profileEmail)
            ActiveSyncField.Empty -> return this
        }
        if (!status.isMeeting || !status.isReceived || status.isCancelled) return this
        return when (responseType) {
            is ActiveSyncField.Value -> this
            ActiveSyncField.Empty -> throw ActiveSyncProtocolDataException("Received meeting response is empty")
            ActiveSyncField.Absent -> {
                val attendeeValues = when (val field = attendees) {
                    is ActiveSyncField.Value -> field.value
                    ActiveSyncField.Absent,
                    ActiveSyncField.Empty,
                    -> {
                        if (requireResponse) {
                            throw ActiveSyncProtocolDataException("Received meeting response cannot be classified")
                        }
                        return this
                    }
                }
                copy(
                    responseType =
                        ActiveSyncField.Value(
                            CurrentUserResponseResolver.resolveRequired(profileEmail, attendeeValues),
                        ),
                )
            }
        }
    }

    private fun ActiveSyncCalendarItem.withAttendeeOnlyResponse(profileEmail: String): ActiveSyncCalendarItem {
        if (responseType != ActiveSyncField.Absent) return this
        val attendeeValues = (attendees as? ActiveSyncField.Value)?.value ?: return this
        if (attendeeValues.none { attendee -> attendee.email.equals(profileEmail, ignoreCase = true) }) return this
        return copy(
            responseType =
                ActiveSyncField.Value(
                    CurrentUserResponseResolver.resolveRequired(profileEmail, attendeeValues),
                ),
        )
    }
}

private fun WbxmlElement.textField(tag: WbxmlTag): ActiveSyncField<String> =
    valueField(tag) { value -> value }

private fun <T> WbxmlElement.valueField(
    tag: WbxmlTag,
    parser: (String) -> T,
): ActiveSyncField<T> {
    val element = child(tag) ?: return ActiveSyncField.Absent
    val text = element.text
    return if (text.isNullOrEmpty()) {
        ActiveSyncField.Empty
    } else {
        try {
            ActiveSyncField.Value(parser(text))
        } catch (error: ActiveSyncCalendarValueException) {
            throw error.withContext(
                reason = tag.toValidationReason(error.reason),
                field = error.field ?: tag.toDiagnosticCalendarField(),
            )
        }
    }
}

private fun WbxmlElement.bodyField(): ActiveSyncField<String> {
    val modern = child(AirSyncBase.BODY)
    if (modern != null) {
        val data = modern.child(AirSyncBase.DATA)
        return if (data?.text.isNullOrEmpty()) ActiveSyncField.Empty else ActiveSyncField.Value(checkNotNull(data.text))
    }
    return textField(Calendar.BODY)
}

private fun WbxmlElement.attendeesField(
    optionalStatus: Boolean = false,
): ActiveSyncField<List<ActiveSyncAttendee>> {
    val container = child(Calendar.ATTENDEES) ?: return ActiveSyncField.Absent
    val elements = container.children(Calendar.ATTENDEE)
    if (elements.isEmpty()) return ActiveSyncField.Empty
    return ActiveSyncField.Value(
        elements.mapIndexed { index, attendee ->
            val email = attendee.child(Calendar.EMAIL)?.text
            val name = attendee.child(Calendar.NAME)?.text
            val status = attendee.child(Calendar.ATTENDEE_STATUS)?.text
            val type = attendee.child(Calendar.ATTENDEE_TYPE)?.text
            try {
                if (optionalStatus) {
                    ActiveSyncCalendarValueParsers.parseAttendeeWithOptionalStatus(email, name, status, type)
                } else {
                    ActiveSyncCalendarValueParsers.parseAttendee(email, name, status, type)
                }
            } catch (error: ActiveSyncCalendarValueException) {
                throw error.withContext(
                    reason = ActiveSyncValidationReason.INVALID_ATTENDEE,
                    attendeeIndex = index,
                )
            }
        },
    )
}

private fun WbxmlElement.recurrenceField(): ActiveSyncField<ActiveSyncRecurrence> {
    val recurrence = child(Calendar.RECURRENCE) ?: return ActiveSyncField.Absent
    if (recurrence.children.isEmpty()) return ActiveSyncField.Empty
    return ActiveSyncField.Value(
        ActiveSyncRecurrenceParser.parse(
            RawActiveSyncRecurrence(
                type = recurrence.child(Calendar.TYPE)?.text,
                interval = recurrence.child(Calendar.INTERVAL)?.text,
                dayOfWeek = recurrence.child(Calendar.DAY_OF_WEEK)?.text,
                dayOfMonth = recurrence.child(Calendar.DAY_OF_MONTH)?.text,
                weekOfMonth = recurrence.child(Calendar.WEEK_OF_MONTH)?.text,
                monthOfYear = recurrence.child(Calendar.MONTH_OF_YEAR)?.text,
                calendarType = recurrence.child(Calendar.CALENDAR_TYPE)?.text,
                isLeapMonth = recurrence.child(Calendar.IS_LEAP_MONTH)?.text,
                firstDayOfWeek = recurrence.child(Calendar.FIRST_DAY_OF_WEEK)?.text,
                occurrences = recurrence.child(Calendar.OCCURRENCES)?.text,
                until = recurrence.child(Calendar.UNTIL)?.text,
            ),
        ),
    )
}

private fun WbxmlElement.locationField(version: ActiveSyncVersion): ActiveSyncField<String> =
    when (version) {
        ActiveSyncVersion.V14_0,
        ActiveSyncVersion.V14_1,
        -> textField(Calendar.LOCATION)
        ActiveSyncVersion.V16_0,
        ActiveSyncVersion.V16_1,
        -> {
            val container = child(AirSyncBase.LOCATION) ?: return ActiveSyncField.Absent
            val displayName = container.child(AirSyncBase.DISPLAY_NAME)?.text
            if (displayName.isNullOrEmpty()) ActiveSyncField.Empty else ActiveSyncField.Value(displayName)
        }
    }

private fun WbxmlElement.exceptionsField(
    version: ActiveSyncVersion,
    profileEmail: String,
): ActiveSyncField<List<ActiveSyncCalendarException>> {
    val exceptions = child(Calendar.EXCEPTIONS) ?: return ActiveSyncField.Absent
    val elements = exceptions.children(Calendar.EXCEPTION)
    if (elements.isEmpty()) return ActiveSyncField.Empty
    return ActiveSyncField.Value(
        elements.mapIndexed { index, exception ->
            try {
                exception.parseCalendarException(version, profileEmail)
            } catch (error: ActiveSyncProtocolDataException) {
                throw error.withExceptionIndex(index)
            } catch (error: ActiveSyncCalendarValueException) {
                throw ActiveSyncProtocolDataException(
                    message = "Calendar exception application data is malformed",
                    reason = error.reason,
                    exceptionIndex = index,
                    failedField = error.field,
                    attendeeIndex = error.attendeeIndex,
                    currentUserAttendeeCount = error.currentUserAttendeeCount,
                    cause = error,
                )
            } catch (error: IllegalArgumentException) {
                throw ActiveSyncProtocolDataException(
                    message = "Calendar exception application data is malformed",
                    reason = ActiveSyncValidationReason.INVALID_APPLICATION_DATA,
                    exceptionIndex = index,
                    cause = error,
                )
            }
        },
    )
}

private fun WbxmlElement.parseCalendarException(
    version: ActiveSyncVersion,
    profileEmail: String,
): ActiveSyncCalendarException =
    ActiveSyncCalendarException(
        instanceStart = parseExceptionInstance(version),
        deleted = parseExceptionDeleted(),
        subject = textField(Calendar.SUBJECT),
        body = bodyField(),
        location = locationField(version),
        start = valueField(Calendar.START_TIME, ActiveSyncCalendarValueParsers::parseDateTime),
        end = valueField(Calendar.END_TIME, ActiveSyncCalendarValueParsers::parseDateTime),
        allDay = valueField(Calendar.ALL_DAY_EVENT, ::parseProtocolBoolean),
        reminderMinutes = valueField(Calendar.REMINDER, ActiveSyncCalendarValueParsers::parseReminder),
        attendees = attendeesField(optionalStatus = true),
        meetingStatus = valueField(Calendar.MEETING_STATUS, ActiveSyncCalendarValueParsers::parseMeetingStatus),
        responseType = valueField(Calendar.RESPONSE_TYPE, ActiveSyncCalendarValueParsers::parseResponseType),
        responseRequested = valueField(Calendar.RESPONSE_REQUESTED, ActiveSyncCalendarValueParsers::parseResponseRequested),
        availability = valueField(Calendar.BUSY_STATUS, ActiveSyncCalendarValueParsers::parseAvailability),
        sensitivity = valueField(Calendar.SENSITIVITY, ActiveSyncCalendarValueParsers::parseSensitivity),
    ).withAttendeeOnlyResponse(profileEmail)

private fun WbxmlElement.parseExceptionInstance(version: ActiveSyncVersion): java.time.Instant {
    val identityTag =
        when (version) {
            ActiveSyncVersion.V14_0,
            ActiveSyncVersion.V14_1,
            -> Calendar.EXCEPTION_START_TIME
            ActiveSyncVersion.V16_0,
            ActiveSyncVersion.V16_1,
            -> AirSyncBase.INSTANCE_ID
        }
    val value =
        child(identityTag)?.text
            ?: throw ActiveSyncProtocolDataException(
                message = "Calendar exception identity is missing",
                reason = ActiveSyncValidationReason.MISSING_REQUIRED_VALUE,
                failedField = DiagnosticCalendarField.EXCEPTION_INSTANCE,
            )
    return try {
        when (version) {
            ActiveSyncVersion.V14_0,
            ActiveSyncVersion.V14_1,
            -> ActiveSyncCalendarValueParsers.parseDateTime(value)
            ActiveSyncVersion.V16_0,
            ActiveSyncVersion.V16_1,
            -> ActiveSyncCalendarValueParsers.parseInstanceId(value)
        }
    } catch (error: ActiveSyncCalendarValueException) {
        throw error.withContext(field = DiagnosticCalendarField.EXCEPTION_INSTANCE)
    }
}

private fun WbxmlElement.parseExceptionDeleted(): Boolean {
    val value = child(Calendar.DELETED)?.text ?: return false
    return try {
        parseProtocolBoolean(value)
    } catch (error: ActiveSyncCalendarValueException) {
        throw error.withContext(field = DiagnosticCalendarField.EXCEPTION_DELETED)
    }
}

private fun ActiveSyncCalendarException.withAttendeeOnlyResponse(
    profileEmail: String,
): ActiveSyncCalendarException {
    if (responseType != ActiveSyncField.Absent) return this
    val attendeeValues = (attendees as? ActiveSyncField.Value)?.value ?: return this
    val inferred = CurrentUserResponseResolver.resolveOptional(profileEmail, attendeeValues) ?: return this
    return copy(responseType = ActiveSyncField.Value(inferred))
}

private fun parseProtocolBoolean(value: String): Boolean =
    when (value) {
        "0" -> false
        "1" -> true
        else -> throw ActiveSyncCalendarValueException("Invalid ActiveSync Boolean")
    }

private fun WbxmlTag.toDiagnosticCalendarField(): DiagnosticCalendarField? =
    when (this) {
        Calendar.UID -> DiagnosticCalendarField.UID
        Calendar.SUBJECT -> DiagnosticCalendarField.SUBJECT
        Calendar.BODY,
        AirSyncBase.DATA,
        -> DiagnosticCalendarField.BODY
        Calendar.LOCATION,
        AirSyncBase.DISPLAY_NAME,
        -> DiagnosticCalendarField.LOCATION
        Calendar.START_TIME -> DiagnosticCalendarField.START
        Calendar.END_TIME -> DiagnosticCalendarField.END
        Calendar.ALL_DAY_EVENT -> DiagnosticCalendarField.ALL_DAY
        Calendar.TIMEZONE -> DiagnosticCalendarField.TIME_ZONE_RAW
        Calendar.ORGANIZER_EMAIL -> DiagnosticCalendarField.ORGANIZER_EMAIL
        Calendar.ORGANIZER_NAME -> DiagnosticCalendarField.ORGANIZER_NAME
        Calendar.MEETING_STATUS -> DiagnosticCalendarField.MEETING_STATUS_RAW
        Calendar.RESPONSE_TYPE -> DiagnosticCalendarField.RESPONSE_TYPE
        Calendar.RESPONSE_REQUESTED -> DiagnosticCalendarField.RESPONSE_REQUESTED
        Calendar.BUSY_STATUS -> DiagnosticCalendarField.AVAILABILITY
        Calendar.SENSITIVITY -> DiagnosticCalendarField.SENSITIVITY
        Calendar.REMINDER -> DiagnosticCalendarField.REMINDER_MINUTES
        Calendar.EXCEPTION_START_TIME,
        AirSyncBase.INSTANCE_ID,
        -> DiagnosticCalendarField.EXCEPTION_INSTANCE
        Calendar.DELETED -> DiagnosticCalendarField.EXCEPTION_DELETED
        else -> null
    }

private fun WbxmlTag.toValidationReason(fallback: ActiveSyncValidationReason): ActiveSyncValidationReason =
    when (this) {
        Calendar.ALL_DAY_EVENT -> ActiveSyncValidationReason.INVALID_ALL_DAY
        Calendar.TIMEZONE -> ActiveSyncValidationReason.INVALID_TIME_ZONE
        Calendar.MEETING_STATUS,
        Calendar.RESPONSE_TYPE,
        Calendar.RESPONSE_REQUESTED,
        -> ActiveSyncValidationReason.INVALID_MEETING_RESPONSE
        else -> fallback
    }
