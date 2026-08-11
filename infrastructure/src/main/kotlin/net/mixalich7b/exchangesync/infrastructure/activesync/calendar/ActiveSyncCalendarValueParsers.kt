package net.mixalich7b.exchangesync.infrastructure.activesync.calendar

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Base64
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncAttendee
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncAttendeeStatus
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncAttendeeType
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncAvailability
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncField
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncMeetingStatus
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncRecurrence
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncRecurrenceEnd
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncRecurrenceType
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncResponseType
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncSensitivity
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncSystemTime
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncTimeZone

internal class ActiveSyncCalendarValueException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal data class ActiveSyncAllDayRange(
    val start: LocalDate,
    val endExclusive: LocalDate,
)

internal object ActiveSyncCalendarValueParsers {
    private val dateTimeFormatter =
        DateTimeFormatter
            .ofPattern("uuuuMMdd'T'HHmmss'Z'")
            .withResolverStyle(ResolverStyle.STRICT)
    fun parseDateTime(value: String): Instant =
        try {
            LocalDateTime.parse(value, dateTimeFormatter).toInstant(ZoneOffset.UTC)
        } catch (error: DateTimeParseException) {
            throw ActiveSyncCalendarValueException("Invalid ActiveSync date-time", error)
        }

    fun parseInstanceId(value: String): Instant =
        try {
            parseDateTime(value)
        } catch (error: ActiveSyncCalendarValueException) {
            throw ActiveSyncCalendarValueException("Invalid ActiveSync InstanceId", error)
        }

    fun parseAllDayRange(start: String, end: String): ActiveSyncAllDayRange {
        val startInstant = parseDateTime(start)
        val endInstant = parseDateTime(end)
        if (startInstant.atOffset(ZoneOffset.UTC).toLocalTime() != java.time.LocalTime.MIDNIGHT ||
            endInstant.atOffset(ZoneOffset.UTC).toLocalTime() != java.time.LocalTime.MIDNIGHT ||
            !endInstant.isAfter(startInstant)
        ) {
            throw ActiveSyncCalendarValueException("Invalid all-day boundaries")
        }
        return ActiveSyncAllDayRange(
            start = startInstant.atOffset(ZoneOffset.UTC).toLocalDate(),
            endExclusive = endInstant.atOffset(ZoneOffset.UTC).toLocalDate(),
        )
    }

    fun parseReminder(value: String): Int = value.parseInteger("reminder").also { parsed ->
        if (parsed < 0) throw ActiveSyncCalendarValueException("Invalid reminder")
    }

    fun <T> parseField(
        present: Boolean,
        text: String?,
        parser: (String) -> T,
    ): ActiveSyncField<T> =
        when {
            !present -> ActiveSyncField.Absent
            text.isNullOrEmpty() -> ActiveSyncField.Empty
            else -> ActiveSyncField.Value(parser(text))
        }

    fun parseMeetingStatus(value: String): ActiveSyncMeetingStatus {
        val raw = value.parseInteger("meeting status")
        if (raw != 0 && (raw !in 1..15 || raw and MEETING_BIT == 0)) {
            throw ActiveSyncCalendarValueException("Invalid meeting status")
        }
        return ActiveSyncMeetingStatus(
            rawValue = raw,
            isMeeting = raw and MEETING_BIT != 0,
            isReceived = raw and RECEIVED_BIT != 0,
            isCancelled = raw and CANCELLED_BIT != 0,
            isForwarded = raw and FORWARDED_BIT != 0,
        )
    }

    fun parseResponseType(value: String): ActiveSyncResponseType =
        enumByWireValue(value, "response type", ActiveSyncResponseType.entries) { it.wireValue }

    fun parseResponseRequested(value: String): Boolean =
        when (value) {
            "0" -> false
            "1" -> true
            else -> throw ActiveSyncCalendarValueException("Invalid response-requested value")
        }

    fun parseAvailability(value: String): ActiveSyncAvailability =
        enumByWireValue(value, "availability", ActiveSyncAvailability.entries) { it.wireValue }

    fun parseSensitivity(value: String): ActiveSyncSensitivity =
        enumByWireValue(value, "sensitivity", ActiveSyncSensitivity.entries) { it.wireValue }

    fun parseAttendee(
        email: String?,
        name: String?,
        status: String?,
        type: String?,
    ): ActiveSyncAttendee {
        val parsedEmail = email?.takeIf(String::isNotBlank)
            ?: throw ActiveSyncCalendarValueException("Attendee email is missing")
        val parsedName = name?.takeIf(String::isNotBlank)
            ?: throw ActiveSyncCalendarValueException("Attendee name is missing")
        val parsedStatus = status?.let { raw ->
            enumByWireValue(raw, "attendee status", ActiveSyncAttendeeStatus.entries) { it.wireValue }
        }
        val parsedType = type?.let { raw ->
            enumByWireValue(raw, "attendee type", ActiveSyncAttendeeType.entries) { it.wireValue }
        }
        return ActiveSyncAttendee(parsedEmail, parsedName, parsedStatus, parsedType)
    }

    fun resolveExceptionResponse(
        series: ActiveSyncField<ActiveSyncResponseType>,
        exception: ActiveSyncField<ActiveSyncResponseType>,
    ): ActiveSyncField<ActiveSyncResponseType> =
        when (exception) {
            ActiveSyncField.Absent -> series
            ActiveSyncField.Empty -> ActiveSyncField.Empty
            is ActiveSyncField.Value -> exception
        }

    fun parseBody(present: Boolean, data: String?): ActiveSyncField<String> =
        when {
            !present -> ActiveSyncField.Absent
            data.isNullOrEmpty() -> ActiveSyncField.Empty
            else -> ActiveSyncField.Value(data)
        }

    private fun String.parseInteger(label: String): Int =
        toIntOrNull() ?: throw ActiveSyncCalendarValueException("Invalid $label")

    private fun <T> enumByWireValue(
        value: String,
        label: String,
        entries: List<T>,
        wireValue: (T) -> Int,
    ): T {
        val parsed = value.parseInteger(label)
        return entries.firstOrNull { entry -> wireValue(entry) == parsed }
            ?: throw ActiveSyncCalendarValueException("Invalid $label")
    }

    private const val MEETING_BIT = 1
    private const val RECEIVED_BIT = 2
    private const val CANCELLED_BIT = 4
    private const val FORWARDED_BIT = 8
}

internal data class RawActiveSyncRecurrence(
    val type: String?,
    val interval: String? = null,
    val dayOfWeek: String? = null,
    val dayOfMonth: String? = null,
    val weekOfMonth: String? = null,
    val monthOfYear: String? = null,
    val calendarType: String? = null,
    val isLeapMonth: String? = null,
    val firstDayOfWeek: String? = null,
    val occurrences: String? = null,
    val until: String? = null,
)

internal object ActiveSyncRecurrenceParser {
    fun parse(raw: RawActiveSyncRecurrence): ActiveSyncRecurrence {
        validateCalendarSystem(raw)
        val type = parseType(raw.type)
        val interval = raw.interval?.positiveInteger("recurrence interval") ?: 1
        val dayOfWeek = raw.dayOfWeek?.boundedInteger("day-of-week mask", 1, 127)
        val dayOfMonth = raw.dayOfMonth?.boundedInteger("day of month", 1, 31)
        val weekOfMonth = raw.weekOfMonth?.boundedInteger("week of month", 1, 5)
        val monthOfYear = raw.monthOfYear?.boundedInteger("month of year", 1, 12)
        val firstDayOfWeek = raw.firstDayOfWeek?.boundedInteger("first day of week", 0, 6)
        validateRequiredPattern(type, dayOfWeek, dayOfMonth, weekOfMonth, monthOfYear)
        return ActiveSyncRecurrence(
            type = type,
            interval = interval,
            dayOfWeekMask = dayOfWeek,
            dayOfMonth = dayOfMonth,
            weekOfMonth = weekOfMonth,
            monthOfYear = monthOfYear,
            firstDayOfWeek = firstDayOfWeek,
            end = parseEnd(raw),
        )
    }

    private fun parseType(value: String?): ActiveSyncRecurrenceType {
        val parsed = value?.toIntOrNull() ?: throw ActiveSyncCalendarValueException("Recurrence type is missing")
        return ActiveSyncRecurrenceType.entries.firstOrNull { type -> type.wireValue == parsed }
            ?: throw ActiveSyncCalendarValueException("Unsupported recurrence type")
    }

    private fun validateCalendarSystem(raw: RawActiveSyncRecurrence) {
        val calendarType = raw.calendarType?.boundedInteger("calendar type", 0, 255) ?: DEFAULT_CALENDAR_TYPE
        if (calendarType !in GREGORIAN_CALENDAR_TYPES) {
            throw ActiveSyncCalendarValueException("Unsupported recurrence calendar system")
        }
        raw.isLeapMonth?.boundedInteger("recurrence leap-month flag", 0, 1)
    }

    private fun validateRequiredPattern(
        type: ActiveSyncRecurrenceType,
        dayOfWeek: Int?,
        dayOfMonth: Int?,
        weekOfMonth: Int?,
        monthOfYear: Int?,
    ) {
        val valid =
            when (type) {
                ActiveSyncRecurrenceType.DAILY -> true
                ActiveSyncRecurrenceType.WEEKLY -> dayOfWeek != null
                ActiveSyncRecurrenceType.MONTHLY -> dayOfMonth != null
                ActiveSyncRecurrenceType.MONTHLY_NTH -> dayOfWeek != null && weekOfMonth != null
                ActiveSyncRecurrenceType.YEARLY -> dayOfMonth != null && monthOfYear != null
                ActiveSyncRecurrenceType.YEARLY_NTH ->
                    dayOfWeek != null && weekOfMonth != null && monthOfYear != null
            }
        if (!valid) throw ActiveSyncCalendarValueException("Required recurrence fields are missing")
    }

    private fun parseEnd(raw: RawActiveSyncRecurrence): ActiveSyncRecurrenceEnd {
        if (raw.occurrences != null && raw.until != null) {
            throw ActiveSyncCalendarValueException("Recurrence has conflicting end rules")
        }
        return when {
            raw.occurrences != null -> ActiveSyncRecurrenceEnd.Count(raw.occurrences.positiveInteger("occurrences"))
            raw.until != null -> ActiveSyncRecurrenceEnd.Until(ActiveSyncCalendarValueParsers.parseDateTime(raw.until))
            else -> ActiveSyncRecurrenceEnd.Infinite
        }
    }

    private fun String.positiveInteger(label: String): Int = boundedInteger(label, 1, Int.MAX_VALUE)

    private fun String.boundedInteger(label: String, minimum: Int, maximum: Int): Int {
        val parsed = toIntOrNull() ?: throw ActiveSyncCalendarValueException("Invalid $label")
        if (parsed !in minimum..maximum) throw ActiveSyncCalendarValueException("Invalid $label")
        return parsed
    }

    private const val DEFAULT_CALENDAR_TYPE: Int = 0
    private val GREGORIAN_CALENDAR_TYPES: Set<Int> = setOf(0, 1, 2, 9, 10, 11, 12)
}

internal object CurrentUserResponseResolver {
    fun resolve(profileEmail: String, attendees: List<ActiveSyncAttendee>): ActiveSyncResponseType {
        val matching = attendees.filter { attendee -> attendee.email.equals(profileEmail, ignoreCase = true) }
        if (matching.size != 1) {
            throw ActiveSyncCalendarValueException("Current-user attendee response is ambiguous")
        }
        return when (matching.single().status) {
            null -> throw ActiveSyncCalendarValueException("Current-user attendee response is missing")
            ActiveSyncAttendeeStatus.NONE -> ActiveSyncResponseType.NONE
            ActiveSyncAttendeeStatus.TENTATIVE -> ActiveSyncResponseType.TENTATIVE
            ActiveSyncAttendeeStatus.ACCEPTED -> ActiveSyncResponseType.ACCEPTED
            ActiveSyncAttendeeStatus.DECLINED -> ActiveSyncResponseType.DECLINED
            ActiveSyncAttendeeStatus.NOT_RESPONDED -> ActiveSyncResponseType.NOT_RESPONDED
        }
    }
}

internal object ActiveSyncTimeZoneParser {
    private const val ENCODED_SIZE = 172
    private const val NAME_SIZE = 64

    fun parse(encoded: String): ActiveSyncTimeZone {
        val bytes =
            try {
                Base64.getDecoder().decode(encoded)
            } catch (error: IllegalArgumentException) {
                throw ActiveSyncCalendarValueException("Invalid ActiveSync time zone encoding", error)
            }
        if (bytes.size != ENCODED_SIZE) throw ActiveSyncCalendarValueException("Invalid ActiveSync time zone size")

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ActiveSyncTimeZone(
            biasMinutes = buffer.int,
            standardName = buffer.readName(),
            standardTransition = buffer.readSystemTime(),
            standardBiasMinutes = buffer.int,
            daylightName = buffer.readName(),
            daylightTransition = buffer.readSystemTime(),
            daylightBiasMinutes = buffer.int,
        )
    }

    private fun ByteBuffer.readName(): String {
        val bytes = ByteArray(NAME_SIZE)
        get(bytes)
        var length = 0
        while (length + 1 < bytes.size && (bytes[length].toInt() != 0 || bytes[length + 1].toInt() != 0)) {
            length += 2
        }
        return try {
            StandardCharsets.UTF_16LE
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, length))
                .toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw ActiveSyncCalendarValueException("Invalid ActiveSync time zone name", error)
        }
    }

    private fun ByteBuffer.readSystemTime(): ActiveSyncSystemTime {
        val value =
            ActiveSyncSystemTime(
                year = short.toInt() and 0xffff,
                month = short.toInt() and 0xffff,
                dayOfWeek = short.toInt() and 0xffff,
                day = short.toInt() and 0xffff,
                hour = short.toInt() and 0xffff,
                minute = short.toInt() and 0xffff,
                second = short.toInt() and 0xffff,
                milliseconds = short.toInt() and 0xffff,
            )
        val noTransition = value.month == 0
        val valid =
            value.year == 0 &&
                value.month in 0..12 &&
                value.dayOfWeek in 0..6 &&
                value.hour in 0..23 &&
                value.minute in 0..59 &&
                value.second in 0..59 &&
                value.milliseconds in 0..999 &&
                if (noTransition) value.day == 0 else value.day in 1..5
        if (!valid) throw ActiveSyncCalendarValueException("Invalid ActiveSync time zone transition")
        return value
    }
}
