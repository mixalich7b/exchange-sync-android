package net.mixalich7b.exchangesync.infrastructure.calendar

internal interface OwnedCalendarDisplayNameMigrationState {
    suspend fun isComplete(): Boolean

    suspend fun markComplete()
}

internal class OwnedCalendarDisplayNameMigrator(
    private val store: OwnedCalendarStore,
    private val state: OwnedCalendarDisplayNameMigrationState,
) {
    suspend fun execute() {
        if (state.isComplete()) return

        store.queryOwned().forEach { row ->
            if (!store.updateDisplayName(row.id, OwnedCalendarIdentity.DISPLAY_NAME)) {
                throw OwnedCalendarProviderException("Owned calendar display-name update failed")
            }
        }
        state.markComplete()
    }
}
