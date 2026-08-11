package net.mixalich7b.exchangesync.infrastructure.calendar

internal const val MAX_PROVIDER_OPERATIONS_PER_SUB_BATCH: Int = 50

internal data class CalendarProviderInsertResult(
    val operationIndex: Int,
    val providerRowId: Long,
)

internal data class CalendarProviderSubBatchResult(
    val appliedOperationCount: Int,
    val insertResults: List<CalendarProviderInsertResult>,
)

internal data class CalendarProviderSubBatch(
    val calendarId: Long,
    val ordinal: Int,
    val totalSubBatchCount: Int,
    val totalOperationCount: Int,
    val startOperationIndex: Int,
    val operations: List<CalendarProviderBatchOperation>,
)

internal class CalendarProviderSubBatchCursor(
    private val plan: CalendarProviderBatchPlan,
) {
    private val resolvedInsertIds = mutableMapOf<Int, Long>()
    private var nextOperationIndex = 0
    private var awaitingResult: CalendarProviderSubBatch? = null

    fun next(): CalendarProviderSubBatch? {
        if (awaitingResult != null) {
            throw CalendarPlanningException("Calendar Provider sub-batch result is missing")
        }
        if (nextOperationIndex == plan.operations.size) return null
        val endOperationIndex =
            (nextOperationIndex + MAX_PROVIDER_OPERATIONS_PER_SUB_BATCH)
                .coerceAtMost(plan.operations.size)
        val rewritten =
            plan.operations
                .subList(nextOperationIndex, endOperationIndex)
                .mapIndexed { localIndex, operation ->
                    operation.rewriteReferences(
                        globalOperationIndex = nextOperationIndex + localIndex,
                        subBatchStartIndex = nextOperationIndex,
                    )
                }
        return CalendarProviderSubBatch(
            calendarId = plan.calendarId,
            ordinal = nextOperationIndex / MAX_PROVIDER_OPERATIONS_PER_SUB_BATCH + 1,
            totalSubBatchCount =
                (plan.operations.size + MAX_PROVIDER_OPERATIONS_PER_SUB_BATCH - 1) /
                    MAX_PROVIDER_OPERATIONS_PER_SUB_BATCH,
            totalOperationCount = plan.operations.size,
            startOperationIndex = nextOperationIndex,
            operations = rewritten,
        ).also { subBatch -> awaitingResult = subBatch }
    }

    fun record(result: CalendarProviderSubBatchResult) {
        val subBatch = awaitingResult
            ?: throw CalendarPlanningException("Calendar Provider sub-batch has no pending result")
        if (result.appliedOperationCount != subBatch.operations.size) {
            throw CalendarPlanningException("Calendar Provider sub-batch result count is invalid")
        }
        val expectedInsertIndexes =
            plan.operations.indices
                .filter { index ->
                    index in subBatch.startOperationIndex until
                        (subBatch.startOperationIndex + subBatch.operations.size) &&
                        plan.operations[index].returnsProviderRowId()
                }
                .toSet()
        val actualInsertIndexes = result.insertResults.map(CalendarProviderInsertResult::operationIndex)
        if (
            actualInsertIndexes.size != actualInsertIndexes.distinct().size ||
            actualInsertIndexes.toSet() != expectedInsertIndexes ||
            result.insertResults.any { insert -> insert.providerRowId <= 0L }
        ) {
            throw CalendarPlanningException("Calendar Provider insert results are invalid")
        }
        result.insertResults.forEach { insert ->
            resolvedInsertIds[insert.operationIndex] = insert.providerRowId
        }
        nextOperationIndex += subBatch.operations.size
        awaitingResult = null
    }

    private fun CalendarProviderBatchOperation.rewriteReferences(
        globalOperationIndex: Int,
        subBatchStartIndex: Int,
    ): CalendarProviderBatchOperation =
        when (this) {
            is CalendarProviderBatchOperation.OrganizerDelete ->
                copy(event = resolve(event, globalOperationIndex, subBatchStartIndex))
            is CalendarProviderBatchOperation.AttendeeInsert ->
                copy(event = resolve(event, globalOperationIndex, subBatchStartIndex))
            is CalendarProviderBatchOperation.RemindersDelete ->
                copy(event = resolve(event, globalOperationIndex, subBatchStartIndex))
            is CalendarProviderBatchOperation.ReminderInsert ->
                copy(event = resolve(event, globalOperationIndex, subBatchStartIndex))
            is CalendarProviderBatchOperation.ExceptionInsert ->
                copy(series = resolve(series, globalOperationIndex, subBatchStartIndex))
            is CalendarProviderBatchOperation.EventInsert,
            is CalendarProviderBatchOperation.EventUpdate,
            is CalendarProviderBatchOperation.EventDelete,
            is CalendarProviderBatchOperation.AttendeesDelete,
            is CalendarProviderBatchOperation.ExceptionsDelete,
            is CalendarProviderBatchOperation.ExceptionResponseUpdate,
            -> this
        }

    private fun resolve(
        reference: EventReference,
        globalOperationIndex: Int,
        subBatchStartIndex: Int,
    ): EventReference =
        when (reference) {
            is EventReference.Existing -> {
                if (reference.eventId <= 0L) {
                    throw CalendarPlanningException("Calendar Provider row reference is invalid")
                }
                reference
            }
            is EventReference.Inserted -> {
                val referencedIndex = reference.operationIndex
                if (referencedIndex !in 0 until globalOperationIndex) {
                    throw CalendarPlanningException("Calendar Provider insert reference is not backward")
                }
                if (!plan.operations[referencedIndex].returnsProviderRowId()) {
                    throw CalendarPlanningException("Calendar Provider insert reference targets a non-insert")
                }
                if (referencedIndex >= subBatchStartIndex) {
                    EventReference.Inserted(referencedIndex - subBatchStartIndex)
                } else {
                    EventReference.Existing(
                        resolvedInsertIds[referencedIndex]
                            ?: throw CalendarPlanningException("Calendar Provider insert result is missing"),
                    )
                }
            }
        }
}

private fun CalendarProviderBatchOperation.returnsProviderRowId(): Boolean =
    this is CalendarProviderBatchOperation.EventInsert ||
        this is CalendarProviderBatchOperation.ExceptionInsert
