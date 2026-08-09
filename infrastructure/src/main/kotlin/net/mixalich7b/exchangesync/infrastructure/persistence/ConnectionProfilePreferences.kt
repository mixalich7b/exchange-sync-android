package net.mixalich7b.exchangesync.infrastructure.persistence

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import net.mixalich7b.exchangesync.core.connection.ConnectionDraft
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import net.mixalich7b.exchangesync.core.connection.ConnectionValidator

internal object ConnectionProfilePreferences {
    val email: Preferences.Key<String> = stringPreferencesKey("connection_email")
    val account: Preferences.Key<String> = stringPreferencesKey("connection_account")
    val serverHost: Preferences.Key<String> = stringPreferencesKey("connection_server_host")
    val clientCertificateAlias: Preferences.Key<String> =
        stringPreferencesKey("connection_client_certificate_alias")

    fun decode(preferences: Preferences): ConnectionProfile? {
        val values =
            listOf(
                preferences[email],
                preferences[account],
                preferences[serverHost],
                preferences[clientCertificateAlias],
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

    fun hasValidProfile(preferences: Preferences): Boolean =
        runCatching { decode(preferences) != null }.getOrDefault(false)

    fun write(
        preferences: MutablePreferences,
        profile: ConnectionProfile,
    ) {
        preferences[email] = profile.email
        preferences[account] = profile.account
        preferences[serverHost] = profile.serverHost
        preferences[clientCertificateAlias] = profile.clientCertificateAlias
    }
}
