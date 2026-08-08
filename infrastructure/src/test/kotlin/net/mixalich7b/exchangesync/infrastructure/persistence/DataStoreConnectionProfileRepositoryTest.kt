package net.mixalich7b.exchangesync.infrastructure.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.mixalich7b.exchangesync.core.connection.ConnectionProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class DataStoreConnectionProfileRepositoryTest {
    @Test
    fun `absent preferences load as no profile`() =
        runTest {
            val repository = DataStoreConnectionProfileRepository(RecordingDataStore(emptyPreferences()))

            assertEquals(null, repository.load())
        }

    @Test
    fun `complete preferences load as one profile`() =
        runTest {
            val repository = DataStoreConnectionProfileRepository(RecordingDataStore(preferencesFor(profile())))

            assertEquals(profile(), repository.load())
        }

    @Test
    fun `partial preferences fail instead of producing a partial profile`() =
        runTest {
            val partial = mutablePreferencesOf(stringPreferencesKey("connection_email") to "calendar@example.test")
            val repository = DataStoreConnectionProfileRepository(RecordingDataStore(partial))

            val failure =
                try {
                    repository.load()
                    null
                } catch (error: Exception) {
                    error
                }

            assertInstanceOf(CorruptConnectionProfile::class.java, failure)
        }

    @Test
    fun `replacement performs one atomic update containing only the profile`() =
        runTest {
            val initial =
                mutablePreferencesOf(
                    stringPreferencesKey("connection_email") to "old@example.test",
                    stringPreferencesKey("unrelated") to "must-not-survive",
                )
            val store = RecordingDataStore(initial)
            val repository = DataStoreConnectionProfileRepository(store)

            repository.replace(profile())

            assertEquals(1, store.updateCalls)
            assertEquals(
                mapOf(
                    "connection_email" to "calendar@example.test",
                    "connection_account" to "DOMAIN\\calendar",
                    "connection_server_host" to "exchange.example.test",
                    "connection_client_certificate_alias" to "work-certificate",
                ),
                store.current.asMap().mapKeys { (key, _) -> key.name },
            )
            assertEquals(profile(), repository.load())
        }

    private class RecordingDataStore(initial: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        var updateCalls: Int = 0

        val current: Preferences
            get() = state.value

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            updateCalls += 1
            return transform(state.value).also { updated -> state.value = updated }
        }
    }

    private fun preferencesFor(profile: ConnectionProfile): Preferences =
        mutablePreferencesOf(
            stringPreferencesKey("connection_email") to profile.email,
            stringPreferencesKey("connection_account") to profile.account,
            stringPreferencesKey("connection_server_host") to profile.serverHost,
            stringPreferencesKey("connection_client_certificate_alias") to profile.clientCertificateAlias,
        )

    private fun profile(): ConnectionProfile =
        ConnectionProfile(
            email = "calendar@example.test",
            account = "DOMAIN\\calendar",
            serverHost = "exchange.example.test",
            clientCertificateAlias = "work-certificate",
        )
}
