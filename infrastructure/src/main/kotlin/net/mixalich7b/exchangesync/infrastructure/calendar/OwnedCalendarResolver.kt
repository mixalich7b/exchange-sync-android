package net.mixalich7b.exchangesync.infrastructure.calendar

import net.mixalich7b.exchangesync.infrastructure.diagnostics.OwnedCalendarAction

internal interface OwnedCalendarStore {
    fun queryOwned(): List<OwnedCalendarRow>

    fun create(definition: OwnedCalendarDefinition): Long

    fun deleteOwned(calendarId: Long): Boolean
}

internal data class OwnedCalendarResolution(
    val calendarId: Long,
    val color: Int,
    val wasRecreated: Boolean = false,
    val action: OwnedCalendarAction =
        if (wasRecreated) OwnedCalendarAction.CREATED else OwnedCalendarAction.REUSED,
)

internal class OwnedCalendarProviderException(message: String) : IllegalStateException(message)

internal data class OwnedCalendarCleanupResult(
    val ownedRowCount: Int,
    val deletedRowCount: Int,
) {
    init {
        require(ownedRowCount >= 0)
        require(deletedRowCount in 0..ownedRowCount)
    }

    val completed: Boolean
        get() = deletedRowCount == ownedRowCount
}

internal class OwnedCalendarResolver(
    private val store: OwnedCalendarStore,
) {
    fun resolve(profileEmail: String): OwnedCalendarResolution =
        when (val plan = OwnedCalendarPlanner.plan(store.queryOwned())) {
            is CalendarOwnershipPlan.Use -> plan.row.toResolution(OwnedCalendarAction.REUSED)
            CalendarOwnershipPlan.Create -> createAndVerify(profileEmail, OwnedCalendarAction.CREATED)
            is CalendarOwnershipPlan.Repair -> {
                plan.deleteIds.forEach { calendarId ->
                    if (!store.deleteOwned(calendarId)) {
                        throw OwnedCalendarProviderException("Owned calendar repair failed")
                    }
                }
                createAndVerify(profileEmail, OwnedCalendarAction.REPAIRED)
            }
        }

    fun deleteAllOwned(): OwnedCalendarCleanupResult {
        val owned = store.queryOwned()
        val deleted = owned.count { row -> store.deleteOwned(row.id) }
        return OwnedCalendarCleanupResult(owned.size, deleted)
    }

    private fun createAndVerify(
        profileEmail: String,
        action: OwnedCalendarAction,
    ): OwnedCalendarResolution {
        val insertedId = store.create(OwnedCalendarDefinition.forProfile(profileEmail))
        val verified = store.queryOwned().singleOrNull { row -> row.id == insertedId }
            ?: throw OwnedCalendarProviderException("Created calendar ownership could not be verified")
        return verified.toResolution(action)
    }

    private fun OwnedCalendarRow.toResolution(action: OwnedCalendarAction): OwnedCalendarResolution =
        OwnedCalendarResolution(
            calendarId = id,
            color = color,
            wasRecreated = action != OwnedCalendarAction.REUSED,
            action = action,
        )
}
