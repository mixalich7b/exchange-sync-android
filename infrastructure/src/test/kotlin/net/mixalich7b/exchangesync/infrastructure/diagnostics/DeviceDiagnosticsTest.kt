package net.mixalich7b.exchangesync.infrastructure.diagnostics

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
