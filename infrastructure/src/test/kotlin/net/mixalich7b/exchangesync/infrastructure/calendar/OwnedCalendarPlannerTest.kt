package net.mixalich7b.exchangesync.infrastructure.calendar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OwnedCalendarPlannerTest {
    @Test
    fun `identity uses constant application account local type and internal name`() {
        assertEquals("net.mixalich7b.exchangesync.calendar", OwnedCalendarIdentity.ACCOUNT_NAME)
        assertEquals("LOCAL", OwnedCalendarIdentity.ACCOUNT_TYPE)
        assertEquals("exchange_primary_calendar", OwnedCalendarIdentity.INTERNAL_NAME)
    }

    @Test
    fun `one complete ownership match is reused regardless of display and owner metadata`() {
        val row = ownedRow(id = 7, displayName = "Renamed by user", ownerEmail = "old@example.test")

        assertEquals(CalendarOwnershipPlan.Use(row), OwnedCalendarPlanner.plan(listOf(row)))
    }

    @Test
    fun `same display name or profile email never makes an unrelated calendar owned`() {
        val unrelated =
            listOf(
                ownedRow(id = 1).copy(accountName = "other-app"),
                ownedRow(id = 2).copy(accountType = "com.example.remote"),
                ownedRow(id = 3).copy(internalName = "different_internal_name"),
                OwnedCalendarRow(
                    id = 4,
                    accountName = "personal@example.test",
                    accountType = "LOCAL",
                    internalName = "personal",
                    displayName = OwnedCalendarIdentity.DISPLAY_NAME,
                    ownerEmail = "calendar@example.test",
                    color = COLOR,
                ),
            )

        assertEquals(CalendarOwnershipPlan.Create, OwnedCalendarPlanner.plan(unrelated))
    }

    @Test
    fun `duplicate complete ownership rows are all repaired then one calendar is recreated`() {
        val owned = listOf(ownedRow(9), ownedRow(11))
        val unrelated = ownedRow(13).copy(accountName = "another-app")

        assertEquals(
            CalendarOwnershipPlan.Repair(deleteIds = listOf(9, 11)),
            OwnedCalendarPlanner.plan(owned + unrelated),
        )
    }

    @Test
    fun `creation definition is visible synchronized read-only and supports alert reminders`() {
        val definition = OwnedCalendarDefinition.forProfile("calendar@example.test")

        assertEquals(OwnedCalendarIdentity.ACCOUNT_NAME, definition.accountName)
        assertEquals(OwnedCalendarIdentity.ACCOUNT_TYPE, definition.accountType)
        assertEquals(OwnedCalendarIdentity.INTERNAL_NAME, definition.internalName)
        assertEquals("calendar@example.test", definition.ownerEmail)
        assertTrue(definition.visible)
        assertTrue(definition.syncEvents)
        assertFalse(definition.canModifyEvents)
        assertEquals(setOf(OwnedReminderMethod.ALERT), definition.allowedReminderMethods)
    }

    private fun ownedRow(
        id: Long,
        displayName: String = OwnedCalendarIdentity.DISPLAY_NAME,
        ownerEmail: String = "calendar@example.test",
    ) =
        OwnedCalendarRow(
            id = id,
            accountName = OwnedCalendarIdentity.ACCOUNT_NAME,
            accountType = OwnedCalendarIdentity.ACCOUNT_TYPE,
            internalName = OwnedCalendarIdentity.INTERNAL_NAME,
            displayName = displayName,
            ownerEmail = ownerEmail,
            color = COLOR,
        )

    private companion object {
        const val COLOR: Int = -12_625_339
    }
}
