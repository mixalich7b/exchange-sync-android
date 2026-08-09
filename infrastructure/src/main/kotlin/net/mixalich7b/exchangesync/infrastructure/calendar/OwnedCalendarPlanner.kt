package net.mixalich7b.exchangesync.infrastructure.calendar

internal object OwnedCalendarIdentity {
    const val ACCOUNT_NAME = "net.mixalich7b.exchangesync.calendar"
    const val ACCOUNT_TYPE = "LOCAL"
    const val INTERNAL_NAME = "exchange_primary_calendar"
    const val DISPLAY_NAME = "Exchange Calendar"
}

internal data class OwnedCalendarRow(
    val id: Long,
    val accountName: String,
    val accountType: String,
    val internalName: String,
    val displayName: String,
    val ownerEmail: String,
    val color: Int,
)

internal enum class OwnedReminderMethod {
    ALERT,
}

internal data class OwnedCalendarDefinition(
    val accountName: String,
    val accountType: String,
    val internalName: String,
    val displayName: String,
    val ownerEmail: String,
    val color: Int,
    val visible: Boolean,
    val syncEvents: Boolean,
    val canModifyEvents: Boolean,
    val allowedReminderMethods: Set<OwnedReminderMethod>,
) {
    companion object {
        fun forProfile(profileEmail: String): OwnedCalendarDefinition {
            require(profileEmail.isNotBlank())
            return OwnedCalendarDefinition(
                accountName = OwnedCalendarIdentity.ACCOUNT_NAME,
                accountType = OwnedCalendarIdentity.ACCOUNT_TYPE,
                internalName = OwnedCalendarIdentity.INTERNAL_NAME,
                displayName = OwnedCalendarIdentity.DISPLAY_NAME,
                ownerEmail = profileEmail,
                color = DEFAULT_COLOR,
                visible = true,
                syncEvents = true,
                canModifyEvents = false,
                allowedReminderMethods = setOf(OwnedReminderMethod.ALERT),
            )
        }

        private const val DEFAULT_COLOR: Int = -12_625_339
    }
}

internal sealed interface CalendarOwnershipPlan {
    data class Use(val row: OwnedCalendarRow) : CalendarOwnershipPlan

    data object Create : CalendarOwnershipPlan

    data class Repair(val deleteIds: List<Long>) : CalendarOwnershipPlan
}

internal object OwnedCalendarPlanner {
    fun plan(rows: List<OwnedCalendarRow>): CalendarOwnershipPlan {
        val owned = rows.filter { row -> row.hasCompleteIdentity() }
        return when (owned.size) {
            0 -> CalendarOwnershipPlan.Create
            1 -> CalendarOwnershipPlan.Use(owned.single())
            else -> CalendarOwnershipPlan.Repair(owned.map(OwnedCalendarRow::id).sorted())
        }
    }

    private fun OwnedCalendarRow.hasCompleteIdentity(): Boolean =
        accountName == OwnedCalendarIdentity.ACCOUNT_NAME &&
            accountType == OwnedCalendarIdentity.ACCOUNT_TYPE &&
            internalName == OwnedCalendarIdentity.INTERNAL_NAME
}
