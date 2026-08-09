package net.mixalich7b.exchangesync.infrastructure.calendar

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.os.OperationCanceledException
import android.os.RemoteException
import android.os.TransactionTooLargeException
import android.content.pm.PackageManager
import android.provider.BaseColumns
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncAvailability
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncField
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncResponseType
import net.mixalich7b.exchangesync.core.calendar.ProviderAccessLevel
import net.mixalich7b.exchangesync.core.calendar.ProviderAvailability
import net.mixalich7b.exchangesync.core.calendar.ProviderAttendee
import net.mixalich7b.exchangesync.core.calendar.ProviderAttendeeRole
import net.mixalich7b.exchangesync.core.calendar.ProviderAttendeeStatus
import net.mixalich7b.exchangesync.core.calendar.ProviderCalendarException
import net.mixalich7b.exchangesync.core.calendar.ProviderEvent
import net.mixalich7b.exchangesync.core.calendar.ProviderEventStatus
import net.mixalich7b.exchangesync.core.calendar.ProviderSelfStatus
import net.mixalich7b.exchangesync.core.calendar.CalendarMappingException
import net.mixalich7b.exchangesync.core.connection.ConnectionProfileRepository
import net.mixalich7b.exchangesync.core.sync.LocalPageOutcome
import net.mixalich7b.exchangesync.core.sync.OwnedCalendarPort
import net.mixalich7b.exchangesync.core.sync.RemoteCalendarPage
import net.mixalich7b.exchangesync.core.sync.SyncFence
import net.mixalich7b.exchangesync.core.sync.SyncProblem
import net.mixalich7b.exchangesync.core.sync.SyncState
import net.mixalich7b.exchangesync.core.sync.SyncStateRepository
import net.mixalich7b.exchangesync.core.sync.SyncStateTransitions
import net.mixalich7b.exchangesync.core.sync.SynchronizationMutationLock
import net.mixalich7b.exchangesync.infrastructure.activesync.calendar.ActiveSyncCalendarValueParsers

internal class CalendarProviderTransactionTooLargeException : RuntimeException()

internal class CalendarProviderAccessException(cause: Throwable? = null) : RuntimeException(cause)

internal interface OwnedCalendarProviderGateway {
    fun resolveOwned(profileEmail: String): OwnedCalendarResolution

    fun deleteAllOwned(): Boolean

    fun queryExisting(
        calendarId: Long,
        syncIds: Set<String>,
    ): List<ExistingProviderEvent>

    fun applyBatch(plan: CalendarProviderBatchPlan)
}

internal object OwnedCalendarRecreationPolicy {
    fun canPopulate(state: SyncState, fence: SyncFence): Boolean =
        state.generation == fence.generation &&
            state.runToken == fence.runToken &&
            state.fullSyncRequired &&
            state.checkpoints.collectionSyncKey == null
}

public class AndroidOwnedCalendarAdapter internal constructor(
    private val profileRepository: ConnectionProfileRepository,
    private val gateway: OwnedCalendarProviderGateway,
    private val timeZoneResolver: CalendarProviderTimeZoneResolver,
    private val isFenceCurrent: suspend (SyncFence) -> Boolean = { true },
    private val isCleanupFenceCurrent: suspend (SyncFence) -> Boolean = isFenceCurrent,
    private val isFullSyncRequired: suspend (SyncFence) -> Boolean = { false },
    private val hasCalendarAccess: () -> Boolean = { true },
    private val mutationLock: SynchronizationMutationLock = SynchronizationMutationLock(),
) : OwnedCalendarPort {
    public constructor(
        context: Context,
        profileRepository: ConnectionProfileRepository,
        stateRepository: SyncStateRepository,
        mutationLock: SynchronizationMutationLock,
    ) : this(
        profileRepository = profileRepository,
        gateway = AndroidOwnedCalendarProviderGateway(context.applicationContext.contentResolver),
        timeZoneResolver = AndroidCalendarProviderTimeZoneResolver,
        isFenceCurrent = { fence ->
            SyncStateTransitions.mayPerformSideEffect(stateRepository.load(), fence)
        },
        isCleanupFenceCurrent = { fence ->
            val current = stateRepository.load()
            current.generation == fence.generation && current.runToken == fence.runToken
        },
        isFullSyncRequired = { fence ->
            OwnedCalendarRecreationPolicy.canPopulate(stateRepository.load(), fence)
        },
        hasCalendarAccess = {
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        },
        mutationLock = mutationLock,
    )

    override suspend fun deleteOwnedCalendar(fence: SyncFence?): Boolean =
        withContext(Dispatchers.IO) {
            mutationLock.withLock {
                if (fence != null && !isCleanupFenceCurrent(fence)) return@withLock false
                try {
                    gateway.deleteAllOwned()
                } catch (_: OwnedCalendarProviderException) {
                    false
                } catch (_: CalendarProviderAccessException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            }
        }

    override suspend fun applyPage(
        fence: SyncFence,
        page: RemoteCalendarPage,
    ): LocalPageOutcome =
        withContext(Dispatchers.IO) {
            mutationLock.withLock { applyProviderPage(fence, page) }
        }

    private suspend fun applyProviderPage(
        fence: SyncFence,
        page: RemoteCalendarPage,
    ): LocalPageOutcome {
        if (!isFenceCurrent(fence)) return LocalPageOutcome.Obsolete
        val profile = profileRepository.load() ?: return LocalPageOutcome.Failed(SyncProblem.PROTOCOL_DATA)
        return try {
            val owned = gateway.resolveOwned(profile.email)
            if (owned.wasRecreated && !isFullSyncRequired(fence)) {
                throw CalendarMirrorResetRequiredException()
            }
            val syncIds = page.changes.mapTo(linkedSetOf(), CalendarChangeIdentity::requireSyncId)
            val existing = gateway.queryExisting(owned.calendarId, syncIds)
            val pagePlan = CalendarPagePlanner.plan(page, owned, existing)
            val batchPlan = CalendarProviderBatchPlanner.plan(pagePlan, timeZoneResolver)
            if (!isFenceCurrent(fence)) return LocalPageOutcome.Obsolete
            gateway.applyBatch(batchPlan)
            LocalPageOutcome.Applied
        } catch (_: CalendarProviderTransactionTooLargeException) {
            LocalPageOutcome.TransactionTooLarge
        } catch (_: CalendarMirrorResetRequiredException) {
            LocalPageOutcome.FullResetRequired
        } catch (_: CalendarMappingException) {
            LocalPageOutcome.Failed(SyncProblem.PROTOCOL_DATA)
        } catch (_: CalendarPlanningException) {
            LocalPageOutcome.Failed(SyncProblem.PROTOCOL_DATA)
        } catch (_: OwnedCalendarProviderException) {
            LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER)
        } catch (_: CalendarProviderAccessException) {
            LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER)
        } catch (_: SecurityException) {
            LocalPageOutcome.Failed(
                if (hasCalendarAccess()) SyncProblem.CALENDAR_PROVIDER else SyncProblem.CALENDAR_PERMISSION,
            )
        }
    }
}

private object CalendarChangeIdentity {
    fun requireSyncId(change: net.mixalich7b.exchangesync.core.sync.CalendarChange): String =
        when (change) {
            is net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarMutation.Delete -> change.serverId
            is net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarMutation.Upsert -> change.item.serverId
            else -> throw CalendarPlanningException("Calendar page contains an unsupported change")
        }
}

private class AndroidOwnedCalendarProviderGateway(
    private val contentResolver: ContentResolver,
) : OwnedCalendarProviderGateway {
    private val resolver = OwnedCalendarResolver(AndroidOwnedCalendarStore(contentResolver))

    override fun resolveOwned(profileEmail: String): OwnedCalendarResolution = resolver.resolve(profileEmail)

    override fun deleteAllOwned(): Boolean = resolver.deleteAllOwned()

    override fun queryExisting(
        calendarId: Long,
        syncIds: Set<String>,
    ): List<ExistingProviderEvent> {
        if (syncIds.isEmpty()) return emptyList()
        val placeholders = List(syncIds.size) { "?" }.joinToString(",")
        val selection = "${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID} IN ($placeholders)"
        val arguments = arrayOf(calendarId.toString(), *syncIds.toTypedArray())
        val cursor =
            contentResolver.query(
                Events.CONTENT_URI,
                EVENT_IDENTITY_PROJECTION,
                selection,
                arguments,
                null,
            ) ?: throw CalendarProviderAccessException()
        val events = cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        ExistingProviderEvent(
                            eventId = it.getLong(it.getColumnIndexOrThrow(BaseColumns._ID)),
                            calendarId = it.getLong(it.getColumnIndexOrThrow(Events.CALENDAR_ID)),
                            syncId = it.getString(it.getColumnIndexOrThrow(Events._SYNC_ID)),
                            snapshot = it.toProviderSnapshot(),
                            providerTimeZone = it.nullableString(CalendarProviderField.EVENT_TIME_ZONE),
                            isDirty = it.getInt(it.getColumnIndexOrThrow(CalendarProviderField.DIRTY)) != 0,
                        ),
                    )
                }
            }
        }
        val attendees = queryAttendees(events.mapTo(linkedSetOf(), ExistingProviderEvent::eventId))
        val reminders = queryReminders(events.mapTo(linkedSetOf(), ExistingProviderEvent::eventId))
        val exceptions = queryExceptions(calendarId, events.mapTo(linkedSetOf(), ExistingProviderEvent::eventId))
        return events.map { event ->
            val snapshot = checkNotNull(event.snapshot)
            event.copy(
                snapshot =
                    snapshot.copy(
                        attendees = ActiveSyncField.Value(attendees[event.eventId].orEmpty()),
                        exceptions = ActiveSyncField.Value(exceptions[event.eventId].orEmpty()),
                        reminderMinutes =
                            reminders[event.eventId]?.let { minutes -> ActiveSyncField.Value(minutes) }
                                ?: ActiveSyncField.Empty,
                    ),
            )
        }
    }

    private fun queryExceptions(
        calendarId: Long,
        seriesIds: Set<Long>,
    ): Map<Long, List<ProviderCalendarException>> {
        if (seriesIds.isEmpty()) return emptyMap()
        val placeholders = List(seriesIds.size) { "?" }.joinToString(",")
        val cursor =
            contentResolver.query(
                Events.CONTENT_URI,
                EXCEPTION_RESPONSE_PROJECTION,
                "${Events.CALENDAR_ID}=? AND ${Events.ORIGINAL_ID} IN ($placeholders)",
                arrayOf(calendarId.toString(), *seriesIds.map(Long::toString).toTypedArray()),
                null,
            ) ?: throw CalendarProviderAccessException()
        return cursor.use {
            buildMap<Long, MutableList<ProviderCalendarException>> {
                while (it.moveToNext()) {
                    if (it.getInt(it.getColumnIndexOrThrow(CalendarProviderField.DIRTY)) != 0) {
                        throw CalendarMirrorResetRequiredException()
                    }
                    val seriesId = it.getLong(it.getColumnIndexOrThrow(Events.ORIGINAL_ID))
                    getOrPut(seriesId, ::mutableListOf) += it.toProviderExceptionResponseSnapshot()
                }
            }
        }
    }

    private fun queryAttendees(eventIds: Set<Long>): Map<Long, List<ProviderAttendee>> {
        if (eventIds.isEmpty()) return emptyMap()
        val placeholders = List(eventIds.size) { "?" }.joinToString(",")
        val cursor =
            contentResolver.query(
                Attendees.CONTENT_URI,
                arrayOf(
                    Attendees.EVENT_ID,
                    Attendees.ATTENDEE_EMAIL,
                    Attendees.ATTENDEE_NAME,
                    Attendees.ATTENDEE_RELATIONSHIP,
                    Attendees.ATTENDEE_TYPE,
                    Attendees.ATTENDEE_STATUS,
                ),
                "${Attendees.EVENT_ID} IN ($placeholders) AND ${Attendees.ATTENDEE_RELATIONSHIP}=?",
                arrayOf(*eventIds.map(Long::toString).toTypedArray(), Attendees.RELATIONSHIP_ATTENDEE.toString()),
                null,
            ) ?: throw CalendarProviderAccessException()
        return cursor.use {
            buildMap<Long, MutableList<ProviderAttendee>> {
                while (it.moveToNext()) {
                    val eventId = it.getLong(it.getColumnIndexOrThrow(Attendees.EVENT_ID))
                    getOrPut(eventId, ::mutableListOf) +=
                        ProviderAttendee(
                            email = it.getString(it.getColumnIndexOrThrow(Attendees.ATTENDEE_EMAIL)),
                            name = it.nullableString(Attendees.ATTENDEE_NAME),
                            role = it.getInt(it.getColumnIndexOrThrow(Attendees.ATTENDEE_TYPE)).toProviderRole(),
                            status = it.getInt(it.getColumnIndexOrThrow(Attendees.ATTENDEE_STATUS)).toProviderStatus(),
                        )
                }
            }
        }
    }

    private fun queryReminders(eventIds: Set<Long>): Map<Long, Int> {
        if (eventIds.isEmpty()) return emptyMap()
        val placeholders = List(eventIds.size) { "?" }.joinToString(",")
        val cursor =
            contentResolver.query(
                Reminders.CONTENT_URI,
                arrayOf(Reminders.EVENT_ID, Reminders.MINUTES, Reminders.METHOD),
                "${Reminders.EVENT_ID} IN ($placeholders) AND ${Reminders.METHOD}=?",
                arrayOf(*eventIds.map(Long::toString).toTypedArray(), Reminders.METHOD_ALERT.toString()),
                null,
            ) ?: throw CalendarProviderAccessException()
        return cursor.use {
            buildMap {
                while (it.moveToNext()) {
                    val eventId = it.getLong(it.getColumnIndexOrThrow(Reminders.EVENT_ID))
                    if (eventId in this) throw CalendarProviderAccessException()
                    put(eventId, it.getInt(it.getColumnIndexOrThrow(Reminders.MINUTES)))
                }
            }
        }
    }

    override fun applyBatch(plan: CalendarProviderBatchPlan) {
        val operations = ArrayList<ContentProviderOperation>(plan.operations.size)
        plan.operations.forEach { operation -> operations += operation.toAndroidOperation() }
        try {
            contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
        } catch (_: TransactionTooLargeException) {
            throw CalendarProviderTransactionTooLargeException()
        } catch (error: RemoteException) {
            throw CalendarProviderAccessException(error)
        } catch (error: android.content.OperationApplicationException) {
            throw CalendarProviderAccessException(error)
        } catch (error: OperationCanceledException) {
            throw CalendarProviderAccessException(error)
        } catch (error: IllegalArgumentException) {
            throw CalendarProviderAccessException(error)
        }
    }

    private fun CalendarProviderBatchOperation.toAndroidOperation(): ContentProviderOperation =
        when (this) {
            is CalendarProviderBatchOperation.EventInsert ->
                ContentProviderOperation.newInsert(syncAdapterUri(Events.CONTENT_URI))
                    .withValues(values.toContentValues().withCalendar(calendarId))
                    .build()
            is CalendarProviderBatchOperation.EventUpdate ->
                ContentProviderOperation.newUpdate(syncAdapterUri(Events.CONTENT_URI))
                    .withSelection(
                        "${BaseColumns._ID}=? AND ${Events.CALENDAR_ID}=?",
                        arrayOf(eventId.toString(), calendarId.toString()),
                    )
                    .withValues(values.toContentValues())
                    .withExpectedCount(1)
                    .build()
            is CalendarProviderBatchOperation.EventDelete ->
                ContentProviderOperation.newDelete(syncAdapterUri(Events.CONTENT_URI))
                    .withSelection(
                        "${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID}=?",
                        arrayOf(calendarId.toString(), syncId),
                    )
                    .build()
            is CalendarProviderBatchOperation.AttendeesDelete ->
                ContentProviderOperation.newDelete(syncAdapterUri(Attendees.CONTENT_URI))
                    .withSelection(
                        "${Attendees.EVENT_ID}=? AND ${Attendees.ATTENDEE_RELATIONSHIP}<>?",
                        arrayOf(eventId.toString(), Attendees.RELATIONSHIP_ORGANIZER.toString()),
                    )
                    .build()
            is CalendarProviderBatchOperation.OrganizerDelete ->
                ContentProviderOperation.newDelete(syncAdapterUri(Attendees.CONTENT_URI))
                    .withOrganizerSelection(event)
                    .build()
            is CalendarProviderBatchOperation.AttendeeInsert ->
                ContentProviderOperation.newInsert(syncAdapterUri(Attendees.CONTENT_URI))
                    .withValues(values.toContentValues())
                    .withEventValue(Attendees.EVENT_ID, event)
                    .build()
            is CalendarProviderBatchOperation.RemindersDelete ->
                ContentProviderOperation.newDelete(syncAdapterUri(Reminders.CONTENT_URI))
                    .withEventSelection(event, "${Reminders.EVENT_ID}=?", 0)
                    .build()
            is CalendarProviderBatchOperation.ReminderInsert ->
                ContentProviderOperation.newInsert(syncAdapterUri(Reminders.CONTENT_URI))
                    .withValues(values.toContentValues())
                    .withEventValue(Reminders.EVENT_ID, event)
                    .build()
            is CalendarProviderBatchOperation.ExceptionsDelete ->
                ContentProviderOperation.newDelete(syncAdapterUri(Events.CONTENT_URI))
                    .withSelection(
                        "${Events.CALENDAR_ID}=? AND ${Events.ORIGINAL_ID}=?",
                        arrayOf(calendarId.toString(), seriesId.toString()),
                    )
                    .build()
            is CalendarProviderBatchOperation.ExceptionInsert ->
                ContentProviderOperation.newInsert(syncAdapterUri(Events.CONTENT_URI))
                    .withValues(values.toContentValues().withCalendar(calendarId))
                    .withEventValue(Events.ORIGINAL_ID, series)
                    .build()
            is CalendarProviderBatchOperation.ExceptionResponseUpdate ->
                ContentProviderOperation.newUpdate(syncAdapterUri(Events.CONTENT_URI))
                    .withSelection(
                        "${Events.CALENDAR_ID}=? AND ${Events.ORIGINAL_ID}=? AND " +
                            "${CalendarProviderField.ORIGINAL_INSTANCE_TIME}=?",
                        arrayOf(
                            calendarId.toString(),
                            seriesId.toString(),
                            originalInstance.toEpochMilli().toString(),
                        ),
                    )
                    .withValues(values.toContentValues())
                    .withExpectedCount(1)
                    .build()
        }

    private fun ContentProviderOperation.Builder.withEventValue(
        column: String,
        reference: EventReference,
    ): ContentProviderOperation.Builder =
        when (reference) {
            is EventReference.Existing -> withValue(column, reference.eventId)
            is EventReference.Inserted -> withValueBackReference(column, reference.operationIndex)
        }

    private fun ContentProviderOperation.Builder.withEventSelection(
        reference: EventReference,
        selection: String,
        argumentIndex: Int,
    ): ContentProviderOperation.Builder =
        when (reference) {
            is EventReference.Existing -> withSelection(selection, arrayOf(reference.eventId.toString()))
            is EventReference.Inserted ->
                withSelection(selection, arrayOf("0"))
                    .withSelectionBackReference(argumentIndex, reference.operationIndex)
        }

    private fun ContentProviderOperation.Builder.withOrganizerSelection(
        reference: EventReference,
    ): ContentProviderOperation.Builder =
        when (reference) {
            is EventReference.Existing ->
                withSelection(
                    "${Attendees.EVENT_ID}=? AND ${Attendees.ATTENDEE_RELATIONSHIP}=?",
                    arrayOf(reference.eventId.toString(), Attendees.RELATIONSHIP_ORGANIZER.toString()),
                )
            is EventReference.Inserted ->
                withSelection(
                    "${Attendees.EVENT_ID}=? AND ${Attendees.ATTENDEE_RELATIONSHIP}=?",
                    arrayOf("0", Attendees.RELATIONSHIP_ORGANIZER.toString()),
                ).withSelectionBackReference(0, reference.operationIndex)
        }

    private fun syncAdapterUri(uri: android.net.Uri): android.net.Uri =
        uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, OwnedCalendarIdentity.ACCOUNT_NAME)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, OwnedCalendarIdentity.ACCOUNT_TYPE)
            .build()

    private fun Map<String, Any?>.toContentValues(): ContentValues =
        ContentValues(size).apply {
            this@toContentValues.forEach { (key, value) ->
                when (value) {
                    null -> putNull(key)
                    is String -> put(key, value)
                    is Int -> put(key, value)
                    is Long -> put(key, value)
                    else -> throw CalendarProviderAccessException()
                }
            }
        }

    private fun ContentValues.withCalendar(calendarId: Long): ContentValues =
        apply { put(Events.CALENDAR_ID, calendarId) }

    private companion object {
        val EVENT_IDENTITY_PROJECTION =
            arrayOf(
                BaseColumns._ID,
                Events.CALENDAR_ID,
                Events._SYNC_ID,
                CalendarProviderField.DIRTY,
                CalendarProviderField.UID,
                CalendarProviderField.TITLE,
                CalendarProviderField.DESCRIPTION,
                CalendarProviderField.LOCATION,
                CalendarProviderField.START,
                CalendarProviderField.END,
                CalendarProviderField.DURATION,
                CalendarProviderField.ALL_DAY,
                CalendarProviderField.EVENT_TIME_ZONE,
                CalendarProviderField.RECURRENCE_RULE,
                CalendarProviderField.ORGANIZER_EMAIL,
                CalendarProviderField.STATUS,
                CalendarProviderField.AVAILABILITY,
                CalendarProviderField.SELF_ATTENDEE_STATUS,
                CalendarProviderField.EVENT_COLOR,
                CalendarProviderField.ACCESS_LEVEL,
                CalendarProviderField.RESPONSE_TYPE,
                CalendarProviderField.MEETING_STATUS,
                CalendarProviderField.RESPONSE_REQUESTED,
                CalendarProviderField.SERVER_AVAILABILITY,
            )
        val EXCEPTION_RESPONSE_PROJECTION =
            arrayOf(
                Events.ORIGINAL_ID,
                CalendarProviderField.ORIGINAL_INSTANCE_TIME,
                CalendarProviderField.DIRTY,
                CalendarProviderField.RESPONSE_TYPE,
                CalendarProviderField.MEETING_STATUS,
                CalendarProviderField.SERVER_AVAILABILITY,
                CalendarProviderField.EXCEPTION_RESPONSE_OVERRIDE,
                CalendarProviderField.EXCEPTION_DELETED,
            )
    }
}

private fun Cursor.toProviderExceptionResponseSnapshot(): ProviderCalendarException =
    ProviderCalendarException(
        originalInstance =
            Instant.ofEpochMilli(
                getLong(getColumnIndexOrThrow(CalendarProviderField.ORIGINAL_INSTANCE_TIME)),
            ),
        deleted = requiredBooleanField(CalendarProviderField.EXCEPTION_DELETED),
        title = ActiveSyncField.Absent,
        description = ActiveSyncField.Absent,
        location = ActiveSyncField.Absent,
        start = ActiveSyncField.Absent,
        end = ActiveSyncField.Absent,
        reminderMinutes = ActiveSyncField.Absent,
        attendees = ActiveSyncField.Absent,
        meetingStatus =
            intField(CalendarProviderField.MEETING_STATUS) { value ->
                ActiveSyncCalendarValueParsers.parseMeetingStatus(value.toString())
            },
        responseType =
            intEnumField(CalendarProviderField.RESPONSE_TYPE, ActiveSyncResponseType.entries) { it.wireValue },
        responseRequested = ActiveSyncField.Absent,
        serverAvailability =
            intEnumField(CalendarProviderField.SERVER_AVAILABILITY, ActiveSyncAvailability.entries) { it.wireValue },
        accessLevel = ActiveSyncField.Absent,
        status = ActiveSyncField.Absent,
        availability = ActiveSyncField.Absent,
        selfStatus = ActiveSyncField.Absent,
        eventColor = ActiveSyncField.Absent,
        responseTypeOverride =
            optionalIntEnumField(
                CalendarProviderField.EXCEPTION_RESPONSE_OVERRIDE,
                ActiveSyncResponseType.entries,
            ) { it.wireValue },
    )

private fun Cursor.requiredBooleanField(column: String): Boolean {
    val index = getColumnIndexOrThrow(column)
    if (isNull(index)) throw CalendarMirrorResetRequiredException()
    return getInt(index) != 0
}

private fun Cursor.toProviderSnapshot(): ProviderEvent {
    val syncId = getString(getColumnIndexOrThrow(Events._SYNC_ID))
    val start = instantField(CalendarProviderField.START)
    val end =
        when (val direct = instantField(CalendarProviderField.END)) {
            ActiveSyncField.Empty -> {
                val startInstant = (start as? ActiveSyncField.Value)?.value
                val duration = nullableString(CalendarProviderField.DURATION)?.parseProviderDurationMillis()
                if (startInstant != null && duration != null) ActiveSyncField.Value(startInstant.plusMillis(duration))
                else ActiveSyncField.Empty
            }
            else -> direct
        }
    return ProviderEvent(
        syncId = syncId,
        uid = stringField(CalendarProviderField.UID),
        title = stringField(CalendarProviderField.TITLE),
        description = stringField(CalendarProviderField.DESCRIPTION),
        location = stringField(CalendarProviderField.LOCATION),
        start = start,
        end = end,
        allDay = intField(CalendarProviderField.ALL_DAY) { value -> value != 0 },
        timeZone = ActiveSyncField.Absent,
        recurrenceRule = stringField(CalendarProviderField.RECURRENCE_RULE),
        exceptions = ActiveSyncField.Absent,
        organizerEmail = stringField(CalendarProviderField.ORGANIZER_EMAIL),
        organizerName = ActiveSyncField.Absent,
        attendees = ActiveSyncField.Absent,
        meetingStatus =
            intField(CalendarProviderField.MEETING_STATUS) { value ->
                ActiveSyncCalendarValueParsers.parseMeetingStatus(value.toString())
            },
        responseType = intEnumField(CalendarProviderField.RESPONSE_TYPE, ActiveSyncResponseType.entries) { it.wireValue },
        responseRequested = intField(CalendarProviderField.RESPONSE_REQUESTED) { value -> value != 0 },
        serverAvailability =
            intEnumField(CalendarProviderField.SERVER_AVAILABILITY, ActiveSyncAvailability.entries) { it.wireValue },
        status = intEnumField(CalendarProviderField.STATUS, ProviderEventStatus.entries) { it.providerValue },
        availability =
            intField(CalendarProviderField.AVAILABILITY) { value ->
                when (value) {
                    ProviderInteger.BUSY -> ProviderAvailability.BUSY
                    ProviderInteger.FREE -> ProviderAvailability.FREE
                    ProviderInteger.TENTATIVE_AVAILABILITY -> ProviderAvailability.TENTATIVE
                    else -> throw CalendarProviderAccessException()
                }
            },
        selfStatus = selfStatusField(CalendarProviderField.SELF_ATTENDEE_STATUS),
        eventColor = intField(CalendarProviderField.EVENT_COLOR) { it },
        accessLevel = intEnumField(CalendarProviderField.ACCESS_LEVEL, ProviderAccessLevel.entries) { it.providerValue },
        reminderMinutes = ActiveSyncField.Absent,
    )
}

private fun Cursor.stringField(column: String): ActiveSyncField<String> =
    nullableString(column)?.let { value -> ActiveSyncField.Value(value) } ?: ActiveSyncField.Empty

private fun Cursor.instantField(column: String): ActiveSyncField<Instant> {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) ActiveSyncField.Empty else ActiveSyncField.Value(Instant.ofEpochMilli(getLong(index)))
}

private fun <T> Cursor.intField(column: String, transform: (Int) -> T): ActiveSyncField<T> {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) ActiveSyncField.Empty else ActiveSyncField.Value(transform(getInt(index)))
}

private fun Cursor.selfStatusField(column: String): ActiveSyncField<ProviderSelfStatus> {
    val index = getColumnIndexOrThrow(column)
    if (isNull(index)) return ActiveSyncField.Empty
    return when (val value = getInt(index)) {
        ProviderInteger.NONE_ATTENDEE -> ActiveSyncField.Empty
        ProviderInteger.ACCEPTED_ATTENDEE -> ActiveSyncField.Value(ProviderSelfStatus.ACCEPTED)
        ProviderInteger.DECLINED_ATTENDEE -> ActiveSyncField.Value(ProviderSelfStatus.DECLINED)
        ProviderInteger.INVITED_ATTENDEE -> ActiveSyncField.Value(ProviderSelfStatus.INVITED)
        ProviderInteger.TENTATIVE_ATTENDEE -> ActiveSyncField.Value(ProviderSelfStatus.TENTATIVE)
        else -> throw CalendarProviderAccessException()
    }
}

private fun <T> Cursor.intEnumField(
    column: String,
    values: List<T>,
    wireValue: (T) -> Int,
): ActiveSyncField<T> =
    intField(column) { raw ->
        values.singleOrNull { value -> wireValue(value) == raw } ?: throw CalendarProviderAccessException()
    }

private fun <T> Cursor.optionalIntEnumField(
    column: String,
    values: List<T>,
    wireValue: (T) -> Int,
): ActiveSyncField<T> {
    val index = getColumnIndexOrThrow(column)
    if (isNull(index)) return ActiveSyncField.Absent
    val raw = getInt(index)
    return ActiveSyncField.Value(
        values.singleOrNull { value -> wireValue(value) == raw } ?: throw CalendarProviderAccessException(),
    )
}

private fun Cursor.nullableString(column: String): String? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getString(index)
}

private fun String.parseProviderDurationMillis(): Long? =
    removePrefix("PT").removeSuffix("S").takeIf { this.startsWith("PT") && this.endsWith("S") }
        ?.toLongOrNull()?.times(1_000)

private val ProviderEventStatus.providerValue: Int
    get() = when (this) {
        ProviderEventStatus.TENTATIVE -> ProviderInteger.TENTATIVE_EVENT
        ProviderEventStatus.CONFIRMED -> ProviderInteger.CONFIRMED_EVENT
        ProviderEventStatus.CANCELLED -> ProviderInteger.CANCELLED_EVENT
    }

private val ProviderAccessLevel.providerValue: Int
    get() = when (this) {
        ProviderAccessLevel.CONFIDENTIAL -> ProviderInteger.CONFIDENTIAL_ACCESS
        ProviderAccessLevel.PRIVATE -> ProviderInteger.PRIVATE_ACCESS
        ProviderAccessLevel.PUBLIC -> ProviderInteger.PUBLIC_ACCESS
    }

private fun Int.toProviderRole(): ProviderAttendeeRole =
    when (this) {
        ProviderInteger.UNSPECIFIED_ATTENDEE -> ProviderAttendeeRole.UNSPECIFIED
        ProviderInteger.REQUIRED_ATTENDEE -> ProviderAttendeeRole.REQUIRED
        ProviderInteger.OPTIONAL_ATTENDEE -> ProviderAttendeeRole.OPTIONAL
        ProviderInteger.RESOURCE_ATTENDEE -> ProviderAttendeeRole.RESOURCE
        else -> throw CalendarProviderAccessException()
    }

private fun Int.toProviderStatus(): ProviderAttendeeStatus =
    when (this) {
        ProviderInteger.NONE_ATTENDEE -> ProviderAttendeeStatus.NONE
        ProviderInteger.ACCEPTED_ATTENDEE -> ProviderAttendeeStatus.ACCEPTED
        ProviderInteger.DECLINED_ATTENDEE -> ProviderAttendeeStatus.DECLINED
        ProviderInteger.INVITED_ATTENDEE -> ProviderAttendeeStatus.INVITED
        ProviderInteger.TENTATIVE_ATTENDEE -> ProviderAttendeeStatus.TENTATIVE
        else -> throw CalendarProviderAccessException()
    }
