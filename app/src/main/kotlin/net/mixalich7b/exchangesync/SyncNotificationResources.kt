package net.mixalich7b.exchangesync

import android.content.Context
import net.mixalich7b.exchangesync.infrastructure.notification.SyncProblemNotificationResources

internal data class SyncNotificationResources(
    val channelName: String,
    val channelDescription: String,
    val notificationTitle: String,
    val certificateProblem: String,
    val tlsProblem: String,
    val accessProblem: String,
    val compatibilityProblem: String,
    val primaryCalendarProblem: String,
    val protocolDataProblem: String,
    val calendarPermissionProblem: String,
    val calendarProviderProblem: String,
    val availabilityProblem: String,
    val smallIconResourceId: Int,
) {
    companion object {
        fun from(context: Context): SyncNotificationResources =
            SyncNotificationResources(
                channelName = context.getString(R.string.sync_problem_channel_name),
                channelDescription = context.getString(R.string.sync_problem_channel_description),
                notificationTitle = context.getString(R.string.sync_problem_notification_title),
                certificateProblem = context.getString(R.string.sync_problem_certificate),
                tlsProblem = context.getString(R.string.sync_problem_tls),
                accessProblem = context.getString(R.string.sync_problem_access),
                compatibilityProblem = context.getString(R.string.sync_problem_compatibility),
                primaryCalendarProblem = context.getString(R.string.sync_problem_primary_calendar),
                protocolDataProblem = context.getString(R.string.sync_problem_protocol_data),
                calendarPermissionProblem = context.getString(R.string.sync_problem_calendar_permission),
                calendarProviderProblem = context.getString(R.string.sync_problem_calendar_provider),
                availabilityProblem = context.getString(R.string.sync_problem_availability),
                smallIconResourceId = R.drawable.ic_sync_problem,
            )
    }

    fun toInfrastructureResources(): SyncProblemNotificationResources =
        SyncProblemNotificationResources(
            channelName = channelName,
            channelDescription = channelDescription,
            title = notificationTitle,
            certificate = certificateProblem,
            tls = tlsProblem,
            access = accessProblem,
            compatibility = compatibilityProblem,
            primaryCalendar = primaryCalendarProblem,
            protocolData = protocolDataProblem,
            calendarPermission = calendarPermissionProblem,
            calendarProvider = calendarProviderProblem,
            availability = availabilityProblem,
            smallIconResourceId = smallIconResourceId,
        )
}
