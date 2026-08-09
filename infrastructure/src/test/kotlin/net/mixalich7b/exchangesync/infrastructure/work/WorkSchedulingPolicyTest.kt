package net.mixalich7b.exchangesync.infrastructure.work

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkSchedulingPolicyTest {
    @Test
    fun `periodic policy is one network constrained fifteen minute trigger per generation`() {
        val spec = WorkSchedulingPolicy.periodic(generation = 8)

        assertEquals("exchange-calendar-periodic", spec.uniqueName)
        assertEquals(15L, spec.intervalMinutes)
        assertTrue(spec.requiresNetwork)
        assertEquals(8L, spec.input.generation)
        assertEquals(UniqueWorkReconciliation.UPDATE, spec.reconciliation)
        assertTrue(spec.remainsEligibleAfterPersistentFailure)
    }

    @Test
    fun `immediate execution replaces stale duplicates and carries the complete fence`() {
        val spec = WorkSchedulingPolicy.execution(generation = 8, runToken = 12)

        assertEquals("exchange-calendar-execution", spec.uniqueName)
        assertTrue(spec.requiresNetwork)
        assertEquals(WorkFenceInput(8, 12), spec.input)
        assertEquals(UniqueWorkReconciliation.REPLACE, spec.reconciliation)
        assertEquals(BackoffKind.EXPONENTIAL, spec.backoffKind)
        assertEquals(30L, spec.initialBackoffSeconds)
        assertEquals(5, spec.transientAttemptBudget)
    }

    @Test
    fun `continuation appends behind the current unique execution`() {
        val spec = WorkSchedulingPolicy.continuation(generation = 8, runToken = 12)

        assertEquals("exchange-calendar-execution", spec.uniqueName)
        assertEquals(UniqueWorkReconciliation.APPEND_OR_REPLACE, spec.reconciliation)
        assertEquals(WorkFenceInput(8, 12), spec.input)
    }

    @Test
    fun `startup recovery keeps an existing backed off execution`() {
        val spec = WorkSchedulingPolicy.recovery(generation = 8, runToken = 12)

        assertEquals("exchange-calendar-execution", spec.uniqueName)
        assertEquals(UniqueWorkReconciliation.KEEP, spec.reconciliation)
        assertEquals(WorkFenceInput(8, 12), spec.input)
    }
}
