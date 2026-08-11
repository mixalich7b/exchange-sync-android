package net.mixalich7b.exchangesync.infrastructure.calendar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AndroidOwnedCalendarStoreTest {
    @Test
    fun `delete uses the sync-adapter collection target and complete ownership predicate`() {
        lateinit var captured: OwnedCalendarDeleteRequest
        val store =
            AndroidOwnedCalendarStore(
                OwnedCalendarDeleteOperation { request ->
                    captured = request
                    1
                },
            )

        assertTrue(store.deleteOwned(42))

        assertEquals(CalendarDeleteTarget.COLLECTION, captured.target)
        assertTrue(captured.callerIsSyncAdapter)
        assertEquals("net.mixalich7b.exchangesync.calendar", captured.accountNameParameter)
        assertEquals("LOCAL", captured.accountTypeParameter)
        assertEquals("_id=? AND account_name=? AND account_type=? AND name=?", captured.selection)
        assertEquals(
            listOf(
                "42",
                "net.mixalich7b.exchangesync.calendar",
                "LOCAL",
                "exchange_primary_calendar",
            ),
            captured.selectionArguments,
        )
        assertFalse(captured.selectionArguments.contains("account_name_local"))
    }

    @Test
    fun `delete reports a missing or rejected owned row without broadening its target`() {
        val requests = mutableListOf<OwnedCalendarDeleteRequest>()
        val store =
            AndroidOwnedCalendarStore(
                OwnedCalendarDeleteOperation { request ->
                    requests += request
                    0
                },
            )

        assertFalse(store.deleteOwned(99))
        assertEquals(1, requests.size)
        assertEquals(listOf("99"), requests.single().selectionArguments.take(1))
        assertEquals(CalendarDeleteTarget.COLLECTION, requests.single().target)
    }
}
