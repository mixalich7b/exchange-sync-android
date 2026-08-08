package net.mixalich7b.exchangesync.infrastructure.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import net.mixalich7b.exchangesync.core.connection.ConnectionDraft
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.connection.ConnectionProfileRepository
import net.mixalich7b.exchangesync.core.connection.ConnectionValidator

internal class CorruptConnectionProfile : IllegalStateException("Stored connection profile is incomplete or invalid")

public class DataStoreConnectionProfileRepository(
    private val dataStore: DataStore<Preferences>,
) : ConnectionProfileRepository {
    override suspend fun load(): ConnectionProfile? = decode(dataStore.data.first())

    override suspend fun replace(profile: ConnectionProfile) {
        dataStore.updateData {
            mutablePreferencesOf(
                EMAIL to profile.email,
                ACCOUNT to profile.account,
                SERVER_HOST to profile.serverHost,
                CLIENT_CERTIFICATE_ALIAS to profile.clientCertificateAlias,
            )
        }
    }

    private fun decode(preferences: Preferences): ConnectionProfile? {
        val values =
            listOf(
                preferences[EMAIL],
                preferences[ACCOUNT],
                preferences[SERVER_HOST],
                preferences[CLIENT_CERTIFICATE_ALIAS],
            )
        if (values.all { value -> value == null }) return null
        if (values.any { value -> value == null }) throw CorruptConnectionProfile()

        return ConnectionValidator.toProfile(
            ConnectionDraft(
                email = values[0].orEmpty(),
                account = values[1].orEmpty(),
                serverHost = values[2].orEmpty(),
                clientCertificateAlias = values[3],
            ),
        ) ?: throw CorruptConnectionProfile()
    }

    private companion object {
        val EMAIL: Preferences.Key<String> = stringPreferencesKey("connection_email")
        val ACCOUNT: Preferences.Key<String> = stringPreferencesKey("connection_account")
        val SERVER_HOST: Preferences.Key<String> = stringPreferencesKey("connection_server_host")
        val CLIENT_CERTIFICATE_ALIAS: Preferences.Key<String> =
            stringPreferencesKey("connection_client_certificate_alias")
    }
}
