package net.mixalich7b.exchangesync.infrastructure.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.mixalich7b.exchangesync.infrastructure.persistence.DataStoreCalendarDisplayNameMigrationState

public class AndroidOwnedCalendarDisplayNameMigrator internal constructor(
    private val migrator: OwnedCalendarDisplayNameMigrator,
    private val hasCalendarAccess: () -> Boolean,
) {
    public constructor(
        context: Context,
        dataStore: DataStore<Preferences>,
    ) : this(
        migrator =
            OwnedCalendarDisplayNameMigrator(
                store = AndroidOwnedCalendarStore(context.applicationContext.contentResolver),
                state = DataStoreCalendarDisplayNameMigrationState(dataStore),
            ),
        hasCalendarAccess = {
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        },
    )

    public suspend fun execute() {
        if (!hasCalendarAccess()) return
        withContext(Dispatchers.IO) {
            try {
                migrator.execute()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: OwnedCalendarProviderException) {
                // The durable marker remains unset, so the next app start retries.
            } catch (_: SecurityException) {
                // Calendar permission can be revoked between the check and the provider call.
            } catch (_: RuntimeException) {
                // Unexpected provider failures leave the marker unset for a later retry.
            }
        }
    }
}
