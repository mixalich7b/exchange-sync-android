package net.mixalich7b.exchangesync.infrastructure.activesync.calendar

import java.time.Instant
import java.time.LocalDate
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActiveSyncCalendarValueParsersTest {
    @Test
    fun `compact UTC date-time accepts exact calendar values and rejects shifted or invalid forms`() {
        assertEquals(
            Instant.parse("2026-03-28T09:15:42Z"),
            ActiveSyncCalendarValueParsers.parseDateTime("20260328T091542Z"),
        )

        listOf(
            "2026-03-28T09:15:42Z",
            "20260328T091542+0300",
            "20260230T091542Z",
            "20260328T241542Z",
            "20260328t091542z",
            "",
        ).forEach { value ->
            assertThrows(ActiveSyncCalendarValueException::class.java, { ActiveSyncCalendarValueParsers.parseDateTime(value) }, value)
        }
    }

    @Test
    fun `InstanceId uses the protocol Compact DateTime grammar`() {
        assertEquals(
            Instant.parse("2026-08-10T09:00:00Z"),
            ActiveSyncCalendarValueParsers.parseInstanceId("20260810T090000Z"),
        )

        listOf(
            "2026-08-10T09:00:00.000Z",
            "20260810T090000.000Z",
            "20260810T090000+0300",
            "20260230T090000Z",
            "20260810t090000z",
            "",
        ).forEach { value ->
            assertThrows(
                ActiveSyncCalendarValueException::class.java,
                { ActiveSyncCalendarValueParsers.parseInstanceId(value) },
                value,
            )
        }
    }

    @Test
    fun `all-day boundaries remain exclusive UTC dates and reject non-midnight values`() {
        assertEquals(
            ActiveSyncAllDayRange(
                start = LocalDate.parse("2026-03-28"),
                endExclusive = LocalDate.parse("2026-03-30"),
            ),
            ActiveSyncCalendarValueParsers.parseAllDayRange(
                start = "20260328T000000Z",
                end = "20260330T000000Z",
            ),
        )

        assertThrows(ActiveSyncCalendarValueException::class.java) {
            ActiveSyncCalendarValueParsers.parseAllDayRange("20260328T010000Z", "20260330T000000Z")
        }
        assertThrows(ActiveSyncCalendarValueException::class.java) {
            ActiveSyncCalendarValueParsers.parseAllDayRange("20260330T000000Z", "20260330T000000Z")
        }
    }

    @Test
    fun `field parsing distinguishes absence explicit removal and a reminder value`() {
        assertEquals(
            ActiveSyncField.Absent,
            ActiveSyncCalendarValueParsers.parseField(present = false, text = null) { value ->
                ActiveSyncCalendarValueParsers.parseReminder(value)
            },
        )
        assertEquals(
            ActiveSyncField.Empty,
            ActiveSyncCalendarValueParsers.parseField(present = true, text = null) { value ->
                ActiveSyncCalendarValueParsers.parseReminder(value)
            },
        )
        assertEquals(
            ActiveSyncField.Value(15),
            ActiveSyncCalendarValueParsers.parseField(present = true, text = "15") { value ->
                ActiveSyncCalendarValueParsers.parseReminder(value)
            },
        )
        assertEquals(0, ActiveSyncCalendarValueParsers.parseReminder("0"))
        assertThrows(ActiveSyncCalendarValueException::class.java) {
            ActiveSyncCalendarValueParsers.parseReminder("-1")
        }
    }

    @Test
    fun `recurrence variants preserve pattern fields and finite or infinite endings`() {
        val fixtures =
            listOf(
                RawActiveSyncRecurrence(type = "0", interval = "2") to
                    ActiveSyncRecurrence(
                        type = ActiveSyncRecurrenceType.DAILY,
                        interval = 2,
                        end = ActiveSyncRecurrenceEnd.Infinite,
                    ),
                RawActiveSyncRecurrence(type = "1", interval = "1", dayOfWeek = "10", firstDayOfWeek = "1") to
                    ActiveSyncRecurrence(
                        type = ActiveSyncRecurrenceType.WEEKLY,
                        interval = 1,
                        dayOfWeekMask = 10,
                        firstDayOfWeek = 1,
                        end = ActiveSyncRecurrenceEnd.Infinite,
                    ),
                RawActiveSyncRecurrence(type = "2", dayOfMonth = "15", occurrences = "9") to
                    ActiveSyncRecurrence(
                        type = ActiveSyncRecurrenceType.MONTHLY,
                        interval = 1,
                        dayOfMonth = 15,
                        end = ActiveSyncRecurrenceEnd.Count(9),
                    ),
                RawActiveSyncRecurrence(type = "3", dayOfWeek = "2", weekOfMonth = "2") to
                    ActiveSyncRecurrence(
                        type = ActiveSyncRecurrenceType.MONTHLY_NTH,
                        interval = 1,
                        dayOfWeekMask = 2,
                        weekOfMonth = 2,
                        end = ActiveSyncRecurrenceEnd.Infinite,
                    ),
                RawActiveSyncRecurrence(type = "5", dayOfMonth = "20", monthOfYear = "6") to
                    ActiveSyncRecurrence(
                        type = ActiveSyncRecurrenceType.YEARLY,
                        interval = 1,
                        dayOfMonth = 20,
                        monthOfYear = 6,
                        end = ActiveSyncRecurrenceEnd.Infinite,
                    ),
                RawActiveSyncRecurrence(
                    type = "6",
                    dayOfWeek = "1",
                    weekOfMonth = "5",
                    monthOfYear = "11",
                    until = "20281130T000000Z",
                ) to
                    ActiveSyncRecurrence(
                        type = ActiveSyncRecurrenceType.YEARLY_NTH,
                        interval = 1,
                        dayOfWeekMask = 1,
                        weekOfMonth = 5,
                        monthOfYear = 11,
                        end = ActiveSyncRecurrenceEnd.Until(Instant.parse("2028-11-30T00:00:00Z")),
                    ),
            )

        fixtures.forEach { (raw, expected) -> assertEquals(expected, ActiveSyncRecurrenceParser.parse(raw), raw.toString()) }
    }

    @Test
    fun `recurrence rejects missing required fields conflicting ends and invalid masks`() {
        listOf(
            RawActiveSyncRecurrence(type = "1"),
            RawActiveSyncRecurrence(type = "2", dayOfMonth = "0"),
            RawActiveSyncRecurrence(type = "3", dayOfWeek = "2", weekOfMonth = "6"),
            RawActiveSyncRecurrence(type = "5", dayOfMonth = "20"),
            RawActiveSyncRecurrence(type = "6", weekOfMonth = "1", monthOfYear = "13"),
            RawActiveSyncRecurrence(type = "1", dayOfWeek = "128"),
            RawActiveSyncRecurrence(type = "0", occurrences = "3", until = "20270101T000000Z"),
            RawActiveSyncRecurrence(type = "4"),
        ).forEach { raw ->
            assertThrows(ActiveSyncCalendarValueException::class.java, { ActiveSyncRecurrenceParser.parse(raw) }, raw.toString())
        }
    }

    @Test
    fun `meeting status response request availability and sensitivity accept only protocol values`() {
        assertEquals(ActiveSyncMeetingStatus(3, isMeeting = true, isReceived = true, isCancelled = false), ActiveSyncCalendarValueParsers.parseMeetingStatus("3"))
        assertEquals(ActiveSyncMeetingStatus(5, isMeeting = true, isReceived = false, isCancelled = true), ActiveSyncCalendarValueParsers.parseMeetingStatus("5"))
        assertEquals(ActiveSyncResponseType.entries, (0..5).map { value -> ActiveSyncCalendarValueParsers.parseResponseType(value.toString()) })
        assertTrue(ActiveSyncCalendarValueParsers.parseResponseRequested("1"))
        assertFalse(ActiveSyncCalendarValueParsers.parseResponseRequested("0"))
        assertEquals(ActiveSyncAvailability.entries, (0..4).map { value -> ActiveSyncCalendarValueParsers.parseAvailability(value.toString()) })
        assertEquals(ActiveSyncSensitivity.entries, (0..3).map { value -> ActiveSyncCalendarValueParsers.parseSensitivity(value.toString()) })

        listOf("2", "8", "255").forEach { value ->
            assertThrows(ActiveSyncCalendarValueException::class.java, { ActiveSyncCalendarValueParsers.parseMeetingStatus(value) }, value)
        }
        assertThrows(ActiveSyncCalendarValueException::class.java) { ActiveSyncCalendarValueParsers.parseResponseType("6") }
        assertThrows(ActiveSyncCalendarValueException::class.java) { ActiveSyncCalendarValueParsers.parseResponseRequested("true") }
        assertThrows(ActiveSyncCalendarValueException::class.java) { ActiveSyncCalendarValueParsers.parseAvailability("5") }
        assertThrows(ActiveSyncCalendarValueException::class.java) { ActiveSyncCalendarValueParsers.parseSensitivity("4") }
    }

    @Test
    fun `attendees preserve role and response while current-user fallback is unambiguous`() {
        val attendees =
            listOf(
                ActiveSyncCalendarValueParsers.parseAttendee(
                    email = "other@example.test",
                    name = "Other",
                    status = "3",
                    type = "1",
                ),
                ActiveSyncCalendarValueParsers.parseAttendee(
                    email = "Calendar@Example.Test",
                    name = "Current User",
                    status = "2",
                    type = "2",
                ),
            )

        assertEquals(ActiveSyncAttendeeStatus.ACCEPTED, attendees.first().status)
        assertEquals(ActiveSyncAttendeeType.REQUIRED, attendees.first().type)
        assertEquals(ActiveSyncAttendeeStatus.TENTATIVE, attendees.last().status)
        assertEquals(ActiveSyncAttendeeType.OPTIONAL, attendees.last().type)
        assertEquals(
            ActiveSyncResponseType.TENTATIVE,
            CurrentUserResponseResolver.resolveRequired("calendar@example.test", attendees),
        )

        assertThrows(ActiveSyncCalendarValueException::class.java) {
            CurrentUserResponseResolver.resolveRequired("missing@example.test", attendees)
        }
        assertThrows(ActiveSyncCalendarValueException::class.java) {
            CurrentUserResponseResolver.resolveRequired(
                "calendar@example.test",
                listOf(
                    ActiveSyncAttendee(
                        email = "calendar@example.test",
                        name = "Current User",
                        status = null,
                        type = ActiveSyncAttendeeType.REQUIRED,
                    ),
                ),
            )
        }
        assertThrows(ActiveSyncCalendarValueException::class.java) {
            CurrentUserResponseResolver.resolveRequired(
                "calendar@example.test",
                attendees +
                    ActiveSyncAttendee(
                        email = "calendar@example.test",
                        name = "Duplicate",
                        status = ActiveSyncAttendeeStatus.DECLINED,
                        type = ActiveSyncAttendeeType.RESOURCE,
                    ),
            )
        }
    }

    @Test
    fun `exception response inherits series state unless explicitly removed or overridden`() {
        assertEquals(
            ActiveSyncField.Value(ActiveSyncResponseType.NOT_RESPONDED),
            ActiveSyncCalendarValueParsers.resolveExceptionResponse(
                series = ActiveSyncField.Value(ActiveSyncResponseType.NOT_RESPONDED),
                exception = ActiveSyncField.Absent,
            ),
        )
        assertEquals(
            ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED),
            ActiveSyncCalendarValueParsers.resolveExceptionResponse(
                series = ActiveSyncField.Value(ActiveSyncResponseType.NOT_RESPONDED),
                exception = ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED),
            ),
        )
        assertEquals(
            ActiveSyncField.Empty,
            ActiveSyncCalendarValueParsers.resolveExceptionResponse(
                series = ActiveSyncField.Value(ActiveSyncResponseType.NOT_RESPONDED),
                exception = ActiveSyncField.Empty,
            ),
        )
    }

    @Test
    fun `body presence remains distinguishable across omitted empty and populated values`() {
        assertEquals(ActiveSyncField.Absent, ActiveSyncCalendarValueParsers.parseBody(present = false, data = null))
        assertEquals(ActiveSyncField.Empty, ActiveSyncCalendarValueParsers.parseBody(present = true, data = null))
        assertEquals(
            ActiveSyncField.Value("Agenda"),
            ActiveSyncCalendarValueParsers.parseBody(present = true, data = "Agenda"),
        )
    }

    @Test
    fun `Windows time-zone blob decodes fixed layout and rejects malformed transitions`() {
        val encoded =
            "4AEAAFAAYQBjAGkAZgBpAGMAIABTAHQAYQBuAGQAYQByAGQAIABUAGkAbQBl" +
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAsAAAABAAIAAAAAAAAAAAAAAFAAYQBj" +
                "AGkAZgBpAGMAIABEAGEAeQBsAGkAZwBoAHQAIABUAGkAbQBlAAAAAAAAAAAAAAAA" +
                "AAAAAAAAAAAAAAAAAAMAAAACAAIAAAAAAAAAxP///w=="

        val timeZone = ActiveSyncTimeZoneParser.parse(encoded)

        assertEquals(480, timeZone.biasMinutes)
        assertEquals("Pacific Standard Time", timeZone.standardName)
        assertEquals(11, timeZone.standardTransition.month)
        assertEquals(1, timeZone.standardTransition.day)
        assertEquals(2, timeZone.standardTransition.hour)
        assertEquals(0, timeZone.standardBiasMinutes)
        assertEquals("Pacific Daylight Time", timeZone.daylightName)
        assertEquals(3, timeZone.daylightTransition.month)
        assertEquals(2, timeZone.daylightTransition.day)
        assertEquals(-60, timeZone.daylightBiasMinutes)

        assertThrows(ActiveSyncCalendarValueException::class.java) { ActiveSyncTimeZoneParser.parse("AA==") }
        assertThrows(ActiveSyncCalendarValueException::class.java) { ActiveSyncTimeZoneParser.parse("not-base64") }
    }
}
