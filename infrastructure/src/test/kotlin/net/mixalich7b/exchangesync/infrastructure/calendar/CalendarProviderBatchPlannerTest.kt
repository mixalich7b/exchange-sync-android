package net.mixalich7b.exchangesync.infrastructure.calendar

import java.time.Instant
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncAttendee
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncAttendeeStatus
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncAttendeeType
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncAvailability
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarException
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarItem
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarMutation
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncField
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncMeetingStatus
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncResponseType
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncSensitivity
import net.mixalich7b.exchangesync.core.sync.RemoteCalendarPage
import net.mixalich7b.exchangesync.core.sync.SyncCheckpoints
import net.mixalich7b.exchangesync.infrastructure.activesync.HighElementCalendarSyncFixture
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

class CalendarProviderBatchPlannerTest {
    @Test
    fun `exception explicit clears use required provider defaults and nullable nulls`() {
        val exceptionStart = Instant.parse("2026-08-16T09:00:00Z")
        val item =
            baseItem().copy(
                subject = ActiveSyncField.Value("Series title"),
                availability = ActiveSyncField.Value(ActiveSyncAvailability.OUT_OF_OFFICE),
                sensitivity = ActiveSyncField.Value(ActiveSyncSensitivity.CONFIDENTIAL),
                recurrence = ActiveSyncField.Value(dailyRecurrence()),
                exceptions =
                    ActiveSyncField.Value(
                        listOf(
                            ActiveSyncCalendarException(
                                instanceStart = exceptionStart,
                                deleted = false,
                                subject = ActiveSyncField.Empty,
                                availability = ActiveSyncField.Empty,
                                sensitivity = ActiveSyncField.Empty,
                            ),
                        ),
                    ),
            )
        val batch =
            CalendarProviderBatchPlanner.plan(
                plan(ActiveSyncCalendarMutation.Upsert(item, isAddition = true)),
                FixedTimeZoneResolver("UTC"),
            )
        lateinit var request: AndroidCalendarProviderBatchRequest
        val gateway =
            AndroidCalendarProviderSubBatchGateway { candidate ->
                request = candidate
                candidate.operations.mapIndexed { index, operation ->
                    AndroidCalendarProviderOperationResult(
                        uri =
                            "content://com.android.calendar/events/${700 + index}"
                                .takeIf {
                                    operation.target == AndroidCalendarProviderTarget.EVENTS &&
                                        operation.action == AndroidCalendarProviderAction.INSERT
                                },
                        count = 1,
                    )
                }
            }

        gateway.apply(CalendarProviderSubBatchCursor(batch).next()!!)

        val exceptionValues =
            request.operations.single { operation ->
                operation.target == AndroidCalendarProviderTarget.EVENTS &&
                    operation.values[CalendarProviderField.ORIGINAL_SYNC_ID] == "server-1"
            }.values
        assertTrue(exceptionValues.containsKey(CalendarProviderField.TITLE))
        assertNull(exceptionValues[CalendarProviderField.TITLE])
        assertEquals(ProviderInteger.BUSY, exceptionValues[CalendarProviderField.AVAILABILITY])
        assertEquals(0, exceptionValues[CalendarProviderField.ACCESS_LEVEL])
        assertTrue(
            exceptionValues
                .filterKeys { key -> key in REQUIRED_EVENT_PROVIDER_FIELDS }
                .values
                .none { value -> value == null },
        )
    }

    @Test
    fun `batch planning rejects null for a required event provider field`() {
        val failure =
            assertThrows(CalendarPlanningException::class.java) {
                CalendarProviderBatchPlan.create(
                    OWNED_CALENDAR,
                    listOf(
                        CalendarProviderBatchOperation.EventInsert(
                            OWNED_CALENDAR,
                            mapOf(CalendarProviderField.ALL_DAY to null),
                        ),
                    ),
                )
            }

        assertEquals(CalendarPlanningRule.PROVIDER_REQUIRED_VALUE_NULL, failure.planningRule)
    }

    @Test
    fun `high fanout series keeps organizer recurrence and unique exceptions while suppressing guests`() {
        val mutation = HighElementCalendarSyncFixture.mutation()
        val pagePlan =
            CalendarPagePlanner.plan(
                page(mutation),
                resolution(),
                emptyList(),
            )

        val batch = CalendarProviderBatchPlanner.plan(pagePlan, FixedTimeZoneResolver("UTC"))

        val series = batch.operations.filterIsInstance<CalendarProviderBatchOperation.EventInsert>().single()
        assertEquals("FREQ=DAILY;INTERVAL=1", series.values[CalendarProviderField.RECURRENCE_RULE])
        assertEquals(0, batch.nonOrganizerAttendeeInserts().size)
        assertEquals(
            HighElementCalendarSyncFixture.CHANGED_EXCEPTION_COUNT + 1,
            batch.attendeeSuppressions.size,
        )
        assertTrue(batch.attendeeSuppressions.all { suppression ->
            suppression.inputCount == HighElementCalendarSyncFixture.ATTENDEE_COUNT && suppression.organizerRetained
        })

        val exceptions = batch.operations.filterIsInstance<CalendarProviderBatchOperation.ExceptionInsert>()
        assertEquals(HighElementCalendarSyncFixture.TOTAL_EXCEPTION_COUNT, exceptions.size)
        val nonDeletedExceptionReferences =
            batch.operations.withIndex()
                .filter { (_, operation) ->
                    operation is CalendarProviderBatchOperation.ExceptionInsert &&
                        operation.values[CalendarProviderField.EXCEPTION_DELETED] == 0
                }.map { (index, _) -> EventReference.Inserted(index) }
        val organizerInserts = batch.organizerAttendeeInserts()
        assertEquals(HighElementCalendarSyncFixture.CHANGED_EXCEPTION_COUNT + 1, organizerInserts.size)
        assertEquals("organizer@example.test", organizerInserts.single { insert ->
            insert.event == EventReference.Inserted(0)
        }.values[CalendarProviderField.ATTENDEE_EMAIL])
        assertTrue(nonDeletedExceptionReferences.all { reference ->
            organizerInserts.single { insert -> insert.event == reference }
                .values[CalendarProviderField.ATTENDEE_EMAIL] == "organizer@example.test"
        })
        val exceptionIds = exceptions.map { exception -> exception.values.getValue(CalendarProviderField.SYNC_ID) }
        assertEquals(exceptionIds.size, exceptionIds.distinct().size)
        assertEquals(
            HighElementCalendarSyncFixture.DELETED_EXCEPTION_COUNT,
            exceptions.count { exception -> exception.values[CalendarProviderField.EXCEPTION_DELETED] == 1 },
        )
        assertTrue(
            exceptions.filter { exception -> exception.values[CalendarProviderField.EXCEPTION_DELETED] == 0 }
                .all { exception -> exception.values[CalendarProviderField.HAS_ATTENDEE_DATA] == 1 },
        )
    }

    @Test
    fun `addition inserts one stable event plus attendees reminder and linked exceptions`() {
        val exceptionStart = Instant.parse("2026-08-16T09:00:00Z")
        val item =
            baseItem().copy(
                uid = ActiveSyncField.Value("exchange-uid"),
                subject = ActiveSyncField.Value("Planning"),
                organizerEmail = ActiveSyncField.Value("owner@example.test"),
                organizerName = ActiveSyncField.Value("Owner"),
                responseRequested = ActiveSyncField.Value(true),
                attendees =
                    ActiveSyncField.Value(
                        listOf(
                            ActiveSyncAttendee(
                                "guest@example.test",
                                "Guest",
                                ActiveSyncAttendeeStatus.TENTATIVE,
                                ActiveSyncAttendeeType.OPTIONAL,
                            ),
                        ),
                    ),
                reminderMinutes = ActiveSyncField.Value(20),
                recurrence = ActiveSyncField.Value(dailyRecurrence()),
                exceptions =
                    ActiveSyncField.Value(
                        listOf(
                            ActiveSyncCalendarException(
                                instanceStart = exceptionStart,
                                deleted = false,
                                subject = ActiveSyncField.Value("Moved planning"),
                                start = ActiveSyncField.Value(exceptionStart.plusSeconds(3_600)),
                                end = ActiveSyncField.Value(exceptionStart.plusSeconds(7_200)),
                                reminderMinutes = ActiveSyncField.Empty,
                                meetingStatus = ActiveSyncField.Value(ActiveSyncMeetingStatus(3, true, true, false)),
                                responseType = ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED),
                                responseRequested = ActiveSyncField.Value(true),
                                sensitivity = ActiveSyncField.Value(ActiveSyncSensitivity.CONFIDENTIAL),
                                attendees =
                                    ActiveSyncField.Value(
                                        listOf(
                                            ActiveSyncAttendee(
                                                "exception@example.test",
                                                "Exception Guest",
                                                null,
                                                null,
                                            ),
                                        ),
                                    ),
                            ),
                            ActiveSyncCalendarException(
                                instanceStart = exceptionStart.plusSeconds(86_400),
                                deleted = true,
                            ),
                        ),
                    ),
            )
        val pagePlan = plan(ActiveSyncCalendarMutation.Upsert(item, isAddition = true))

        val batch = CalendarProviderBatchPlanner.plan(pagePlan, FixedTimeZoneResolver("Europe/Moscow"))

        assertEquals(OWNED_CALENDAR, batch.calendarId)
        val eventInsert = batch.operations.first() as CalendarProviderBatchOperation.EventInsert
        assertEquals("server-1", eventInsert.values[CalendarProviderField.SYNC_ID])
        assertEquals("exchange-uid", eventInsert.values[CalendarProviderField.UID])
        assertEquals("Europe/Moscow", eventInsert.values[CalendarProviderField.EVENT_TIME_ZONE])
        assertEquals("PT3600S", eventInsert.values[CalendarProviderField.DURATION])
        assertFalse(eventInsert.values.containsKey(CalendarProviderField.END))
        assertEquals(1, eventInsert.values[CalendarProviderField.HAS_ALARM])
        assertEquals(1, eventInsert.values[CalendarProviderField.HAS_ATTENDEE_DATA])
        assertEquals(1, eventInsert.values[CalendarProviderField.RESPONSE_REQUESTED])

        val attendeeInserts = batch.operations.filterIsInstance<CalendarProviderBatchOperation.AttendeeInsert>()
        assertEquals(4, attendeeInserts.size)
        assertEquals(2, attendeeInserts.count {
            it.values[CalendarProviderField.ATTENDEE_RELATIONSHIP] == ProviderInteger.ORGANIZER_RELATIONSHIP
        })
        assertEquals(
            ProviderInteger.TENTATIVE_ATTENDEE,
            attendeeInserts.single { it.values[CalendarProviderField.ATTENDEE_EMAIL] == "guest@example.test" }
                .values[CalendarProviderField.ATTENDEE_STATUS],
        )

        val reminder = batch.operations.filterIsInstance<CalendarProviderBatchOperation.ReminderInsert>().single()
        assertEquals(EventReference.Inserted(0), reminder.event)
        assertEquals(20, reminder.values[CalendarProviderField.REMINDER_MINUTES])

        val exceptions = batch.operations.filterIsInstance<CalendarProviderBatchOperation.ExceptionInsert>()
        assertEquals(2, exceptions.size)
        assertTrue(exceptions.all { it.series == EventReference.Inserted(0) })
        assertEquals(exceptionStart.toEpochMilli(), exceptions.first().values[CalendarProviderField.ORIGINAL_INSTANCE_TIME])
        assertEquals("server-1", exceptions.first().values[CalendarProviderField.ORIGINAL_SYNC_ID])
        assertEquals(
            ActiveSyncResponseType.ACCEPTED.wireValue,
            exceptions.first().values[CalendarProviderField.EXCEPTION_RESPONSE_OVERRIDE],
        )
        assertEquals(ProviderInteger.CONFIDENTIAL_ACCESS, exceptions.first().values[CalendarProviderField.ACCESS_LEVEL])
        assertEquals(1, exceptions.first().values[CalendarProviderField.RESPONSE_REQUESTED])
        assertEquals(3, exceptions.first().values[CalendarProviderField.MEETING_STATUS])
        assertTrue(
            attendeeInserts.any { attendee ->
                attendee.event != EventReference.Inserted(0) &&
                    attendee.values[CalendarProviderField.ATTENDEE_EMAIL] == "exception@example.test"
            },
        )
        assertTrue(
            attendeeInserts.any { attendee ->
                attendee.event != EventReference.Inserted(0) &&
                    attendee.values[CalendarProviderField.ATTENDEE_EMAIL] == "owner@example.test" &&
                    attendee.values[CalendarProviderField.ATTENDEE_RELATIONSHIP] ==
                    ProviderInteger.ORGANIZER_RELATIONSHIP
            },
        )
        assertEquals(ProviderInteger.CANCELLED_EVENT, exceptions.last().values[CalendarProviderField.STATUS])
        assertEquals(0, exceptions.first().values[CalendarProviderField.EXCEPTION_DELETED])
        assertEquals(1, exceptions.last().values[CalendarProviderField.EXCEPTION_DELETED])
        assertEquals(2, batch.operations.filterIsInstance<CalendarProviderBatchOperation.RemindersDelete>().size)
    }

    @Test
    fun `top-level attendee materialization preserves one hundred and suppresses all one hundred one`() {
        val batches =
            listOf(100, 101).associateWith { count ->
                CalendarProviderBatchPlanner.plan(
                    plan(
                        ActiveSyncCalendarMutation.Upsert(
                            baseItem().copy(
                                organizerEmail = ActiveSyncField.Value("owner@example.test"),
                                organizerName = ActiveSyncField.Value("Owner"),
                                attendees = ActiveSyncField.Value(attendees(count)),
                            ),
                            isAddition = true,
                        ),
                    ),
                    FixedTimeZoneResolver("UTC"),
                )
            }

        assertEquals(100, batches.getValue(100).nonOrganizerAttendeeInserts().size)
        assertEquals(0, batches.getValue(101).nonOrganizerAttendeeInserts().size)
        assertEquals(1, batches.getValue(101).organizerAttendeeInserts().size)
    }

    @Test
    fun `oversized attendee list without organizer emits no attendee representation`() {
        val batch =
            CalendarProviderBatchPlanner.plan(
                plan(
                    ActiveSyncCalendarMutation.Upsert(
                        baseItem().copy(attendees = ActiveSyncField.Value(attendees(101))),
                        isAddition = true,
                    ),
                ),
                FixedTimeZoneResolver("UTC"),
            )

        val eventInsert = batch.operations.first() as CalendarProviderBatchOperation.EventInsert
        assertEquals(0, eventInsert.values[CalendarProviderField.HAS_ATTENDEE_DATA])
        assertTrue(batch.operations.none { it is CalendarProviderBatchOperation.AttendeeInsert })
    }

    @Test
    fun `attendee replacement removes a prior small list when it grows and restores a later bounded list`() {
        val organizerFields =
            baseItem().copy(
                organizerEmail = ActiveSyncField.Value("owner@example.test"),
                organizerName = ActiveSyncField.Value("Owner"),
            )
        val smallPrevious = organizerFields.copy(attendees = ActiveSyncField.Value(attendees(2)))
        val growBatch = replacementBatch(smallPrevious, attendees(101))
        val oversizedPrevious = organizerFields.copy(attendees = ActiveSyncField.Value(attendees(101)))
        val shrinkBatch = replacementBatch(oversizedPrevious, attendees(100))

        assertTrue(growBatch.operations.contains(CalendarProviderBatchOperation.AttendeesDelete(OWNED_CALENDAR, EVENT_ID)))
        assertEquals(0, growBatch.nonOrganizerAttendeeInserts().size)
        assertEquals(1, (growBatch.operations.first() as CalendarProviderBatchOperation.EventUpdate)
            .values[CalendarProviderField.HAS_ATTENDEE_DATA])
        assertTrue(shrinkBatch.operations.contains(CalendarProviderBatchOperation.AttendeesDelete(OWNED_CALENDAR, EVENT_ID)))
        assertEquals(100, shrinkBatch.nonOrganizerAttendeeInserts().size)
    }

    @Test
    fun `recurrence exceptions apply the attendee limit independently including inherited attendees`() {
        val firstInstance = Instant.parse("2026-08-16T09:00:00Z")
        val inheritedInstance = Instant.parse("2026-08-17T09:00:00Z")
        val item =
            baseItem().copy(
                organizerEmail = ActiveSyncField.Value("owner@example.test"),
                attendees = ActiveSyncField.Value(attendees(101)),
                recurrence = ActiveSyncField.Value(dailyRecurrence()),
                exceptions =
                    ActiveSyncField.Value(
                        listOf(
                            ActiveSyncCalendarException(
                                instanceStart = firstInstance,
                                deleted = false,
                                attendees = ActiveSyncField.Value(attendees(100)),
                            ),
                            ActiveSyncCalendarException(
                                instanceStart = inheritedInstance,
                                deleted = false,
                            ),
                        ),
                    ),
            )

        val batch =
            CalendarProviderBatchPlanner.plan(
                plan(ActiveSyncCalendarMutation.Upsert(item, isAddition = true)),
                FixedTimeZoneResolver("UTC"),
            )
        val exceptions = batch.operations.withIndex()
            .filter { (_, operation) -> operation is CalendarProviderBatchOperation.ExceptionInsert }

        assertEquals(2, exceptions.size)
        assertEquals(100, batch.attendeeInsertsFor(EventReference.Inserted(exceptions[0].index)).size)
        assertEquals(0, batch.attendeeInsertsFor(EventReference.Inserted(exceptions[1].index)).size)
        assertEquals(0, batch.nonOrganizerAttendeeInserts().count { it.event == EventReference.Inserted(0) })
    }

    @Test
    fun `timed exception of all-day series keeps original marker and timed row semantics`() {
        val originalInstance = Instant.parse("2026-08-11T00:00:00Z")
        val item =
            baseItem().copy(
                start = ActiveSyncField.Value(Instant.parse("2026-08-09T00:00:00Z")),
                end = ActiveSyncField.Value(Instant.parse("2026-08-10T00:00:00Z")),
                allDay = ActiveSyncField.Value(true),
                recurrence = ActiveSyncField.Value(dailyRecurrence()),
                exceptions =
                    ActiveSyncField.Value(
                        listOf(
                            ActiveSyncCalendarException(
                                instanceStart = originalInstance,
                                deleted = false,
                                start = ActiveSyncField.Value(Instant.parse("2026-08-11T09:00:00Z")),
                                end = ActiveSyncField.Value(Instant.parse("2026-08-11T10:00:00Z")),
                                allDay = ActiveSyncField.Value(false),
                            ),
                        ),
                    ),
            )
        val pagePlan = plan(ActiveSyncCalendarMutation.Upsert(item, isAddition = true))

        val batch = CalendarProviderBatchPlanner.plan(pagePlan, FixedTimeZoneResolver("Europe/Moscow"))

        val values = batch.operations.filterIsInstance<CalendarProviderBatchOperation.ExceptionInsert>().single().values
        assertEquals(1, values[CalendarProviderField.ORIGINAL_ALL_DAY])
        assertEquals(0, values[CalendarProviderField.ALL_DAY])
        assertEquals("Europe/Moscow", values[CalendarProviderField.EVENT_TIME_ZONE])
        assertEquals("Europe/Moscow", values[CalendarProviderField.EVENT_END_TIME_ZONE])
    }

    @Test
    fun `partial accepted response updates semantic fields and clears color without identity or child churn`() {
        val previous =
            (net.mixalich7b.exchangesync.core.calendar.CalendarEventMapper.map(
                ActiveSyncCalendarMutation.Upsert(
                    baseItem().copy(
                        organizerEmail = ActiveSyncField.Value("owner@example.test"),
                        organizerName = ActiveSyncField.Value("Owner"),
                    ),
                    true,
                ),
                resolution().color,
            ) as net.mixalich7b.exchangesync.core.calendar.ProviderCalendarMutation.Upsert).event
        val mutation =
            ActiveSyncCalendarMutation.Upsert(
                ActiveSyncCalendarItem(
                    serverId = "server-1",
                    meetingStatus = ActiveSyncField.Value(ActiveSyncMeetingStatus(3, true, true, false)),
                    responseType = ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED),
                    availability = ActiveSyncField.Value(ActiveSyncAvailability.BUSY),
                ),
                isAddition = false,
            )
        val pagePlan =
            CalendarPagePlanner.plan(
                page(mutation),
                resolution(),
                listOf(ExistingProviderEvent(EVENT_ID, OWNED_CALENDAR, "server-1", previous)),
            )

        val batch = CalendarProviderBatchPlanner.plan(pagePlan, FixedTimeZoneResolver("UTC"))

        val update = batch.operations.single() as CalendarProviderBatchOperation.EventUpdate
        assertEquals(EVENT_ID, update.eventId)
        assertEquals(OWNED_CALENDAR, update.calendarId)
        assertEquals(ProviderInteger.CONFIRMED_EVENT, update.values[CalendarProviderField.STATUS])
        assertEquals(ProviderInteger.ACCEPTED_ATTENDEE, update.values[CalendarProviderField.SELF_ATTENDEE_STATUS])
        assertTrue(update.values.containsKey(CalendarProviderField.EVENT_COLOR))
        assertNull(update.values[CalendarProviderField.EVENT_COLOR])
        assertFalse(update.values.containsKey(CalendarProviderField.SYNC_ID))
        assertTrue(batch.operations.none { it is CalendarProviderBatchOperation.OrganizerDelete })
        assertTrue(
            batch.operations.filterIsInstance<CalendarProviderBatchOperation.AttendeeInsert>().none {
                it.values[CalendarProviderField.ATTENDEE_RELATIONSHIP] == ProviderInteger.ORGANIZER_RELATIONSHIP
            },
        )
    }

    @Test
    fun `series response change updates inherited exception presentation without recreating exception rows`() {
        val changedInstance = Instant.parse("2026-08-16T09:00:00Z")
        val deletedInstance = Instant.parse("2026-08-17T09:00:00Z")
        val explicitInstance = Instant.parse("2026-08-18T09:00:00Z")
        val previousItem =
            baseItem().copy(
                meetingStatus = ActiveSyncField.Value(ActiveSyncMeetingStatus(3, true, true, false)),
                responseType = ActiveSyncField.Value(ActiveSyncResponseType.NOT_RESPONDED),
                availability = ActiveSyncField.Value(ActiveSyncAvailability.BUSY),
                recurrence = ActiveSyncField.Value(dailyRecurrence()),
                exceptions =
                    ActiveSyncField.Value(
                        listOf(
                            ActiveSyncCalendarException(
                                instanceStart = changedInstance,
                                deleted = false,
                                subject = ActiveSyncField.Value("Changed title"),
                                start = ActiveSyncField.Value(changedInstance.plusSeconds(3_600)),
                                end = ActiveSyncField.Value(changedInstance.plusSeconds(7_200)),
                                reminderMinutes = ActiveSyncField.Value(15),
                                attendees =
                                    ActiveSyncField.Value(
                                        listOf(
                                            ActiveSyncAttendee(
                                                "exception@example.test",
                                                "Exception attendee",
                                                ActiveSyncAttendeeStatus.ACCEPTED,
                                                ActiveSyncAttendeeType.REQUIRED,
                                            ),
                                        ),
                                    ),
                            ),
                            ActiveSyncCalendarException(instanceStart = deletedInstance, deleted = true),
                            ActiveSyncCalendarException(
                                instanceStart = explicitInstance,
                                deleted = false,
                                responseType = ActiveSyncField.Value(ActiveSyncResponseType.TENTATIVE),
                            ),
                        ),
                    ),
            )
        val previous =
            (net.mixalich7b.exchangesync.core.calendar.CalendarEventMapper.map(
                ActiveSyncCalendarMutation.Upsert(previousItem, true),
                resolution().color,
            ) as net.mixalich7b.exchangesync.core.calendar.ProviderCalendarMutation.Upsert).event
        val responseOnly =
            ActiveSyncCalendarMutation.Upsert(
                ActiveSyncCalendarItem(
                    serverId = "server-1",
                    responseType = ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED),
                ),
                isAddition = false,
            )
        val pagePlan =
            CalendarPagePlanner.plan(
                page(responseOnly),
                resolution(),
                listOf(ExistingProviderEvent(EVENT_ID, OWNED_CALENDAR, "server-1", previous)),
            )

        val batch = CalendarProviderBatchPlanner.plan(pagePlan, FixedTimeZoneResolver("UTC"))

        assertTrue(batch.operations.none { it is CalendarProviderBatchOperation.ExceptionsDelete })
        assertTrue(batch.operations.none { it is CalendarProviderBatchOperation.ExceptionInsert })
        assertTrue(batch.operations.none { it is CalendarProviderBatchOperation.AttendeesDelete })
        assertTrue(batch.operations.none { it is CalendarProviderBatchOperation.RemindersDelete })
        val updates = batch.operations.filterIsInstance<CalendarProviderBatchOperation.ExceptionResponseUpdate>()
        assertEquals(listOf(changedInstance, deletedInstance), updates.map { it.originalInstance })
        assertEquals(ProviderInteger.CONFIRMED_EVENT, updates[0].values[CalendarProviderField.STATUS])
        assertEquals(ProviderInteger.CANCELLED_EVENT, updates[1].values[CalendarProviderField.STATUS])
        assertFalse(updates[0].values.containsKey(CalendarProviderField.TITLE))
        assertFalse(updates[0].values.containsKey(CalendarProviderField.START))
    }

    @Test
    fun `pending event stores server availability separately from tentative presentation`() {
        val mutation =
            ActiveSyncCalendarMutation.Upsert(
                baseItem().copy(
                    meetingStatus = ActiveSyncField.Value(ActiveSyncMeetingStatus(3, true, true, false)),
                    responseType = ActiveSyncField.Value(ActiveSyncResponseType.NOT_RESPONDED),
                    availability = ActiveSyncField.Value(ActiveSyncAvailability.OUT_OF_OFFICE),
                ),
                isAddition = true,
            )

        val batch = CalendarProviderBatchPlanner.plan(plan(mutation), FixedTimeZoneResolver("UTC"))
        val insert = batch.operations.first() as CalendarProviderBatchOperation.EventInsert

        assertEquals(ProviderInteger.TENTATIVE_AVAILABILITY, insert.values[CalendarProviderField.AVAILABILITY])
        assertEquals(ActiveSyncAvailability.OUT_OF_OFFICE.wireValue, insert.values["sync_data5"])
    }

    @Test
    fun `replacement and deletion operations stay fenced to the owned calendar and exact event`() {
        val replacement =
            ActiveSyncCalendarMutation.Upsert(
                baseItem().copy(attendees = ActiveSyncField.Empty, reminderMinutes = ActiveSyncField.Empty),
                isAddition = false,
            )
        val pagePlan =
            CalendarPagePlanner.plan(
                page(replacement, ActiveSyncCalendarMutation.Delete("server-2", soft = true)),
                resolution(),
                listOf(ExistingProviderEvent(EVENT_ID, OWNED_CALENDAR, "server-1")),
            )

        val batch = CalendarProviderBatchPlanner.plan(pagePlan, FixedTimeZoneResolver("UTC"))

        assertTrue(batch.operations.all { it.calendarId == OWNED_CALENDAR })
        assertTrue(batch.operations.contains(CalendarProviderBatchOperation.AttendeesDelete(OWNED_CALENDAR, EVENT_ID)))
        assertTrue(batch.operations.contains(CalendarProviderBatchOperation.RemindersDelete(OWNED_CALENDAR, EventReference.Existing(EVENT_ID))))
        assertTrue(batch.operations.contains(CalendarProviderBatchOperation.EventDelete(OWNED_CALENDAR, "server-2")))
        assertThrows(CalendarPlanningException::class.java) {
            CalendarProviderBatchPlan.create(
                OWNED_CALENDAR,
                listOf(CalendarProviderBatchOperation.EventDelete(OTHER_CALENDAR, "escape")),
            )
        }
    }

    @Test
    fun `recurring to single Change clears duration and retains a representable end`() {
        val previousItem = baseItem().copy(recurrence = ActiveSyncField.Value(dailyRecurrence()))
        val previous =
            (net.mixalich7b.exchangesync.core.calendar.CalendarEventMapper.map(
                ActiveSyncCalendarMutation.Upsert(previousItem, true),
                resolution().color,
            ) as net.mixalich7b.exchangesync.core.calendar.ProviderCalendarMutation.Upsert).event
        val change =
            ActiveSyncCalendarMutation.Upsert(
                ActiveSyncCalendarItem(serverId = "server-1", recurrence = ActiveSyncField.Empty),
                false,
            )
        val pagePlan =
            CalendarPagePlanner.plan(
                page(change),
                resolution(),
                listOf(ExistingProviderEvent(EVENT_ID, OWNED_CALENDAR, "server-1", previous)),
            )

        val batch = CalendarProviderBatchPlanner.plan(pagePlan, FixedTimeZoneResolver("UTC"))
        val update = batch.operations.single() as CalendarProviderBatchOperation.EventUpdate

        assertNull(update.values[CalendarProviderField.RECURRENCE_RULE])
        assertNull(update.values[CalendarProviderField.DURATION])
        assertEquals(Instant.parse("2026-08-09T10:00:00Z").toEpochMilli(), update.values[CalendarProviderField.END])
    }

    @Test
    fun `unrepresentable recurring Windows time zone rejects the whole page`() {
        val item =
            baseItem().copy(
                uid = ActiveSyncField.Value("safe-uid"),
                location = ActiveSyncField.Value("Room 17"),
                reminderMinutes = ActiveSyncField.Value(12),
                recurrence = ActiveSyncField.Value(dailyRecurrence()),
            )
        val pagePlan = plan(ActiveSyncCalendarMutation.Upsert(item, isAddition = true))

        val failure =
            assertThrows(CalendarPlanningException::class.java) {
                CalendarProviderBatchPlanner.plan(pagePlan, FixedTimeZoneResolver(null))
            }
        val snapshot = checkNotNull(failure.calendarFailureSnapshot)

        assertEquals(CalendarPlanningRule.RECURRING_TIME_ZONE_UNREPRESENTABLE, failure.planningRule)
        assertEquals(DiagnosticCalendarRule.RECURRING_TIME_ZONE_UNREPRESENTABLE, snapshot.rule)
        assertEquals(
            DiagnosticFieldValue.IntegerValue(-180),
            snapshot.field(DiagnosticCalendarFieldSource.EFFECTIVE, DiagnosticCalendarField.TIME_ZONE_BIAS_MINUTES).value,
        )
        assertEquals(
            DiagnosticFieldValue.Text("FREQ=DAILY;INTERVAL=1"),
            snapshot.field(DiagnosticCalendarFieldSource.EFFECTIVE, DiagnosticCalendarField.RECURRENCE_RULE).value,
        )
        assertEquals(
            DiagnosticFieldValue.IntegerValue(3_600_000),
            snapshot.field(DiagnosticCalendarFieldSource.DERIVED, DiagnosticCalendarField.DURATION_MILLIS).value,
        )
        assertEquals(
            DiagnosticFieldState.EMPTY,
            snapshot.field(DiagnosticCalendarFieldSource.DERIVED, DiagnosticCalendarField.PROVIDER_TIME_ZONE).state,
        )
    }

    @Test
    fun `unrepresentable exception retains its path and selected provider exception metadata`() {
        val originalInstance = Instant.parse("2026-08-16T00:00:00Z")
        val exceptionStart = Instant.parse("2026-08-16T09:00:00Z")
        val item =
            baseItem().copy(
                start = ActiveSyncField.Value(Instant.parse("2026-08-09T00:00:00Z")),
                end = ActiveSyncField.Value(Instant.parse("2026-08-10T00:00:00Z")),
                allDay = ActiveSyncField.Value(true),
                recurrence = ActiveSyncField.Value(dailyRecurrence()),
                exceptions =
                    ActiveSyncField.Value(
                        listOf(
                            ActiveSyncCalendarException(
                                instanceStart = originalInstance,
                                deleted = false,
                                location = ActiveSyncField.Value("Exception room"),
                                start = ActiveSyncField.Value(exceptionStart),
                                end = ActiveSyncField.Value(exceptionStart.plusSeconds(3_600)),
                                allDay = ActiveSyncField.Value(false),
                                reminderMinutes = ActiveSyncField.Value(8),
                                attendees = ActiveSyncField.Value(attendees(1)),
                            ),
                        ),
                    ),
            )
        val pagePlan = plan(ActiveSyncCalendarMutation.Upsert(item, isAddition = true))

        val failure =
            assertThrows(CalendarPlanningException::class.java) {
                CalendarProviderBatchPlanner.plan(pagePlan, FixedTimeZoneResolver(null))
            }
        val snapshot = checkNotNull(failure.calendarFailureSnapshot)

        assertEquals(DiagnosticCalendarPath.Exception(0), snapshot.path)
        assertEquals(
            DiagnosticFieldValue.Timestamp(originalInstance),
            snapshot.field(DiagnosticCalendarFieldSource.EXCEPTION, DiagnosticCalendarField.EXCEPTION_INSTANCE).value,
        )
        assertEquals(
            DiagnosticFieldValue.Text("Exception_room"),
            snapshot.field(DiagnosticCalendarFieldSource.EXCEPTION, DiagnosticCalendarField.LOCATION).value,
        )
        assertEquals(
            DiagnosticFieldValue.IntegerValue(8),
            snapshot.field(DiagnosticCalendarFieldSource.EXCEPTION, DiagnosticCalendarField.REMINDER_MINUTES).value,
        )
        assertEquals(
            DiagnosticFieldValue.IntegerValue(3_600_000),
            snapshot.field(DiagnosticCalendarFieldSource.DERIVED, DiagnosticCalendarField.DURATION_MILLIS).value,
        )
        assertEquals(
            DiagnosticFieldValue.BooleanValue(true),
            snapshot.field(DiagnosticCalendarFieldSource.DERIVED, DiagnosticCalendarField.HAS_ALARM).value,
        )
        assertEquals(
            DiagnosticFieldValue.BooleanValue(true),
            snapshot.field(DiagnosticCalendarFieldSource.DERIVED, DiagnosticCalendarField.HAS_ATTENDEE_DATA).value,
        )
    }

    @Test
    fun `overflowing Windows time zone biases reject the whole page`() {
        val malformedTimeZone =
            testTimeZone().copy(
                biasMinutes = Int.MAX_VALUE,
                standardName = "",
                standardBiasMinutes = Int.MAX_VALUE,
                daylightName = "",
            )
        val item =
            baseItem().copy(
                recurrence = ActiveSyncField.Value(dailyRecurrence()),
                timeZone = ActiveSyncField.Value(malformedTimeZone),
            )
        val pagePlan = plan(ActiveSyncCalendarMutation.Upsert(item, isAddition = true))

        assertThrows(CalendarPlanningException::class.java) {
            CalendarProviderBatchPlanner.plan(pagePlan, AndroidCalendarProviderTimeZoneResolver)
        }
    }

    private fun plan(mutation: ActiveSyncCalendarMutation): CalendarPagePlan =
        CalendarPagePlanner.plan(page(mutation), resolution(), emptyList())

    private fun replacementBatch(
        previousItem: ActiveSyncCalendarItem,
        replacementAttendees: List<ActiveSyncAttendee>,
    ): CalendarProviderBatchPlan {
        val previous =
            (net.mixalich7b.exchangesync.core.calendar.CalendarEventMapper.map(
                ActiveSyncCalendarMutation.Upsert(previousItem, isAddition = true),
                resolution().color,
            ) as net.mixalich7b.exchangesync.core.calendar.ProviderCalendarMutation.Upsert).event
        val change =
            ActiveSyncCalendarMutation.Upsert(
                ActiveSyncCalendarItem(
                    serverId = previousItem.serverId,
                    attendees = ActiveSyncField.Value(replacementAttendees),
                ),
                isAddition = false,
            )
        val pagePlan =
            CalendarPagePlanner.plan(
                page(change),
                resolution(),
                listOf(ExistingProviderEvent(EVENT_ID, OWNED_CALENDAR, previousItem.serverId, previous)),
            )
        return CalendarProviderBatchPlanner.plan(pagePlan, FixedTimeZoneResolver("UTC"))
    }

    private fun attendees(count: Int): List<ActiveSyncAttendee> =
        List(count) { index ->
            ActiveSyncAttendee(
                email = "guest-$index@example.test",
                name = "Guest $index",
                status = ActiveSyncAttendeeStatus.ACCEPTED,
                type = ActiveSyncAttendeeType.REQUIRED,
            )
        }

    private fun CalendarProviderBatchPlan.nonOrganizerAttendeeInserts():
        List<CalendarProviderBatchOperation.AttendeeInsert> =
        operations.filterIsInstance<CalendarProviderBatchOperation.AttendeeInsert>()
            .filter { operation ->
                operation.values[CalendarProviderField.ATTENDEE_RELATIONSHIP] ==
                    ProviderInteger.ATTENDEE_RELATIONSHIP
            }

    private fun CalendarProviderBatchPlan.organizerAttendeeInserts():
        List<CalendarProviderBatchOperation.AttendeeInsert> =
        operations.filterIsInstance<CalendarProviderBatchOperation.AttendeeInsert>()
            .filter { operation ->
                operation.values[CalendarProviderField.ATTENDEE_RELATIONSHIP] ==
                    ProviderInteger.ORGANIZER_RELATIONSHIP
            }

    private fun CalendarProviderBatchPlan.attendeeInsertsFor(
        reference: EventReference,
    ): List<CalendarProviderBatchOperation.AttendeeInsert> =
        nonOrganizerAttendeeInserts().filter { operation -> operation.event == reference }

    private fun net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarFailureSnapshot.field(
        source: DiagnosticCalendarFieldSource,
        field: DiagnosticCalendarField,
    ) = fields.single { entry -> entry.source == source && entry.field == field }

    private fun page(vararg mutations: ActiveSyncCalendarMutation): RemoteCalendarPage =
        RemoteCalendarPage(mutations.toList(), SyncCheckpoints.EMPTY, moreAvailable = false)

    private fun baseItem(): ActiveSyncCalendarItem =
        ActiveSyncCalendarItem(
            serverId = "server-1",
            start = ActiveSyncField.Value(Instant.parse("2026-08-09T09:00:00Z")),
            end = ActiveSyncField.Value(Instant.parse("2026-08-09T10:00:00Z")),
            allDay = ActiveSyncField.Value(false),
            timeZone = ActiveSyncField.Value(testTimeZone()),
        )

    private fun resolution(): OwnedCalendarResolution = OwnedCalendarResolution(OWNED_CALENDAR, -13_408_615)

    private fun dailyRecurrence() =
        net.mixalich7b.exchangesync.core.calendar.ActiveSyncRecurrence(
            type = net.mixalich7b.exchangesync.core.calendar.ActiveSyncRecurrenceType.DAILY,
            interval = 1,
            end = net.mixalich7b.exchangesync.core.calendar.ActiveSyncRecurrenceEnd.Infinite,
        )

    private fun testTimeZone() =
        net.mixalich7b.exchangesync.core.calendar.ActiveSyncTimeZone(
            biasMinutes = -180,
            standardName = "Russian Standard Time",
            standardTransition = systemTime(),
            standardBiasMinutes = 0,
            daylightName = "Russian Daylight Time",
            daylightTransition = systemTime(),
            daylightBiasMinutes = 0,
        )

    private fun systemTime() =
        net.mixalich7b.exchangesync.core.calendar.ActiveSyncSystemTime(0, 0, 0, 0, 0, 0, 0, 0)

    private class FixedTimeZoneResolver(private val id: String?) : CalendarProviderTimeZoneResolver {
        override fun resolve(timeZone: net.mixalich7b.exchangesync.core.calendar.ActiveSyncTimeZone): String? = id
    }

    private companion object {
        val REQUIRED_EVENT_PROVIDER_FIELDS =
            setOf(
                CalendarProviderField.ALL_DAY,
                CalendarProviderField.ACCESS_LEVEL,
                CalendarProviderField.AVAILABILITY,
                CalendarProviderField.SELF_ATTENDEE_STATUS,
                CalendarProviderField.HAS_ALARM,
                CalendarProviderField.HAS_ATTENDEE_DATA,
            )
        const val OWNED_CALENDAR = 12L
        const val OTHER_CALENDAR = 88L
        const val EVENT_ID = 31L
    }
}
