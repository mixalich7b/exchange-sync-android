package net.mixalich7b.exchangesync.infrastructure.calendar

import android.database.Cursor
import java.time.Instant
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarItem
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarException
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncCalendarMutation
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncField
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncAttendee
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncAttendeeStatus
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncAttendeeType
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncAvailability
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncMeetingStatus
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncRecurrence
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncRecurrenceEnd
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncRecurrenceType
import net.mixalich7b.exchangesync.core.calendar.ProviderEvent
import net.mixalich7b.exchangesync.core.calendar.ProviderCalendarException
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.connection.ConnectionProfileRepository
import net.mixalich7b.exchangesync.core.sync.LocalPageOutcome
import net.mixalich7b.exchangesync.core.sync.RemoteCalendarPage
import net.mixalich7b.exchangesync.core.sync.SyncCheckpoints
import net.mixalich7b.exchangesync.core.sync.SyncFence
import net.mixalich7b.exchangesync.core.sync.SyncPhase
import net.mixalich7b.exchangesync.core.sync.SyncProblem
import net.mixalich7b.exchangesync.core.sync.SyncState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AndroidOwnedCalendarAdapterTest {
    @Test
    fun `provider NONE self-attendee status is accepted as an ordinary event state`() {
        val snapshot = readProviderSnapshot(providerCursor(selfAttendeeStatus = ProviderInteger.NONE_ATTENDEE))

        assertEquals(ActiveSyncField.Empty, snapshot.selfStatus)
    }

    @Test
    fun `exception response snapshot retains deletion and response inputs`() {
        val snapshot = readProviderExceptionResponseSnapshot(exceptionResponseCursor())

        assertTrue(snapshot.deleted)
        assertEquals(
            ActiveSyncField.Value(ActiveSyncMeetingStatus(3, true, true, false)),
            snapshot.meetingStatus,
        )
        assertEquals(ActiveSyncField.Value(ActiveSyncAvailability.BUSY), snapshot.serverAvailability)
    }

    @Test
    fun `exception response snapshot without deletion marker requests a full reset`() {
        assertThrows(CalendarMirrorResetRequiredException::class.java) {
            readProviderExceptionResponseSnapshot(exceptionResponseCursor(deletedMarker = null))
        }
    }

    @Test
    fun `recreated calendar during incremental sync requests full reset before applying additions`() = runTest {
        val gateway =
            RecordingCalendarGateway(
                resolution = OwnedCalendarResolution(OWNED_CALENDAR, -13_408_615, wasRecreated = true),
            )
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = gateway,
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                isFullSyncRequired = { false },
            )
        val page =
            RemoteCalendarPage(
                listOf(ActiveSyncCalendarMutation.Upsert(addition("new-event"), true)),
                SyncCheckpoints.EMPTY,
                false,
            )

        assertEquals(LocalPageOutcome.FullResetRequired, adapter.applyPage(SyncFence(1, 1), page))
        assertTrue(gateway.applied.isEmpty())
    }

    @Test
    fun `recreated calendar is populated when a full reset is already active`() = runTest {
        val gateway =
            RecordingCalendarGateway(
                resolution = OwnedCalendarResolution(OWNED_CALENDAR, -13_408_615, wasRecreated = true),
            )
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = gateway,
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                isFullSyncRequired = { true },
            )
        val page =
            RemoteCalendarPage(
                listOf(ActiveSyncCalendarMutation.Upsert(addition("full-event"), true)),
                SyncCheckpoints.EMPTY,
                false,
            )

        assertEquals(LocalPageOutcome.Applied, adapter.applyPage(SyncFence(1, 1), page))
        assertEquals(1, gateway.applied.size)
    }

    @Test
    fun `recreated calendar is safe only before the first full-sync page is committed`() {
        val fence = SyncFence(4, 7)
        val beforeFirstPage =
            SyncState.initial().copy(
                enabled = true,
                generation = fence.generation,
                runToken = fence.runToken,
                fullSyncRequired = true,
                phase = SyncPhase.APPLYING,
            )

        assertTrue(OwnedCalendarRecreationPolicy.canPopulate(beforeFirstPage, fence))
        assertFalse(
            OwnedCalendarRecreationPolicy.canPopulate(
                beforeFirstPage.copy(checkpoints = SyncCheckpoints(collectionSyncKey = "page-1")),
                fence,
            ),
        )
    }

    @Test
    fun `adapter resolves owned calendar queries only page identities and applies one batch`() = runTest {
        val gateway = RecordingCalendarGateway()
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = gateway,
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
            )
        val page =
            RemoteCalendarPage(
                changes =
                    listOf(
                        ActiveSyncCalendarMutation.Upsert(addition("one"), true),
                        ActiveSyncCalendarMutation.Delete("two", false),
                    ),
                nextCheckpoints = SyncCheckpoints.EMPTY,
                moreAvailable = false,
            )

        val outcome = adapter.applyPage(SyncFence(2, 4), page)

        assertEquals(LocalPageOutcome.Applied, outcome)
        assertEquals(listOf(PROFILE.email), gateway.resolvedEmails)
        assertEquals(listOf(OWNED_CALENDAR to setOf("one", "two")), gateway.queries)
        assertEquals(1, gateway.applied.size)
        assertTrue(gateway.applied.single().operations.all { it.calendarId == OWNED_CALENDAR })
    }

    @Test
    fun `transaction-too-large and permanent provider failures remain distinguishable`() = runTest {
        val tooLarge = RecordingCalendarGateway(applyFailure = CalendarProviderTransactionTooLargeException())
        val permanent = RecordingCalendarGateway(applyFailure = CalendarProviderAccessException())
        val page = RemoteCalendarPage(listOf(ActiveSyncCalendarMutation.Upsert(addition("one"), true)), SyncCheckpoints.EMPTY, false)

        val tooLargeOutcome = adapter(tooLarge).applyPage(SyncFence(1, 1), page)
        val permanentOutcome = adapter(permanent).applyPage(SyncFence(1, 1), page)

        assertEquals(LocalPageOutcome.TransactionTooLarge, tooLargeOutcome)
        assertEquals(LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER), permanentOutcome)
    }

    @Test
    fun `obsolete fence immediately before the provider batch prevents every calendar write`() = runTest {
        val gateway = RecordingCalendarGateway()
        var checks = 0
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = gateway,
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                isFenceCurrent = { ++checks == 1 },
            )
        val page = RemoteCalendarPage(listOf(ActiveSyncCalendarMutation.Upsert(addition("one"), true)), SyncCheckpoints.EMPTY, false)

        val outcome = adapter.applyPage(SyncFence(1, 1), page)

        assertEquals(LocalPageOutcome.Obsolete, outcome)
        assertFalse(gateway.applied.isNotEmpty())
    }

    @Test
    fun `owned calendar resolver failure is reported as a permanent provider problem`() = runTest {
        val gateway = RecordingCalendarGateway(resolveFailure = OwnedCalendarProviderException("query failed"))
        val page = RemoteCalendarPage(listOf(ActiveSyncCalendarMutation.Upsert(addition("one"), true)), SyncCheckpoints.EMPTY, false)

        val outcome = adapter(gateway).applyPage(SyncFence(1, 1), page)

        assertEquals(LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER), outcome)
    }

    @Test
    fun `permission revoked at the provider write boundary reports calendar permission`() = runTest {
        val gateway = RecordingCalendarGateway(applyFailure = SecurityException("revoked"))
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = gateway,
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                hasCalendarAccess = { false },
            )
        val page = RemoteCalendarPage(listOf(ActiveSyncCalendarMutation.Upsert(addition("one"), true)), SyncCheckpoints.EMPTY, false)

        val outcome = adapter.applyPage(SyncFence(1, 1), page)

        assertEquals(LocalPageOutcome.Failed(SyncProblem.CALENDAR_PERMISSION), outcome)
    }

    @Test
    fun `missing profile cannot create or mutate a calendar`() = runTest {
        val gateway = RecordingCalendarGateway()
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(null),
                gateway = gateway,
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
            )

        val outcome = adapter.applyPage(SyncFence(1, 1), RemoteCalendarPage(emptyList(), SyncCheckpoints.EMPTY, false))

        assertEquals(LocalPageOutcome.Failed(SyncProblem.PROTOCOL_DATA), outcome)
        assertTrue(gateway.resolvedEmails.isEmpty())
        assertTrue(gateway.applied.isEmpty())
        assertTrue(adapter.deleteOwnedCalendar())
    }

    @Test
    fun `disabled current cleanup fence may delete while stale cleanup fence cannot`() = runTest {
        val currentGateway = RecordingCalendarGateway()
        val current =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = currentGateway,
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                isFenceCurrent = { false },
                isCleanupFenceCurrent = { true },
            )
        val staleGateway = RecordingCalendarGateway()
        val stale =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = staleGateway,
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                isCleanupFenceCurrent = { false },
            )

        assertTrue(current.deleteOwnedCalendar(SyncFence(3, 7)))
        assertFalse(stale.deleteOwnedCalendar(SyncFence(2, 6)))
        assertEquals(1, currentGateway.deleteCalls)
        assertEquals(0, staleGateway.deleteCalls)
    }

    @Test
    fun `replaying an addition upserts one event and replaces every child collection`() = runTest {
        val gateway = ReplayCalendarGateway()
        val adapter = adapter(gateway)
        val item =
            addition("one").copy(
                attendees =
                    ActiveSyncField.Value(
                        listOf(
                            ActiveSyncAttendee(
                                "guest@example.test",
                                "Guest",
                                ActiveSyncAttendeeStatus.ACCEPTED,
                                ActiveSyncAttendeeType.REQUIRED,
                            ),
                        ),
                    ),
                reminderMinutes = ActiveSyncField.Value(10),
                recurrence =
                    ActiveSyncField.Value(
                        ActiveSyncRecurrence(ActiveSyncRecurrenceType.DAILY, 1, end = ActiveSyncRecurrenceEnd.Infinite),
                    ),
                exceptions =
                    ActiveSyncField.Value(
                        listOf(
                            ActiveSyncCalendarException(
                                Instant.parse("2026-08-10T09:00:00Z"),
                                deleted = false,
                            ),
                        ),
                    ),
            )
        val page = RemoteCalendarPage(listOf(ActiveSyncCalendarMutation.Upsert(item, true)), SyncCheckpoints.EMPTY, false)

        assertEquals(LocalPageOutcome.Applied, adapter.applyPage(SyncFence(1, 1), page))
        assertEquals(LocalPageOutcome.Applied, adapter.applyPage(SyncFence(1, 1), page))

        assertEquals(setOf("one"), gateway.eventSyncIds)
        assertEquals(2, gateway.attendeeRows)
        assertEquals(1, gateway.reminderRows)
        assertEquals(1, gateway.exceptionRows)
        assertTrue(gateway.plans[0].operations.first() is CalendarProviderBatchOperation.EventInsert)
        assertTrue(gateway.plans[1].operations.first() is CalendarProviderBatchOperation.EventUpdate)
    }

    private fun adapter(gateway: RecordingCalendarGateway): AndroidOwnedCalendarAdapter =
        AndroidOwnedCalendarAdapter(
            profileRepository = ProfileRepository(PROFILE),
            gateway = gateway,
            timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
        )

    private fun adapter(gateway: OwnedCalendarProviderGateway): AndroidOwnedCalendarAdapter =
        AndroidOwnedCalendarAdapter(
            profileRepository = ProfileRepository(PROFILE),
            gateway = gateway,
            timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
        )

    private fun addition(serverId: String): ActiveSyncCalendarItem =
        ActiveSyncCalendarItem(
            serverId = serverId,
            start = ActiveSyncField.Value(Instant.parse("2026-08-09T09:00:00Z")),
            end = ActiveSyncField.Value(Instant.parse("2026-08-09T10:00:00Z")),
            allDay = ActiveSyncField.Value(false),
        )

    private fun readProviderSnapshot(cursor: Cursor): ProviderEvent {
        val method =
            Class.forName("net.mixalich7b.exchangesync.infrastructure.calendar.AndroidOwnedCalendarAdapterKt")
                .getDeclaredMethod("toProviderSnapshot", Cursor::class.java)
                .apply { isAccessible = true }
        return try {
            method.invoke(null, cursor) as ProviderEvent
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun readProviderExceptionResponseSnapshot(cursor: Cursor): ProviderCalendarException {
        val method =
            Class.forName("net.mixalich7b.exchangesync.infrastructure.calendar.AndroidOwnedCalendarAdapterKt")
                .getDeclaredMethod("toProviderExceptionResponseSnapshot", Cursor::class.java)
                .apply { isAccessible = true }
        return try {
            method.invoke(null, cursor) as ProviderCalendarException
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun exceptionResponseCursor(deletedMarker: Int? = 1): Cursor =
        cursor(
            linkedMapOf(
                "originalInstanceTime" to Instant.parse("2026-08-10T09:00:00Z").toEpochMilli(),
                "sync_data2" to 0,
                "sync_data3" to 3,
                "sync_data5" to ActiveSyncAvailability.BUSY.wireValue,
                "sync_data6" to null,
                "sync_data7" to deletedMarker,
            ),
        )

    private fun providerCursor(selfAttendeeStatus: Int): Cursor {
        val values =
            linkedMapOf<String, Any?>(
                "_sync_id" to "ordinary-event",
                "uid2445" to null,
                "title" to "Ordinary event",
                "description" to null,
                "eventLocation" to null,
                "dtstart" to Instant.parse("2026-08-09T09:00:00Z").toEpochMilli(),
                "dtend" to Instant.parse("2026-08-09T10:00:00Z").toEpochMilli(),
                "duration" to null,
                "allDay" to 0,
                "rrule" to null,
                "organizer" to null,
                "eventStatus" to ProviderInteger.CONFIRMED_EVENT,
                "availability" to ProviderInteger.BUSY,
                "selfAttendeeStatus" to selfAttendeeStatus,
                "eventColor" to null,
                "accessLevel" to ProviderInteger.PUBLIC_ACCESS,
                "sync_data2" to null,
                "sync_data3" to null,
                "sync_data4" to null,
                "sync_data5" to null,
            )
        return cursor(values)
    }

    private fun cursor(values: LinkedHashMap<String, Any?>): Cursor {
        val columns = values.keys.toList()
        return Proxy.newProxyInstance(
            Cursor::class.java.classLoader,
            arrayOf(Cursor::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getColumnIndexOrThrow" -> columns.indexOf(arguments?.single() as String).also { check(it >= 0) }
                "isNull" -> values.getValue(columns[arguments?.single() as Int]) == null
                "getString" -> values.getValue(columns[arguments?.single() as Int]) as String
                "getLong" -> (values.getValue(columns[arguments?.single() as Int]) as Number).toLong()
                "getInt" -> (values.getValue(columns[arguments?.single() as Int]) as Number).toInt()
                "toString" -> "ProviderCursor"
                else -> error("Unexpected Cursor call: ${method.name}")
            }
        } as Cursor
    }

    private class ProfileRepository(private val profile: ConnectionProfile?) : ConnectionProfileRepository {
        override suspend fun load(): ConnectionProfile? = profile

        override suspend fun replace(profile: ConnectionProfile) = Unit
    }

    private class RecordingCalendarGateway(
        private val applyFailure: RuntimeException? = null,
        private val resolveFailure: RuntimeException? = null,
        private val resolution: OwnedCalendarResolution = OwnedCalendarResolution(OWNED_CALENDAR, -13_408_615),
    ) : OwnedCalendarProviderGateway {
        val resolvedEmails = mutableListOf<String>()
        val queries = mutableListOf<Pair<Long, Set<String>>>()
        val applied = mutableListOf<CalendarProviderBatchPlan>()
        var deleteCalls: Int = 0

        override fun resolveOwned(profileEmail: String): OwnedCalendarResolution {
            resolveFailure?.let { throw it }
            resolvedEmails += profileEmail
            return resolution
        }

        override fun deleteAllOwned(): Boolean {
            deleteCalls += 1
            return true
        }

        override fun queryExisting(calendarId: Long, syncIds: Set<String>): List<ExistingProviderEvent> {
            queries += calendarId to syncIds
            return emptyList()
        }

        override fun applyBatch(plan: CalendarProviderBatchPlan) {
            applyFailure?.let { throw it }
            applied += plan
        }
    }

    private class ReplayCalendarGateway : OwnedCalendarProviderGateway {
        val eventSyncIds = linkedSetOf<String>()
        val plans = mutableListOf<CalendarProviderBatchPlan>()
        var attendeeRows = 0
        var reminderRows = 0
        var exceptionRows = 0

        override fun resolveOwned(profileEmail: String): OwnedCalendarResolution =
            OwnedCalendarResolution(OWNED_CALENDAR, -13_408_615)

        override fun deleteAllOwned(): Boolean = true

        override fun queryExisting(calendarId: Long, syncIds: Set<String>): List<ExistingProviderEvent> =
            syncIds.filter(eventSyncIds::contains).map { ExistingProviderEvent(71, calendarId, it) }

        override fun applyBatch(plan: CalendarProviderBatchPlan) {
            plans += plan
            plan.operations.forEach { operation ->
                when (operation) {
                    is CalendarProviderBatchOperation.EventInsert ->
                        eventSyncIds += checkNotNull(operation.values[CalendarProviderField.SYNC_ID] as? String)
                    is CalendarProviderBatchOperation.AttendeesDelete -> attendeeRows = 0
                    is CalendarProviderBatchOperation.AttendeeInsert -> attendeeRows += 1
                    is CalendarProviderBatchOperation.RemindersDelete -> reminderRows = 0
                    is CalendarProviderBatchOperation.ReminderInsert -> reminderRows += 1
                    is CalendarProviderBatchOperation.ExceptionsDelete -> exceptionRows = 0
                    is CalendarProviderBatchOperation.ExceptionInsert -> exceptionRows += 1
                    else -> Unit
                }
            }
        }
    }

    private companion object {
        const val OWNED_CALENDAR = 12L
        val PROFILE = ConnectionProfile("calendar@example.test", "DOMAIN\\user", "mail.example.test", "cert")
    }
}
