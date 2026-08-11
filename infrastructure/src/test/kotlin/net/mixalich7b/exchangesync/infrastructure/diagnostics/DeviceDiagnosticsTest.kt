package net.mixalich7b.exchangesync.infrastructure.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceDiagnosticsTest {
    @Test
    fun `progress summary formats only typed modes booleans outcomes and bounded counts`() {
        val record =
            format(
                DeviceDiagnosticEvent(
                    severity = DiagnosticSeverity.INFO,
                    component = DiagnosticComponent.SYNCHRONIZATION,
                    stage = DiagnosticStage.CALENDAR_SYNC,
                    syncMode = SyncRequestMode.FULL,
                    windowSize = Int.MAX_VALUE,
                    responseBytes = Int.MAX_VALUE,
                    responseEmpty = true,
                    commandCount = Int.MAX_VALUE,
                    addCount = 3,
                    changeCount = 2,
                    deleteCount = 1,
                    moreAvailable = true,
                    keyAdvanced = false,
                    ownershipAction = OwnedCalendarAction.REPAIRED,
                    inputCount = 6,
                    acceptedCount = 5,
                    rejectedCount = 1,
                    plannedOperationCount = 5,
                    attemptedOperationCount = 5,
                    appliedOperationCount = 4,
                    cleanupTrigger = CleanupTrigger.DISABLE,
                    checkpointOutcome = CheckpointOutcome.FAILED,
                ),
            )

        assertTrue(record.contains("sync_mode=full"), record)
        assertTrue(record.contains("window_size=1000000"), record)
        assertTrue(record.contains("response_bytes=1000000"), record)
        assertTrue(record.contains("response_empty=true"), record)
        assertTrue(record.contains("command_count=1000000"), record)
        assertTrue(record.contains("add_count=3"), record)
        assertTrue(record.contains("change_count=2"), record)
        assertTrue(record.contains("delete_count=1"), record)
        assertTrue(record.contains("more_available=true"), record)
        assertTrue(record.contains("key_advanced=false"), record)
        assertTrue(record.contains("ownership_action=repaired"), record)
        assertTrue(record.contains("input_count=6"), record)
        assertTrue(record.contains("accepted_count=5"), record)
        assertTrue(record.contains("rejected_count=1"), record)
        assertTrue(record.contains("planned_operation_count=5"), record)
        assertTrue(record.contains("attempted_operation_count=5"), record)
        assertTrue(record.contains("applied_operation_count=4"), record)
        assertTrue(record.contains("cleanup_trigger=disable"), record)
        assertTrue(record.contains("checkpoint_outcome=failed"), record)
    }

    @Test
    fun `large-entity and provider sub-batch diagnostics format only allow-listed aggregate fields`() {
        val record =
            format(
                DeviceDiagnosticEvent(
                    severity = DiagnosticSeverity.WARN,
                    component = DiagnosticComponent.CALENDAR,
                    stage = DiagnosticStage.PROVIDER_BATCH,
                    operation = DeviceDiagnostics().operation(DiagnosticOperationKind.SYNCHRONIZATION, 3, 9),
                    attendeeLimit = 100,
                    attendeeInputCount = 101,
                    attendeeOmittedCount = 101,
                    attendeeRepresentation = DiagnosticAttendeeRepresentation.ORGANIZER_ONLY,
                    providerOperationCount = 121,
                    subBatchCount = 3,
                    subBatchOrdinal = 2,
                    subBatchOperationCount = 50,
                    confirmedOperationCount = 50,
                    providerCallOutcome = DiagnosticProviderCallOutcome.UNKNOWN,
                    providerFailureCause = DiagnosticProviderFailureCause.REMOTE,
                    reasonCode = "secret-cause-detail",
                    serverId = "secret-event-id",
                    command = "secret-sync-key",
                    path = "/secret-payload",
                    throwable = IllegalStateException("owner@example.test Private meeting row=987654321"),
                ),
            )

        listOf(
            "attendee_limit=100",
            "attendee_input_count=101",
            "attendee_omitted_count=101",
            "attendee_representation=organizer_only",
            "provider_operation_count=121",
            "sub_batch_count=3",
            "sub_batch_ordinal=2",
            "sub_batch_operation_count=50",
            "confirmed_operation_count=50",
            "provider_call_outcome=unknown",
            "provider_failure_cause=remote",
        ).forEach { expected -> assertTrue(record.contains(expected), record) }
        listOf(
            "secret-event-id",
            "secret-sync-key",
            "/secret-payload",
            "owner@example.test",
            "Private meeting",
            "987654321",
            "secret-cause-detail",
        ).forEach { secret -> assertFalse(record.contains(secret), secret) }
    }

    @Test
    fun `progress event model has no slots for keys identities rows timestamps payload or WBXML`() {
        val propertyNames = DeviceDiagnosticEvent::class.java.declaredFields.map { field -> field.name }.toSet()

        setOf(
            "syncKey",
            "previousSyncKey",
            "nextSyncKey",
            "accountName",
            "accountType",
            "calendarId",
            "rowId",
            "timestamp",
            "payload",
            "responseBody",
            "wbxml",
            "attendeeEmail",
            "organizerEmail",
            "providerRowId",
        ).forEach { forbidden -> assertFalse(forbidden in propertyNames, forbidden) }
    }

    @Test
    fun `payload-sensitive exception chain is bounded cycle-safe and omits its messages`() {
        val root = IllegalStateException("subject=Private email=calendar@example.test key=next-secret")
        val cause = IllegalArgumentException("WBXML domain\\login row=42 2026-08-11T10:20:30Z")
        root.initCause(cause)
        cause.initCause(root)

        val record =
            format(
                DeviceDiagnosticEvent(
                    severity = DiagnosticSeverity.ERROR,
                    component = DiagnosticComponent.ACTIVE_SYNC,
                    stage = DiagnosticStage.CALENDAR_SYNC,
                    throwable = root,
                ),
            )

        assertTrue(record.contains(IllegalStateException::class.java.name), record)
        assertTrue(record.contains(IllegalArgumentException::class.java.name), record)
        assertTrue(record.contains("cycle"), record)
        listOf("Private", "calendar@example.test", "next-secret", "WBXML", "domain\\login", "row=42", "2026-08-11")
            .forEach { secret -> assertFalse(record.contains(secret), secret) }
    }

    @Test
    fun `negative counts are formatted as zero`() {
        val record =
            format(
                DeviceDiagnosticEvent(
                    severity = DiagnosticSeverity.INFO,
                    component = DiagnosticComponent.CALENDAR,
                    stage = DiagnosticStage.PROVIDER_BATCH,
                    inputCount = -1,
                    appliedOperationCount = -20,
                ),
            )

        assertTrue(record.contains("input_count=0"), record)
        assertTrue(record.contains("applied_operation_count=0"), record)
    }

    @Test
    fun `capacity diagnostics format only typed classification and bounded window fields`() {
        val operation = DeviceDiagnostics().operation(DiagnosticOperationKind.SYNCHRONIZATION, 3, 9)
        val records =
            DiagnosticCapacityKind.entries.associateWith { kind ->
                format(
                    DeviceDiagnosticEvent(
                        severity = DiagnosticSeverity.WARN,
                        component = DiagnosticComponent.ACTIVE_SYNC,
                        stage = DiagnosticStage.WBXML,
                        operation = operation,
                        capacityKind = kind,
                        capacityCommand = DiagnosticActiveSyncCommand.SYNC,
                        capacityOutcome = DiagnosticCapacityOutcome.WINDOW_REDUCTION,
                        capacityProblem = DiagnosticCapacityProblem.PROTOCOL_DATA,
                        windowSize = 100,
                        reducedWindowSize = 50,
                        trigger = "WORK\\calendar",
                        host = "exchange.private.test",
                        path = "/secret-payload",
                        command = "Sync secret-collection",
                        reasonCode = "secret-folder-name",
                        serverId = "secret-server-id",
                        outcome = "secret-sync-key",
                        throwable = IllegalStateException("secret payload calendar@example.test"),
                    ),
                )
            }

        assertEquals(DiagnosticCapacityKind.entries.toSet(), records.keys)
        records.forEach { (kind, record) ->
            assertTrue(record.contains("capacity_kind=${kind.name.lowercase()}"), record)
            assertTrue(record.contains("capacity_command=sync"), record)
            assertTrue(record.contains("capacity_outcome=window_reduction"), record)
            assertTrue(record.contains("capacity_problem=protocol_data"), record)
            assertTrue(record.contains("window_size=100"), record)
            assertTrue(record.contains("reduced_window_size=50"), record)
            assertTrue(record.contains("generation=3"), record)
            assertTrue(record.contains("run_token=9"), record)
            listOf(
                "secret-collection",
                "secret-folder-name",
                "secret-server-id",
                "secret-sync-key",
                "secret payload",
                "calendar@example.test",
                "WORK\\calendar",
                "exchange.private.test",
                "/secret-payload",
            ).forEach { secret -> assertFalse(record.contains(secret), secret) }
        }
    }

    @Test
    fun `minimum window and terminal capacity outcomes remain distinct`() {
        val minimum =
            format(
                DeviceDiagnosticEvent(
                    DiagnosticSeverity.WARN,
                    DiagnosticComponent.ACTIVE_SYNC,
                    DiagnosticStage.WBXML,
                    capacityKind = DiagnosticCapacityKind.WBXML_ELEMENT_COUNT,
                    capacityCommand = DiagnosticActiveSyncCommand.SYNC,
                    capacityOutcome = DiagnosticCapacityOutcome.MINIMUM_WINDOW_BLOCK,
                    capacityProblem = DiagnosticCapacityProblem.PROTOCOL_DATA,
                    windowSize = 1,
                ),
            )
        val terminal =
            format(
                DeviceDiagnosticEvent(
                    DiagnosticSeverity.WARN,
                    DiagnosticComponent.ACTIVE_SYNC,
                    DiagnosticStage.WBXML,
                    capacityKind = DiagnosticCapacityKind.WBXML_DEPTH,
                    capacityCommand = DiagnosticActiveSyncCommand.SYNC,
                    capacityOutcome = DiagnosticCapacityOutcome.TERMINAL,
                    capacityProblem = DiagnosticCapacityProblem.PROTOCOL_DATA,
                ),
            )

        assertTrue(minimum.contains("capacity_outcome=minimum_window_block"), minimum)
        assertTrue(minimum.contains("window_size=1"), minimum)
        assertFalse(minimum.contains("reduced_window_size="), minimum)
        assertTrue(terminal.contains("capacity_outcome=terminal"), terminal)
    }

    @Test
    fun `folder preparation diagnostics format only bounded outcomes and correlation`() {
        val records =
            FolderPreparationOutcome.entries.map { outcome ->
                format(
                    DeviceDiagnosticEvent(
                        severity = DiagnosticSeverity.INFO,
                        component = DiagnosticComponent.ACTIVE_SYNC,
                        stage = DiagnosticStage.FOLDER_SYNC,
                        operation = DeviceDiagnostics().operation(DiagnosticOperationKind.SYNCHRONIZATION, 7, 11),
                        folderPreparationOutcome = outcome,
                        command = "FolderSync secret-folder",
                        serverId = "secret-collection",
                        outcome = "secret-key",
                    ),
                )
            }

        assertEquals(FolderPreparationOutcome.entries.size, records.size)
        records.zip(FolderPreparationOutcome.entries).forEach { (record, outcome) ->
            assertTrue(record.contains("folder_preparation=${outcome.name.lowercase()}"), record)
            assertTrue(record.contains("generation=7"), record)
            assertTrue(record.contains("run_token=11"), record)
            listOf("secret-folder", "secret-collection", "secret-key")
                .forEach { secret -> assertFalse(record.contains(secret), secret) }
        }
    }

    private fun format(event: DeviceDiagnosticEvent): String {
        val formatterClass = Class.forName("net.mixalich7b.exchangesync.infrastructure.diagnostics.DeviceDiagnosticFormatter")
        val instanceField = formatterClass.getDeclaredField("INSTANCE").apply { isAccessible = true }
        val formatMethod =
            formatterClass.getDeclaredMethod("format", DeviceDiagnosticEvent::class.java).apply {
                isAccessible = true
            }
        return formatMethod.invoke(instanceField.get(null), event) as String
    }
}
