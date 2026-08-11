package net.mixalich7b.exchangesync.infrastructure.calendar

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.content.pm.PackageManager
import android.provider.BaseColumns
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import net.mixalich7b.exchangesync.core.sync.OwnedCalendarCleanupOutcome
import net.mixalich7b.exchangesync.core.sync.OwnedCalendarCleanupTrigger
import net.mixalich7b.exchangesync.core.sync.OwnedCalendarPort
import net.mixalich7b.exchangesync.core.sync.RemoteCalendarPage
import net.mixalich7b.exchangesync.core.sync.SyncFence
import net.mixalich7b.exchangesync.core.sync.SyncProblem
import net.mixalich7b.exchangesync.core.sync.SyncState
import net.mixalich7b.exchangesync.core.sync.SyncStateRepository
import net.mixalich7b.exchangesync.core.sync.SyncStateTransitions
import net.mixalich7b.exchangesync.core.sync.SynchronizationMutationLock
import net.mixalich7b.exchangesync.infrastructure.activesync.calendar.ActiveSyncCalendarValueParsers
import net.mixalich7b.exchangesync.infrastructure.diagnostics.AndroidLogcatDiagnosticSink
import net.mixalich7b.exchangesync.infrastructure.diagnostics.CleanupTrigger
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnosticEvent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnostics
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticComponent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticAttendeeRepresentation
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperationKind
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticProviderCallOutcome
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticProviderFailureCause
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticSeverity
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticStage
import net.mixalich7b.exchangesync.infrastructure.diagnostics.OwnedCalendarAction

internal class CalendarProviderTransactionTooLargeException : RuntimeException()

internal enum class CalendarProviderFailureCause {
    ACCESS,
    REMOTE,
    OPERATION_APPLICATION,
    OPERATION_CANCELLED,
    INVALID_ARGUMENT,
    INVALID_REQUEST,
    INVALID_RESULT,
    INVALID_REFERENCE,
    UNSUPPORTED_VALUE,
    UNEXPECTED,
}

internal class CalendarProviderAccessException(
    cause: Throwable? = null,
    val failureCause: CalendarProviderFailureCause = CalendarProviderFailureCause.ACCESS,
) : RuntimeException(cause)

internal interface OwnedCalendarProviderGateway {
    fun resolveOwned(profileEmail: String): OwnedCalendarResolution

    fun deleteAllOwned(): OwnedCalendarCleanupResult

    fun queryExisting(
        calendarId: Long,
        syncIds: Set<String>,
    ): List<ExistingProviderEvent>

    fun applySubBatch(subBatch: CalendarProviderSubBatch): CalendarProviderSubBatchResult
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
    private val diagnostics: DeviceDiagnostics = DeviceDiagnostics(),
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
        diagnostics = DeviceDiagnostics(AndroidLogcatDiagnosticSink()),
    )

    override suspend fun deleteOwnedCalendar(
        fence: SyncFence?,
        trigger: OwnedCalendarCleanupTrigger,
    ): OwnedCalendarCleanupOutcome =
        withContext(Dispatchers.IO) {
            mutationLock.withLock {
                if (fence != null && !isCleanupFenceCurrent(fence)) {
                    return@withLock OwnedCalendarCleanupOutcome.Obsolete
                }
                val operation =
                    diagnostics.operation(
                        DiagnosticOperationKind.SYNCHRONIZATION,
                        fence?.generation,
                        fence?.runToken,
                    )
                val cleanupTrigger = CleanupTrigger.valueOf(trigger.name)
                try {
                    val result = gateway.deleteAllOwned()
                    diagnostics.emit(
                        DeviceDiagnosticEvent(
                            severity =
                                if (result.completed) {
                                    DiagnosticSeverity.INFO
                                } else {
                                    DiagnosticSeverity.ERROR
                                },
                            component = DiagnosticComponent.CALENDAR,
                            stage = DiagnosticStage.CLEANUP,
                            operation = operation,
                            ownershipAction =
                                if (result.deletedRowCount == 0) {
                                    OwnedCalendarAction.UNCHANGED
                                } else {
                                    OwnedCalendarAction.DELETED
                                },
                            inputCount = result.ownedRowCount,
                            attemptedOperationCount = result.ownedRowCount,
                            appliedOperationCount = result.deletedRowCount,
                            cleanupTrigger = cleanupTrigger,
                            failureCategory = SyncProblem.CALENDAR_PROVIDER.name.takeUnless { result.completed },
                            outcome = if (result.completed) "success" else "failure",
                        ),
                    )
                    if (result.completed) {
                        OwnedCalendarCleanupOutcome.Completed
                    } else {
                        OwnedCalendarCleanupOutcome.Failed(SyncProblem.CALENDAR_PROVIDER)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: OwnedCalendarProviderException) {
                    emitProviderFailure(
                        operation,
                        DiagnosticStage.CLEANUP,
                        SyncProblem.CALENDAR_PROVIDER,
                        failure,
                        cleanupTrigger = cleanupTrigger,
                    )
                    OwnedCalendarCleanupOutcome.Failed(SyncProblem.CALENDAR_PROVIDER)
                } catch (failure: CalendarProviderAccessException) {
                    emitProviderFailure(
                        operation,
                        DiagnosticStage.CLEANUP,
                        SyncProblem.CALENDAR_PROVIDER,
                        failure,
                        cleanupTrigger = cleanupTrigger,
                    )
                    OwnedCalendarCleanupOutcome.Failed(SyncProblem.CALENDAR_PROVIDER)
                } catch (failure: SecurityException) {
                    emitProviderFailure(
                        operation,
                        DiagnosticStage.CLEANUP,
                        SyncProblem.CALENDAR_PERMISSION,
                        failure,
                        cleanupTrigger = cleanupTrigger,
                    )
                    OwnedCalendarCleanupOutcome.Failed(SyncProblem.CALENDAR_PERMISSION)
                } catch (failure: RuntimeException) {
                    emitProviderFailure(
                        operation,
                        DiagnosticStage.CLEANUP,
                        SyncProblem.CALENDAR_PROVIDER,
                        failure,
                        cleanupTrigger = cleanupTrigger,
                    )
                    OwnedCalendarCleanupOutcome.Failed(SyncProblem.CALENDAR_PROVIDER)
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
        val operation =
            diagnostics.operation(
                DiagnosticOperationKind.SYNCHRONIZATION,
                fence.generation,
                fence.runToken,
            )
        var stage = DiagnosticStage.OWNERSHIP
        var attemptedOperations: Int? = null
        var confirmedOperations = 0
        var activeSubBatch: CalendarProviderSubBatch? = null
        return try {
            val owned = callCalendarProvider { gateway.resolveOwned(profile.email) }
            diagnostics.emit(
                DeviceDiagnosticEvent(
                    severity = DiagnosticSeverity.INFO,
                    component = DiagnosticComponent.CALENDAR,
                    stage = DiagnosticStage.OWNERSHIP,
                    operation = operation,
                    ownershipAction = owned.action,
                    inputCount = page.changes.size,
                    outcome = "success",
                ),
            )
            if (owned.wasRecreated && !isFullSyncRequired(fence)) {
                throw CalendarMirrorResetRequiredException()
            }
            stage = DiagnosticStage.PROVIDER_QUERY
            val syncIds = page.changes.mapTo(linkedSetOf(), CalendarChangeIdentity::requireSyncId)
            val existing = callCalendarProvider { gateway.queryExisting(owned.calendarId, syncIds) }
            stage = DiagnosticStage.EVENT_MAP
            val pagePlan = CalendarPagePlanner.plan(page, owned, existing)
            diagnostics.emit(
                DeviceDiagnosticEvent(
                    severity = DiagnosticSeverity.INFO,
                    component = DiagnosticComponent.CALENDAR,
                    stage = DiagnosticStage.EVENT_MAP,
                    operation = operation,
                    inputCount = page.changes.size,
                    acceptedCount = page.changes.size,
                    rejectedCount = 0,
                    plannedOperationCount = pagePlan.operations.size,
                    outcome = "success",
                ),
            )
            stage = DiagnosticStage.PROVIDER_BATCH
            val batchPlan = CalendarProviderBatchPlanner.plan(pagePlan, timeZoneResolver)
            attemptedOperations = batchPlan.operations.size
            batchPlan.attendeeSuppressions.forEach { suppression ->
                diagnostics.emit(
                    DeviceDiagnosticEvent(
                        severity = DiagnosticSeverity.INFO,
                        component = DiagnosticComponent.CALENDAR,
                        stage = DiagnosticStage.ATTENDEE_SUPPRESSION,
                        operation = operation,
                        outcome = "suppressed",
                        attendeeLimit = MAX_MATERIALIZED_NON_ORGANIZER_ATTENDEES,
                        attendeeInputCount = suppression.inputCount,
                        attendeeOmittedCount = suppression.inputCount,
                        attendeeRepresentation =
                            if (suppression.organizerRetained) {
                                DiagnosticAttendeeRepresentation.ORGANIZER_ONLY
                            } else {
                                DiagnosticAttendeeRepresentation.EMPTY
                            },
                    ),
                )
            }
            val cursor = CalendarProviderSubBatchCursor(batchPlan)
            while (true) {
                val subBatch = cursor.next() ?: break
                currentCoroutineContext().ensureActive()
                if (!isFenceCurrent(fence)) return LocalPageOutcome.Obsolete
                activeSubBatch = subBatch
                val result = callCalendarProvider { gateway.applySubBatch(subBatch) }
                cursor.record(result)
                confirmedOperations += result.appliedOperationCount
                diagnostics.emit(
                    DeviceDiagnosticEvent(
                        severity = DiagnosticSeverity.INFO,
                        component = DiagnosticComponent.CALENDAR,
                        stage = DiagnosticStage.PROVIDER_BATCH,
                        operation = operation,
                        attemptedOperationCount = subBatch.operations.size,
                        appliedOperationCount = result.appliedOperationCount,
                        providerOperationCount = subBatch.totalOperationCount,
                        subBatchCount = subBatch.totalSubBatchCount,
                        subBatchOrdinal = subBatch.ordinal,
                        subBatchOperationCount = subBatch.operations.size,
                        confirmedOperationCount = confirmedOperations,
                        providerCallOutcome = DiagnosticProviderCallOutcome.CONFIRMED,
                        outcome = "success",
                    ),
                )
                activeSubBatch = null
            }
            diagnostics.emit(
                DeviceDiagnosticEvent(
                    severity = DiagnosticSeverity.INFO,
                    component = DiagnosticComponent.CALENDAR,
                    stage = DiagnosticStage.PROVIDER_BATCH,
                    operation = operation,
                    attemptedOperationCount = attemptedOperations,
                    appliedOperationCount = confirmedOperations,
                    providerOperationCount = attemptedOperations,
                    subBatchCount =
                        (attemptedOperations + MAX_PROVIDER_OPERATIONS_PER_SUB_BATCH - 1) /
                            MAX_PROVIDER_OPERATIONS_PER_SUB_BATCH,
                    confirmedOperationCount = confirmedOperations,
                    providerCallOutcome =
                        DiagnosticProviderCallOutcome.CONFIRMED.takeIf { attemptedOperations > 0 },
                    outcome = "page_success",
                ),
            )
            LocalPageOutcome.Applied
        } catch (failure: CalendarProviderTransactionTooLargeException) {
            emitProviderFailure(
                operation,
                stage,
                SyncProblem.CALENDAR_PROVIDER,
                failure,
                attemptedOperationCount = attemptedOperations ?: 0,
                appliedOperationCount = confirmedOperations,
                activeSubBatch = activeSubBatch,
                confirmedOperationCount = confirmedOperations,
            )
            LocalPageOutcome.TransactionTooLarge
        } catch (failure: CalendarMirrorResetRequiredException) {
            emitProviderFailure(operation, stage, SyncProblem.PROTOCOL_DATA, failure, failure.serverId)
            LocalPageOutcome.FullResetRequired
        } catch (failure: CalendarMappingException) {
            emitProviderFailure(
                operation,
                stage,
                SyncProblem.PROTOCOL_DATA,
                failure,
                inputCount = page.changes.size,
                acceptedCount = 0,
                rejectedCount = 1,
                attemptedOperationCount = attemptedOperations,
            )
            LocalPageOutcome.Failed(SyncProblem.PROTOCOL_DATA)
        } catch (failure: CalendarPlanningException) {
            val failedIndex = failure.serverId?.let { serverId -> page.indexOfServerId(serverId) }
            if (failure.serverId == null) {
                emitProviderFailure(
                    operation,
                    stage,
                    SyncProblem.PROTOCOL_DATA,
                    failure,
                    inputCount = page.changes.size,
                    acceptedCount = failedIndex?.coerceAtLeast(0),
                    rejectedCount = 1,
                    attemptedOperationCount = attemptedOperations,
                )
            } else {
                emitProviderFailure(
                    operation,
                    stage,
                    SyncProblem.PROTOCOL_DATA,
                    failure,
                    serverId = failure.serverId,
                )
                emitProgressFailure(
                    operation = operation,
                    stage = stage,
                    problem = SyncProblem.PROTOCOL_DATA,
                    inputCount = page.changes.size,
                    acceptedCount = failedIndex?.coerceAtLeast(0),
                    rejectedCount = 1,
                    attemptedOperationCount = attemptedOperations,
                )
            }
            LocalPageOutcome.Failed(SyncProblem.PROTOCOL_DATA)
        } catch (failure: OwnedCalendarProviderException) {
            emitProviderFailure(
                operation,
                stage,
                SyncProblem.CALENDAR_PROVIDER,
                failure,
                attemptedOperationCount = attemptedOperations,
            )
            LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER)
        } catch (failure: CalendarProviderAccessException) {
            emitProviderFailure(
                operation,
                stage,
                SyncProblem.CALENDAR_PROVIDER,
                failure,
                attemptedOperationCount = attemptedOperations ?: 0,
                appliedOperationCount = confirmedOperations,
                activeSubBatch = activeSubBatch,
                confirmedOperationCount = confirmedOperations,
            )
            LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER)
        } catch (failure: SecurityException) {
            val problem = if (hasCalendarAccess()) SyncProblem.CALENDAR_PROVIDER else SyncProblem.CALENDAR_PERMISSION
            emitProviderFailure(
                operation,
                stage,
                problem,
                failure,
                attemptedOperationCount = attemptedOperations ?: 0,
                appliedOperationCount = confirmedOperations,
                activeSubBatch = activeSubBatch,
                confirmedOperationCount = confirmedOperations,
            )
            LocalPageOutcome.Failed(problem)
        }
    }

    private fun emitProviderFailure(
        operation: net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperation,
        stage: DiagnosticStage,
        problem: SyncProblem,
        throwable: Throwable,
        serverId: String? = null,
        inputCount: Int? = null,
        acceptedCount: Int? = null,
        rejectedCount: Int? = null,
        attemptedOperationCount: Int? = null,
        appliedOperationCount: Int? = null,
        activeSubBatch: CalendarProviderSubBatch? = null,
        confirmedOperationCount: Int? = null,
        cleanupTrigger: CleanupTrigger? = null,
    ) {
        diagnostics.emit(
            DeviceDiagnosticEvent(
                DiagnosticSeverity.ERROR,
                DiagnosticComponent.CALENDAR,
                stage,
                operation,
                reasonCode = throwable.javaClass.simpleName,
                failureCategory = problem.name,
                serverId = serverId,
                inputCount = inputCount,
                acceptedCount = acceptedCount,
                rejectedCount = rejectedCount,
                attemptedOperationCount = activeSubBatch?.operations?.size ?: attemptedOperationCount,
                appliedOperationCount = appliedOperationCount ?: attemptedOperationCount?.let { 0 },
                providerOperationCount = activeSubBatch?.totalOperationCount,
                subBatchCount = activeSubBatch?.totalSubBatchCount,
                subBatchOrdinal = activeSubBatch?.ordinal,
                subBatchOperationCount = activeSubBatch?.operations?.size,
                confirmedOperationCount = confirmedOperationCount,
                providerCallOutcome = activeSubBatch?.let { DiagnosticProviderCallOutcome.UNKNOWN },
                providerFailureCause =
                    activeSubBatch?.let {
                        when (throwable) {
                            is CalendarProviderTransactionTooLargeException ->
                                DiagnosticProviderFailureCause.TRANSACTION_TOO_LARGE
                            is CalendarProviderAccessException ->
                                DiagnosticProviderFailureCause.valueOf(throwable.failureCause.name)
                            is SecurityException -> DiagnosticProviderFailureCause.SECURITY
                            else -> DiagnosticProviderFailureCause.ACCESS
                        }
                    },
                cleanupTrigger = cleanupTrigger,
                outcome = "failure",
                throwable = throwable,
            ),
        )
    }

    private fun emitProgressFailure(
        operation: net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticOperation,
        stage: DiagnosticStage,
        problem: SyncProblem,
        inputCount: Int,
        acceptedCount: Int?,
        rejectedCount: Int,
        attemptedOperationCount: Int?,
    ) {
        diagnostics.emit(
            DeviceDiagnosticEvent(
                severity = DiagnosticSeverity.ERROR,
                component = DiagnosticComponent.CALENDAR,
                stage = stage,
                operation = operation,
                reasonCode = "PROGRESS_SUMMARY",
                failureCategory = problem.name,
                inputCount = inputCount,
                acceptedCount = acceptedCount,
                rejectedCount = rejectedCount,
                attemptedOperationCount = attemptedOperationCount,
                appliedOperationCount = attemptedOperationCount?.let { 0 },
                outcome = "failure",
            ),
        )
    }

    private inline fun <T> callCalendarProvider(block: () -> T): T =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: SecurityException) {
            throw failure
        } catch (failure: CalendarProviderTransactionTooLargeException) {
            throw failure
        } catch (failure: CalendarProviderAccessException) {
            throw failure
        } catch (failure: OwnedCalendarProviderException) {
            throw failure
        } catch (failure: RuntimeException) {
            throw CalendarProviderAccessException(failure, CalendarProviderFailureCause.UNEXPECTED)
        }

    private fun RemoteCalendarPage.indexOfServerId(serverId: String): Int =
        changes.indexOfFirst { change ->
            runCatching { CalendarChangeIdentity.requireSyncId(change) }.getOrNull() == serverId
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
    private val subBatchGateway =
        AndroidCalendarProviderSubBatchGateway(AndroidContentResolverBatchExecutor(contentResolver))

    override fun resolveOwned(profileEmail: String): OwnedCalendarResolution = resolver.resolve(profileEmail)

    override fun deleteAllOwned(): OwnedCalendarCleanupResult = resolver.deleteAllOwned()

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

    override fun applySubBatch(subBatch: CalendarProviderSubBatch): CalendarProviderSubBatchResult =
        subBatchGateway.apply(subBatch)

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
