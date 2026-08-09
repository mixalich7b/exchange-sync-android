package net.mixalich7b.exchangesync.core.calendar

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CalendarRecurrenceMapperTest {
    @Test
    fun `daily weekly monthly nth and yearly patterns map to deterministic finite or infinite RRULE`() {
        val cases =
            listOf(
                ActiveSyncRecurrence(ActiveSyncRecurrenceType.DAILY, 2, end = ActiveSyncRecurrenceEnd.Infinite) to
                    "FREQ=DAILY;INTERVAL=2",
                ActiveSyncRecurrence(
                    ActiveSyncRecurrenceType.DAILY,
                    2,
                    dayOfWeekMask = 10,
                    firstDayOfWeek = 1,
                    end = ActiveSyncRecurrenceEnd.Infinite,
                ) to "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;WKST=MO",
                ActiveSyncRecurrence(
                    ActiveSyncRecurrenceType.WEEKLY,
                    1,
                    dayOfWeekMask = 10,
                    firstDayOfWeek = 1,
                    end = ActiveSyncRecurrenceEnd.Count(8),
                ) to "FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,WE;WKST=MO;COUNT=8",
                ActiveSyncRecurrence(
                    ActiveSyncRecurrenceType.MONTHLY,
                    1,
                    dayOfMonth = 15,
                    end = ActiveSyncRecurrenceEnd.Infinite,
                ) to "FREQ=MONTHLY;INTERVAL=1;BYMONTHDAY=15",
                ActiveSyncRecurrence(
                    ActiveSyncRecurrenceType.MONTHLY_NTH,
                    1,
                    dayOfWeekMask = 2,
                    weekOfMonth = 2,
                    end = ActiveSyncRecurrenceEnd.Infinite,
                ) to "FREQ=MONTHLY;INTERVAL=1;BYDAY=MO;BYSETPOS=2",
                ActiveSyncRecurrence(
                    ActiveSyncRecurrenceType.YEARLY,
                    1,
                    dayOfMonth = 20,
                    monthOfYear = 6,
                    end = ActiveSyncRecurrenceEnd.Until(Instant.parse("2028-11-30T00:00:00Z")),
                ) to "FREQ=YEARLY;INTERVAL=1;BYMONTH=6;BYMONTHDAY=20;UNTIL=20281130T000000Z",
                ActiveSyncRecurrence(
                    ActiveSyncRecurrenceType.YEARLY_NTH,
                    1,
                    dayOfWeekMask = 1,
                    weekOfMonth = 5,
                    monthOfYear = 11,
                    end = ActiveSyncRecurrenceEnd.Infinite,
                ) to "FREQ=YEARLY;INTERVAL=1;BYMONTH=11;BYDAY=SU;BYSETPOS=-1",
            )

        cases.forEach { (recurrence, expected) ->
            val mapped = mappedEvent(baseItem().copy(recurrence = ActiveSyncField.Value(recurrence)))
            assertEquals(ActiveSyncField.Value(expected), mapped.recurrenceRule, recurrence.toString())
        }
    }

    @Test
    fun `invalid recurrence representation fails rather than shifting or broadening occurrences`() {
        val invalid =
            ActiveSyncRecurrence(
                ActiveSyncRecurrenceType.WEEKLY,
                interval = 1,
                dayOfWeekMask = 128,
                end = ActiveSyncRecurrenceEnd.Infinite,
            )

        assertThrows(CalendarMappingException::class.java) {
            mappedEvent(baseItem().copy(recurrence = ActiveSyncField.Value(invalid)))
        }
    }

    @Test
    fun `changed and deleted exceptions retain original instance and inherit or override response presentation`() {
        val original1 = Instant.parse("2026-08-16T09:00:00Z")
        val original2 = Instant.parse("2026-08-23T09:00:00Z")
        val original3 = Instant.parse("2026-08-30T09:00:00Z")
        val item =
            pendingItem().copy(
                recurrence =
                    ActiveSyncField.Value(
                        ActiveSyncRecurrence(
                            ActiveSyncRecurrenceType.WEEKLY,
                            interval = 1,
                            dayOfWeekMask = 1,
                            end = ActiveSyncRecurrenceEnd.Infinite,
                        ),
                    ),
                exceptions =
                    ActiveSyncField.Value(
                        listOf(
                            ActiveSyncCalendarException(
                                instanceStart = original1,
                                deleted = false,
                                start = ActiveSyncField.Value(Instant.parse("2026-08-16T11:00:00Z")),
                                end = ActiveSyncField.Value(Instant.parse("2026-08-16T12:00:00Z")),
                            ),
                            ActiveSyncCalendarException(
                                instanceStart = original2,
                                deleted = false,
                                responseType = ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED),
                            ),
                            ActiveSyncCalendarException(instanceStart = original3, deleted = true),
                        ),
                    ),
            )

        val exceptions = (mappedEvent(item).exceptions as ActiveSyncField.Value<List<ProviderCalendarException>>).value

        assertEquals(listOf(original1, original2, original3), exceptions.map { exception -> exception.originalInstance })
        assertEquals(ActiveSyncField.Value(ProviderEventStatus.TENTATIVE), exceptions[0].status)
        assertEquals(ActiveSyncField.Value(PALE_COLOR), exceptions[0].eventColor)
        assertEquals(ActiveSyncField.Value(ProviderEventStatus.CONFIRMED), exceptions[1].status)
        assertEquals(ActiveSyncField.Empty, exceptions[1].eventColor)
        assertEquals(true, exceptions[2].deleted)
    }

    @Test
    fun `non-deleted exception rejects an explicit end not after its effective start`() {
        val instanceStart = Instant.parse("2026-08-16T09:00:00Z")
        val malformed =
            baseItem().copy(
                exceptions =
                    ActiveSyncField.Value(
                        listOf(
                            ActiveSyncCalendarException(
                                instanceStart = instanceStart,
                                deleted = false,
                                end = ActiveSyncField.Value(Instant.parse("2026-08-16T08:59:59Z")),
                            ),
                        ),
                    ),
            )

        assertThrows(CalendarMappingException::class.java) {
            mappedEvent(malformed)
        }
    }

    @Test
    fun `pending accepted and pending updates keep the same ServerId while color is restored and cleared`() {
        val states =
            listOf(
                ActiveSyncResponseType.NONE,
                ActiveSyncResponseType.ACCEPTED,
                ActiveSyncResponseType.TENTATIVE,
            ).map { response ->
                mappedEvent(
                    pendingItem().copy(
                        responseType = ActiveSyncField.Value(response),
                        availability = ActiveSyncField.Value(ActiveSyncAvailability.BUSY),
                    ),
                )
            }

        assertEquals(listOf("series-1", "series-1", "series-1"), states.map(ProviderEvent::syncId))
        assertEquals(ActiveSyncField.Value(PALE_COLOR), states[0].eventColor)
        assertEquals(ActiveSyncField.Empty, states[1].eventColor)
        assertEquals(ActiveSyncField.Value(PALE_COLOR), states[2].eventColor)
    }

    @Test
    fun `partial exception response omission preserves only a prior explicit override`() {
        val instance = Instant.parse("2026-08-16T09:00:00Z")
        val previousExplicit =
            mappedEvent(
                pendingItem().copy(
                    exceptions =
                        ActiveSyncField.Value(
                            listOf(
                                ActiveSyncCalendarException(
                                    instanceStart = instance,
                                    deleted = false,
                                    responseType = ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED),
                                ),
                            ),
                        ),
                ),
            )
        val explicitPartial =
            pendingItem().copy(
                start = ActiveSyncField.Absent,
                end = ActiveSyncField.Absent,
                allDay = ActiveSyncField.Absent,
                responseType = ActiveSyncField.Value(ActiveSyncResponseType.NONE),
                exceptions =
                    ActiveSyncField.Value(
                        listOf(ActiveSyncCalendarException(instanceStart = instance, deleted = false)),
                    ),
            )

        val preserved = mappedEvent(explicitPartial, previousExplicit)
        val preservedException =
            (preserved.exceptions as ActiveSyncField.Value<List<ProviderCalendarException>>).value.single()

        assertEquals(ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED), preservedException.responseType)
        assertEquals(ActiveSyncField.Value(ProviderEventStatus.CONFIRMED), preservedException.status)

        val previousInherited =
            mappedEvent(
                pendingItem().copy(
                    exceptions =
                        ActiveSyncField.Value(
                            listOf(ActiveSyncCalendarException(instanceStart = instance, deleted = false)),
                        ),
                ),
            )
        val inheritedPartial =
            explicitPartial.copy(responseType = ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED))
        val inherited = mappedEvent(inheritedPartial, previousInherited)
        val inheritedException =
            (inherited.exceptions as ActiveSyncField.Value<List<ProviderCalendarException>>).value.single()

        assertEquals(ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED), inheritedException.responseType)
        assertEquals(ActiveSyncField.Value(ProviderEventStatus.CONFIRMED), inheritedException.status)
    }

    private fun mappedEvent(item: ActiveSyncCalendarItem): ProviderEvent =
        (CalendarEventMapper.map(ActiveSyncCalendarMutation.Upsert(item, true), CALENDAR_COLOR)
            as ProviderCalendarMutation.Upsert).event

    private fun mappedEvent(item: ActiveSyncCalendarItem, previous: ProviderEvent): ProviderEvent =
        (CalendarEventMapper.map(ActiveSyncCalendarMutation.Upsert(item, false), CALENDAR_COLOR, previous)
            as ProviderCalendarMutation.Upsert).event

    private fun pendingItem(): ActiveSyncCalendarItem =
        baseItem().copy(
            meetingStatus = ActiveSyncField.Value(ActiveSyncMeetingStatus(3, true, true, false)),
            responseType = ActiveSyncField.Value(ActiveSyncResponseType.NONE),
            availability = ActiveSyncField.Value(ActiveSyncAvailability.BUSY),
        )

    private fun baseItem(): ActiveSyncCalendarItem =
        ActiveSyncCalendarItem(
            serverId = "series-1",
            start = ActiveSyncField.Value(Instant.parse("2026-08-09T09:00:00Z")),
            end = ActiveSyncField.Value(Instant.parse("2026-08-09T10:00:00Z")),
            allDay = ActiveSyncField.Value(false),
        )

    private companion object {
        const val CALENDAR_COLOR: Int = -13_408_615
        const val PALE_COLOR: Int = -7_361_593
    }
}
