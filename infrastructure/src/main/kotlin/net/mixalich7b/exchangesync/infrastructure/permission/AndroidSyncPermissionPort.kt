package net.mixalich7b.exchangesync.infrastructure.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import net.mixalich7b.exchangesync.core.sync.SyncPermissionPort

internal object CalendarPermissionPolicy {
    fun hasCalendarAccess(
        readGranted: Boolean,
        writeGranted: Boolean,
    ): Boolean = readGranted && writeGranted
}

public class AndroidSyncPermissionPort(
    context: Context,
) : SyncPermissionPort {
    private val applicationContext = context.applicationContext

    override fun hasCalendarAccess(): Boolean =
        CalendarPermissionPolicy.hasCalendarAccess(
            readGranted = isGranted(Manifest.permission.READ_CALENDAR),
            writeGranted = isGranted(Manifest.permission.WRITE_CALENDAR),
        )

    override fun hasNotificationAccess(): Boolean = isGranted(Manifest.permission.POST_NOTIFICATIONS)

    private fun isGranted(permission: String): Boolean =
        applicationContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
