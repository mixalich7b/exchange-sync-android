package net.mixalich7b.exchangesync

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import net.mixalich7b.exchangesync.core.connection.ConnectionProfileRepository
import net.mixalich7b.exchangesync.core.connection.SaveConnection
import net.mixalich7b.exchangesync.core.connection.SaveConnectionAction
import net.mixalich7b.exchangesync.core.connection.VerifyConnection
import net.mixalich7b.exchangesync.core.connection.VerifyConnectionAction
import net.mixalich7b.exchangesync.infrastructure.activesync.AndroidActiveSyncConnectionVerifier
import net.mixalich7b.exchangesync.infrastructure.persistence.DataStoreConnectionProfileRepository

private val Context.connectionDataStore by preferencesDataStore(name = "connection_profile")

internal class AppContainer(context: Context) {
    val repository: ConnectionProfileRepository =
        DataStoreConnectionProfileRepository(context.applicationContext.connectionDataStore)

    private val verifier = AndroidActiveSyncConnectionVerifier(context.applicationContext)

    val verifyConnection: VerifyConnectionAction = VerifyConnection(verifier)

    val saveConnection: SaveConnectionAction =
        SaveConnection(
            repository = repository,
            verifyConnection = verifyConnection,
        )
}
