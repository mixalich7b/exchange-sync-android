package net.mixalich7b.exchangesync.infrastructure.activesync

import java.time.Instant
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncAttendeeStatus
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarMutation
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncField
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncResponseType
import net.mixalich7b.exchangesync.core.calendar.CalendarEventMapper
import net.mixalich7b.exchangesync.core.calendar.ProviderCalendarMutation
import net.mixalich7b.exchangesync.core.calendar.ProviderEventStatus
import net.mixalich7b.exchangesync.core.calendar.ProviderSelfStatus
import net.mixalich7b.exchangesync.core.sync.ActiveSyncVersion
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.AirSync
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.AirSyncBase
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.ActiveSyncWbxmlTokens.Calendar
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlElement
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlReader
import net.mixalich7b.exchangesync.infrastructure.activesync.wbxml.WbxmlTag
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarCommandKind
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarField
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarFieldSource
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarPath
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarRule
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticFieldState
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticFieldValue
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
    fun `received meeting response classification uses the complete oversized attendee list`() {
        val attendeeElements =
            List(100) { index ->
                element(
                    Calendar.ATTENDEE,
                    text(Calendar.EMAIL, "guest-$index@example.test"),
                    text(Calendar.NAME, "Guest $index"),
                    text(Calendar.ATTENDEE_STATUS, "0"),
                    text(Calendar.ATTENDEE_TYPE, "1"),
                )
            } +
                element(
                    Calendar.ATTENDEE,
                    text(Calendar.EMAIL, PROFILE_EMAIL),
                    text(Calendar.NAME, "Current User"),
                    text(Calendar.ATTENDEE_STATUS, "3"),
                    text(Calendar.ATTENDEE_TYPE, "1"),
                )
        val data =
            element(
                AirSync.APPLICATION_DATA,
                *ordinaryChildren(subject = "Oversized meeting").toTypedArray(),
                text(Calendar.MEETING_STATUS, "3"),
                element(Calendar.ATTENDEES, *attendeeElements.toTypedArray()),
            )

        val mutation =
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(RawCalendarCommandKind.ADD, "oversized-response", data),
                PROFILE_EMAIL,
            ) as ActiveSyncCalendarMutation.Upsert

        assertEquals(ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED), mutation.item.responseType)
        assertEquals(101, (mutation.item.attendees as ActiveSyncField.Value).value.size)
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
                    Calendar.RECURRENCE,
                    text(Calendar.TYPE, "0"),
                    text(Calendar.INTERVAL, "1"),
                ),
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
    fun `16_1 exception with omitted current-user status inherits accepted series presentation`() {
        val data =
            element(
                AirSync.APPLICATION_DATA,
                *ordinaryChildren(subject = "Accepted series").toTypedArray(),
                text(Calendar.MEETING_STATUS, "3"),
                text(Calendar.RESPONSE_TYPE, "3"),
                element(
                    Calendar.RECURRENCE,
                    text(Calendar.TYPE, "0"),
                    text(Calendar.INTERVAL, "1"),
                ),
                element(
                    Calendar.EXCEPTIONS,
                    element(
                        Calendar.EXCEPTION,
                        text(AirSyncBase.INSTANCE_ID, "20260810T090000Z"),
                        element(
                            Calendar.ATTENDEES,
                            element(
                                Calendar.ATTENDEE,
                                text(Calendar.EMAIL, PROFILE_EMAIL),
                                text(Calendar.NAME, "Current User"),
                                text(Calendar.ATTENDEE_TYPE, "1"),
                            ),
                        ),
                    ),
                ),
            )

        val mutation =
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(RawCalendarCommandKind.ADD, "series-missing-exception-status", data),
                PROFILE_EMAIL,
                ActiveSyncVersion.V16_1,
            ) as ActiveSyncCalendarMutation.Upsert
        val parsedException = (mutation.item.exceptions as ActiveSyncField.Value).value.single()
        val mapped =
            (CalendarEventMapper.map(mutation, CALENDAR_COLOR) as ProviderCalendarMutation.Upsert)
                .event
        val mappedException = (mapped.exceptions as ActiveSyncField.Value).value.single()

        assertEquals(ActiveSyncField.Absent, parsedException.responseType)
        assertEquals(ActiveSyncField.Absent, mappedException.responseTypeOverride)
        assertEquals(ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED), mappedException.responseType)
        assertEquals(ActiveSyncField.Value(ProviderEventStatus.CONFIRMED), mappedException.status)
        assertEquals(ActiveSyncField.Value(ProviderSelfStatus.ACCEPTED), mappedException.selfStatus)
        assertEquals(ActiveSyncField.Empty, mappedException.eventColor)
    }

    @Test
    fun `explicit exception ResponseType remains authoritative over attendee status`() {
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
                        text(Calendar.RESPONSE_TYPE, "4"),
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
                RawCalendarCommand(RawCalendarCommandKind.ADD, "explicit-exception-response", data),
                PROFILE_EMAIL,
            ) as ActiveSyncCalendarMutation.Upsert

        val exception = (mutation.item.exceptions as ActiveSyncField.Value).value.single()
        assertEquals(ActiveSyncField.Value(ActiveSyncResponseType.DECLINED), exception.responseType)
    }

    @Test
    fun `exception without ResponseType keeps no override for missing or ambiguous current-user status`() {
        val attendeeFixtures =
            listOf(
                listOf(
                    element(
                        Calendar.ATTENDEE,
                        text(Calendar.EMAIL, PROFILE_EMAIL),
                        text(Calendar.NAME, "Current User"),
                    ),
                ),
                listOf(
                    element(
                        Calendar.ATTENDEE,
                        text(Calendar.EMAIL, PROFILE_EMAIL),
                        text(Calendar.NAME, "Current User"),
                        text(Calendar.ATTENDEE_STATUS, "3"),
                    ),
                    element(
                        Calendar.ATTENDEE,
                        text(Calendar.EMAIL, "CALENDAR@example.test"),
                        text(Calendar.NAME, "Duplicate Current User"),
                        text(Calendar.ATTENDEE_STATUS, "4"),
                    ),
                ),
            )

        attendeeFixtures.forEachIndexed { index, attendees ->
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
                            element(Calendar.ATTENDEES, *attendees.toTypedArray()),
                        ),
                    ),
                )

            val mutation =
                ActiveSyncCalendarApplicationParser.parse(
                    RawCalendarCommand(RawCalendarCommandKind.ADD, "optional-exception-response-$index", data),
                    PROFILE_EMAIL,
                ) as ActiveSyncCalendarMutation.Upsert

            val exception = (mutation.item.exceptions as ActiveSyncField.Value).value.single()
            assertEquals(ActiveSyncField.Absent, exception.responseType, "fixture $index")
        }
    }

    @Test
    fun `exception current-user attendee with unsupported status keeps no response override`() {
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
                                text(Calendar.ATTENDEE_STATUS, "99"),
                            ),
                        ),
                    ),
                ),
            )

        val mutation =
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(RawCalendarCommandKind.ADD, "unsupported-exception-response", data),
                PROFILE_EMAIL,
            ) as ActiveSyncCalendarMutation.Upsert

        val exception = (mutation.item.exceptions as ActiveSyncField.Value).value.single()
        assertNull((exception.attendees as ActiveSyncField.Value).value.single().status)
        assertEquals(ActiveSyncField.Absent, exception.responseType)
    }

    @Test
    fun `received series keeps unsupported current-user attendee status strict`() {
        assertThrows(ActiveSyncProtocolDataException::class.java) {
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(
                    RawCalendarCommandKind.ADD,
                    "unsupported-series-response",
                    meetingData(meetingStatus = "3", responseType = null, attendeeStatus = "99"),
                ),
                PROFILE_EMAIL,
            )
        }
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
    fun `16_x application data uses structured location and Compact InstanceId exception identity`() {
        val instance = "20260810T090000Z"
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
    fun `16_x exception rejects malformed and extended InstanceId values`() {
        listOf(
            "2026-08-10T09:00:00.000Z",
            "20260810T090000.000Z",
            "20260810T090000+0300",
            "not-an-instance",
        ).forEach { instanceId ->
            val data =
                element(
                    AirSync.APPLICATION_DATA,
                    *ordinaryChildren(subject = "Series").toTypedArray(),
                    element(
                        Calendar.EXCEPTIONS,
                        element(
                            Calendar.EXCEPTION,
                            text(AirSyncBase.INSTANCE_ID, instanceId),
                            text(Calendar.DELETED, "1"),
                        ),
                    ),
                )

            assertThrows(
                ActiveSyncProtocolDataException::class.java,
                {
                    ActiveSyncCalendarApplicationParser.parse(
                        RawCalendarCommand(RawCalendarCommandKind.ADD, "series-16", data),
                        PROFILE_EMAIL,
                        ActiveSyncVersion.V16_1,
                    )
                },
                instanceId,
            )
        }
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
    fun `malformed Add retains typed safe scalar and excluded field presence`() {
        val data =
            element(
                AirSync.APPLICATION_DATA,
                text(Calendar.SUBJECT, "title-must-not-enter-snapshot"),
                text(Calendar.START_TIME, "20260809T090000Z"),
                text(Calendar.END_TIME, "invalid-end-marker"),
                text(Calendar.ALL_DAY_EVENT, "0"),
                text(Calendar.LOCATION, "Room 7"),
            )

        val failure =
            assertThrows(ActiveSyncProtocolDataException::class.java) {
                ActiveSyncCalendarApplicationParser.parse(
                    RawCalendarCommand(RawCalendarCommandKind.ADD, "malformed-add", data),
                    PROFILE_EMAIL,
                )
            }
        val snapshot = checkNotNull(failure.calendarFailureSnapshot)

        assertEquals("ADD", failure.commandKind)
        assertEquals("malformed-add", failure.serverId)
        assertEquals(ActiveSyncValidationReason.INVALID_VALUE, failure.reason)
        assertEquals(DiagnosticCalendarCommandKind.ADD, snapshot.commandKind)
        assertEquals("malformed-add", snapshot.serverId)
        assertEquals(DiagnosticCalendarRule.INVALID_VALUE, snapshot.rule)
        assertEquals(DiagnosticCalendarPath.Event, snapshot.path)
        assertEquals(DiagnosticCalendarField.END, snapshot.failedField)
        assertEquals(
            DiagnosticFieldValue.Text("invalid-end-marker"),
            snapshot.field(DiagnosticCalendarFieldSource.RESPONSE, DiagnosticCalendarField.END).value,
        )
        val subject = snapshot.field(DiagnosticCalendarFieldSource.RESPONSE, DiagnosticCalendarField.SUBJECT)
        assertEquals(DiagnosticFieldState.PRESENT, subject.state)
        assertNull(subject.value)
    }

    @Test
    fun `malformed nested Change retains failing exception scalar and exception index`() {
        val data =
            element(
                AirSync.APPLICATION_DATA,
                element(
                    Calendar.EXCEPTIONS,
                    element(
                        Calendar.EXCEPTION,
                        text(Calendar.EXCEPTION_START_TIME, "20260810T090000Z"),
                        text(Calendar.DELETED, "1"),
                    ),
                    element(
                        Calendar.EXCEPTION,
                        text(Calendar.EXCEPTION_START_TIME, "20260811T090000Z"),
                        text(Calendar.END_TIME, "nested-invalid-end-marker"),
                        element(Calendar.LOCATION),
                    ),
                ),
            )

        val failure =
            assertThrows(ActiveSyncProtocolDataException::class.java) {
                ActiveSyncCalendarApplicationParser.parse(
                    RawCalendarCommand(RawCalendarCommandKind.CHANGE, "malformed-change", data),
                    PROFILE_EMAIL,
                )
            }
        val snapshot = checkNotNull(failure.calendarFailureSnapshot)

        assertEquals(DiagnosticCalendarCommandKind.CHANGE, snapshot.commandKind)
        assertEquals("malformed-change", snapshot.serverId)
        assertEquals(DiagnosticCalendarRule.INVALID_VALUE, snapshot.rule)
        assertEquals(DiagnosticCalendarPath.Exception(1), snapshot.path)
        assertEquals(DiagnosticCalendarField.END, snapshot.failedField)
        assertEquals(
            DiagnosticFieldValue.Text("nested-invalid-end-marker"),
            snapshot.field(DiagnosticCalendarFieldSource.EXCEPTION, DiagnosticCalendarField.END).value,
        )
        assertEquals(
            DiagnosticFieldState.EMPTY,
            snapshot.field(DiagnosticCalendarFieldSource.EXCEPTION, DiagnosticCalendarField.LOCATION).state,
        )
    }

    @Test
    fun `exception identity and deleted failures retain typed field context for 14 and 16`() {
        val identities =
            listOf(
                Triple(ActiveSyncVersion.V14_0, Calendar.EXCEPTION_START_TIME, "20260810T090000Z"),
                Triple(ActiveSyncVersion.V16_1, AirSyncBase.INSTANCE_ID, "20260810T090000Z"),
            )

        identities.forEach { (version, identityTag, validIdentity) ->
            val malformedIdentity =
                exceptionFailure(
                    version,
                    element(Calendar.EXCEPTION, text(identityTag, "invalid-identity-marker")),
                )
            val missingIdentity = exceptionFailure(version, element(Calendar.EXCEPTION))
            val invalidDeleted =
                exceptionFailure(
                    version,
                    element(
                        Calendar.EXCEPTION,
                        text(identityTag, validIdentity),
                        text(Calendar.DELETED, "invalid-deleted-marker"),
                    ),
                )

            assertEquals(ActiveSyncValidationReason.INVALID_VALUE, malformedIdentity.reason, version.name)
            assertEquals(
                DiagnosticCalendarField.EXCEPTION_INSTANCE,
                malformedIdentity.calendarFailureSnapshot?.failedField,
                version.name,
            )
            assertEquals(ActiveSyncValidationReason.MISSING_REQUIRED_VALUE, missingIdentity.reason, version.name)
            assertEquals(
                DiagnosticCalendarField.EXCEPTION_INSTANCE,
                missingIdentity.calendarFailureSnapshot?.failedField,
                version.name,
            )
            assertEquals(ActiveSyncValidationReason.INVALID_VALUE, invalidDeleted.reason, version.name)
            assertEquals(
                DiagnosticCalendarField.EXCEPTION_DELETED,
                invalidDeleted.calendarFailureSnapshot?.failedField,
                version.name,
            )
            listOf(malformedIdentity, missingIdentity, invalidDeleted).forEach { failure ->
                assertEquals(DiagnosticCalendarPath.Exception(0), failure.calendarFailureSnapshot?.path)
            }
        }
    }

    @Test
    fun `attendee parse failure retains only structural narrative and people context`() {
        val markers =
            listOf(
                "title-parse-secret-marker",
                "body-parse-secret-marker",
                "organizer-email-parse-secret-marker@example.test",
                "organizer-name-parse-secret-marker",
                "attendee-email-parse-secret-marker@example.test",
                "attendee-name-parse-secret-marker",
            )
        val data =
            element(
                AirSync.APPLICATION_DATA,
                text(Calendar.SUBJECT, markers[0]),
                element(AirSyncBase.BODY, text(AirSyncBase.DATA, markers[1])),
                text(Calendar.START_TIME, "20260809T090000Z"),
                text(Calendar.END_TIME, "20260809T100000Z"),
                text(Calendar.ALL_DAY_EVENT, "0"),
                text(Calendar.ORGANIZER_EMAIL, markers[2]),
                text(Calendar.ORGANIZER_NAME, markers[3]),
                element(
                    Calendar.ATTENDEES,
                    element(
                        Calendar.ATTENDEE,
                        text(Calendar.EMAIL, markers[4]),
                        text(Calendar.NAME, markers[5]),
                        text(Calendar.ATTENDEE_STATUS, "99"),
                        text(Calendar.ATTENDEE_TYPE, "1"),
                    ),
                ),
            )

        val failure =
            assertThrows(ActiveSyncProtocolDataException::class.java) {
                ActiveSyncCalendarApplicationParser.parse(
                    RawCalendarCommand(RawCalendarCommandKind.ADD, "people-failure", data),
                    PROFILE_EMAIL,
                )
            }
        val snapshot = checkNotNull(failure.calendarFailureSnapshot)

        assertEquals(ActiveSyncValidationReason.INVALID_ATTENDEE, failure.reason)
        assertEquals(DiagnosticCalendarRule.INVALID_ATTENDEE, snapshot.rule)
        assertEquals(DiagnosticCalendarField.ATTENDEE_STATUS, snapshot.failedField)
        assertEquals(0, snapshot.attendeeIndex)
        listOf(
            DiagnosticCalendarField.SUBJECT,
            DiagnosticCalendarField.BODY,
            DiagnosticCalendarField.ORGANIZER_EMAIL,
            DiagnosticCalendarField.ORGANIZER_NAME,
            DiagnosticCalendarField.ATTENDEES,
        ).forEach { field ->
            val entry = snapshot.field(DiagnosticCalendarFieldSource.RESPONSE, field)
            assertEquals(DiagnosticFieldState.PRESENT, entry.state, field.name)
            assertNull(entry.value, field.name)
        }
        assertEquals(
            DiagnosticFieldValue.Count(1),
            snapshot.field(DiagnosticCalendarFieldSource.RESPONSE, DiagnosticCalendarField.ATTENDEE_COUNT).value,
        )
        listOf(
            DiagnosticCalendarField.ATTENDEE_EMAIL,
            DiagnosticCalendarField.ATTENDEE_NAME,
            DiagnosticCalendarField.ATTENDEE_STATUS,
            DiagnosticCalendarField.ATTENDEE_TYPE,
        ).forEach { field ->
            val entry = snapshot.field(DiagnosticCalendarFieldSource.RESPONSE, field)
            assertEquals(DiagnosticFieldState.PRESENT, entry.state, field.name)
            assertNull(entry.value, field.name)
        }
        assertEquals(
            DiagnosticFieldValue.Count(0),
            snapshot.field(
                DiagnosticCalendarFieldSource.DERIVED,
                DiagnosticCalendarField.CURRENT_USER_ATTENDEE_COUNT,
            ).value,
        )
        val retainedThrowableGraph = throwableGraphText(failure)
        markers.forEach { marker -> assertFalse(retainedThrowableGraph.contains(marker), marker) }
    }

    @Test
    fun `ambiguous current-user response retains only its typed rule and matching count`() {
        val attendees =
            List(2) { index ->
                element(
                    Calendar.ATTENDEE,
                    text(Calendar.EMAIL, PROFILE_EMAIL),
                    text(Calendar.NAME, "current-user-name-secret-marker-$index"),
                    text(Calendar.ATTENDEE_STATUS, "3"),
                    text(Calendar.ATTENDEE_TYPE, "1"),
                )
            }
        val data =
            element(
                AirSync.APPLICATION_DATA,
                *ordinaryChildren(subject = "response-title-secret-marker").toTypedArray(),
                text(Calendar.MEETING_STATUS, "3"),
                element(Calendar.ATTENDEES, *attendees.toTypedArray()),
            )

        val failure =
            assertThrows(ActiveSyncProtocolDataException::class.java) {
                ActiveSyncCalendarApplicationParser.parse(
                    RawCalendarCommand(RawCalendarCommandKind.ADD, "ambiguous-current-user", data),
                    PROFILE_EMAIL,
                )
            }
        val snapshot = checkNotNull(failure.calendarFailureSnapshot)

        assertEquals(ActiveSyncValidationReason.INVALID_MEETING_RESPONSE, failure.reason)
        assertEquals(DiagnosticCalendarRule.INVALID_MEETING_RESPONSE, snapshot.rule)
        assertEquals(DiagnosticCalendarField.RESPONSE_TYPE, snapshot.failedField)
        assertEquals(
            DiagnosticFieldValue.Count(2),
            snapshot.field(
                DiagnosticCalendarFieldSource.DERIVED,
                DiagnosticCalendarField.CURRENT_USER_ATTENDEE_COUNT,
            ).value,
        )
        assertFalse(throwableGraphText(failure).contains("current-user-name-secret-marker"))
        assertFalse(throwableGraphText(failure).contains("response-title-secret-marker"))
    }

    @Test
    fun `malformed timezone retains its typed rule and raw field identity`() {
        val data =
            element(
                AirSync.APPLICATION_DATA,
                *ordinaryChildren(subject = "timezone-title-secret-marker").toTypedArray(),
                text(Calendar.TIMEZONE, "invalid-timezone-marker"),
            )

        val failure =
            assertThrows(ActiveSyncProtocolDataException::class.java) {
                ActiveSyncCalendarApplicationParser.parse(
                    RawCalendarCommand(RawCalendarCommandKind.ADD, "invalid-timezone", data),
                    PROFILE_EMAIL,
                )
            }
        val snapshot = checkNotNull(failure.calendarFailureSnapshot)

        assertEquals(ActiveSyncValidationReason.INVALID_TIME_ZONE, failure.reason)
        assertEquals(DiagnosticCalendarRule.INVALID_TIME_ZONE, snapshot.rule)
        assertEquals(DiagnosticCalendarField.TIME_ZONE_RAW, snapshot.failedField)
        assertEquals(
            DiagnosticFieldValue.Text("invalid-timezone-marker"),
            snapshot.field(DiagnosticCalendarFieldSource.RESPONSE, DiagnosticCalendarField.TIME_ZONE_RAW).value,
        )
    }

    @Test
    fun `all-day and response value failures retain tag-specific rules`() {
        val base = ordinaryChildren(subject = "typed-rule")
        val invalidAllDay =
            element(
                AirSync.APPLICATION_DATA,
                *(base.filterNot { child -> child.tag == Calendar.ALL_DAY_EVENT } +
                    text(Calendar.ALL_DAY_EVENT, "not-a-boolean")).toTypedArray(),
            )
        val invalidResponse =
            element(
                AirSync.APPLICATION_DATA,
                *base.toTypedArray(),
                text(Calendar.MEETING_STATUS, "3"),
                text(Calendar.RESPONSE_TYPE, "99"),
            )

        val allDayFailure =
            assertThrows(ActiveSyncProtocolDataException::class.java) {
                ActiveSyncCalendarApplicationParser.parse(
                    RawCalendarCommand(RawCalendarCommandKind.ADD, "invalid-all-day", invalidAllDay),
                    PROFILE_EMAIL,
                )
            }
        val responseFailure =
            assertThrows(ActiveSyncProtocolDataException::class.java) {
                ActiveSyncCalendarApplicationParser.parse(
                    RawCalendarCommand(RawCalendarCommandKind.ADD, "invalid-response", invalidResponse),
                    PROFILE_EMAIL,
                )
            }

        assertEquals(ActiveSyncValidationReason.INVALID_ALL_DAY, allDayFailure.reason)
        assertEquals(DiagnosticCalendarField.ALL_DAY, allDayFailure.calendarFailureSnapshot?.failedField)
        assertEquals(ActiveSyncValidationReason.INVALID_MEETING_RESPONSE, responseFailure.reason)
        assertEquals(DiagnosticCalendarField.RESPONSE_TYPE, responseFailure.calendarFailureSnapshot?.failedField)
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

        val failure =
            assertThrows(ActiveSyncProtocolDataException::class.java) {
                ActiveSyncCalendarApplicationParser.parse(
                    RawCalendarCommand(RawCalendarCommandKind.ADD, "hebrew-recurrence", data),
                    PROFILE_EMAIL,
                )
            }

        assertEquals(ActiveSyncValidationReason.INVALID_RECURRENCE, failure.reason)
        val snapshot = checkNotNull(failure.calendarFailureSnapshot)
        assertEquals(DiagnosticCalendarRule.INVALID_RECURRENCE, snapshot.rule)
        assertEquals(DiagnosticCalendarField.RECURRENCE_CALENDAR_TYPE, snapshot.failedField)
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

    private fun exceptionFailure(
        version: ActiveSyncVersion,
        exception: WbxmlElement,
    ): ActiveSyncProtocolDataException =
        assertThrows(ActiveSyncProtocolDataException::class.java) {
            ActiveSyncCalendarApplicationParser.parse(
                RawCalendarCommand(
                    RawCalendarCommandKind.CHANGE,
                    "exception-field-context",
                    element(
                        AirSync.APPLICATION_DATA,
                        element(Calendar.EXCEPTIONS, exception),
                    ),
                ),
                PROFILE_EMAIL,
                version,
            )
        }

    private fun ordinaryChildren(subject: String): List<WbxmlElement> =
        listOf(
            text(Calendar.SUBJECT, subject),
            text(Calendar.START_TIME, "20260809T090000Z"),
            text(Calendar.END_TIME, "20260809T100000Z"),
            text(Calendar.ALL_DAY_EVENT, "0"),
        )

    private fun element(tag: WbxmlTag, vararg children: WbxmlElement) = WbxmlElement(tag, children = children.toList())

    private fun net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarFailureSnapshot.field(
        source: DiagnosticCalendarFieldSource,
        field: DiagnosticCalendarField,
    ) = fields.single { entry -> entry.source == source && entry.field == field }

    private fun throwableGraphText(root: Throwable): String =
        generateSequence(root) { throwable -> throwable.cause }
            .joinToString("|") { throwable ->
                buildString {
                    append(throwable.javaClass.name)
                    append(':').append(throwable.message)
                    if (throwable is ActiveSyncProtocolDataException) {
                        append(':').append(throwable.calendarFailureSnapshot)
                    }
                }
            }

    private fun text(tag: WbxmlTag, value: String) = WbxmlElement(tag, text = value)

    private companion object {
        const val PROFILE_EMAIL = "calendar@example.test"
        const val CALENDAR_COLOR = -13_408_615
    }
}
