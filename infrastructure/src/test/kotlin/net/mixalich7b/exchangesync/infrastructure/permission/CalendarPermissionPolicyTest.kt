package net.mixalich7b.exchangesync.infrastructure.permission

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalendarPermissionPolicyTest {
    @Test
    fun `calendar access requires both read and write while notification is independent`() {
        assertTrue(CalendarPermissionPolicy.hasCalendarAccess(readGranted = true, writeGranted = true))
        assertFalse(CalendarPermissionPolicy.hasCalendarAccess(readGranted = true, writeGranted = false))
        assertFalse(CalendarPermissionPolicy.hasCalendarAccess(readGranted = false, writeGranted = true))
        assertFalse(CalendarPermissionPolicy.hasCalendarAccess(readGranted = false, writeGranted = false))
    }
}
