package net.mixalich7b.exchangesync.infrastructure.calendar

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.OperationApplicationException
import android.net.Uri
import android.os.OperationCanceledException
import android.os.RemoteException
import android.os.TransactionTooLargeException
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import java.net.URI

internal enum class AndroidCalendarProviderTarget {
    EVENTS,
    ATTENDEES,
    REMINDERS,
}

internal enum class AndroidCalendarProviderAction {
    INSERT,
    UPDATE,
    DELETE,
}

internal data class AndroidCalendarProviderOperationRequest(
    val target: AndroidCalendarProviderTarget,
    val action: AndroidCalendarProviderAction,
    val callerIsSyncAdapter: Boolean,
    val accountName: String,
    val accountType: String,
    val values: Map<String, Any?> = emptyMap(),
    val valueBackReferences: Map<String, Int> = emptyMap(),
    val selection: String? = null,
    val selectionArguments: List<String> = emptyList(),
    val selectionBackReferences: Map<Int, Int> = emptyMap(),
    val expectedCount: Int? = null,
)

internal data class AndroidCalendarProviderBatchRequest(
    val authority: String,
    val operations: List<AndroidCalendarProviderOperationRequest>,
)

internal data class AndroidCalendarProviderOperationResult(
    val uri: String?,
    val count: Int?,
)

internal fun interface AndroidCalendarProviderBatchExecutor {
    fun execute(request: AndroidCalendarProviderBatchRequest): List<AndroidCalendarProviderOperationResult>
}

internal class AndroidCalendarProviderSubBatchGateway(
    private val executor: AndroidCalendarProviderBatchExecutor,
) {
    fun apply(subBatch: CalendarProviderSubBatch): CalendarProviderSubBatchResult {
        if (
            subBatch.operations.size > MAX_PROVIDER_OPERATIONS_PER_SUB_BATCH ||
            subBatch.operations.any { operation -> operation.calendarId != subBatch.calendarId }
        ) {
            throw CalendarProviderAccessException(failureCause = CalendarProviderFailureCause.INVALID_REQUEST)
        }
        val request =
            AndroidCalendarProviderBatchRequest(
                authority = CalendarContract.AUTHORITY,
                operations =
                    subBatch.operations.mapIndexed { localIndex, operation ->
                        operation.toAndroidRequest(localIndex)
                    },
            )
        val results =
            try {
                executor.execute(request)
            } catch (_: TransactionTooLargeException) {
                throw CalendarProviderTransactionTooLargeException()
            } catch (failure: RemoteException) {
                throw CalendarProviderAccessException(failure, CalendarProviderFailureCause.REMOTE)
            } catch (failure: OperationApplicationException) {
                throw CalendarProviderAccessException(failure, CalendarProviderFailureCause.OPERATION_APPLICATION)
            } catch (failure: OperationCanceledException) {
                throw CalendarProviderAccessException(failure, CalendarProviderFailureCause.OPERATION_CANCELLED)
            } catch (failure: IllegalArgumentException) {
                throw CalendarProviderAccessException(failure, CalendarProviderFailureCause.INVALID_ARGUMENT)
            }
        if (results.size != subBatch.operations.size) {
            throw CalendarProviderAccessException(failureCause = CalendarProviderFailureCause.INVALID_RESULT)
        }
        val insertResults =
            subBatch.operations.mapIndexedNotNull { localIndex, operation ->
                if (!operation.returnsResolvableProviderRowId()) return@mapIndexedNotNull null
                val rowId = results[localIndex].uri?.toCalendarEventRowId()
                    ?: throw CalendarProviderAccessException(failureCause = CalendarProviderFailureCause.INVALID_RESULT)
                CalendarProviderInsertResult(subBatch.startOperationIndex + localIndex, rowId)
            }
        return CalendarProviderSubBatchResult(subBatch.operations.size, insertResults)
    }

    private fun CalendarProviderBatchOperation.toAndroidRequest(
        localOperationIndex: Int,
    ): AndroidCalendarProviderOperationRequest =
        when (this) {
            is CalendarProviderBatchOperation.EventInsert ->
                request(
                    target = AndroidCalendarProviderTarget.EVENTS,
                    action = AndroidCalendarProviderAction.INSERT,
                    values = values + (Events.CALENDAR_ID to calendarId),
                )
            is CalendarProviderBatchOperation.EventUpdate ->
                request(
                    target = AndroidCalendarProviderTarget.EVENTS,
                    action = AndroidCalendarProviderAction.UPDATE,
                    values = values,
                    selection = "${Events._ID}=? AND ${Events.CALENDAR_ID}=?",
                    selectionArguments = listOf(eventId.toString(), calendarId.toString()),
                    expectedCount = 1,
                )
            is CalendarProviderBatchOperation.EventDelete ->
                request(
                    target = AndroidCalendarProviderTarget.EVENTS,
                    action = AndroidCalendarProviderAction.DELETE,
                    selection = "${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID}=?",
                    selectionArguments = listOf(calendarId.toString(), syncId),
                )
            is CalendarProviderBatchOperation.AttendeesDelete ->
                request(
                    target = AndroidCalendarProviderTarget.ATTENDEES,
                    action = AndroidCalendarProviderAction.DELETE,
                    selection = "${Attendees.EVENT_ID}=? AND ${Attendees.ATTENDEE_RELATIONSHIP}<>?",
                    selectionArguments =
                        listOf(eventId.toString(), Attendees.RELATIONSHIP_ORGANIZER.toString()),
                )
            is CalendarProviderBatchOperation.OrganizerDelete ->
                request(
                    target = AndroidCalendarProviderTarget.ATTENDEES,
                    action = AndroidCalendarProviderAction.DELETE,
                ).withEventSelection(
                    reference = event,
                    selection = "${Attendees.EVENT_ID}=? AND ${Attendees.ATTENDEE_RELATIONSHIP}=?",
                    trailingArguments = listOf(Attendees.RELATIONSHIP_ORGANIZER.toString()),
                    localOperationIndex = localOperationIndex,
                )
            is CalendarProviderBatchOperation.AttendeeInsert ->
                request(
                    target = AndroidCalendarProviderTarget.ATTENDEES,
                    action = AndroidCalendarProviderAction.INSERT,
                    values = values,
                ).withEventValue(Attendees.EVENT_ID, event, localOperationIndex)
            is CalendarProviderBatchOperation.RemindersDelete ->
                request(
                    target = AndroidCalendarProviderTarget.REMINDERS,
                    action = AndroidCalendarProviderAction.DELETE,
                ).withEventSelection(
                    reference = event,
                    selection = "${Reminders.EVENT_ID}=?",
                    trailingArguments = emptyList(),
                    localOperationIndex = localOperationIndex,
                )
            is CalendarProviderBatchOperation.ReminderInsert ->
                request(
                    target = AndroidCalendarProviderTarget.REMINDERS,
                    action = AndroidCalendarProviderAction.INSERT,
                    values = values,
                ).withEventValue(Reminders.EVENT_ID, event, localOperationIndex)
            is CalendarProviderBatchOperation.ExceptionsDelete ->
                request(
                    target = AndroidCalendarProviderTarget.EVENTS,
                    action = AndroidCalendarProviderAction.DELETE,
                    selection = "${Events.CALENDAR_ID}=? AND ${Events.ORIGINAL_ID}=?",
                    selectionArguments = listOf(calendarId.toString(), seriesId.toString()),
                )
            is CalendarProviderBatchOperation.ExceptionInsert ->
                request(
                    target = AndroidCalendarProviderTarget.EVENTS,
                    action = AndroidCalendarProviderAction.INSERT,
                    values = values + (Events.CALENDAR_ID to calendarId),
                ).withEventValue(Events.ORIGINAL_ID, series, localOperationIndex)
            is CalendarProviderBatchOperation.ExceptionResponseUpdate ->
                request(
                    target = AndroidCalendarProviderTarget.EVENTS,
                    action = AndroidCalendarProviderAction.UPDATE,
                    values = values,
                    selection =
                        "${Events.CALENDAR_ID}=? AND ${Events.ORIGINAL_ID}=? AND " +
                            "${CalendarProviderField.ORIGINAL_INSTANCE_TIME}=?",
                    selectionArguments =
                        listOf(
                            calendarId.toString(),
                            seriesId.toString(),
                            originalInstance.toEpochMilli().toString(),
                        ),
                    expectedCount = 1,
                )
        }

    private fun request(
        target: AndroidCalendarProviderTarget,
        action: AndroidCalendarProviderAction,
        values: Map<String, Any?> = emptyMap(),
        selection: String? = null,
        selectionArguments: List<String> = emptyList(),
        expectedCount: Int? = null,
    ): AndroidCalendarProviderOperationRequest =
        AndroidCalendarProviderOperationRequest(
            target = target,
            action = action,
            callerIsSyncAdapter = true,
            accountName = OwnedCalendarIdentity.ACCOUNT_NAME,
            accountType = OwnedCalendarIdentity.ACCOUNT_TYPE,
            values = values,
            selection = selection,
            selectionArguments = selectionArguments,
            expectedCount = expectedCount,
        )

    private fun AndroidCalendarProviderOperationRequest.withEventValue(
        column: String,
        reference: EventReference,
        localOperationIndex: Int,
    ): AndroidCalendarProviderOperationRequest =
        when (reference) {
            is EventReference.Existing -> {
                if (reference.eventId <= 0L) {
                    throw CalendarProviderAccessException(failureCause = CalendarProviderFailureCause.INVALID_REFERENCE)
                }
                copy(values = values + (column to reference.eventId))
            }
            is EventReference.Inserted -> {
                reference.requireLocalBackwardReference(localOperationIndex)
                copy(valueBackReferences = valueBackReferences + (column to reference.operationIndex))
            }
        }

    private fun AndroidCalendarProviderOperationRequest.withEventSelection(
        reference: EventReference,
        selection: String,
        trailingArguments: List<String>,
        localOperationIndex: Int,
    ): AndroidCalendarProviderOperationRequest =
        when (reference) {
            is EventReference.Existing -> {
                if (reference.eventId <= 0L) {
                    throw CalendarProviderAccessException(failureCause = CalendarProviderFailureCause.INVALID_REFERENCE)
                }
                copy(
                    selection = selection,
                    selectionArguments = listOf(reference.eventId.toString()) + trailingArguments,
                )
            }
            is EventReference.Inserted -> {
                reference.requireLocalBackwardReference(localOperationIndex)
                copy(
                    selection = selection,
                    selectionArguments = listOf("0") + trailingArguments,
                    selectionBackReferences = mapOf(0 to reference.operationIndex),
                )
            }
        }

    private fun EventReference.Inserted.requireLocalBackwardReference(localOperationIndex: Int) {
        if (operationIndex !in 0 until localOperationIndex) {
            throw CalendarProviderAccessException(failureCause = CalendarProviderFailureCause.INVALID_REFERENCE)
        }
    }
}

internal class AndroidContentResolverBatchExecutor(
    private val contentResolver: ContentResolver,
) : AndroidCalendarProviderBatchExecutor {
    override fun execute(request: AndroidCalendarProviderBatchRequest): List<AndroidCalendarProviderOperationResult> {
        val operations = ArrayList<ContentProviderOperation>(request.operations.size)
        request.operations.forEach { operation -> operations += operation.toContentProviderOperation() }
        return contentResolver.applyBatch(request.authority, operations).map { result ->
            AndroidCalendarProviderOperationResult(result.uri?.toString(), result.count)
        }
    }

    private fun AndroidCalendarProviderOperationRequest.toContentProviderOperation(): ContentProviderOperation {
        val uri = target.contentUri().withSyncAdapterParameters(this)
        val builder =
            when (action) {
                AndroidCalendarProviderAction.INSERT -> ContentProviderOperation.newInsert(uri)
                AndroidCalendarProviderAction.UPDATE -> ContentProviderOperation.newUpdate(uri)
                AndroidCalendarProviderAction.DELETE -> ContentProviderOperation.newDelete(uri)
            }
        if (values.isNotEmpty()) builder.withValues(values.toContentValues())
        valueBackReferences.forEach { (column, operationIndex) ->
            builder.withValueBackReference(column, operationIndex)
        }
        selection?.let { value -> builder.withSelection(value, selectionArguments.toTypedArray()) }
        selectionBackReferences.forEach { (argumentIndex, operationIndex) ->
            builder.withSelectionBackReference(argumentIndex, operationIndex)
        }
        expectedCount?.let(builder::withExpectedCount)
        return builder.build()
    }

    private fun AndroidCalendarProviderTarget.contentUri(): Uri =
        when (this) {
            AndroidCalendarProviderTarget.EVENTS -> Events.CONTENT_URI
            AndroidCalendarProviderTarget.ATTENDEES -> Attendees.CONTENT_URI
            AndroidCalendarProviderTarget.REMINDERS -> Reminders.CONTENT_URI
        }

    private fun Uri.withSyncAdapterParameters(request: AndroidCalendarProviderOperationRequest): Uri =
        buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, request.callerIsSyncAdapter.toString())
            .appendQueryParameter(Calendars.ACCOUNT_NAME, request.accountName)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, request.accountType)
            .build()

    private fun Map<String, Any?>.toContentValues(): ContentValues =
        ContentValues(size).apply {
            this@toContentValues.forEach { (key, value) ->
                when (value) {
                    null -> putNull(key)
                    is String -> put(key, value)
                    is Int -> put(key, value)
                    is Long -> put(key, value)
                    else ->
                        throw CalendarProviderAccessException(
                            failureCause = CalendarProviderFailureCause.UNSUPPORTED_VALUE,
                        )
                }
            }
        }
}

private fun String.toCalendarEventRowId(): Long? {
    val uri = runCatching { URI(this) }.getOrNull() ?: return null
    if (uri.scheme != "content" || uri.authority != CalendarContract.AUTHORITY) return null
    val pathSegments = uri.path?.split('/')?.filter(String::isNotEmpty).orEmpty()
    if (pathSegments.size != 2 || pathSegments.first() != CALENDAR_EVENTS_PATH) return null
    return pathSegments.last().toLongOrNull()?.takeIf { rowId -> rowId > 0L }
}

private fun CalendarProviderBatchOperation.returnsResolvableProviderRowId(): Boolean =
    this is CalendarProviderBatchOperation.EventInsert ||
        this is CalendarProviderBatchOperation.ExceptionInsert

private const val CALENDAR_EVENTS_PATH: String = "events"
