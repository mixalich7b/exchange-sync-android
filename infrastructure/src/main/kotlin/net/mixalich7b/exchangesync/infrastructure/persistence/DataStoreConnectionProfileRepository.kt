package net.mixalich7b.exchangesync.infrastructure.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.first
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.connection.ConnectionProfileRepository

internal class CorruptConnectionProfile : IllegalStateException("Stored connection profile is incomplete or invalid")

public class DataStoreConnectionProfileRepository(
    private val dataStore: DataStore<Preferences>,
) : ConnectionProfileRepository {
    override suspend fun load(): ConnectionProfile? = ConnectionProfilePreferences.decode(dataStore.data.first())

    override suspend fun replace(profile: ConnectionProfile) {
        dataStore.updateData { current ->
            mutablePreferencesOf().apply {
                this += current
                ConnectionProfilePreferences.write(this, profile)
            }
        }
    }
}
