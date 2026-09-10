package net.mixalich7b.exchangesync.infrastructure.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DataStoreCalendarDisplayNameMigrationStateTest {
    @Test
    fun `migration marker is initially absent and is persisted without dropping other preferences`() = runTest {
        val dataStore = RecordingDataStore(mutablePreferencesOf(stringPreferencesKey("keep") to "value"))
        val state = DataStoreCalendarDisplayNameMigrationState(dataStore)

        assertFalse(state.isComplete())
        state.markComplete()

        assertTrue(state.isComplete())
        assertEquals(
            mapOf(
                "keep" to "value",
                "calendar_display_name_migration_v1" to true,
            ),
            dataStore.current.asMap().mapKeys { (key, _) -> key.name },
        )
    }

    private class RecordingDataStore(initial: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)

        val current: Preferences
            get() = state.value

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { updated -> state.value = updated }
    }
}
