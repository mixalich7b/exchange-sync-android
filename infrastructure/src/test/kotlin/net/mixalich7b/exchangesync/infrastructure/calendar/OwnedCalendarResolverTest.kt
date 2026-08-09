package net.mixalich7b.exchangesync.infrastructure.calendar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OwnedCalendarResolverTest {
    @Test
    fun `existing owned row is reused without mutation`() {
        val row = ownedRow(7)
        val store = FakeOwnedCalendarStore(mutableListOf(row))

        assertEquals(OwnedCalendarResolution(7, COLOR), OwnedCalendarResolver(store).resolve(PROFILE_EMAIL))
        assertEquals(emptyList<Long>(), store.deleted)
        assertEquals(emptyList<OwnedCalendarDefinition>(), store.created)
    }

    @Test
    fun `missing calendar is created and verified from the owned query`() {
        val store = FakeOwnedCalendarStore(mutableListOf())

        val resolved = OwnedCalendarResolver(store).resolve(PROFILE_EMAIL)

        assertEquals(
            OwnedCalendarResolution(
                100,
                OwnedCalendarDefinition.forProfile(PROFILE_EMAIL).color,
                wasRecreated = true,
            ),
            resolved,
        )
        assertEquals(listOf(OwnedCalendarDefinition.forProfile(PROFILE_EMAIL)), store.created)
        assertEquals(1, store.queryCallsAfterCreate)
    }

    @Test
    fun `duplicate repair deletes every complete owned id before recreating one`() {
        val store = FakeOwnedCalendarStore(mutableListOf(ownedRow(9), ownedRow(4)))

        val resolved = OwnedCalendarResolver(store).resolve(PROFILE_EMAIL)

        assertEquals(listOf(4L, 9L), store.deleted.sorted())
        assertEquals(100, resolved.calendarId)
        assertTrue(resolved.wasRecreated)
        assertEquals(1, store.rows.size)
    }

    @Test
    fun `delete all removes only rows returned by complete ownership query`() {
        val store = FakeOwnedCalendarStore(mutableListOf(ownedRow(1), ownedRow(2)))

        assertTrue(OwnedCalendarResolver(store).deleteAllOwned())
        assertEquals(listOf(1L, 2L), store.deleted)
        assertTrue(store.rows.isEmpty())
    }

    private class FakeOwnedCalendarStore(
        val rows: MutableList<OwnedCalendarRow>,
    ) : OwnedCalendarStore {
        val deleted = mutableListOf<Long>()
        val created = mutableListOf<OwnedCalendarDefinition>()
        var queryCallsAfterCreate = 0
        private var didCreate = false

        override fun queryOwned(): List<OwnedCalendarRow> {
            if (didCreate) queryCallsAfterCreate += 1
            return rows.toList()
        }

        override fun create(definition: OwnedCalendarDefinition): Long {
            created += definition
            didCreate = true
            val row =
                OwnedCalendarRow(
                    id = 100,
                    accountName = definition.accountName,
                    accountType = definition.accountType,
                    internalName = definition.internalName,
                    displayName = definition.displayName,
                    ownerEmail = definition.ownerEmail,
                    color = definition.color,
                )
            rows += row
            return row.id
        }

        override fun deleteOwned(calendarId: Long): Boolean {
            deleted += calendarId
            return rows.removeAll { row -> row.id == calendarId }
        }
    }

    private fun ownedRow(id: Long) =
        OwnedCalendarRow(
            id = id,
            accountName = OwnedCalendarIdentity.ACCOUNT_NAME,
            accountType = OwnedCalendarIdentity.ACCOUNT_TYPE,
            internalName = OwnedCalendarIdentity.INTERNAL_NAME,
            displayName = OwnedCalendarIdentity.DISPLAY_NAME,
            ownerEmail = PROFILE_EMAIL,
            color = COLOR,
        )

    private companion object {
        const val PROFILE_EMAIL = "calendar@example.test"
        const val COLOR = -12_625_339
    }
}
