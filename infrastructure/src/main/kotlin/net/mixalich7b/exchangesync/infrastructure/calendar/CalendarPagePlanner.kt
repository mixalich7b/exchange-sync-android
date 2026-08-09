package net.mixalich7b.exchangesync.infrastructure.calendar

import net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarMutation
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncField
import net.mixalich7b.exchangesync.core.calendar.CalendarEventMapper
import net.mixalich7b.exchangesync.core.calendar.ProviderCalendarMutation
import net.mixalich7b.exchangesync.core.calendar.ProviderEvent
import net.mixalich7b.exchangesync.core.sync.RemoteCalendarPage

internal class CalendarPlanningException(
    message: String,
    val serverId: String? = null,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause) {
    fun withServerId(value: String): CalendarPlanningException =
        if (serverId != null) this else CalendarPlanningException(message.orEmpty(), value, this)
}

internal class CalendarMirrorResetRequiredException(
    val serverId: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(null, cause) {
    fun withServerId(value: String): CalendarMirrorResetRequiredException =
        if (serverId != null) this else CalendarMirrorResetRequiredException(value, this)
}

internal data class ExistingProviderEvent(
    val eventId: Long,
    val calendarId: Long,
    val syncId: String,
    val snapshot: ProviderEvent? = null,
    val providerTimeZone: String? = null,
    val isDirty: Boolean = false,
)

internal sealed interface CalendarEventPlan {
    val calendarId: Long

    data class Upsert(
        override val calendarId: Long,
        val eventId: Long?,
        val event: ProviderEvent,
        val isAddition: Boolean,
        val replaceOrganizer: Boolean,
        val replaceAttendees: Boolean,
        val replaceReminders: Boolean,
        val replaceExceptions: Boolean,
        val refreshExceptionResponses: Boolean,
        val providerTimeZone: String?,
    ) : CalendarEventPlan

    data class Delete(
        override val calendarId: Long,
        val syncId: String,
    ) : CalendarEventPlan
}

internal class CalendarPagePlan private constructor(
    val calendarId: Long,
    val operations: List<CalendarEventPlan>,
) {
    companion object {
        fun create(calendarId: Long, operations: List<CalendarEventPlan>): CalendarPagePlan {
            if (calendarId < 0 || operations.any { operation -> operation.calendarId != calendarId }) {
                throw CalendarPlanningException("Calendar page plan escaped the owned calendar")
            }
            return CalendarPagePlan(calendarId, operations)
        }
    }
}

internal object CalendarPagePlanner {
    fun plan(
        page: RemoteCalendarPage,
        owned: OwnedCalendarResolution,
        existingEvents: List<ExistingProviderEvent>,
    ): CalendarPagePlan {
        val existingOwned = existingEvents.filter { event -> event.calendarId == owned.calendarId }
        if (existingOwned.any(ExistingProviderEvent::isDirty)) {
            throw CalendarMirrorResetRequiredException()
        }
        val bySyncId = existingOwned.groupBy(ExistingProviderEvent::syncId)
        if (bySyncId.values.any { matches -> matches.size > 1 }) {
            throw CalendarPlanningException("Owned calendar has duplicate ServerId rows")
        }
        val existingBySyncId = bySyncId.mapValues { (_, values) -> values.single() }
        val operations =
            page.changes.map { change ->
                val mutation = change as? ActiveSyncCalendarMutation
                    ?: throw CalendarPlanningException("Calendar page contains an unsupported change")
                val serverId = CalendarPlanIdentity.syncId(mutation)
                try {
                    val existing = existingBySyncId[serverId]
                    when (val mapped = CalendarEventMapper.map(mutation, owned.color, existing?.snapshot)) {
                        is ProviderCalendarMutation.Delete ->
                            CalendarEventPlan.Delete(owned.calendarId, mapped.syncId)
                        is ProviderCalendarMutation.Upsert -> {
                            val source = mutation as ActiveSyncCalendarMutation.Upsert
                            if (!mapped.isAddition && existing == null) {
                                throw CalendarMirrorResetRequiredException(serverId)
                            }
                            CalendarEventPlan.Upsert(
                                calendarId = owned.calendarId,
                                eventId = existing?.eventId,
                                event = mapped.event,
                                isAddition = mapped.isAddition,
                                replaceOrganizer =
                                    source.item.organizerEmail != ActiveSyncField.Absent ||
                                        source.item.organizerName != ActiveSyncField.Absent,
                                replaceAttendees = source.item.attendees != ActiveSyncField.Absent,
                                replaceReminders = source.item.reminderMinutes != ActiveSyncField.Absent,
                                replaceExceptions =
                                    source.item.exceptions != ActiveSyncField.Absent,
                                refreshExceptionResponses =
                                    source.item.exceptions == ActiveSyncField.Absent &&
                                        source.item.responseType != ActiveSyncField.Absent &&
                                        existing?.snapshot?.exceptions is ActiveSyncField.Value,
                                providerTimeZone = existing?.providerTimeZone,
                            )
                        }
                    }
                } catch (failure: CalendarMirrorResetRequiredException) {
                    throw failure.withServerId(serverId)
                } catch (failure: CalendarPlanningException) {
                    throw failure.withServerId(serverId)
                } catch (failure: IllegalArgumentException) {
                    throw CalendarPlanningException("Calendar event cannot be mapped", serverId, failure)
                }
            }
        return CalendarPagePlan.create(owned.calendarId, operations)
    }
}

private object CalendarPlanIdentity {
    fun syncId(mutation: ActiveSyncCalendarMutation): String =
        when (mutation) {
            is ActiveSyncCalendarMutation.Delete -> mutation.serverId
            is ActiveSyncCalendarMutation.Upsert -> mutation.item.serverId
        }
}
