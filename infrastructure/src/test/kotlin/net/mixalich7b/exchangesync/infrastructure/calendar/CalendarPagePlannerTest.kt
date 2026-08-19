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
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncRecurrence
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncRecurrenceEnd
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncRecurrenceType
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncResponseType
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncSensitivity
import net.mixalich7b.exchangesync.core.calendar.CalendarMappingRule
import net.mixalich7b.exchangesync.core.calendar.ProviderAvailability
import net.mixalich7b.exchangesync.core.calendar.ProviderAttendee
import net.mixalich7b.exchangesync.core.calendar.ProviderCalendarException
import net.mixalich7b.exchangesync.core.calendar.ProviderEventStatus
import net.mixalich7b.exchangesync.core.calendar.ProviderSelfStatus
import net.mixalich7b.exchangesync.core.calendar.CalendarEventMapper
import net.mixalich7b.exchangesync.core.calendar.ProviderCalendarMutation
import net.mixalich7b.exchangesync.core.sync.RemoteCalendarPage
import net.mixalich7b.exchangesync.core.sync.SyncCheckpoints
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarCommandKind
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarField
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarFieldEntry
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarFieldSource
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarPath
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarRule
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticFieldState
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticFieldValue
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticRelationship
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalendarPagePlannerTest {
    @Test
    fun `ServerId upsert reuses one owned row and never targets same identity in another calendar`() {
        val existing =
            listOf(
                ExistingProviderEvent(31, OWNED_CALENDAR, "event-1"),
                ExistingProviderEvent(99, OTHER_CALENDAR, "event-1"),
            )

        val plan = CalendarPagePlanner.plan(page(addition("event-1")), resolution(), existing)
        val upsert = plan.operations.single() as CalendarEventPlan.Upsert

        assertEquals(31, upsert.eventId)
        assertEquals(OWNED_CALENDAR, upsert.calendarId)
        assertEquals("event-1", upsert.event.syncId)
    }

    @Test
    fun `pending accepted and pending updates atomically carry semantic fields and color clearing`() {
        val existing = listOf(ExistingProviderEvent(31, OWNED_CALENDAR, "event-1"))
        val responses = listOf(ActiveSyncResponseType.NONE, ActiveSyncResponseType.ACCEPTED, ActiveSyncResponseType.TENTATIVE)

        val plans =
            responses.map { response ->
                val mutation =
                    ActiveSyncCalendarMutation.Upsert(
                        item = meetingItem("event-1", response),
                        isAddition = false,
                    )
                (CalendarPagePlanner.plan(page(mutation), resolution(), existing).operations.single()
                    as CalendarEventPlan.Upsert).event
            }

        assertEquals(ActiveSyncField.Value(ProviderEventStatus.TENTATIVE), plans[0].status)
        assertEquals(ActiveSyncField.Value(ProviderSelfStatus.INVITED), plans[0].selfStatus)
        assertEquals(ActiveSyncField.Value(ProviderAvailability.TENTATIVE), plans[0].availability)
        assertTrue(plans[0].eventColor is ActiveSyncField.Value)
        assertEquals(ActiveSyncField.Value(ProviderEventStatus.CONFIRMED), plans[1].status)
        assertEquals(ActiveSyncField.Value(ProviderSelfStatus.ACCEPTED), plans[1].selfStatus)
        assertEquals(ActiveSyncField.Empty, plans[1].eventColor)
        assertEquals(ActiveSyncField.Value(ProviderEventStatus.TENTATIVE), plans[2].status)
        assertTrue(plans[2].eventColor is ActiveSyncField.Value)
    }

    @Test
    fun `response-only Change restores server availability from the owned provider snapshot`() {
        val previous = providerEvent(meetingItem("event-1", ActiveSyncResponseType.NOT_RESPONDED))
        val partial =
            ActiveSyncCalendarItem(
                serverId = "event-1",
                responseType = ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED),
            )

        val upsert =
            CalendarPagePlanner.plan(
                page(ActiveSyncCalendarMutation.Upsert(partial, false)),
                resolution(),
                listOf(ExistingProviderEvent(31, OWNED_CALENDAR, "event-1", previous)),
            ).operations.single() as CalendarEventPlan.Upsert

        assertEquals(ActiveSyncField.Value(ProviderEventStatus.CONFIRMED), upsert.event.status)
        assertEquals(ActiveSyncField.Value(ProviderSelfStatus.ACCEPTED), upsert.event.selfStatus)
        assertEquals(ActiveSyncField.Value(ProviderAvailability.BUSY), upsert.event.availability)
        assertEquals(ActiveSyncField.Empty, upsert.event.eventColor)
        assertEquals(previous.start, upsert.event.start)
    }

    @Test
    fun `partial Change failure separates response prior and effective equal timestamps`() {
        val previous = providerEvent(baseItem("event-1"))
        val responseStart = Instant.parse("2026-08-09T10:00:00Z")
        val partial =
            ActiveSyncCalendarItem(
                serverId = "event-1",
                start = ActiveSyncField.Value(responseStart),
            )

        val failure =
            assertThrows(CalendarPlanningException::class.java) {
                CalendarPagePlanner.plan(
                    page(ActiveSyncCalendarMutation.Upsert(partial, false)),
                    resolution(),
                    listOf(ExistingProviderEvent(31, OWNED_CALENDAR, "event-1", previous)),
                )
            }
        val snapshot = checkNotNull(failure.calendarFailureSnapshot)

        assertEquals(CalendarMappingRule.EVENT_TIME_RANGE_INVALID, failure.mappingRule)
        assertEquals(DiagnosticCalendarCommandKind.CHANGE, snapshot.commandKind)
        assertEquals("event-1", snapshot.serverId)
        assertEquals(DiagnosticCalendarRule.EVENT_TIME_RANGE_INVALID, snapshot.rule)
        assertEquals(
            DiagnosticFieldValue.Timestamp(responseStart),
            snapshot.field(DiagnosticCalendarFieldSource.RESPONSE, DiagnosticCalendarField.START).value,
        )
        assertEquals(
            DiagnosticFieldValue.Timestamp(Instant.parse("2026-08-09T09:00:00Z")),
            snapshot.field(DiagnosticCalendarFieldSource.PRIOR, DiagnosticCalendarField.START).value,
        )
        assertEquals(
            DiagnosticFieldValue.Timestamp(Instant.parse("2026-08-09T10:00:00Z")),
            snapshot.field(DiagnosticCalendarFieldSource.PRIOR, DiagnosticCalendarField.END).value,
        )
        assertEquals(
            DiagnosticFieldValue.Timestamp(responseStart),
            snapshot.field(DiagnosticCalendarFieldSource.EFFECTIVE, DiagnosticCalendarField.START).value,
        )
        assertEquals(
            DiagnosticFieldValue.Timestamp(Instant.parse("2026-08-09T10:00:00Z")),
            snapshot.field(DiagnosticCalendarFieldSource.EFFECTIVE, DiagnosticCalendarField.END).value,
        )
        assertEquals(
            DiagnosticFieldValue.Relationship(DiagnosticRelationship.EQUAL),
            snapshot.field(DiagnosticCalendarFieldSource.DERIVED, DiagnosticCalendarField.TIME_RELATIONSHIP).value,
        )
    }

    @Test
    fun `mapping representation rules retain recurrence all-day and meeting-response inputs`() {
        val cases =
            listOf(
                Triple(
                    baseItem("recurrence").copy(
                        recurrence =
                            ActiveSyncField.Value(
                                ActiveSyncRecurrence(
                                    ActiveSyncRecurrenceType.DAILY,
                                    0,
                                    end = ActiveSyncRecurrenceEnd.Infinite,
                                ),
                            ),
                    ),
                    DiagnosticCalendarRule.RECURRENCE_INTERVAL_INVALID,
                    DiagnosticCalendarField.RECURRENCE_INTERVAL to DiagnosticFieldValue.IntegerValue(0),
                ),
                Triple(
                    baseItem("all-day").copy(allDay = ActiveSyncField.Value(true)),
                    DiagnosticCalendarRule.EVENT_ALL_DAY_NOT_UTC_ALIGNED,
                    DiagnosticCalendarField.ALL_DAY to DiagnosticFieldValue.BooleanValue(true),
                ),
                Triple(
                    baseItem("meeting").copy(responseType = ActiveSyncField.Empty),
                    DiagnosticCalendarRule.MEETING_RESPONSE_EMPTY,
                    DiagnosticCalendarField.RESPONSE_TYPE to null,
                ),
            )

        cases.forEach { (item, expectedRule, expectedField) ->
            val failure =
                assertThrows(CalendarPlanningException::class.java) {
                    CalendarPagePlanner.plan(
                        page(ActiveSyncCalendarMutation.Upsert(item, true)),
                        resolution(),
                        emptyList(),
                    )
                }
            val snapshot = checkNotNull(failure.calendarFailureSnapshot)
            val field = snapshot.field(DiagnosticCalendarFieldSource.RESPONSE, expectedField.first)

            assertEquals(expectedRule, snapshot.rule, expectedRule.name)
            assertEquals(expectedField.second, field.value, expectedRule.name)
            assertEquals(
                if (expectedField.second == null) DiagnosticFieldState.EMPTY else DiagnosticFieldState.PRESENT,
                field.state,
                expectedRule.name,
            )
        }
    }

    @Test
    fun `mapping failure snapshot includes permitted values and only structural narrative and people data`() {
        val item =
            baseItem("safe-server-id").copy(
                uid = ActiveSyncField.Value("safe-uid"),
                subject = ActiveSyncField.Value("title-secret-marker"),
                body = ActiveSyncField.Value("body-secret-marker"),
                location = ActiveSyncField.Value("Room 17"),
                allDay = ActiveSyncField.Value(true),
                recurrence =
                    ActiveSyncField.Value(
                        ActiveSyncRecurrence(
                            ActiveSyncRecurrenceType.DAILY,
                            1,
                            end = ActiveSyncRecurrenceEnd.Count(4),
                        ),
                    ),
                organizerEmail = ActiveSyncField.Value("organizer-secret@example.test"),
                organizerName = ActiveSyncField.Value("organizer-name-secret-marker"),
                attendees =
                    ActiveSyncField.Value(
                        listOf(
                            ActiveSyncAttendee(
                                "attendee-secret@example.test",
                                "attendee-name-secret-marker",
                                ActiveSyncAttendeeStatus.ACCEPTED,
                                ActiveSyncAttendeeType.REQUIRED,
                            ),
                        ),
                    ),
                meetingStatus = ActiveSyncField.Value(ActiveSyncMeetingStatus(3, true, true, false)),
                responseType = ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED),
                responseRequested = ActiveSyncField.Value(true),
                availability = ActiveSyncField.Value(ActiveSyncAvailability.BUSY),
                sensitivity = ActiveSyncField.Value(ActiveSyncSensitivity.PRIVATE),
                reminderMinutes = ActiveSyncField.Value(15),
            )

        val failure =
            assertThrows(CalendarPlanningException::class.java) {
                CalendarPagePlanner.plan(
                    page(ActiveSyncCalendarMutation.Upsert(item, true)),
                    resolution(),
                    emptyList(),
                )
            }
        val snapshot = checkNotNull(failure.calendarFailureSnapshot)

        assertEquals(
            DiagnosticFieldValue.Text("safe-uid"),
            snapshot.field(DiagnosticCalendarFieldSource.RESPONSE, DiagnosticCalendarField.UID).value,
        )
        assertEquals(
            DiagnosticFieldValue.Text("Room_17"),
            snapshot.field(DiagnosticCalendarFieldSource.RESPONSE, DiagnosticCalendarField.LOCATION).value,
        )
        assertEquals(
            DiagnosticFieldValue.IntegerValue(15),
            snapshot.field(DiagnosticCalendarFieldSource.RESPONSE, DiagnosticCalendarField.REMINDER_MINUTES).value,
        )
        assertEquals(
            DiagnosticFieldValue.Count(1),
            snapshot.field(DiagnosticCalendarFieldSource.RESPONSE, DiagnosticCalendarField.ATTENDEE_COUNT).value,
        )
        listOf(
            DiagnosticCalendarField.SUBJECT,
            DiagnosticCalendarField.BODY,
            DiagnosticCalendarField.ORGANIZER_EMAIL,
            DiagnosticCalendarField.ORGANIZER_NAME,
            DiagnosticCalendarField.ATTENDEES,
        ).forEach { structural ->
            val field = snapshot.field(DiagnosticCalendarFieldSource.RESPONSE, structural)
            assertEquals(DiagnosticFieldState.PRESENT, field.state, structural.name)
            assertEquals(null, field.value, structural.name)
        }
        val retained = snapshot.toString()
        listOf(
            "title-secret-marker",
            "body-secret-marker",
            "organizer-secret@example.test",
            "organizer-name-secret-marker",
            "attendee-secret@example.test",
            "attendee-name-secret-marker",
        ).forEach { excluded -> assertFalse(retained.contains(excluded), excluded) }
    }

    @Test
    fun `nested mapping failure retains selected exception metadata path and bounded counts`() {
        val firstInstance = Instant.parse("2026-08-16T09:00:00Z")
        val failedInstance = Instant.parse("2026-08-23T09:00:00Z")
        val item =
            baseItem("series-1").copy(
                exceptions =
                    ActiveSyncField.Value(
                        listOf(
                            ActiveSyncCalendarException(firstInstance, deleted = true),
                            ActiveSyncCalendarException(
                                instanceStart = failedInstance,
                                deleted = false,
                                location = ActiveSyncField.Value("Exception room"),
                                end = ActiveSyncField.Value(failedInstance.minusSeconds(1)),
                                reminderMinutes = ActiveSyncField.Value(7),
                            ),
                        ),
                    ),
            )

        val failure =
            assertThrows(CalendarPlanningException::class.java) {
                CalendarPagePlanner.plan(
                    page(ActiveSyncCalendarMutation.Upsert(item, true)),
                    resolution(),
                    emptyList(),
                )
            }
        val snapshot = checkNotNull(failure.calendarFailureSnapshot)

        assertEquals(DiagnosticCalendarPath.Exception(1), snapshot.path)
        assertEquals(
            DiagnosticFieldValue.Count(2),
            snapshot.field(DiagnosticCalendarFieldSource.RESPONSE, DiagnosticCalendarField.EXCEPTION_COUNT).value,
        )
        assertEquals(
            DiagnosticFieldValue.Timestamp(failedInstance),
            snapshot.field(DiagnosticCalendarFieldSource.EXCEPTION, DiagnosticCalendarField.EXCEPTION_INSTANCE).value,
        )
        assertEquals(
            DiagnosticFieldValue.BooleanValue(false),
            snapshot.field(DiagnosticCalendarFieldSource.EXCEPTION, DiagnosticCalendarField.EXCEPTION_DELETED).value,
        )
        assertEquals(
            DiagnosticFieldValue.Text("Exception_room"),
            snapshot.field(DiagnosticCalendarFieldSource.EXCEPTION, DiagnosticCalendarField.LOCATION).value,
        )
        assertFalse(
            snapshot.fields.any { field -> field.value == DiagnosticFieldValue.Timestamp(firstInstance) },
        )
    }

    @Test
    fun `series response Change refreshes inherited exceptions while preserving explicit overrides`() {
        val inheritedInstance = Instant.parse("2026-08-16T09:00:00Z")
        val overriddenInstance = Instant.parse("2026-08-23T09:00:00Z")
        val previous =
            providerEvent(
                meetingItem("series-1", ActiveSyncResponseType.NOT_RESPONDED).copy(
                    exceptions =
                        ActiveSyncField.Value(
                            listOf(
                                ActiveSyncCalendarException(inheritedInstance, deleted = false),
                                ActiveSyncCalendarException(
                                    overriddenInstance,
                                    deleted = false,
                                    responseType = ActiveSyncField.Value(ActiveSyncResponseType.TENTATIVE),
                                ),
                            ),
                        ),
                ),
            )
        val partial =
            ActiveSyncCalendarItem(
                serverId = "series-1",
                responseType = ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED),
            )

        val upsert =
            CalendarPagePlanner.plan(
                page(ActiveSyncCalendarMutation.Upsert(partial, false)),
                resolution(),
                listOf(ExistingProviderEvent(31, OWNED_CALENDAR, "series-1", previous)),
            ).operations.single() as CalendarEventPlan.Upsert
        val exceptions =
            (upsert.event.exceptions as ActiveSyncField.Value<List<ProviderCalendarException>>).value

        assertFalse(upsert.replaceExceptions)
        assertTrue(upsert.refreshExceptionResponses)
        assertEquals(ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED), exceptions[0].responseType)
        assertEquals(ActiveSyncField.Value(ProviderEventStatus.CONFIRMED), exceptions[0].status)
        assertEquals(ActiveSyncField.Value(ProviderSelfStatus.ACCEPTED), exceptions[0].selfStatus)
        assertEquals(ActiveSyncField.Empty, exceptions[0].eventColor)
        assertEquals(ActiveSyncField.Value(ActiveSyncResponseType.TENTATIVE), exceptions[1].responseType)
        assertEquals(ActiveSyncField.Value(ProviderEventStatus.TENTATIVE), exceptions[1].status)
        assertTrue(exceptions[1].eventColor is ActiveSyncField.Value)
    }

    @Test
    fun `attendee reminder and exception presence controls complete child replacement`() {
        val item =
            meetingItem("series-1", ActiveSyncResponseType.NONE).copy(
                attendees =
                    ActiveSyncField.Value(
                        listOf(
                            ActiveSyncAttendee(
                                "calendar@example.test",
                                "Current User",
                                ActiveSyncAttendeeStatus.NONE,
                                ActiveSyncAttendeeType.REQUIRED,
                            ),
                        ),
                    ),
                reminderMinutes = ActiveSyncField.Empty,
                exceptions =
                    ActiveSyncField.Value(
                        listOf(
                            ActiveSyncCalendarException(
                                instanceStart = Instant.parse("2026-08-16T09:00:00Z"),
                                deleted = false,
                                responseType = ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED),
                            ),
                        ),
                    ),
            )

        val upsert =
            CalendarPagePlanner.plan(page(ActiveSyncCalendarMutation.Upsert(item, false)), resolution(), listOf(ExistingProviderEvent(4, OWNED_CALENDAR, "series-1")))
                .operations.single() as CalendarEventPlan.Upsert

        assertTrue(upsert.replaceAttendees)
        assertTrue(upsert.replaceReminders)
        assertTrue(upsert.replaceExceptions)
        val attendees = upsert.event.attendees as ActiveSyncField.Value<List<ProviderAttendee>>
        val exceptions = upsert.event.exceptions as ActiveSyncField.Value<List<ProviderCalendarException>>
        assertEquals(1, attendees.value.size)
        assertEquals(ActiveSyncField.Empty, upsert.event.reminderMinutes)
        assertEquals(ActiveSyncField.Value(ProviderEventStatus.CONFIRMED), exceptions.value.single().status)

        val omitted =
            CalendarPagePlanner.plan(
                page(ActiveSyncCalendarMutation.Upsert(baseItem("series-1"), false)),
                resolution(),
                listOf(ExistingProviderEvent(4, OWNED_CALENDAR, "series-1")),
            ).operations.single() as CalendarEventPlan.Upsert
        assertFalse(omitted.replaceAttendees)
        assertFalse(omitted.replaceReminders)
        assertFalse(omitted.replaceExceptions)
    }

    @Test
    fun `delete and soft delete are sync-adapter plans scoped by owned calendar and ServerId`() {
        val plan =
            CalendarPagePlanner.plan(
                page(
                    ActiveSyncCalendarMutation.Delete("event-1", soft = false),
                    ActiveSyncCalendarMutation.Delete("event-2", soft = true),
                ),
                resolution(),
                emptyList(),
            )

        assertEquals(
            listOf(
                CalendarEventPlan.Delete(OWNED_CALENDAR, "event-1"),
                CalendarEventPlan.Delete(OWNED_CALENDAR, "event-2"),
            ),
            plan.operations,
        )
    }

    @Test
    fun `page plan rejects duplicate owned identities and any foreign calendar operation`() {
        assertThrows(CalendarPlanningException::class.java) {
            CalendarPagePlanner.plan(
                page(addition("event-1")),
                resolution(),
                listOf(
                    ExistingProviderEvent(1, OWNED_CALENDAR, "event-1"),
                    ExistingProviderEvent(2, OWNED_CALENDAR, "event-1"),
                ),
            )
        }
        assertThrows(CalendarMirrorResetRequiredException::class.java) {
            CalendarPagePlanner.plan(
                page(ActiveSyncCalendarMutation.Upsert(baseItem("missing"), isAddition = false)),
                resolution(),
                emptyList(),
            )
        }
        assertThrows(CalendarPlanningException::class.java) {
            CalendarPagePlan.create(
                OWNED_CALENDAR,
                listOf(CalendarEventPlan.Delete(OTHER_CALENDAR, "event-1")),
            )
        }
    }

    @Test
    fun `dirty owned snapshot requests a full reset instead of merging local values`() {
        val dirty =
            ExistingProviderEvent(
                eventId = 31,
                calendarId = OWNED_CALENDAR,
                syncId = "event-1",
                snapshot = providerEvent(meetingItem("event-1", ActiveSyncResponseType.NONE)),
                isDirty = true,
            )
        val partial =
            ActiveSyncCalendarMutation.Upsert(
                ActiveSyncCalendarItem(
                    serverId = "event-1",
                    responseType = ActiveSyncField.Value(ActiveSyncResponseType.ACCEPTED),
                ),
                isAddition = false,
            )

        assertThrows(CalendarMirrorResetRequiredException::class.java) {
            CalendarPagePlanner.plan(page(partial), resolution(), listOf(dirty))
        }
    }

    private fun page(vararg mutations: ActiveSyncCalendarMutation) =
        RemoteCalendarPage(mutations.toList(), SyncCheckpoints.EMPTY, moreAvailable = false)

    private fun addition(serverId: String) = ActiveSyncCalendarMutation.Upsert(baseItem(serverId), isAddition = true)

    private fun providerEvent(item: ActiveSyncCalendarItem) =
        ((CalendarEventMapper.map(ActiveSyncCalendarMutation.Upsert(item, true), CALENDAR_COLOR)
            as ProviderCalendarMutation.Upsert).event)

    private fun meetingItem(serverId: String, response: ActiveSyncResponseType) =
        baseItem(serverId).copy(
            meetingStatus = ActiveSyncField.Value(ActiveSyncMeetingStatus(3, true, true, false)),
            responseType = ActiveSyncField.Value(response),
            availability = ActiveSyncField.Value(ActiveSyncAvailability.BUSY),
        )

    private fun baseItem(serverId: String) =
        ActiveSyncCalendarItem(
            serverId = serverId,
            start = ActiveSyncField.Value(Instant.parse("2026-08-09T09:00:00Z")),
            end = ActiveSyncField.Value(Instant.parse("2026-08-09T10:00:00Z")),
            allDay = ActiveSyncField.Value(false),
        )

    private fun resolution() = OwnedCalendarResolution(OWNED_CALENDAR, CALENDAR_COLOR)

    private fun net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarFailureSnapshot.field(
        source: DiagnosticCalendarFieldSource,
        field: DiagnosticCalendarField,
    ): DiagnosticCalendarFieldEntry = fields.single { entry -> entry.source == source && entry.field == field }

    private companion object {
        const val OWNED_CALENDAR = 12L
        const val OTHER_CALENDAR = 88L
        const val CALENDAR_COLOR = -13_408_615
    }
}
