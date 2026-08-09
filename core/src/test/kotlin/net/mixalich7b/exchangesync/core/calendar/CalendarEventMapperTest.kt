package net.mixalich7b.exchangesync.core.calendar

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CalendarEventMapperTest {
    @Test
    fun `ordinary timed event preserves identity descriptive fields people availability privacy and reminder`() {
        val item =
            baseItem("event-1").copy(
                uid = ActiveSyncField.Value("exchange-uid"),
                subject = ActiveSyncField.Value("Planning"),
                body = ActiveSyncField.Value("Agenda"),
                location = ActiveSyncField.Value("Room 7"),
                organizerEmail = ActiveSyncField.Value("owner@example.test"),
                organizerName = ActiveSyncField.Value("Owner"),
                attendees =
                    ActiveSyncField.Value(
                        listOf(
                            ActiveSyncAttendee(
                                email = "calendar@example.test",
                                name = "Current User",
                                status = ActiveSyncAttendeeStatus.ACCEPTED,
                                type = ActiveSyncAttendeeType.REQUIRED,
                            ),
                        ),
                    ),
                meetingStatus = ActiveSyncField.Value(ActiveSyncMeetingStatus(0, false, false, false)),
                availability = ActiveSyncField.Value(ActiveSyncAvailability.OUT_OF_OFFICE),
                sensitivity = ActiveSyncField.Value(ActiveSyncSensitivity.CONFIDENTIAL),
                reminderMinutes = ActiveSyncField.Value(20),
            )

        val mapped = CalendarEventMapper.map(ActiveSyncCalendarMutation.Upsert(item, isAddition = true), CALENDAR_COLOR)
            as ProviderCalendarMutation.Upsert

        assertEquals("event-1", mapped.event.syncId)
        assertEquals(ActiveSyncField.Value("exchange-uid"), mapped.event.uid)
        assertEquals(ActiveSyncField.Value("Planning"), mapped.event.title)
        assertEquals(ActiveSyncField.Value("Agenda"), mapped.event.description)
        assertEquals(ActiveSyncField.Value("Room 7"), mapped.event.location)
        assertEquals(item.start, mapped.event.start)
        assertEquals(item.end, mapped.event.end)
        assertEquals(ActiveSyncField.Value(ProviderEventStatus.CONFIRMED), mapped.event.status)
        assertEquals(ActiveSyncField.Value(ProviderAvailability.OUT_OF_OFFICE), mapped.event.availability)
        assertEquals(ActiveSyncField.Value(ProviderAccessLevel.CONFIDENTIAL), mapped.event.accessLevel)
        assertEquals(ActiveSyncField.Value(20), mapped.event.reminderMinutes)
        val attendees = mapped.event.attendees as ActiveSyncField.Value<List<ProviderAttendee>>
        assertEquals(ProviderAttendeeRole.REQUIRED, attendees.value.single().role)
        assertEquals(ProviderAttendeeStatus.ACCEPTED, attendees.value.single().status)
        assertEquals(ActiveSyncField.Empty, mapped.event.eventColor)
    }

    @Test
    fun `pending tentative accepted organizer and declined responses map semantic presentation`() {
        val cases =
            listOf(
                case(ActiveSyncResponseType.NONE, received = true, ProviderEventStatus.TENTATIVE, ProviderSelfStatus.INVITED, ProviderAvailability.TENTATIVE, PALE_COLOR),
                case(ActiveSyncResponseType.NOT_RESPONDED, received = true, ProviderEventStatus.TENTATIVE, ProviderSelfStatus.INVITED, ProviderAvailability.TENTATIVE, PALE_COLOR),
                case(ActiveSyncResponseType.TENTATIVE, received = true, ProviderEventStatus.TENTATIVE, ProviderSelfStatus.TENTATIVE, ProviderAvailability.TENTATIVE, PALE_COLOR),
                case(ActiveSyncResponseType.ACCEPTED, received = true, ProviderEventStatus.CONFIRMED, ProviderSelfStatus.ACCEPTED, ProviderAvailability.BUSY, null),
                case(ActiveSyncResponseType.ORGANIZER, received = false, ProviderEventStatus.CONFIRMED, ProviderSelfStatus.ORGANIZER, ProviderAvailability.BUSY, null),
                case(ActiveSyncResponseType.DECLINED, received = true, ProviderEventStatus.CANCELLED, ProviderSelfStatus.DECLINED, ProviderAvailability.BUSY, null),
            )

        cases.forEach { fixture ->
            val item =
                baseItem(fixture.response.name).copy(
                    meetingStatus =
                        ActiveSyncField.Value(
                            ActiveSyncMeetingStatus(
                                rawValue = if (fixture.received) 3 else 1,
                                isMeeting = true,
                                isReceived = fixture.received,
                                isCancelled = false,
                            ),
                        ),
                    responseType = ActiveSyncField.Value(fixture.response),
                    availability = ActiveSyncField.Value(ActiveSyncAvailability.BUSY),
                )
            val event =
                (CalendarEventMapper.map(ActiveSyncCalendarMutation.Upsert(item, true), CALENDAR_COLOR)
                    as ProviderCalendarMutation.Upsert).event

            assertEquals(ActiveSyncField.Value(fixture.status), event.status, fixture.response.name)
            assertEquals(ActiveSyncField.Value(fixture.selfStatus), event.selfStatus, fixture.response.name)
            assertEquals(ActiveSyncField.Value(fixture.availability), event.availability, fixture.response.name)
            if (fixture.color == null) {
                assertEquals(ActiveSyncField.Empty, event.eventColor, fixture.response.name)
            } else {
                assertEquals(ActiveSyncField.Value(fixture.color), event.eventColor, fixture.response.name)
            }
        }
    }

    @Test
    fun `pale color blends each opaque channel forty five percent toward white`() {
        assertEquals(PALE_COLOR, CalendarEventMapper.paleColor(CALENDAR_COLOR))
        assertEquals(0xFFFFFFFF.toInt(), CalendarEventMapper.paleColor(0xFFFFFFFF.toInt()))
        assertThrows(IllegalArgumentException::class.java) { CalendarEventMapper.paleColor(0x7F336699) }
    }

    @Test
    fun `partial response absence remains absent while accepted clears prior pending color atomically`() {
        val partial = baseItem("event-1").copy(
            start = ActiveSyncField.Absent,
            end = ActiveSyncField.Absent,
            allDay = ActiveSyncField.Absent,
            meetingStatus = ActiveSyncField.Absent,
            responseType = ActiveSyncField.Absent,
            availability = ActiveSyncField.Absent,
            reminderMinutes = ActiveSyncField.Empty,
        )
        val partialMapped =
            (CalendarEventMapper.map(ActiveSyncCalendarMutation.Upsert(partial, false), CALENDAR_COLOR)
                as ProviderCalendarMutation.Upsert).event
        assertEquals(ActiveSyncField.Absent, partialMapped.status)
        assertEquals(ActiveSyncField.Absent, partialMapped.selfStatus)
        assertEquals(ActiveSyncField.Absent, partialMapped.eventColor)
        assertEquals(ActiveSyncField.Empty, partialMapped.reminderMinutes)

        val accepted =
            baseItem("event-1").copy(
                meetingStatus = ActiveSyncField.Value(ActiveSyncMeetingStatus(3, true, true, false)),
                responseType = ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED),
                availability = ActiveSyncField.Value(ActiveSyncAvailability.FREE),
            )
        val acceptedMapped =
            (CalendarEventMapper.map(ActiveSyncCalendarMutation.Upsert(accepted, false), CALENDAR_COLOR)
                as ProviderCalendarMutation.Upsert).event
        assertEquals(ActiveSyncField.Value(ProviderEventStatus.CONFIRMED), acceptedMapped.status)
        assertEquals(ActiveSyncField.Value(ProviderSelfStatus.ACCEPTED), acceptedMapped.selfStatus)
        assertEquals(ActiveSyncField.Value(ProviderAvailability.FREE), acceptedMapped.availability)
        assertEquals(ActiveSyncField.Empty, acceptedMapped.eventColor)
    }

    @Test
    fun `response-only accepted change clears pending presentation when MeetingStatus is ghosted`() {
        val accepted =
            baseItem("event-1").copy(
                start = ActiveSyncField.Absent,
                end = ActiveSyncField.Absent,
                allDay = ActiveSyncField.Absent,
                meetingStatus = ActiveSyncField.Absent,
                responseType = ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED),
                availability = ActiveSyncField.Value(ActiveSyncAvailability.BUSY),
            )

        val mapped =
            (CalendarEventMapper.map(ActiveSyncCalendarMutation.Upsert(accepted, false), CALENDAR_COLOR)
                as ProviderCalendarMutation.Upsert).event

        assertEquals(ActiveSyncField.Value(ProviderEventStatus.CONFIRMED), mapped.status)
        assertEquals(ActiveSyncField.Value(ProviderSelfStatus.ACCEPTED), mapped.selfStatus)
        assertEquals(ActiveSyncField.Value(ProviderAvailability.BUSY), mapped.availability)
        assertEquals(ActiveSyncField.Empty, mapped.eventColor)
    }

    @Test
    fun `partial change rejects a merged end that no longer follows start`() {
        val previous =
            (CalendarEventMapper.map(
                ActiveSyncCalendarMutation.Upsert(baseItem("event-1"), isAddition = true),
                CALENDAR_COLOR,
            ) as ProviderCalendarMutation.Upsert).event
        val invalidChange =
            ActiveSyncCalendarItem(
                serverId = "event-1",
                start = ActiveSyncField.Value(Instant.parse("2026-08-09T11:00:00Z")),
            )

        assertThrows(CalendarMappingException::class.java) {
            CalendarEventMapper.map(
                ActiveSyncCalendarMutation.Upsert(invalidChange, isAddition = false),
                CALENDAR_COLOR,
                previous,
            )
        }
    }

    @Test
    fun `partial change rejects merged all-day times that are not UTC date boundaries`() {
        val previous =
            (CalendarEventMapper.map(
                ActiveSyncCalendarMutation.Upsert(baseItem("event-1"), isAddition = true),
                CALENDAR_COLOR,
            ) as ProviderCalendarMutation.Upsert).event
        val invalidChange =
            ActiveSyncCalendarItem(
                serverId = "event-1",
                allDay = ActiveSyncField.Value(true),
            )

        assertThrows(CalendarMappingException::class.java) {
            CalendarEventMapper.map(
                ActiveSyncCalendarMutation.Upsert(invalidChange, isAddition = false),
                CALENDAR_COLOR,
                previous,
            )
        }
    }

    @Test
    fun `received active meeting without resolved response fails deterministically`() {
        val item =
            baseItem("ambiguous").copy(
                meetingStatus = ActiveSyncField.Value(ActiveSyncMeetingStatus(3, true, true, false)),
                responseType = ActiveSyncField.Absent,
            )

        assertThrows(CalendarMappingException::class.java) {
            CalendarEventMapper.map(ActiveSyncCalendarMutation.Upsert(item, true), CALENDAR_COLOR)
        }
    }

    @Test
    fun `delete keeps ServerId and soft deletion intent`() {
        assertEquals(
            ProviderCalendarMutation.Delete("event-9", soft = true),
            CalendarEventMapper.map(ActiveSyncCalendarMutation.Delete("event-9", soft = true), CALENDAR_COLOR),
        )
    }

    private fun baseItem(serverId: String): ActiveSyncCalendarItem =
        ActiveSyncCalendarItem(
            serverId = serverId,
            start = ActiveSyncField.Value(Instant.parse("2026-08-09T09:00:00Z")),
            end = ActiveSyncField.Value(Instant.parse("2026-08-09T10:00:00Z")),
            allDay = ActiveSyncField.Value(false),
        )

    private fun case(
        response: ActiveSyncResponseType,
        received: Boolean,
        status: ProviderEventStatus,
        selfStatus: ProviderSelfStatus,
        availability: ProviderAvailability,
        color: Int?,
    ) = PresentationCase(response, received, status, selfStatus, availability, color)

    private data class PresentationCase(
        val response: ActiveSyncResponseType,
        val received: Boolean,
        val status: ProviderEventStatus,
        val selfStatus: ProviderSelfStatus,
        val availability: ProviderAvailability,
        val color: Int?,
    )

    private companion object {
        const val CALENDAR_COLOR: Int = -13_408_615 // 0xFF336699
        const val PALE_COLOR: Int = -7_361_593 // 0xFF8FABC7
    }
}
