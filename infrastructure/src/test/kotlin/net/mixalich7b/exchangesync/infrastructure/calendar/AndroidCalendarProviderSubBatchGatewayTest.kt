package net.mixalich7b.exchangesync.infrastructure.calendar

import android.os.TransactionTooLargeException
import android.os.RemoteException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AndroidCalendarProviderSubBatchGatewayTest {
    @Test
    fun `local insert references become Android value and selection back references`() {
        val executor = RecordingExecutor(results(4, "content://com.android.calendar/events/700"))
        val gateway = AndroidCalendarProviderSubBatchGateway(executor)
        val batch =
            subBatch(
                listOf(
                    CalendarProviderBatchOperation.EventInsert(CALENDAR_ID, emptyMap()),
                    CalendarProviderBatchOperation.AttendeeInsert(CALENDAR_ID, EventReference.Inserted(0), emptyMap()),
                    CalendarProviderBatchOperation.RemindersDelete(CALENDAR_ID, EventReference.Inserted(0)),
                    CalendarProviderBatchOperation.OrganizerDelete(CALENDAR_ID, EventReference.Inserted(0)),
                ),
            )

        gateway.apply(batch)

        val requests = executor.request.operations
        assertEquals(mapOf("event_id" to 0), requests[1].valueBackReferences)
        assertEquals(mapOf(0 to 0), requests[2].selectionBackReferences)
        assertEquals(mapOf(0 to 0), requests[3].selectionBackReferences)
        assertTrue(requests.all(AndroidCalendarProviderOperationRequest::callerIsSyncAdapter))
        assertTrue(requests.all { request -> request.accountName == OwnedCalendarIdentity.ACCOUNT_NAME })
        assertTrue(requests.all { request -> request.accountType == OwnedCalendarIdentity.ACCOUNT_TYPE })
    }

    @Test
    fun `existing row references become explicit values and owned-row selections`() {
        val executor = RecordingExecutor(results(3))
        val gateway = AndroidCalendarProviderSubBatchGateway(executor)
        gateway.apply(
            subBatch(
                listOf(
                    CalendarProviderBatchOperation.AttendeeInsert(CALENDAR_ID, EventReference.Existing(77L), emptyMap()),
                    CalendarProviderBatchOperation.RemindersDelete(CALENDAR_ID, EventReference.Existing(77L)),
                    CalendarProviderBatchOperation.OrganizerDelete(CALENDAR_ID, EventReference.Existing(77L)),
                ),
            ),
        )

        val requests = executor.request.operations
        assertEquals(77L, requests[0].values["event_id"])
        assertTrue(requests[0].valueBackReferences.isEmpty())
        assertEquals(listOf("77"), requests[1].selectionArguments)
        assertEquals(listOf("77", ProviderInteger.ORGANIZER_RELATIONSHIP.toString()), requests[2].selectionArguments)
        assertTrue(requests.all { request -> request.selectionBackReferences.isEmpty() })
    }

    @Test
    fun `event and exception insert identifiers are returned with global operation indexes`() {
        val executor =
            RecordingExecutor(
                listOf(
                    AndroidCalendarProviderOperationResult(
                        "content://com.android.calendar/events/700?caller_is_syncadapter=true&account_name=owner",
                        null,
                    ),
                    AndroidCalendarProviderOperationResult(null, 1),
                    AndroidCalendarProviderOperationResult(
                        "content://com.android.calendar/events/701?caller_is_syncadapter=true&account_name=owner",
                        null,
                    ),
                ),
            )
        val gateway = AndroidCalendarProviderSubBatchGateway(executor)
        val batch =
            subBatch(
                operations =
                    listOf(
                        CalendarProviderBatchOperation.EventInsert(CALENDAR_ID, emptyMap()),
                        CalendarProviderBatchOperation.EventDelete(CALENDAR_ID, "old"),
                        CalendarProviderBatchOperation.ExceptionInsert(
                            CALENDAR_ID,
                            EventReference.Inserted(0),
                            emptyMap(),
                        ),
                    ),
                startOperationIndex = 50,
            )

        val result = gateway.apply(batch)

        assertEquals(3, result.appliedOperationCount)
        assertEquals(
            listOf(
                CalendarProviderInsertResult(50, 700L),
                CalendarProviderInsertResult(52, 701L),
            ),
            result.insertResults,
        )
    }

    @Test
    fun `missing malformed and non-positive insert results are rejected`() {
        val batch = subBatch(listOf(CalendarProviderBatchOperation.EventInsert(CALENDAR_ID, emptyMap())))
        val invalid =
            listOf(
                emptyList(),
                listOf(AndroidCalendarProviderOperationResult(null, null)),
                listOf(AndroidCalendarProviderOperationResult("content://com.android.calendar/events/not-a-row", null)),
                listOf(AndroidCalendarProviderOperationResult("content://com.android.calendar/events/0", null)),
                listOf(AndroidCalendarProviderOperationResult("content://foreign.calendar/events/7", null)),
                listOf(AndroidCalendarProviderOperationResult("content://com.android.calendar/calendars/7", null)),
                listOf(AndroidCalendarProviderOperationResult("content://com.android.calendar/events/7/child", null)),
            )

        invalid.forEach { results ->
            assertThrows(CalendarProviderAccessException::class.java, {
                AndroidCalendarProviderSubBatchGateway(RecordingExecutor(results)).apply(batch)
            }, results.toString())
        }
    }

    @Test
    fun `Android transaction-too-large failure retains its typed capacity classification`() {
        val gateway =
            AndroidCalendarProviderSubBatchGateway(
                AndroidCalendarProviderBatchExecutor { throw TransactionTooLargeException() },
            )

        assertThrows(CalendarProviderTransactionTooLargeException::class.java) {
            gateway.apply(subBatch(listOf(CalendarProviderBatchOperation.EventDelete(CALENDAR_ID, "old"))))
        }
    }

    @Test
    fun `wrapped Android provider failure retains a typed diagnostic cause`() {
        val gateway =
            AndroidCalendarProviderSubBatchGateway(
                AndroidCalendarProviderBatchExecutor { throw RemoteException() },
            )

        val failure =
            assertThrows(CalendarProviderAccessException::class.java) {
                gateway.apply(subBatch(listOf(CalendarProviderBatchOperation.EventDelete(CALENDAR_ID, "old"))))
            }

        assertEquals(CalendarProviderFailureCause.REMOTE, failure.failureCause)
        assertEquals(CalendarProviderDispatchState.UNKNOWN, failure.dispatchState)
    }

    @Test
    fun `invalid local reference is classified before provider dispatch`() {
        var executorCalls = 0
        val gateway =
            AndroidCalendarProviderSubBatchGateway(
                AndroidCalendarProviderBatchExecutor {
                    executorCalls += 1
                    results(1)
                },
            )

        val failure =
            assertThrows(CalendarProviderAccessException::class.java) {
                gateway.apply(
                    subBatch(
                        listOf(
                            CalendarProviderBatchOperation.AttendeeInsert(
                                CALENDAR_ID,
                                EventReference.Existing(0),
                                emptyMap(),
                            ),
                        ),
                    ),
                )
            }

        assertEquals(CalendarProviderFailureCause.INVALID_REFERENCE, failure.failureCause)
        assertEquals(CalendarProviderDispatchState.NOT_DISPATCHED, failure.dispatchState)
        assertEquals(0, executorCalls)
    }

    @Test
    fun `every Android operation construction failure is classified before dispatch`() {
        val fixtures =
            listOf(
                IllegalArgumentException("invalid builder input") to CalendarProviderFailureCause.INVALID_ARGUMENT,
                SecurityException("builder access denied") to CalendarProviderFailureCause.SECURITY,
                IllegalStateException("builder runtime failure") to CalendarProviderFailureCause.UNEXPECTED,
            )

        fixtures.forEach { (source, expectedCause) ->
            val failure =
                assertThrows(CalendarProviderAccessException::class.java) {
                    beforeCalendarProviderDispatch<Unit> { throw source }
                }

            assertEquals(expectedCause, failure.failureCause, source.javaClass.simpleName)
            assertEquals(CalendarProviderDispatchState.NOT_DISPATCHED, failure.dispatchState)
            assertEquals(source, failure.cause)
        }
    }

    private fun subBatch(
        operations: List<CalendarProviderBatchOperation>,
        startOperationIndex: Int = 0,
    ): CalendarProviderSubBatch =
        CalendarProviderSubBatch(
            calendarId = CALENDAR_ID,
            ordinal = 1,
            totalSubBatchCount = 1,
            totalOperationCount = operations.size,
            startOperationIndex = startOperationIndex,
            operations = operations,
        )

    private fun results(
        count: Int,
        firstUri: String? = null,
    ): List<AndroidCalendarProviderOperationResult> =
        List(count) { index -> AndroidCalendarProviderOperationResult(firstUri.takeIf { index == 0 }, 1) }

    private class RecordingExecutor(
        private val results: List<AndroidCalendarProviderOperationResult>,
    ) : AndroidCalendarProviderBatchExecutor {
        lateinit var request: AndroidCalendarProviderBatchRequest

        override fun execute(request: AndroidCalendarProviderBatchRequest): List<AndroidCalendarProviderOperationResult> {
            assertFalse(this::request.isInitialized)
            this.request = request
            return results
        }
    }

    private companion object {
        const val CALENDAR_ID = 12L
    }
}
