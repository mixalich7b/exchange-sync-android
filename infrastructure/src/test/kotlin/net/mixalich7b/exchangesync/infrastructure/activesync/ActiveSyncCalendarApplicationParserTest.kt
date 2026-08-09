package net.mixalich7b.exchangesync.infrastructure.activesync

import java.time.Instant
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncAttendeeStatus
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarMutation
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncField
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncResponseType
import net.mixalich7b.exchangesync.core.sync.ActiveSyncVersion
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.AirSync
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.AirSyncBase
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.Calendar
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlElement
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlReader
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActiveSyncCalendarApplicationParserTest {
    @Test
    fun `14_x request declares supported ghosted properties while 16_x relies on omission merge`() {
        val request14 = requestCollection(ActiveSyncVersion.V14_0)
        val priming14 = requestCollection(ActiveSyncVersion.V14_0, getChanges = false)
        val request16 = requestCollection(ActiveSyncVersion.V16_1)

        val supported = request14.child(AirSync.SUPPORTED)
        assertTrue(priming14.child(AirSync.SUPPORTED)?.children.orEmpty().any { child -> child.tag == Calendar.RESPONSE_TYPE })
        assertTrue(supported?.children.orEmpty().any { child -> child.tag == Calendar.RESPONSE_TYPE })
        assertTrue(supported?.children.orEmpty().any { child -> child.tag == Calendar.MEETING_STATUS })
        assertTrue(supported?.children.orEmpty().any { child -> child.tag == AirSyncBase.BODY })
        assertNull(request16.child(AirSync.SUPPORTED))
        listOf(request14, request16).forEach { collection ->
            val bodyPreference = collection.child(AirSync.OPTIONS)?.child(AirSyncBase.BODY_PREFERENCE)
            assertEquals("1", bodyPreference?.child(AirSyncBase.TYPE)?.text)
            assertEquals("32768", bodyPreference?.child(AirSyncBase.TRUNCATION_SIZE)?.text)
        }
    }

    @Test
    fun `primary Calendar meeting fixtures preserve every authoritative response state`() {
        val fixtures =
            listOf(
                Triple("pending-none", "3", "0") to ActiveSyncResponseType.NONE,
                Triple("pending-no-response", "3", "5") to ActiveSyncResponseType.NOT_RESPONDED,
                Triple("tentative", "3", "2") to ActiveSyncResponseType.TENTATIVE,
                Triple("accepted", "3", "3") to ActiveSyncResponseType.ACCEPTED,
                Triple("organizer", "1", "1") to ActiveSyncResponseType.ORGANIZER,
                Triple("declined", "3", "4") to ActiveSyncResponseType.DECLINED,
            )

        fixtures.forEach { (values, expected) ->
            val (serverId, meetingStatus, responseType) = values
            val mutation =
                ActiveSyncCalendarApplicationParser.parse(
                    command = addCommand(serverId, meetingStatus, responseType),
                    profileEmail = "calendar@example.test",
                ) as ActiveSyncCalendarMutation.Upsert

            assertEquals(ActiveSyncField.Value(expected), mutation.item.responseType, serverId)
            assertEquals(meetingStatus.toInt(), (mutation.item.meetingStatus as ActiveSyncField.Value).value.rawValue)
        }
    }

    @Test
    fun `received Add without ResponseType uses exactly one current-user attendee response`() {
        val applicationData =
            meetingData(
                meetingStatus = "3",
                responseType = null,
                attendeeStatus = "2",
            )

        val mutation =
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(RawCalendarCommandKind.ADD, "fallback", applicationData),
                profileEmail = "CALENDAR@example.test",
            ) as ActiveSyncCalendarMutation.Upsert

        assertEquals(ActiveSyncField.Value(ActiveSyncResponseType.TENTATIVE), mutation.item.responseType)
        val attendees = (mutation.item.attendees as ActiveSyncField.Value).value
        assertEquals(ActiveSyncAttendeeStatus.TENTATIVE, attendees.single().status)
    }

    @Test
    fun `received Change without ResponseType uses a supplied current-user attendee response`() {
        val mutation =
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(
                    RawCalendarCommandKind.CHANGE,
                    "fallback-change",
                    meetingData(meetingStatus = "3", responseType = null, attendeeStatus = "3"),
                ),
                profileEmail = "calendar@example.test",
            ) as ActiveSyncCalendarMutation.Upsert

        assertEquals(ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED), mutation.item.responseType)
    }

    @Test
    fun `exception without ResponseType uses its current-user attendee response`() {
        val data =
            element(
                AirSync.APPLICATION_DATA,
                *ordinaryChildren(subject = "Accepted series").toTypedArray(),
                text(Calendar.MEETING_STATUS, "3"),
                text(Calendar.RESPONSE_TYPE, "3"),
                element(
                    Calendar.EXCEPTIONS,
                    element(
                        Calendar.EXCEPTION,
                        text(Calendar.EXCEPTION_START_TIME, "20260810T090000Z"),
                        element(
                            Calendar.ATTENDEES,
                            element(
                                Calendar.ATTENDEE,
                                text(Calendar.EMAIL, PROFILE_EMAIL),
                                text(Calendar.NAME, "Current User"),
                                text(Calendar.ATTENDEE_STATUS, "2"),
                                text(Calendar.ATTENDEE_TYPE, "1"),
                            ),
                        ),
                    ),
                ),
            )

        val mutation =
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(RawCalendarCommandKind.ADD, "exception-fallback", data),
                PROFILE_EMAIL,
            ) as ActiveSyncCalendarMutation.Upsert

        val exception = (mutation.item.exceptions as ActiveSyncField.Value).value.single()
        assertEquals(ActiveSyncField.Value(ActiveSyncResponseType.TENTATIVE), exception.responseType)
    }

    @Test
    fun `attendee-only Change derives response when meeting fields are ghosted`() {
        val data =
            element(
                AirSync.APPLICATION_DATA,
                element(
                    Calendar.ATTENDEES,
                    element(
                        Calendar.ATTENDEE,
                        text(Calendar.EMAIL, PROFILE_EMAIL),
                        text(Calendar.NAME, "Current User"),
                        text(Calendar.ATTENDEE_STATUS, "3"),
                        text(Calendar.ATTENDEE_TYPE, "1"),
                    ),
                ),
            )

        val mutation =
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(RawCalendarCommandKind.CHANGE, "ghosted-meeting", data),
                PROFILE_EMAIL,
            ) as ActiveSyncCalendarMutation.Upsert

        assertEquals(ActiveSyncField.Absent, mutation.item.meetingStatus)
        assertEquals(ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED), mutation.item.responseType)
    }

    @Test
    fun `authoritative ResponseType accepts attendee with omitted optional status and type`() {
        val data =
            element(
                AirSync.APPLICATION_DATA,
                *ordinaryChildren(subject = "Meeting").toTypedArray(),
                text(Calendar.MEETING_STATUS, "3"),
                text(Calendar.RESPONSE_TYPE, "3"),
                element(
                    Calendar.ATTENDEES,
                    element(
                        Calendar.ATTENDEE,
                        text(Calendar.EMAIL, PROFILE_EMAIL),
                        text(Calendar.NAME, "Current User"),
                    ),
                ),
            )

        val mutation =
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(RawCalendarCommandKind.ADD, "optional-attendee-fields", data),
                PROFILE_EMAIL,
            ) as ActiveSyncCalendarMutation.Upsert

        val attendee = (mutation.item.attendees as ActiveSyncField.Value).value.single()
        assertNull(attendee.status)
        assertNull(attendee.type)
        assertEquals(ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED), mutation.item.responseType)
    }

    @Test
    fun `16_x application data uses structured location and InstanceId exception identity`() {
        val instance = "2026-08-10T09:00:00.000Z"
        val data =
            element(
                AirSync.APPLICATION_DATA,
                *ordinaryChildren(subject = "Series").toTypedArray(),
                element(AirSyncBase.LOCATION, text(AirSyncBase.DISPLAY_NAME, "Room 16")),
                element(
                    Calendar.EXCEPTIONS,
                    element(
                        Calendar.EXCEPTION,
                        text(AirSyncBase.INSTANCE_ID, instance),
                        element(AirSyncBase.LOCATION, text(AirSyncBase.DISPLAY_NAME, "Room 16B")),
                        text(Calendar.MEETING_STATUS, "3"),
                        text(Calendar.RESPONSE_REQUESTED, "1"),
                        text(Calendar.SENSITIVITY, "3"),
                        element(
                            Calendar.ATTENDEES,
                            element(
                                Calendar.ATTENDEE,
                                text(Calendar.EMAIL, "guest@example.test"),
                                text(Calendar.NAME, "Guest"),
                            ),
                        ),
                    ),
                ),
            )

        val mutation =
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(RawCalendarCommandKind.ADD, "series-16", data),
                PROFILE_EMAIL,
                ActiveSyncVersion.V16_1,
            ) as ActiveSyncCalendarMutation.Upsert

        assertEquals(ActiveSyncField.Value("Room 16"), mutation.item.location)
        val exception = (mutation.item.exceptions as ActiveSyncField.Value).value.single()
        assertEquals(Instant.parse("2026-08-10T09:00:00Z"), exception.instanceStart)
        assertEquals(ActiveSyncField.Value("Room 16B"), exception.location)
        assertEquals(3, (exception.meetingStatus as ActiveSyncField.Value).value.rawValue)
        assertEquals(ActiveSyncField.Value(true), exception.responseRequested)
        assertEquals(3, (exception.sensitivity as ActiveSyncField.Value).value.wireValue)
        assertEquals("guest@example.test", (exception.attendees as ActiveSyncField.Value).value.single().email)
    }

    @Test
    fun `exception preserves an all-day override that differs from its series`() {
        val data =
            element(
                AirSync.APPLICATION_DATA,
                text(Calendar.SUBJECT, "All-day series"),
                text(Calendar.START_TIME, "20260809T000000Z"),
                text(Calendar.END_TIME, "20260810T000000Z"),
                text(Calendar.ALL_DAY_EVENT, "1"),
                element(
                    Calendar.EXCEPTIONS,
                    element(
                        Calendar.EXCEPTION,
                        text(Calendar.EXCEPTION_START_TIME, "20260811T000000Z"),
                        text(Calendar.START_TIME, "20260811T090000Z"),
                        text(Calendar.END_TIME, "20260811T100000Z"),
                        text(Calendar.ALL_DAY_EVENT, "0"),
                    ),
                ),
            )

        val mutation =
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(RawCalendarCommandKind.ADD, "all-day-override", data),
                PROFILE_EMAIL,
            ) as ActiveSyncCalendarMutation.Upsert

        val exception = (mutation.item.exceptions as ActiveSyncField.Value).value.single()
        assertEquals(ActiveSyncField.Value(false), exception.allDay)
    }

    @Test
    fun `attendee Name is required even when ResponseType is authoritative`() {
        val data =
            element(
                AirSync.APPLICATION_DATA,
                *ordinaryChildren(subject = "Meeting").toTypedArray(),
                text(Calendar.MEETING_STATUS, "3"),
                text(Calendar.RESPONSE_TYPE, "3"),
                element(Calendar.ATTENDEES, element(Calendar.ATTENDEE, text(Calendar.EMAIL, PROFILE_EMAIL))),
            )

        assertThrows(ActiveSyncProtocolDataException::class.java) {
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(RawCalendarCommandKind.ADD, "missing-attendee-name", data),
                PROFILE_EMAIL,
            )
        }
    }

    @Test
    fun `Add Change Delete and SoftDelete preserve identity and field absence versus removal`() {
        val add =
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(RawCalendarCommandKind.ADD, "event-1", ordinaryData(subject = "Initial")),
                PROFILE_EMAIL,
            ) as ActiveSyncCalendarMutation.Upsert
        val change =
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(
                    RawCalendarCommandKind.CHANGE,
                    "event-1",
                    element(AirSync.APPLICATION_DATA, WbxmlElement(Calendar.SUBJECT)),
                ),
                PROFILE_EMAIL,
            ) as ActiveSyncCalendarMutation.Upsert
        val delete =
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(RawCalendarCommandKind.DELETE, "event-1", null),
                PROFILE_EMAIL,
            )
        val softDelete =
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(RawCalendarCommandKind.SOFT_DELETE, "event-2", null),
                PROFILE_EMAIL,
            )

        assertTrue(add.isAddition)
        assertEquals(ActiveSyncField.Value("Initial"), add.item.subject)
        assertFalse(change.isAddition)
        assertEquals(ActiveSyncField.Empty, change.item.subject)
        assertEquals(ActiveSyncField.Absent, change.item.start)
        assertEquals(ActiveSyncCalendarMutation.Delete("event-1", soft = false), delete)
        assertEquals(ActiveSyncCalendarMutation.Delete("event-2", soft = true), softDelete)
    }

    @Test
    fun `malformed Add and unclassifiable received invitation reject the whole page item`() {
        assertThrows(ActiveSyncProtocolDataException::class.java) {
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(
                    RawCalendarCommandKind.ADD,
                    "missing-time",
                    element(AirSync.APPLICATION_DATA, text(Calendar.SUBJECT, "Broken")),
                ),
                PROFILE_EMAIL,
            )
        }
        assertThrows(ActiveSyncProtocolDataException::class.java) {
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(
                    RawCalendarCommandKind.ADD,
                    "ambiguous-response",
                    meetingData(meetingStatus = "3", responseType = null, attendeeStatus = null),
                ),
                PROFILE_EMAIL,
            )
        }
    }

    @Test
    fun `non-Gregorian recurrence calendar system is rejected instead of shifted`() {
        val data =
            recurrenceData(
                text(Calendar.TYPE, "5"),
                text(Calendar.DAY_OF_MONTH, "10"),
                text(Calendar.MONTH_OF_YEAR, "7"),
                text(Calendar.CALENDAR_TYPE, "8"),
                text(Calendar.IS_LEAP_MONTH, "1"),
            )

        assertThrows(ActiveSyncProtocolDataException::class.java) {
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(RawCalendarCommandKind.ADD, "hebrew-recurrence", data),
                PROFILE_EMAIL,
            )
        }
    }

    @Test
    fun `invalid recurrence leap-month flag is rejected instead of ignored`() {
        val data =
            recurrenceData(
                text(Calendar.TYPE, "5"),
                text(Calendar.DAY_OF_MONTH, "10"),
                text(Calendar.MONTH_OF_YEAR, "7"),
                text(Calendar.CALENDAR_TYPE, "1"),
                text(Calendar.IS_LEAP_MONTH, "2"),
            )

        assertThrows(ActiveSyncProtocolDataException::class.java) {
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(RawCalendarCommandKind.ADD, "invalid-leap-month", data),
                PROFILE_EMAIL,
            )
        }
    }

    @Test
    fun `Gregorian recurrence calendar variants remain representable`() {
        val data =
            recurrenceData(
                text(Calendar.TYPE, "2"),
                text(Calendar.DAY_OF_MONTH, "10"),
                text(Calendar.CALENDAR_TYPE, "2"),
                text(Calendar.IS_LEAP_MONTH, "1"),
            )

        val mutation =
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(RawCalendarCommandKind.ADD, "gregorian-recurrence", data),
                PROFILE_EMAIL,
            ) as ActiveSyncCalendarMutation.Upsert

        assertTrue(mutation.item.recurrence is ActiveSyncField.Value)
    }

    @Test
    fun `adaptive requests accept bounded windows down to one and never add a date filter`() {
        listOf(100, 50, 25, 12, 6, 3, 1).forEach { window ->
            val collection =
                requestCollection(ActiveSyncVersion.V16_1, window)
            assertEquals(window.toString(), collection.child(AirSync.WINDOW_SIZE)?.text)
            assertNull(collection.child(AirSync.FILTER_TYPE))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalendarSyncCodec.encodeRequest(
                "key",
                "calendar",
                0,
                getChanges = true,
                version = ActiveSyncVersion.V16_1,
            )
        }
    }

    private fun requestCollection(
        version: ActiveSyncVersion,
        window: Int = 100,
        getChanges: Boolean = true,
    ): WbxmlElement =
        WbxmlReader()
            .read(
                CalendarSyncCodec.encodeRequest(
                    syncKey = "key",
                    collectionId = "calendar",
                    windowSize = window,
                    getChanges = getChanges,
                    version = version,
                ),
            ).child(AirSync.COLLECTIONS)?.child(AirSync.COLLECTION) ?: error("missing collection")

    private fun addCommand(serverId: String, meetingStatus: String, responseType: String) =
        RawCalendarCommand(
            RawCalendarCommandKind.ADD,
            serverId,
            meetingData(meetingStatus, responseType, attendeeStatus = "0"),
        )

    private fun meetingData(
        meetingStatus: String,
        responseType: String?,
        attendeeStatus: String?,
    ): WbxmlElement {
        val children = ordinaryChildren(subject = "Meeting").toMutableList()
        children += text(Calendar.MEETING_STATUS, meetingStatus)
        responseType?.let { value -> children += text(Calendar.RESPONSE_TYPE, value) }
        attendeeStatus?.let { value ->
            children +=
                element(
                    Calendar.ATTENDEES,
                    element(
                        Calendar.ATTENDEE,
                        text(Calendar.EMAIL, PROFILE_EMAIL),
                        text(Calendar.NAME, "Current User"),
                        text(Calendar.ATTENDEE_STATUS, value),
                        text(Calendar.ATTENDEE_TYPE, "1"),
                    ),
                )
        }
        return WbxmlElement(AirSync.APPLICATION_DATA, children = children)
    }

    private fun ordinaryData(subject: String): WbxmlElement =
        WbxmlElement(AirSync.APPLICATION_DATA, children = ordinaryChildren(subject))

    private fun recurrenceData(vararg recurrenceChildren: WbxmlElement): WbxmlElement =
        element(
            AirSync.APPLICATION_DATA,
            *ordinaryChildren("Recurring event").toTypedArray(),
            element(Calendar.RECURRENCE, *recurrenceChildren),
        )

    private fun ordinaryChildren(subject: String): List<WbxmlElement> =
        listOf(
            text(Calendar.SUBJECT, subject),
            text(Calendar.START_TIME, "20260809T090000Z"),
            text(Calendar.END_TIME, "20260809T100000Z"),
            text(Calendar.ALL_DAY_EVENT, "0"),
        )

    private fun element(tag: WbxmlTag, vararg children: WbxmlElement) = WbxmlElement(tag, children = children.toList())

    private fun text(tag: WbxmlTag, value: String) = WbxmlElement(tag, text = value)

    private companion object {
        const val PROFILE_EMAIL = "calendar@example.test"
    }
}
