package net.mixalich7b.exchangesync.infrastructure.calendar

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OwnedCalendarDisplayNameMigratorTest {
    @Test
    fun `renames every owned row and records completion`() = runTest {
        val store = RecordingOwnedCalendarStore(listOf(ownedRow(7), ownedRow(11)))
        val state = RecordingMigrationState()

        OwnedCalendarDisplayNameMigrator(store, state).execute()

        assertEquals(listOf(7L, 11L), store.updatedIds)
        assertEquals(listOf("Exchange-sync", "Exchange-sync"), store.updatedNames)
        assertTrue(state.completed)
    }

    @Test
    fun `does not touch provider after migration was already completed`() = runTest {
        val store = RecordingOwnedCalendarStore(listOf(ownedRow(7)))
        val state = RecordingMigrationState(completed = true)

        OwnedCalendarDisplayNameMigrator(store, state).execute()

        assertTrue(store.updatedIds.isEmpty())
    }

    @Test
    fun `does not record completion when provider update fails`() = runTest {
        val store = RecordingOwnedCalendarStore(listOf(ownedRow(7)), failOnUpdate = true)
        val state = RecordingMigrationState()

        var failed = false
        try {
            OwnedCalendarDisplayNameMigrator(store, state).execute()
        } catch (_: OwnedCalendarProviderException) {
            failed = true
        }

        assertTrue(failed)
        assertFalse(state.completed)
    }

    private class RecordingOwnedCalendarStore(
        private val rows: List<OwnedCalendarRow>,
        private val failOnUpdate: Boolean = false,
    ) : OwnedCalendarStore {
        val updatedIds = mutableListOf<Long>()
        val updatedNames = mutableListOf<String>()

        override fun queryOwned(): List<OwnedCalendarRow> = rows

        override fun create(definition: OwnedCalendarDefinition): Long = error("unused")

        override fun deleteOwned(calendarId: Long): Boolean = error("unused")

        override fun updateDisplayName(calendarId: Long, displayName: String): Boolean {
            if (failOnUpdate) throw OwnedCalendarProviderException("update failed")
            updatedIds += calendarId
            updatedNames += displayName
            return true
        }
    }

    private class RecordingMigrationState(
        var completed: Boolean = false,
    ) : OwnedCalendarDisplayNameMigrationState {
        override suspend fun isComplete(): Boolean = completed

        override suspend fun markComplete() {
            completed = true
        }
    }

    private fun ownedRow(id: Long) =
        OwnedCalendarRow(
            id = id,
            accountName = OwnedCalendarIdentity.ACCOUNT_NAME,
            accountType = OwnedCalendarIdentity.ACCOUNT_TYPE,
            internalName = OwnedCalendarIdentity.INTERNAL_NAME,
            displayName = "legacy.package.name",
            ownerEmail = "calendar@example.test",
            color = -12_625_339,
        )
}
