package net.mixalich7b.exchangesync.infrastructure.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.first
import net.mixalich7b.exchangesync.infrastructure.calendar.OwnedCalendarDisplayNameMigrationState

internal class DataStoreCalendarDisplayNameMigrationState(
    private val dataStore: DataStore<Preferences>,
) : OwnedCalendarDisplayNameMigrationState {
    override suspend fun isComplete(): Boolean =
        dataStore.data.first()[MIGRATION_COMPLETE] == true

    override suspend fun markComplete() {
        dataStore.updateData { preferences ->
            mutablePreferencesOf().apply {
                this += preferences
                this[MIGRATION_COMPLETE] = true
            }
        }
    }

    private companion object {
        val MIGRATION_COMPLETE = booleanPreferencesKey("calendar_display_name_migration_v1")
    }
}
