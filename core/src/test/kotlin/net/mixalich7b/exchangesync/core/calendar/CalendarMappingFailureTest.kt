package net.mixalich7b.exchangesync.core.calendar

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalendarMappingFailureTest {
    @Test
    fun `every calendar mapping rejection exposes its stable rule`() {
        mappingFailures().forEach { case ->
            val failure = assertThrows(CalendarMappingException::class.java, case.mapping, case.label)

            assertEquals(case.rule, failure.rule, case.label)
            assertEquals(case.path, failure.path, case.label)
            assertTrue(failure.message?.isNotBlank() == true, case.label)
        }
    }

    @Test
    fun `nested exception rejection exposes its zero based exception path`() {
        val item =
            baseItem().copy(
                exceptions =
                    ActiveSyncField.Value(
                        listOf(
                            ActiveSyncCalendarException(
                                instanceStart = Instant.parse("2026-08-16T09:00:00Z"),
                                deleted = true,
                            ),
                            ActiveSyncCalendarException(
                                instanceStart = Instant.parse("2026-08-23T09:00:00Z"),
                                deleted = false,
                                end = ActiveSyncField.Value(Instant.parse("2026-08-23T08:59:59Z")),
                            ),
                        ),
                    ),
            )

        val failure = assertThrows(CalendarMappingException::class.java) { map(item) }

        assertEquals(CalendarMappingRule.EXCEPTION_TIME_RANGE_INVALID, failure.rule)
        assertEquals(CalendarMappingPath.Exception(1), failure.path)
    }

    private fun mappingFailures(): List<MappingFailureCase> =
        listOf(
            failure("meeting response is empty", CalendarMappingRule.MEETING_RESPONSE_EMPTY) {
                baseItem().copy(responseType = ActiveSyncField.Empty)
            },
            failure("received meeting response is missing", CalendarMappingRule.RECEIVED_MEETING_RESPONSE_MISSING) {
                receivedMeeting().copy(responseType = ActiveSyncField.Absent)
            },
            failure("received meeting response is empty", CalendarMappingRule.RECEIVED_MEETING_RESPONSE_EMPTY) {
                receivedMeeting().copy(responseType = ActiveSyncField.Empty)
            },
            failure("event range is reversed", CalendarMappingRule.EVENT_TIME_RANGE_INVALID) {
                baseItem().copy(end = ActiveSyncField.Value(Instant.parse("2026-08-09T08:59:59Z")))
            },
            failure("all-day event is not UTC aligned", CalendarMappingRule.EVENT_ALL_DAY_NOT_UTC_ALIGNED) {
                baseItem().copy(allDay = ActiveSyncField.Value(true))
            },
            failure(
                "exception range is reversed",
                CalendarMappingRule.EXCEPTION_TIME_RANGE_INVALID,
                CalendarMappingPath.Exception(0),
            ) {
                baseItem().copy(
                    exceptions =
                        ActiveSyncField.Value(
                            listOf(
                                ActiveSyncCalendarException(
                                    instanceStart = Instant.parse("2026-08-16T09:00:00Z"),
                                    deleted = false,
                                    end = ActiveSyncField.Value(Instant.parse("2026-08-16T08:59:59Z")),
                                ),
                            ),
                        ),
                )
            },
            recurrenceFailure(
                "recurrence interval is invalid",
                CalendarMappingRule.RECURRENCE_INTERVAL_INVALID,
                ActiveSyncRecurrence(ActiveSyncRecurrenceType.DAILY, 0, end = ActiveSyncRecurrenceEnd.Infinite),
            ),
            recurrenceFailure(
                "recurrence count is invalid",
                CalendarMappingRule.RECURRENCE_COUNT_INVALID,
                ActiveSyncRecurrence(
                    ActiveSyncRecurrenceType.DAILY,
                    1,
                    end = ActiveSyncRecurrenceEnd.Count(0),
                ),
            ),
            recurrenceFailure(
                "recurrence weekdays are missing",
                CalendarMappingRule.RECURRENCE_WEEKDAYS_MISSING,
                ActiveSyncRecurrence(ActiveSyncRecurrenceType.WEEKLY, 1, end = ActiveSyncRecurrenceEnd.Infinite),
            ),
            recurrenceFailure(
                "recurrence weekday mask is invalid",
                CalendarMappingRule.RECURRENCE_WEEKDAY_MASK_INVALID,
                ActiveSyncRecurrence(
                    ActiveSyncRecurrenceType.WEEKLY,
                    1,
                    dayOfWeekMask = 128,
                    end = ActiveSyncRecurrenceEnd.Infinite,
                ),
            ),
            recurrenceFailure(
                "first recurrence weekday is invalid",
                CalendarMappingRule.RECURRENCE_FIRST_WEEKDAY_INVALID,
                ActiveSyncRecurrence(
                    ActiveSyncRecurrenceType.WEEKLY,
                    1,
                    dayOfWeekMask = 2,
                    firstDayOfWeek = 7,
                    end = ActiveSyncRecurrenceEnd.Infinite,
                ),
            ),
            recurrenceFailure(
                "recurrence week position is invalid",
                CalendarMappingRule.RECURRENCE_WEEK_POSITION_INVALID,
                ActiveSyncRecurrence(
                    ActiveSyncRecurrenceType.MONTHLY_NTH,
                    1,
                    dayOfWeekMask = 2,
                    weekOfMonth = 0,
                    end = ActiveSyncRecurrenceEnd.Infinite,
                ),
            ),
            recurrenceFailure(
                "recurrence day of month is invalid",
                CalendarMappingRule.RECURRENCE_DAY_OF_MONTH_INVALID,
                ActiveSyncRecurrence(
                    ActiveSyncRecurrenceType.MONTHLY,
                    1,
                    dayOfMonth = 0,
                    end = ActiveSyncRecurrenceEnd.Infinite,
                ),
            ),
            recurrenceFailure(
                "recurrence month of year is invalid",
                CalendarMappingRule.RECURRENCE_MONTH_OF_YEAR_INVALID,
                ActiveSyncRecurrence(
                    ActiveSyncRecurrenceType.YEARLY,
                    1,
                    dayOfMonth = 1,
                    monthOfYear = 13,
                    end = ActiveSyncRecurrenceEnd.Infinite,
                ),
            ),
        )

    private fun failure(
        label: String,
        rule: CalendarMappingRule,
        path: CalendarMappingPath = CalendarMappingPath.Event,
        item: () -> ActiveSyncCalendarItem,
    ): MappingFailureCase = MappingFailureCase(label, rule, path) { map(item()) }

    private fun recurrenceFailure(
        label: String,
        rule: CalendarMappingRule,
        recurrence: ActiveSyncRecurrence,
    ): MappingFailureCase =
        failure(label, rule) {
            baseItem().copy(recurrence = ActiveSyncField.Value(recurrence))
        }

    private fun map(item: ActiveSyncCalendarItem) {
        CalendarEventMapper.map(
            ActiveSyncCalendarMutation.Upsert(item, isAddition = true),
            CALENDAR_COLOR,
        )
    }

    private fun baseItem(): ActiveSyncCalendarItem =
        ActiveSyncCalendarItem(
            serverId = "event-1",
            start = ActiveSyncField.Value(Instant.parse("2026-08-09T09:00:00Z")),
            end = ActiveSyncField.Value(Instant.parse("2026-08-09T10:00:00Z")),
            allDay = ActiveSyncField.Value(false),
        )

    private fun receivedMeeting(): ActiveSyncCalendarItem =
        baseItem().copy(
            meetingStatus = ActiveSyncField.Value(ActiveSyncMeetingStatus(3, true, true, false)),
            responseType = ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED),
        )

    private data class MappingFailureCase(
        val label: String,
        val rule: CalendarMappingRule,
        val path: CalendarMappingPath,
        val mapping: () -> Unit,
    )

    private companion object {
        const val CALENDAR_COLOR: Int = -13_408_615
    }
}
