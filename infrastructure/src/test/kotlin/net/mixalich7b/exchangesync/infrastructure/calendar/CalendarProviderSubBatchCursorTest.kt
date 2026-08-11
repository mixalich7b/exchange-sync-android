package net.mixalich7b.exchangesync.infrastructure.calendar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CalendarProviderSubBatchCursorTest {
    @Test
    fun `empty plan emits no sub-batch`() {
        assertNull(cursor(emptyList()).next())
    }

    @Test
    fun `plans are emitted as consecutive sub-batches of at most fifty operations`() {
        val expectedSizes =
            mapOf(
                50 to listOf(50),
                51 to listOf(50, 1),
                121 to listOf(50, 50, 21),
            )

        expectedSizes.forEach { (operationCount, sizes) ->
            val cursor = cursor(List(operationCount, ::deleteOperation))
            val actual = mutableListOf<Int>()
            while (true) {
                val subBatch = cursor.next() ?: break
                actual += subBatch.operations.size
                cursor.record(success(subBatch))
            }
            assertEquals(sizes, actual, "$operationCount operations")
        }
    }

    @Test
    fun `same-sub-batch insert references are rewritten to local indexes`() {
        val operations =
            List(50, ::deleteOperation) +
                CalendarProviderBatchOperation.EventInsert(CALENDAR_ID, emptyMap()) +
                attendeeInsert(EventReference.Inserted(50))
        val cursor = cursor(operations)
        cursor.next().also { first -> cursor.record(success(checkNotNull(first))) }

        val second = checkNotNull(cursor.next())

        assertEquals(EventReference.Inserted(0), (second.operations[1] as CalendarProviderBatchOperation.AttendeeInsert).event)
    }

    @Test
    fun `references to inserts from earlier sub-batches use returned provider row identifiers`() {
        val operations =
            List(49, ::deleteOperation) +
                CalendarProviderBatchOperation.EventInsert(CALENDAR_ID, emptyMap()) +
                attendeeInsert(EventReference.Inserted(49))
        val cursor = cursor(operations)
        val first = checkNotNull(cursor.next())
        cursor.record(
            CalendarProviderSubBatchResult(
                appliedOperationCount = 50,
                insertResults = listOf(CalendarProviderInsertResult(49, 900L)),
            ),
        )

        val second = checkNotNull(cursor.next())

        assertEquals(EventReference.Existing(900L), (second.operations.single() as CalendarProviderBatchOperation.AttendeeInsert).event)
    }

    @Test
    fun `forward and non-insert references are rejected before a provider call`() {
        val forward =
            cursor(
                listOf(
                    attendeeInsert(EventReference.Inserted(1)),
                    CalendarProviderBatchOperation.EventInsert(CALENDAR_ID, emptyMap()),
                ),
            )
        val nonInsert = cursor(listOf(deleteOperation(0), attendeeInsert(EventReference.Inserted(0))))

        assertThrows(CalendarPlanningException::class.java) { forward.next() }
        assertThrows(CalendarPlanningException::class.java) { nonInsert.next() }
    }

    @Test
    fun `missing unexpected duplicate and invalid insert results are rejected`() {
        val plan =
            listOf(
                CalendarProviderBatchOperation.EventInsert(CALENDAR_ID, emptyMap()),
                CalendarProviderBatchOperation.ExceptionInsert(
                    CALENDAR_ID,
                    EventReference.Inserted(0),
                    emptyMap(),
                ),
            )
        val invalidResults =
            listOf(
                listOf(CalendarProviderInsertResult(0, 10L)),
                listOf(
                    CalendarProviderInsertResult(0, 10L),
                    CalendarProviderInsertResult(1, 11L),
                    CalendarProviderInsertResult(2, 12L),
                ),
                listOf(
                    CalendarProviderInsertResult(0, 10L),
                    CalendarProviderInsertResult(0, 11L),
                    CalendarProviderInsertResult(1, 12L),
                ),
                listOf(
                    CalendarProviderInsertResult(0, 0L),
                    CalendarProviderInsertResult(1, 11L),
                ),
            )

        invalidResults.forEach { results ->
            val cursor = cursor(plan)
            checkNotNull(cursor.next())
            assertThrows(CalendarPlanningException::class.java, { cursor.record(CalendarProviderSubBatchResult(2, results)) }, results.toString())
        }
    }

    @Test
    fun `sub-batches preserve canonical planner operation order`() {
        val operations =
            listOf(
                CalendarProviderBatchOperation.EventInsert(CALENDAR_ID, emptyMap()),
                CalendarProviderBatchOperation.OrganizerDelete(CALENDAR_ID, EventReference.Inserted(0)),
                attendeeInsert(EventReference.Inserted(0)),
                CalendarProviderBatchOperation.RemindersDelete(CALENDAR_ID, EventReference.Inserted(0)),
                CalendarProviderBatchOperation.ReminderInsert(CALENDAR_ID, EventReference.Inserted(0), emptyMap()),
                CalendarProviderBatchOperation.ExceptionInsert(CALENDAR_ID, EventReference.Inserted(0), emptyMap()),
                attendeeInsert(EventReference.Inserted(5)),
            )
        val subBatch = checkNotNull(cursor(operations).next())

        assertEquals(
            listOf(
                CalendarProviderBatchOperation.EventInsert::class,
                CalendarProviderBatchOperation.OrganizerDelete::class,
                CalendarProviderBatchOperation.AttendeeInsert::class,
                CalendarProviderBatchOperation.RemindersDelete::class,
                CalendarProviderBatchOperation.ReminderInsert::class,
                CalendarProviderBatchOperation.ExceptionInsert::class,
                CalendarProviderBatchOperation.AttendeeInsert::class,
            ),
            subBatch.operations.map { operation -> operation::class },
        )
    }

    private fun cursor(operations: List<CalendarProviderBatchOperation>): CalendarProviderSubBatchCursor =
        CalendarProviderSubBatchCursor(CalendarProviderBatchPlan.create(CALENDAR_ID, operations))

    private fun success(subBatch: CalendarProviderSubBatch): CalendarProviderSubBatchResult =
        CalendarProviderSubBatchResult(subBatch.operations.size, emptyList())

    private fun deleteOperation(index: Int): CalendarProviderBatchOperation =
        CalendarProviderBatchOperation.EventDelete(CALENDAR_ID, "event-$index")

    private fun attendeeInsert(reference: EventReference): CalendarProviderBatchOperation =
        CalendarProviderBatchOperation.AttendeeInsert(CALENDAR_ID, reference, emptyMap())

    private companion object {
        const val CALENDAR_ID = 12L
    }
}
