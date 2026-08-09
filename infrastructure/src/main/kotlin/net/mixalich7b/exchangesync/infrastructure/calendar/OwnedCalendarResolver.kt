package net.mixalich7b.exchangesync.infrastructure.calendar

internal interface OwnedCalendarStore {
    fun queryOwned(): List<OwnedCalendarRow>

    fun create(definition: OwnedCalendarDefinition): Long

    fun deleteOwned(calendarId: Long): Boolean
}

internal data class OwnedCalendarResolution(
    val calendarId: Long,
    val color: Int,
    val wasRecreated: Boolean = false,
)

internal class OwnedCalendarProviderException(message: String) : IllegalStateException(message)

internal class OwnedCalendarResolver(
    private val store: OwnedCalendarStore,
) {
    fun resolve(profileEmail: String): OwnedCalendarResolution =
        when (val plan = OwnedCalendarPlanner.plan(store.queryOwned())) {
            is CalendarOwnershipPlan.Use -> plan.row.toResolution()
            CalendarOwnershipPlan.Create -> createAndVerify(profileEmail)
            is CalendarOwnershipPlan.Repair -> {
                plan.deleteIds.forEach { calendarId ->
                    if (!store.deleteOwned(calendarId)) {
                        throw OwnedCalendarProviderException("Owned calendar repair failed")
                    }
                }
                createAndVerify(profileEmail)
            }
        }

    fun deleteAllOwned(): Boolean {
        val owned = store.queryOwned()
        return owned.all { row -> store.deleteOwned(row.id) }
    }

    private fun createAndVerify(profileEmail: String): OwnedCalendarResolution {
        val insertedId = store.create(OwnedCalendarDefinition.forProfile(profileEmail))
        val verified = store.queryOwned().singleOrNull { row -> row.id == insertedId }
            ?: throw OwnedCalendarProviderException("Created calendar ownership could not be verified")
        return verified.toResolution(wasRecreated = true)
    }

    private fun OwnedCalendarRow.toResolution(wasRecreated: Boolean = false): OwnedCalendarResolution =
        OwnedCalendarResolution(id, color, wasRecreated)
}
