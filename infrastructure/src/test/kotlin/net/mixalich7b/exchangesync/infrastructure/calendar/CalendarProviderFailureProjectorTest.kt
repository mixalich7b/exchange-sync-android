package net.mixalich7b.exchangesync.infrastructure.calendar

import java.time.Instant
import java.lang.reflect.Modifier
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticFieldState
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticFieldValue
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticProviderColumn
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticProviderColumnPolicy
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticProviderOperationKind
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticProviderReference
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticProviderTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalendarProviderFailureProjectorTest {
    @Test
    fun `projector covers every provider operation variant index target identity and current column`() {
        val snapshots = CalendarProviderFailureProjector.project(subBatch())

        assertEquals(12, snapshots.size)
        assertEquals((50..61).toList(), snapshots.map { snapshot -> snapshot.globalOperationIndex })
        assertEquals((0..11).toList(), snapshots.map { snapshot -> snapshot.subBatchOperationIndex })
        assertEquals(
            DiagnosticProviderOperationKind.entries.toSet(),
            snapshots.map { snapshot -> snapshot.operationKind }.toSet(),
        )
        assertEquals(DiagnosticProviderTarget.EVENT, snapshots[0].target)
        assertEquals(DiagnosticProviderReference.Existing(91), snapshots[1].reference)
        assertEquals(DiagnosticProviderReference.SyncId("delete-sync-id"), snapshots[2].reference)
        assertEquals(DiagnosticProviderTarget.ORGANIZER, snapshots[4].target)
        assertEquals(DiagnosticProviderTarget.ORGANIZER, snapshots[5].target)
        assertEquals(DiagnosticProviderTarget.ATTENDEE, snapshots[6].target)
        assertEquals(DiagnosticProviderReference.BackReference(0), snapshots[7].reference)
        assertEquals(DiagnosticProviderReference.Existing(97), snapshots[11].reference)
        assertEquals(
            DiagnosticProviderColumn.entries.toSet(),
            snapshots[0].columns.map { entry -> entry.column }.toSet(),
        )
    }

    @Test
    fun `projector retains sanitized allowed values and structure only for narrative and people columns`() {
        val snapshots = CalendarProviderFailureProjector.project(subBatch())
        val event = snapshots.first()
        val organizer = snapshots[5]
        val attendee = snapshots[6]

        assertEquals(
            DiagnosticFieldValue.Text("Room_17"),
            event.columns.single { entry -> entry.column == DiagnosticProviderColumn.LOCATION }.value,
        )
        assertEquals(
            DiagnosticFieldValue.IntegerValue(1_777_000_000_000L),
            event.columns.single { entry -> entry.column == DiagnosticProviderColumn.START }.value,
        )
        assertEquals(
            DiagnosticFieldState.EMPTY,
            event.columns.single { entry -> entry.column == DiagnosticProviderColumn.EVENT_COLOR }.state,
        )
        assertEquals(
            DiagnosticFieldValue.TypeName("UnknownValue"),
            event.columns.single { entry -> entry.column == DiagnosticProviderColumn.DURATION }.value,
        )
        listOf(
            DiagnosticProviderColumn.TITLE,
            DiagnosticProviderColumn.DESCRIPTION,
            DiagnosticProviderColumn.ORGANIZER_EMAIL,
            DiagnosticProviderColumn.ATTENDEE_EMAIL,
            DiagnosticProviderColumn.ATTENDEE_NAME,
        ).forEach { structural ->
            assertNull(event.columns.single { entry -> entry.column == structural }.value, structural.name)
        }
        assertTrue(organizer.columns.all { entry -> entry.value == null })
        assertTrue(attendee.columns.all { entry -> entry.value == null })
        val retained = snapshots.toString()
        listOf(
            "title-secret-marker",
            "body-secret-marker",
            "organizer-secret@example.test",
            "attendee-secret@example.test",
            "attendee-name-secret-marker",
            "unknown-to-string-secret-marker",
        ).forEach { excluded -> assertFalse(retained.contains(excluded), excluded) }
    }

    @Test
    fun `unknown provider column defaults to anonymous structural presence`() {
        val snapshot =
            CalendarProviderFailureProjector
                .project(
                    CalendarProviderSubBatch(
                        calendarId = 73,
                        ordinal = 1,
                        totalSubBatchCount = 1,
                        totalOperationCount = 1,
                        startOperationIndex = 0,
                        operations =
                            listOf(
                                CalendarProviderBatchOperation.EventInsert(
                                    73,
                                    mapOf("future-private-column" to "unknown-column-secret-marker"),
                                ),
                            ),
                    ),
                ).single()

        val column = snapshot.columns.single()
        assertEquals("<unknown>", column.column.wireName)
        assertEquals(DiagnosticProviderColumnPolicy.STRUCTURAL_ONLY, column.column.policy)
        assertEquals(DiagnosticFieldState.PRESENT, column.state)
        assertNull(column.value)
        assertFalse(snapshot.toString().contains("future-private-column"))
        assertFalse(snapshot.toString().contains("unknown-column-secret-marker"))
    }

    @Test
    fun `every provider field constant has an explicit diagnostic classification`() {
        val providerFields =
            CalendarProviderField::class.java.declaredFields
                .filter { field ->
                    field.type == String::class.java && Modifier.isStatic(field.modifiers)
                }.map { field -> field.get(null) as String }
                .toSet()
        val classified =
            DiagnosticProviderColumn.entries
                .map(DiagnosticProviderColumn::wireName)
                .filterNot { wireName -> wireName == "<unknown>" }
                .toSet()

        assertEquals(providerFields, classified)
    }

    private fun subBatch(): CalendarProviderSubBatch =
        CalendarProviderSubBatch(
            calendarId = 73,
            ordinal = 2,
            totalSubBatchCount = 4,
            totalOperationCount = 112,
            startOperationIndex = 50,
            operations =
                listOf(
                    CalendarProviderBatchOperation.EventInsert(73, allColumns()),
                    CalendarProviderBatchOperation.EventUpdate(73, 91, mapOf(CalendarProviderField.LOCATION to "Room 18")),
                    CalendarProviderBatchOperation.EventDelete(73, "delete-sync-id"),
                    CalendarProviderBatchOperation.AttendeesDelete(73, 92),
                    CalendarProviderBatchOperation.OrganizerDelete(73, EventReference.Existing(93)),
                    CalendarProviderBatchOperation.AttendeeInsert(73, EventReference.Existing(94), organizerValues()),
                    CalendarProviderBatchOperation.AttendeeInsert(73, EventReference.Existing(95), attendeeValues()),
                    CalendarProviderBatchOperation.RemindersDelete(73, EventReference.Inserted(0)),
                    CalendarProviderBatchOperation.ReminderInsert(
                        73,
                        EventReference.Existing(96),
                        mapOf(CalendarProviderField.REMINDER_MINUTES to 12, CalendarProviderField.REMINDER_METHOD to 1),
                    ),
                    CalendarProviderBatchOperation.ExceptionsDelete(73, 97),
                    CalendarProviderBatchOperation.ExceptionInsert(
                        73,
                        EventReference.Existing(97),
                        mapOf(CalendarProviderField.ORIGINAL_INSTANCE_TIME to 1_777_000_000_000L),
                    ),
                    CalendarProviderBatchOperation.ExceptionResponseUpdate(
                        73,
                        97,
                        Instant.parse("2026-08-19T09:00:00Z"),
                        mapOf(CalendarProviderField.STATUS to 1),
                    ),
                ),
        )

    private fun allColumns(): Map<String, Any?> =
        DiagnosticProviderColumn.entries.associate { column ->
            column.wireName to
                when (column) {
                    DiagnosticProviderColumn.TITLE -> "title-secret-marker"
                    DiagnosticProviderColumn.DESCRIPTION -> "body-secret-marker"
                    DiagnosticProviderColumn.ORGANIZER_EMAIL -> "organizer-secret@example.test"
                    DiagnosticProviderColumn.ATTENDEE_EMAIL -> "attendee-secret@example.test"
                    DiagnosticProviderColumn.ATTENDEE_NAME -> "attendee-name-secret-marker"
                    DiagnosticProviderColumn.LOCATION -> "Room 17"
                    DiagnosticProviderColumn.START -> 1_777_000_000_000L
                    DiagnosticProviderColumn.EVENT_COLOR -> null
                    DiagnosticProviderColumn.DURATION -> UnknownValue()
                    else -> 1
                }
        }

    private fun organizerValues(): Map<String, Any?> =
        mapOf(
            CalendarProviderField.ATTENDEE_EMAIL to "organizer-secret@example.test",
            CalendarProviderField.ATTENDEE_RELATIONSHIP to ProviderInteger.ORGANIZER_RELATIONSHIP,
        )

    private fun attendeeValues(): Map<String, Any?> =
        mapOf(
            CalendarProviderField.ATTENDEE_EMAIL to "attendee-secret@example.test",
            CalendarProviderField.ATTENDEE_NAME to "attendee-name-secret-marker",
            CalendarProviderField.ATTENDEE_RELATIONSHIP to ProviderInteger.ATTENDEE_RELATIONSHIP,
        )

    private class UnknownValue {
        override fun toString(): String = "unknown-to-string-secret-marker"
    }
}
