package net.mixalich7b.exchangesync.infrastructure.calendar

import android.database.Cursor
import java.time.Instant
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncSystemTime
import net.mixalich7b.exchangesync.core.calendar.ActiveSyncTimeZone
import net.mixalich7b.exchangesync.core.calendar.CalendarEventMapper
import net.mixalich7b.exchangesync.core.calendar.ProviderEvent
import net.mixalich7b.exchangesync.core.calendar.ProviderCalendarException
import net.mixalich7b.exchangesync.core.calendar.ProviderCalendarMutation
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.connection.ConnectionProfileRepository
import net.mixalich7b.exchangesync.core.sync.LocalPageOutcome
import net.mixalich7b.exchangesync.core.sync.OwnedCalendarCleanupOutcome
import net.mixalich7b.exchangesync.core.sync.RemoteCalendarPage
import net.mixalich7b.exchangesync.core.sync.SyncCheckpoints
import net.mixalich7b.exchangesync.core.sync.SyncFence
import net.mixalich7b.exchangesync.core.sync.SyncPhase
import net.mixalich7b.exchangesync.core.sync.SyncProblem
import net.mixalich7b.exchangesync.core.sync.SyncState
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnosticEvent
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnostics
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticAttendeeRepresentation
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarField
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarFieldSource
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticCalendarRule
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticFieldValue
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticRelationship
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticProviderCallOutcome
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticProviderFailureCause
import net.mixalich7b.exchangesync.infrastructure.diagnostics.DiagnosticStage
import net.mixalich7b.exchangesync.infrastructure.diagnostics.OwnedCalendarAction
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
    fun `a page of at most fifty operations uses exactly one provider call`() = runTest {
        val gateway = RecordingCalendarGateway()

        val outcome = adapter(gateway).applyPage(SyncFence(2, 4), deletePage(50))

        assertEquals(LocalPageOutcome.Applied, outcome)
        assertEquals(listOf(50), gateway.applied.map { subBatch -> subBatch.operations.size })
    }

    @Test
    fun `larger pages use consecutive provider calls capped at fifty and apply only after the last`() = runTest {
        val gateway = RecordingCalendarGateway()

        val outcome = adapter(gateway).applyPage(SyncFence(2, 4), deletePage(121))

        assertEquals(LocalPageOutcome.Applied, outcome)
        assertEquals(listOf(50, 50, 21), gateway.applied.map { subBatch -> subBatch.operations.size })
    }

    @Test
    fun `an obsolete fence between sub-batches stops before the next provider call`() = runTest {
        val gateway = RecordingCalendarGateway()
        var fenceChecks = 0
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = gateway,
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                isFenceCurrent = { ++fenceChecks <= 3 },
            )

        val outcome = adapter.applyPage(SyncFence(2, 4), deletePage(121))

        assertEquals(LocalPageOutcome.Obsolete, outcome)
        assertEquals(listOf(50, 50), gateway.applied.map { subBatch -> subBatch.operations.size })
        assertEquals(4, fenceChecks)
    }

    @Test
    fun `cancellation between sub-batches is observed before another provider call`() = runTest {
        lateinit var synchronization: kotlinx.coroutines.Job
        val gateway =
            RecordingCalendarGateway(
                onApply = { call -> if (call == 1) synchronization.cancel() },
            )
        synchronization =
            launch(start = CoroutineStart.LAZY) {
                adapter(gateway).applyPage(SyncFence(2, 4), deletePage(121))
            }

        synchronization.start()
        synchronization.join()

        assertTrue(synchronization.isCancelled)
        assertEquals(1, gateway.applyCalls)
    }

    @Test
    fun `failure of a later sub-batch stops immediately and does not report page success`() = runTest {
        val gateway =
            RecordingCalendarGateway(
                applyFailure = CalendarProviderAccessException(),
                applyFailureAtCall = 2,
            )

        val outcome = adapter(gateway).applyPage(SyncFence(2, 4), deletePage(121))

        assertEquals(LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER), outcome)
        assertEquals(2, gateway.applyCalls)
        assertEquals(listOf(50), gateway.applied.map { subBatch -> subBatch.operations.size })
    }

    @Test
    fun `capacity failure of a later bounded sub-batch returns only the confirmed prefix and stops`() = runTest {
        val events = mutableListOf<DeviceDiagnosticEvent>()
        val gateway =
            RecordingCalendarGateway(
                applyFailure = CalendarProviderTransactionTooLargeException(),
                applyFailureAtCall = 2,
            )
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = gateway,
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                diagnostics = DeviceDiagnostics { event -> events += event },
            )

        val outcome = adapter.applyPage(SyncFence(2, 4), deletePage(121))

        assertEquals(LocalPageOutcome.TransactionTooLarge, outcome)
        assertEquals(2, gateway.applyCalls)
        assertEquals(listOf(50), gateway.applied.map { subBatch -> subBatch.operations.size })
        val failure = events.single { event -> event.outcome == "failure" }
        assertEquals(null, failure.appliedOperationCount)
    }

    @Test
    fun `oversized attendee suppression reports counts without identities or event content`() = runTest {
        val events = mutableListOf<DeviceDiagnosticEvent>()
        val attendees =
            List(101) { index ->
                ActiveSyncAttendee(
                    "secret-attendee-$index@example.test",
                    "Secret Attendee $index",
                    ActiveSyncAttendeeStatus.ACCEPTED,
                    ActiveSyncAttendeeType.REQUIRED,
                )
            }
        val item =
            addition("secret-event-id").copy(
                subject = ActiveSyncField.Value("Private board meeting"),
                organizerEmail = ActiveSyncField.Value("secret-owner@example.test"),
                attendees = ActiveSyncField.Value(attendees),
            )
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = RecordingCalendarGateway(),
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                diagnostics = DeviceDiagnostics { event -> events += event },
            )

        assertEquals(
            LocalPageOutcome.Applied,
            adapter.applyPage(
                SyncFence(2, 4),
                RemoteCalendarPage(
                    listOf(ActiveSyncCalendarMutation.Upsert(item, true)),
                    SyncCheckpoints(collectionSyncKey = "secret-next-key"),
                    false,
                ),
            ),
        )

        val suppression = events.single { event -> event.attendeeOmittedCount != null }
        assertEquals(100, suppression.attendeeLimit)
        assertEquals(101, suppression.attendeeInputCount)
        assertEquals(101, suppression.attendeeOmittedCount)
        assertEquals(DiagnosticAttendeeRepresentation.ORGANIZER_ONLY, suppression.attendeeRepresentation)
        assertEquals(null, suppression.serverId)
        val records = events.toString()
        listOf(
            "secret-event-id",
            "Private board meeting",
            "secret-owner@example.test",
            "secret-attendee-100@example.test",
            "secret-next-key",
        ).forEach { secret -> assertFalse(records.contains(secret), secret) }
    }

    @Test
    fun `provider diagnostics report every confirmed sub-batch and a final page summary`() = runTest {
        val events = mutableListOf<DeviceDiagnosticEvent>()
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = RecordingCalendarGateway(),
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                diagnostics = DeviceDiagnostics { event -> events += event },
            )

        assertEquals(LocalPageOutcome.Applied, adapter.applyPage(SyncFence(2, 4), deletePage(121)))

        val providerEvents = events.filter { event -> event.stage == DiagnosticStage.PROVIDER_BATCH }
        val subBatches = providerEvents.filter { event -> event.subBatchOrdinal != null }
        assertEquals(listOf(1, 2, 3), subBatches.map(DeviceDiagnosticEvent::subBatchOrdinal))
        assertEquals(listOf(50, 50, 21), subBatches.map(DeviceDiagnosticEvent::subBatchOperationCount))
        assertEquals(listOf(50, 100, 121), subBatches.map(DeviceDiagnosticEvent::confirmedOperationCount))
        assertTrue(subBatches.all { event -> event.providerOperationCount == 121 })
        assertTrue(subBatches.all { event -> event.subBatchCount == 3 })
        assertTrue(subBatches.all { event -> event.providerCallOutcome == DiagnosticProviderCallOutcome.CONFIRMED })
        val summary = providerEvents.single { event -> event.subBatchOrdinal == null }
        assertEquals(121, summary.attemptedOperationCount)
        assertEquals(121, summary.confirmedOperationCount)
    }

    @Test
    fun `one-batch completion reports matching attempted and confirmed counts`() = runTest {
        val events = mutableListOf<DeviceDiagnosticEvent>()
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = RecordingCalendarGateway(),
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                diagnostics = DeviceDiagnostics { event -> events += event },
            )

        assertEquals(LocalPageOutcome.Applied, adapter.applyPage(SyncFence(2, 4), deletePage(1)))

        val subBatch = events.single { event -> event.subBatchOrdinal == 1 }
        assertEquals(1, subBatch.subBatchCount)
        assertEquals(1, subBatch.subBatchOperationCount)
        assertEquals(1, subBatch.confirmedOperationCount)
    }

    @Test
    fun `later ambiguous failure reports confirmed prefix without claiming the failed call applied zero`() = runTest {
        val events = mutableListOf<DeviceDiagnosticEvent>()
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway =
                    RecordingCalendarGateway(
                        applyFailure =
                            CalendarProviderAccessException(
                                failureCause = CalendarProviderFailureCause.REMOTE,
                            ),
                        applyFailureAtCall = 2,
                    ),
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                diagnostics = DeviceDiagnostics { event -> events += event },
            )

        assertEquals(
            LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER),
            adapter.applyPage(SyncFence(2, 4), deletePage(121)),
        )

        val unknown = events.filter { event -> event.providerCallOutcome == DiagnosticProviderCallOutcome.UNKNOWN }
        val failure = unknown.single { event -> event.providerOperationSnapshot == null }
        val details = unknown.filter { event -> event.providerOperationSnapshot != null }
        assertEquals(121, failure.providerOperationCount)
        assertEquals(3, failure.subBatchCount)
        assertEquals(2, failure.subBatchOrdinal)
        assertEquals(50, failure.subBatchOperationCount)
        assertEquals(50, failure.confirmedOperationCount)
        assertEquals(null, failure.appliedOperationCount)
        assertEquals(DiagnosticProviderFailureCause.REMOTE, failure.providerFailureCause)
        assertEquals(50, details.size)
        assertEquals((50..99).toList(), details.map { event -> event.providerOperationSnapshot!!.globalOperationIndex })
        assertEquals((0..49).toList(), details.map { event -> event.providerOperationSnapshot!!.subBatchOperationIndex })
        assertTrue(details.all { event -> event.confirmedOperationCount == 50 })
        assertTrue(details.all { event -> event.appliedOperationCount == null })
        assertTrue(events.indexOf(failure) < events.indexOf(details.first()))
    }

    @Test
    fun `empty page reports reused ownership and zero mapper planner and batch counts`() = runTest {
        val events = mutableListOf<DeviceDiagnosticEvent>()
        val gateway = RecordingCalendarGateway()
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = gateway,
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                diagnostics = DeviceDiagnostics { event -> events += event },
            )

        val outcome =
            adapter.applyPage(
                SyncFence(2, 4),
                RemoteCalendarPage(emptyList(), SyncCheckpoints.EMPTY, moreAvailable = false),
            )

        assertEquals(LocalPageOutcome.Applied, outcome)
        val ownership = events.single { event -> event.stage == DiagnosticStage.OWNERSHIP }
        assertEquals(OwnedCalendarAction.REUSED, ownership.ownershipAction)
        val mapping =
            events.single { event ->
                event.stage == DiagnosticStage.EVENT_MAP && event.inputCount != null
            }
        assertEquals(0, mapping.inputCount)
        assertEquals(0, mapping.acceptedCount)
        assertEquals(0, mapping.rejectedCount)
        assertEquals(0, mapping.plannedOperationCount)
        val batch = events.single { event -> event.stage == DiagnosticStage.PROVIDER_BATCH }
        assertEquals(0, batch.attemptedOperationCount)
        assertEquals(0, batch.appliedOperationCount)
        assertEquals(null, batch.providerCallOutcome)
        assertTrue(gateway.applied.isEmpty())
    }

    @Test
    fun `mapping failure reports the rejected input and emits no provider batch summary`() = runTest {
        val events = mutableListOf<DeviceDiagnosticEvent>()
        val gateway = RecordingCalendarGateway()
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = gateway,
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                diagnostics = DeviceDiagnostics { event -> events += event },
            )
        val invalid =
            addition("invalid").copy(
                end = ActiveSyncField.Value(Instant.parse("2026-08-09T08:59:59Z")),
            )

        val outcome =
            adapter.applyPage(
                SyncFence(2, 4),
                RemoteCalendarPage(
                    listOf(ActiveSyncCalendarMutation.Upsert(invalid, true)),
                    SyncCheckpoints.EMPTY,
                    moreAvailable = false,
                ),
            )

        assertEquals(LocalPageOutcome.Failed(SyncProblem.PROTOCOL_DATA), outcome)
        val mapping =
            events.single { event ->
                event.stage == DiagnosticStage.EVENT_MAP && event.inputCount != null
            }
        assertEquals(1, mapping.inputCount)
        assertEquals(0, mapping.acceptedCount)
        assertEquals(1, mapping.rejectedCount)
        assertFalse(events.any { event -> event.stage == DiagnosticStage.PROVIDER_BATCH })
        assertTrue(gateway.applied.isEmpty())
    }

    @Test
    fun `partial Change mapping failure emits correlated effective range detail`() = runTest {
        val events = mutableListOf<DeviceDiagnosticEvent>()
        val previous =
            (CalendarEventMapper.map(
                ActiveSyncCalendarMutation.Upsert(addition("event-1"), true),
                -13_408_615,
            ) as ProviderCalendarMutation.Upsert).event
        val gateway =
            RecordingCalendarGateway(
                existingEvents = listOf(ExistingProviderEvent(31, OWNED_CALENDAR, "event-1", previous)),
            )
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = gateway,
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                diagnostics = DeviceDiagnostics { event -> events += event },
            )
        val partial =
            ActiveSyncCalendarItem(
                serverId = "event-1",
                start = ActiveSyncField.Value(Instant.parse("2026-08-09T10:00:00Z")),
            )

        val outcome =
            adapter.applyPage(
                SyncFence(3, 9),
                RemoteCalendarPage(
                    listOf(ActiveSyncCalendarMutation.Upsert(partial, false)),
                    SyncCheckpoints.EMPTY,
                    moreAvailable = false,
                ),
            )

        assertEquals(LocalPageOutcome.Failed(SyncProblem.PROTOCOL_DATA), outcome)
        val event = events.single { emitted -> emitted.calendarFailureSnapshot != null }
        val snapshot = checkNotNull(event.calendarFailureSnapshot)
        val operation = checkNotNull(event.operation)
        assertEquals(DiagnosticStage.EVENT_MAP, event.stage)
        assertEquals(3, operation.generation)
        assertEquals(9, operation.runToken)
        assertEquals(DiagnosticCalendarRule.EVENT_TIME_RANGE_INVALID, snapshot.rule)
        assertEquals(
            DiagnosticFieldValue.Relationship(DiagnosticRelationship.EQUAL),
            snapshot.fields.single { field ->
                field.source == DiagnosticCalendarFieldSource.DERIVED &&
                    field.field == DiagnosticCalendarField.TIME_RELATIONSHIP
            }.value,
        )
    }

    @Test
    fun `planning failure separates opaque identity detail from aggregate progress`() = runTest {
        val events = mutableListOf<DeviceDiagnosticEvent>()
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = RecordingCalendarGateway(),
                timeZoneResolver = CalendarProviderTimeZoneResolver { null },
                diagnostics = DeviceDiagnostics { event -> events += event },
            )
        val item =
            addition("opaque-server-id").copy(
                timeZone = ActiveSyncField.Value(testTimeZone()),
            )

        val outcome =
            adapter.applyPage(
                SyncFence(2, 4),
                RemoteCalendarPage(
                    listOf(ActiveSyncCalendarMutation.Upsert(item, true)),
                    SyncCheckpoints.EMPTY,
                    moreAvailable = false,
                ),
            )

        assertEquals(LocalPageOutcome.Failed(SyncProblem.PROTOCOL_DATA), outcome)
        val failures =
            events.filter { event ->
                event.stage == DiagnosticStage.PROVIDER_BATCH &&
                    event.failureCategory == SyncProblem.PROTOCOL_DATA.name
            }
        assertEquals(2, failures.size)
        val detail = failures.single { event -> event.serverId != null }
        assertEquals("opaque-server-id", detail.serverId)
        assertEquals(
            DiagnosticCalendarRule.TIME_ZONE_UNREPRESENTABLE,
            checkNotNull(detail.calendarFailureSnapshot).rule,
        )
        assertEquals(null, detail.inputCount)
        assertEquals(null, detail.acceptedCount)
        assertEquals(null, detail.rejectedCount)
        assertEquals(null, detail.attemptedOperationCount)
        val progress = failures.single { event -> event.inputCount != null }
        assertEquals(null, progress.serverId)
        assertEquals(1, progress.inputCount)
        assertEquals(0, progress.acceptedCount)
        assertEquals(1, progress.rejectedCount)
        assertEquals(null, progress.attemptedOperationCount)
    }

    @Test
    fun `provider batch failure reports attempted operations and zero applied operations`() = runTest {
        val events = mutableListOf<DeviceDiagnosticEvent>()
        val gateway = RecordingCalendarGateway(applyFailure = CalendarProviderAccessException())
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = gateway,
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                diagnostics = DeviceDiagnostics { event -> events += event },
            )
        val page =
            RemoteCalendarPage(
                listOf(ActiveSyncCalendarMutation.Upsert(addition("one"), true)),
                SyncCheckpoints.EMPTY,
                moreAvailable = false,
            )

        val outcome = adapter.applyPage(SyncFence(2, 4), page)

        assertEquals(LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER), outcome)
        val providerEvents = events.filter { event -> event.stage == DiagnosticStage.PROVIDER_BATCH }
        val batch = providerEvents.single { event -> event.providerOperationSnapshot == null }
        val details = providerEvents.filter { event -> event.providerOperationSnapshot != null }
        assertEquals(1, batch.attemptedOperationCount)
        assertEquals(null, batch.appliedOperationCount)
        assertEquals(SyncProblem.CALENDAR_PROVIDER.name, batch.failureCategory)
        assertEquals(1, details.size)
        assertEquals(0, details.single().providerOperationSnapshot?.globalOperationIndex)
        assertEquals(0, details.single().providerOperationSnapshot?.subBatchOperationIndex)
        assertEquals(DiagnosticProviderCallOutcome.UNKNOWN, details.single().providerCallOutcome)
        assertEquals("PROVIDER_OPERATION_ATTEMPTED", details.single().reasonCode)
        assertTrue(events.indexOf(batch) < events.indexOf(details.single()))
    }

    @Test
    fun `pre-dispatch failure emits unsubmitted detail without claiming a provider call`() = runTest {
        val events = mutableListOf<DeviceDiagnosticEvent>()
        val gateway =
            RecordingCalendarGateway(
                applyFailure =
                    CalendarProviderAccessException(
                        failureCause = CalendarProviderFailureCause.UNSUPPORTED_VALUE,
                        dispatchState = CalendarProviderDispatchState.NOT_DISPATCHED,
                    ),
            )
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = gateway,
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                diagnostics = DeviceDiagnostics { event -> events += event },
            )
        val page =
            RemoteCalendarPage(
                listOf(ActiveSyncCalendarMutation.Upsert(addition("one"), true)),
                SyncCheckpoints.EMPTY,
                moreAvailable = false,
            )

        assertEquals(
            LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER),
            adapter.applyPage(SyncFence(2, 4), page),
        )

        val providerEvents = events.filter { event -> event.stage == DiagnosticStage.PROVIDER_BATCH }
        val aggregate = providerEvents.single { event -> event.providerOperationSnapshot == null }
        val detail = providerEvents.single { event -> event.providerOperationSnapshot != null }
        assertEquals(0, aggregate.attemptedOperationCount)
        assertEquals(0, aggregate.appliedOperationCount)
        assertEquals(null, aggregate.providerCallOutcome)
        assertEquals(0, detail.attemptedOperationCount)
        assertEquals(0, detail.appliedOperationCount)
        assertEquals(null, detail.providerCallOutcome)
        assertEquals("PROVIDER_OPERATION_NOT_SUBMITTED", detail.reasonCode)
        assertEquals("unsubmitted_operation", detail.outcome)
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
    fun `every provider call failure category emits attempted operation detail`() = runTest {
        val fixtures =
            listOf(
                ProviderFailureFixture(
                    "permanent",
                    CalendarProviderAccessException(failureCause = CalendarProviderFailureCause.ACCESS),
                    LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER),
                    DiagnosticProviderFailureCause.ACCESS,
                ),
                ProviderFailureFixture(
                    "owned-calendar permanent",
                    OwnedCalendarProviderException("provider ownership failure"),
                    LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER),
                    DiagnosticProviderFailureCause.ACCESS,
                ),
                ProviderFailureFixture(
                    "remote",
                    CalendarProviderAccessException(failureCause = CalendarProviderFailureCause.REMOTE),
                    LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER),
                    DiagnosticProviderFailureCause.REMOTE,
                ),
                ProviderFailureFixture(
                    "operation application",
                    CalendarProviderAccessException(
                        failureCause = CalendarProviderFailureCause.OPERATION_APPLICATION,
                    ),
                    LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER),
                    DiagnosticProviderFailureCause.OPERATION_APPLICATION,
                ),
                ProviderFailureFixture(
                    "security",
                    SecurityException("provider rejected access"),
                    LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER),
                    DiagnosticProviderFailureCause.SECURITY,
                ),
                ProviderFailureFixture(
                    "runtime",
                    IllegalStateException("provider runtime failure"),
                    LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER),
                    DiagnosticProviderFailureCause.UNEXPECTED,
                ),
                ProviderFailureFixture(
                    "transaction too large",
                    CalendarProviderTransactionTooLargeException(),
                    LocalPageOutcome.TransactionTooLarge,
                    DiagnosticProviderFailureCause.TRANSACTION_TOO_LARGE,
                ),
            )
        val page =
            RemoteCalendarPage(
                listOf(ActiveSyncCalendarMutation.Upsert(addition("one"), true)),
                SyncCheckpoints.EMPTY,
                false,
            )

        fixtures.forEach { fixture ->
            val events = mutableListOf<DeviceDiagnosticEvent>()
            val gateway = RecordingCalendarGateway(applyFailure = fixture.throwable)
            val adapter =
                AndroidOwnedCalendarAdapter(
                    profileRepository = ProfileRepository(PROFILE),
                    gateway = gateway,
                    timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                    diagnostics = DeviceDiagnostics { event -> events += event },
                )

            val outcome = adapter.applyPage(SyncFence(1, 1), page)

            assertEquals(fixture.outcome, outcome, fixture.label)
            val detail = events.single { event -> event.providerOperationSnapshot != null }
            assertEquals(fixture.diagnosticCause, detail.providerFailureCause, fixture.label)
            assertEquals(DiagnosticProviderCallOutcome.UNKNOWN, detail.providerCallOutcome, fixture.label)
            assertEquals(1, gateway.applyCalls, fixture.label)
        }
    }

    @Test
    fun `provider detail projection and sink failures preserve original outcome and single mutation`() = runTest {
        val page =
            RemoteCalendarPage(
                listOf(ActiveSyncCalendarMutation.Upsert(addition("one"), true)),
                SyncCheckpoints(collectionSyncKey = "next-checkpoint"),
                false,
            )
        val projectionGateway = RecordingCalendarGateway(applyFailure = CalendarProviderAccessException())
        val projectionFailure =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = projectionGateway,
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                providerFailureProjector = { throw IllegalStateException("projection failure") },
            )
        val sinkGateway = RecordingCalendarGateway(applyFailure = CalendarProviderAccessException())
        val sinkFailure =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = sinkGateway,
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                diagnostics = DeviceDiagnostics { throw IllegalStateException("formatting or sink failure") },
            )

        assertEquals(
            LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER),
            projectionFailure.applyPage(SyncFence(1, 1), page),
        )
        assertEquals(
            LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER),
            sinkFailure.applyPage(SyncFence(1, 1), page),
        )
        assertEquals(1, projectionGateway.applyCalls)
        assertEquals(1, sinkGateway.applyCalls)
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
    fun `raw provider runtime failures at every page gateway boundary become permanent provider problems`() = runTest {
        val fixtures =
            listOf(
                RecordingCalendarGateway(resolveFailure = IllegalArgumentException("query rejected")) to
                    DiagnosticStage.OWNERSHIP,
                RecordingCalendarGateway(queryFailure = IllegalStateException("event query failed")) to
                    DiagnosticStage.PROVIDER_QUERY,
                RecordingCalendarGateway(applyFailure = IllegalArgumentException("batch rejected")) to
                    DiagnosticStage.PROVIDER_BATCH,
            )
        val page =
            RemoteCalendarPage(
                listOf(ActiveSyncCalendarMutation.Upsert(addition("one"), true)),
                SyncCheckpoints.EMPTY,
                false,
            )

        fixtures.forEach { (gateway, expectedStage) ->
            val events = mutableListOf<DeviceDiagnosticEvent>()
            val adapter =
                AndroidOwnedCalendarAdapter(
                    profileRepository = ProfileRepository(PROFILE),
                    gateway = gateway,
                    timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                    diagnostics = DeviceDiagnostics { event -> events += event },
                )

            val outcome = adapter.applyPage(SyncFence(1, 1), page)

            assertEquals(LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER), outcome, expectedStage.name)
            val failure =
                events.single { event ->
                    event.stage == expectedStage &&
                        event.outcome == "failure" &&
                        event.providerOperationSnapshot == null
                }
            assertEquals(SyncProblem.CALENDAR_PROVIDER.name, failure.failureCategory, expectedStage.name)
        }
    }

    @Test
    fun `page gateway preserves cooperative cancellation`() {
        val cancellation = CancellationException("cancel provider call")
        val adapter = adapter(RecordingCalendarGateway(applyFailure = cancellation))
        val page =
            RemoteCalendarPage(
                listOf(ActiveSyncCalendarMutation.Upsert(addition("one"), true)),
                SyncCheckpoints.EMPTY,
                false,
            )

        val thrown =
            assertThrows(CancellationException::class.java) {
                runBlocking { adapter.applyPage(SyncFence(1, 1), page) }
            }

        assertEquals(cancellation.message, thrown.message)
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
        assertEquals(OwnedCalendarCleanupOutcome.Completed, adapter.deleteOwnedCalendar())
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

        assertEquals(OwnedCalendarCleanupOutcome.Completed, current.deleteOwnedCalendar(SyncFence(3, 7)))
        assertEquals(OwnedCalendarCleanupOutcome.Obsolete, stale.deleteOwnedCalendar(SyncFence(2, 6)))
        assertEquals(1, currentGateway.deleteCalls)
        assertEquals(0, staleGateway.deleteCalls)
    }

    @Test
    fun `successful cleanup reports owned and deleted row counts without row identities`() = runTest {
        val events = mutableListOf<DeviceDiagnosticEvent>()
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = RecordingCalendarGateway(),
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                diagnostics = DeviceDiagnostics { event -> events += event },
            )

        assertEquals(
            OwnedCalendarCleanupOutcome.Completed,
            adapter.deleteOwnedCalendar(
                SyncFence(4, 7),
                net.mixalich7b.exchangesync.core.sync.OwnedCalendarCleanupTrigger.DISABLE,
            ),
        )

        val cleanup = events.single { event -> event.stage == DiagnosticStage.CLEANUP }
        assertEquals(OwnedCalendarAction.DELETED, cleanup.ownershipAction)
        assertEquals(1, cleanup.inputCount)
        assertEquals(1, cleanup.attemptedOperationCount)
        assertEquals(1, cleanup.appliedOperationCount)
        assertEquals(
            net.mixalich7b.exchangesync.infrastructure.diagnostics.CleanupTrigger.DISABLE,
            cleanup.cleanupTrigger,
        )
        assertEquals("success", cleanup.outcome)
    }

    @Test
    fun `cleanup maps provider runtime access and security failures to actionable diagnostics`() = runTest {
        val fixtures =
            listOf(
                IllegalArgumentException("item URI rejected") to SyncProblem.CALENDAR_PROVIDER,
                IllegalStateException("provider runtime failure") to SyncProblem.CALENDAR_PROVIDER,
                CalendarProviderAccessException() to SyncProblem.CALENDAR_PROVIDER,
                SecurityException("calendar access revoked") to SyncProblem.CALENDAR_PERMISSION,
            )

        fixtures.forEach { (failure, expectedProblem) ->
            val events = mutableListOf<DeviceDiagnosticEvent>()
            val adapter =
                AndroidOwnedCalendarAdapter(
                    profileRepository = ProfileRepository(PROFILE),
                    gateway = RecordingCalendarGateway(deleteFailure = failure),
                    timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                    diagnostics = DeviceDiagnostics { event -> events += event },
                )

            assertEquals(
                OwnedCalendarCleanupOutcome.Failed(expectedProblem),
                adapter.deleteOwnedCalendar(
                    SyncFence(4, 7),
                    net.mixalich7b.exchangesync.core.sync.OwnedCalendarCleanupTrigger.USER_RETRY,
                ),
                failure.javaClass.name,
            )
            val cleanup = events.single { event -> event.stage == DiagnosticStage.CLEANUP }
            assertEquals(expectedProblem.name, cleanup.failureCategory, failure.javaClass.name)
            assertEquals(failure.javaClass.simpleName, cleanup.reasonCode, failure.javaClass.name)
            assertEquals(failure, cleanup.throwable, failure.javaClass.name)
            assertEquals(
                net.mixalich7b.exchangesync.infrastructure.diagnostics.CleanupTrigger.USER_RETRY,
                cleanup.cleanupTrigger,
                failure.javaClass.name,
            )
        }
    }

    @Test
    fun `cleanup preserves cooperative cancellation`() {
        val cancellation = CancellationException("cancel cleanup")
        val adapter =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = RecordingCalendarGateway(deleteFailure = cancellation),
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
            )

        val thrown =
            assertThrows(CancellationException::class.java) {
                runBlocking { adapter.deleteOwnedCalendar(SyncFence(4, 7)) }
            }

        assertEquals(cancellation.message, thrown.message)
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
        assertEquals(2, gateway.reminderRows)
        assertEquals(1, gateway.exceptionRows)
        assertTrue(gateway.plans[0].operations.first() is CalendarProviderBatchOperation.EventInsert)
        assertTrue(gateway.plans[1].operations.first() is CalendarProviderBatchOperation.EventUpdate)
    }

    @Test
    fun `unchanged page replay repairs a confirmed prefix with partial attendees`() = runTest {
        val gateway = ReplayCalendarGateway()
        var fenceChecks = 0
        val interrupted =
            AndroidOwnedCalendarAdapter(
                profileRepository = ProfileRepository(PROFILE),
                gateway = gateway,
                timeZoneResolver = CalendarProviderTimeZoneResolver { "UTC" },
                isFenceCurrent = { ++fenceChecks <= 2 },
            )
        val page = replaySeriesPage()

        assertEquals(LocalPageOutcome.Obsolete, interrupted.applyPage(SyncFence(1, 1), page))
        assertEquals(1, gateway.topLevelEventCount)
        assertTrue(gateway.attendeeRows in 1 until 200)

        assertEquals(LocalPageOutcome.Applied, adapter(gateway).applyPage(SyncFence(1, 1), page))

        assertReplayConverged(gateway)
    }

    @Test
    fun `unchanged page replay repairs attendees reminders and exceptions after ambiguous failure`() = runTest {
        val gateway = ReplayCalendarGateway(failOnCall = 3, failAfterApply = true)
        val page = replaySeriesPage()

        assertEquals(
            LocalPageOutcome.Failed(SyncProblem.CALENDAR_PROVIDER),
            adapter(gateway).applyPage(SyncFence(1, 1), page),
        )
        assertEquals(1, gateway.topLevelEventCount)
        assertEquals(1, gateway.exceptionRows)
        assertTrue(gateway.attendeeRows in 101 until 200)
        assertEquals(1, gateway.reminderRows)

        gateway.clearFailure()
        assertEquals(LocalPageOutcome.Applied, adapter(gateway).applyPage(SyncFence(1, 1), page))

        assertReplayConverged(gateway)
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

    private fun deletePage(count: Int): RemoteCalendarPage =
        RemoteCalendarPage(
            changes = List(count) { index -> ActiveSyncCalendarMutation.Delete("event-$index", soft = false) },
            nextCheckpoints = SyncCheckpoints.EMPTY,
            moreAvailable = false,
        )

    private fun replaySeriesPage(): RemoteCalendarPage {
        val attendees =
            ActiveSyncField.Value(
                List(100) { index ->
                    ActiveSyncAttendee(
                        "guest-$index@example.test",
                        "Guest $index",
                        ActiveSyncAttendeeStatus.ACCEPTED,
                        ActiveSyncAttendeeType.REQUIRED,
                    )
                },
            )
        val item =
            addition("large-series").copy(
                organizerEmail = ActiveSyncField.Value("owner@example.test"),
                organizerName = ActiveSyncField.Value("Owner"),
                attendees = attendees,
                reminderMinutes = ActiveSyncField.Value(10),
                recurrence =
                    ActiveSyncField.Value(
                        ActiveSyncRecurrence(ActiveSyncRecurrenceType.DAILY, 1, end = ActiveSyncRecurrenceEnd.Infinite),
                    ),
                exceptions =
                    ActiveSyncField.Value(
                        listOf(
                            ActiveSyncCalendarException(
                                instanceStart = Instant.parse("2026-08-10T09:00:00Z"),
                                deleted = false,
                                attendees = attendees,
                                reminderMinutes = ActiveSyncField.Value(5),
                            ),
                        ),
                    ),
            )
        return RemoteCalendarPage(
            listOf(ActiveSyncCalendarMutation.Upsert(item, isAddition = true)),
            SyncCheckpoints(collectionSyncKey = "next-key"),
            moreAvailable = false,
        )
    }

    private fun assertReplayConverged(gateway: ReplayCalendarGateway) {
        assertEquals(1, gateway.topLevelEventCount)
        assertEquals(setOf("large-series"), gateway.eventSyncIds)
        assertEquals(2, gateway.organizerRows)
        assertEquals(200, gateway.attendeeRows)
        assertEquals(2, gateway.reminderRows)
        assertEquals(1, gateway.exceptionRows)
    }

    private fun testTimeZone(): ActiveSyncTimeZone =
        ActiveSyncTimeZone(
            biasMinutes = -180,
            standardName = "Russian Standard Time",
            standardTransition = ActiveSyncSystemTime(0, 0, 0, 0, 0, 0, 0, 0),
            standardBiasMinutes = 0,
            daylightName = "Russian Daylight Time",
            daylightTransition = ActiveSyncSystemTime(0, 0, 0, 0, 0, 0, 0, 0),
            daylightBiasMinutes = 0,
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

    private data class ProviderFailureFixture(
        val label: String,
        val throwable: RuntimeException,
        val outcome: LocalPageOutcome,
        val diagnosticCause: DiagnosticProviderFailureCause,
    )

    private class RecordingCalendarGateway(
        private val applyFailure: RuntimeException? = null,
        private val applyFailureAtCall: Int = 1,
        private val onApply: (Int) -> Unit = {},
        private val resolveFailure: RuntimeException? = null,
        private val queryFailure: RuntimeException? = null,
        private val deleteFailure: Throwable? = null,
        private val resolution: OwnedCalendarResolution = OwnedCalendarResolution(OWNED_CALENDAR, -13_408_615),
        private val existingEvents: List<ExistingProviderEvent> = emptyList(),
    ) : OwnedCalendarProviderGateway {
        val resolvedEmails = mutableListOf<String>()
        val queries = mutableListOf<Pair<Long, Set<String>>>()
        val applied = mutableListOf<CalendarProviderSubBatch>()
        var applyCalls: Int = 0
        var deleteCalls: Int = 0

        override fun resolveOwned(profileEmail: String): OwnedCalendarResolution {
            resolveFailure?.let { throw it }
            resolvedEmails += profileEmail
            return resolution
        }

        override fun deleteAllOwned(): OwnedCalendarCleanupResult {
            deleteFailure?.let { throw it }
            deleteCalls += 1
            return OwnedCalendarCleanupResult(ownedRowCount = 1, deletedRowCount = 1)
        }

        override fun queryExisting(calendarId: Long, syncIds: Set<String>): List<ExistingProviderEvent> {
            queryFailure?.let { throw it }
            queries += calendarId to syncIds
            return existingEvents
        }

        override fun applySubBatch(subBatch: CalendarProviderSubBatch): CalendarProviderSubBatchResult {
            applyCalls += 1
            onApply(applyCalls)
            if (applyCalls == applyFailureAtCall) applyFailure?.let { throw it }
            applied += subBatch
            return successfulResult(subBatch)
        }
    }

    private class ReplayCalendarGateway(
        private var failOnCall: Int? = null,
        private val failAfterApply: Boolean = false,
    ) : OwnedCalendarProviderGateway {
        private val rows = linkedMapOf<Long, StatefulEventRow>()
        private var nextRowId = 71L
        private var applyCalls = 0
        val plans = mutableListOf<CalendarProviderSubBatch>()
        val eventSyncIds: Set<String>
            get() = rows.values.filter { row -> row.originalId == null }.mapTo(linkedSetOf(), StatefulEventRow::syncId)
        val topLevelEventCount: Int
            get() = rows.values.count { row -> row.originalId == null }
        val attendeeRows: Int
            get() = rows.values.sumOf { row -> row.attendees.size }
        val organizerRows: Int
            get() = rows.values.count { row -> row.organizerEmail != null }
        val reminderRows: Int
            get() = rows.values.count(StatefulEventRow::hasReminder)
        val exceptionRows: Int
            get() = rows.values.count { row -> row.originalId != null }

        override fun resolveOwned(profileEmail: String): OwnedCalendarResolution =
            OwnedCalendarResolution(OWNED_CALENDAR, -13_408_615)

        override fun deleteAllOwned(): OwnedCalendarCleanupResult =
            OwnedCalendarCleanupResult(ownedRowCount = 0, deletedRowCount = 0)

        override fun queryExisting(calendarId: Long, syncIds: Set<String>): List<ExistingProviderEvent> =
            rows.values
                .filter { row -> row.originalId == null && row.syncId in syncIds }
                .map { row -> ExistingProviderEvent(row.id, calendarId, row.syncId) }

        override fun applySubBatch(subBatch: CalendarProviderSubBatch): CalendarProviderSubBatchResult {
            applyCalls += 1
            if (applyCalls == failOnCall && !failAfterApply) throw CalendarProviderAccessException()
            plans += subBatch
            val localInsertIds = mutableMapOf<Int, Long>()
            val insertResults = mutableListOf<CalendarProviderInsertResult>()
            subBatch.operations.forEachIndexed { localIndex, operation ->
                when (operation) {
                    is CalendarProviderBatchOperation.EventInsert -> {
                        val id = nextRowId++
                        rows[id] =
                            StatefulEventRow(
                                id = id,
                                syncId = checkNotNull(operation.values[CalendarProviderField.SYNC_ID] as? String),
                            )
                        localInsertIds[localIndex] = id
                        insertResults +=
                            CalendarProviderInsertResult(subBatch.startOperationIndex + localIndex, id)
                    }
                    is CalendarProviderBatchOperation.EventUpdate -> Unit
                    is CalendarProviderBatchOperation.EventDelete -> {
                        val parentIds = rows.values.filter { row -> row.syncId == operation.syncId }.map(StatefulEventRow::id)
                        parentIds.forEach(::deleteEventAndExceptions)
                    }
                    is CalendarProviderBatchOperation.AttendeesDelete ->
                        checkNotNull(rows[operation.eventId]).attendees.clear()
                    is CalendarProviderBatchOperation.OrganizerDelete ->
                        row(operation.event, localInsertIds).organizerEmail = null
                    is CalendarProviderBatchOperation.AttendeeInsert -> {
                        val row = row(operation.event, localInsertIds)
                        val email = checkNotNull(operation.values[CalendarProviderField.ATTENDEE_EMAIL] as? String)
                        if (
                            operation.values[CalendarProviderField.ATTENDEE_RELATIONSHIP] ==
                            ProviderInteger.ORGANIZER_RELATIONSHIP
                        ) {
                            row.organizerEmail = email
                        } else {
                            row.attendees += email
                        }
                    }
                    is CalendarProviderBatchOperation.RemindersDelete ->
                        row(operation.event, localInsertIds).hasReminder = false
                    is CalendarProviderBatchOperation.ReminderInsert ->
                        row(operation.event, localInsertIds).hasReminder = true
                    is CalendarProviderBatchOperation.ExceptionsDelete ->
                        rows.values.filter { row -> row.originalId == operation.seriesId }
                            .map(StatefulEventRow::id)
                            .forEach(rows::remove)
                    is CalendarProviderBatchOperation.ExceptionInsert -> {
                        val id = nextRowId++
                        rows[id] =
                            StatefulEventRow(
                                id = id,
                                syncId = checkNotNull(operation.values[CalendarProviderField.SYNC_ID] as? String),
                                originalId = row(operation.series, localInsertIds).id,
                            )
                        localInsertIds[localIndex] = id
                        insertResults +=
                            CalendarProviderInsertResult(subBatch.startOperationIndex + localIndex, id)
                    }
                    is CalendarProviderBatchOperation.ExceptionResponseUpdate -> Unit
                }
            }
            if (applyCalls == failOnCall && failAfterApply) throw CalendarProviderAccessException()
            return CalendarProviderSubBatchResult(subBatch.operations.size, insertResults)
        }

        fun clearFailure() {
            failOnCall = null
        }

        private fun row(
            reference: EventReference,
            localInsertIds: Map<Int, Long>,
        ): StatefulEventRow {
            val id =
                when (reference) {
                    is EventReference.Existing -> reference.eventId
                    is EventReference.Inserted -> checkNotNull(localInsertIds[reference.operationIndex])
                }
            return checkNotNull(rows[id])
        }

        private fun deleteEventAndExceptions(eventId: Long) {
            rows.values.filter { row -> row.originalId == eventId }
                .map(StatefulEventRow::id)
                .forEach(rows::remove)
            rows.remove(eventId)
        }

        private data class StatefulEventRow(
            val id: Long,
            val syncId: String,
            val originalId: Long? = null,
            val attendees: MutableList<String> = mutableListOf(),
            var organizerEmail: String? = null,
            var hasReminder: Boolean = false,
        )
    }

    private companion object {
        const val OWNED_CALENDAR = 12L
        val PROFILE = ConnectionProfile("calendar@example.test", "DOMAIN\\user", "mail.example.test", "cert")

        fun successfulResult(subBatch: CalendarProviderSubBatch): CalendarProviderSubBatchResult =
            CalendarProviderSubBatchResult(
                appliedOperationCount = subBatch.operations.size,
                insertResults =
                    subBatch.operations.mapIndexedNotNull { localIndex, operation ->
                        if (
                            operation !is CalendarProviderBatchOperation.EventInsert &&
                            operation !is CalendarProviderBatchOperation.ExceptionInsert
                        ) {
                            return@mapIndexedNotNull null
                        }
                        CalendarProviderInsertResult(subBatch.startOperationIndex + localIndex, 71L + localIndex)
                    },
            )
    }
}
