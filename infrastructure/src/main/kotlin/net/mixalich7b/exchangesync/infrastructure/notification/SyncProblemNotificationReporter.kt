package net.mixalich7b.exchangesync.infrastructure.notification

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.mixalich7b.exchangesync.core.sync.SyncPermissionPort
import net.mixalich7b.exchangesync.core.sync.SyncProblem
import net.mixalich7b.exchangesync.core.sync.SyncProblemReporterPort
import net.mixalich7b.exchangesync.core.sync.SyncStateRepository

public data class SyncProblemNotificationResources(
    public val channelName: String,
    public val channelDescription: String,
    public val title: String,
    public val certificate: String,
    public val tls: String,
    public val access: String,
    public val compatibility: String,
    public val primaryCalendar: String,
    public val protocolData: String,
    public val calendarPermission: String,
    public val calendarProvider: String,
    public val availability: String,
    public val smallIconResourceId: Int,
)

internal data class SyncProblemNotificationSpec(
    val id: Int,
    val generation: Long,
    val title: String,
    val body: String,
    val smallIconResourceId: Int,
    val ongoing: Boolean,
)

internal interface SyncProblemNotificationGateway {
    fun createChannel(resources: SyncProblemNotificationResources)

    fun post(spec: SyncProblemNotificationSpec)

    fun clear(notificationId: Int)
}

internal object SyncProblemNotificationPolicy {
    const val CHANNEL_ID: String = "calendar_sync_problems"
    const val NOTIFICATION_ID: Int = 3_407

    fun spec(
        generation: Long,
        problem: SyncProblem,
        resources: SyncProblemNotificationResources,
    ): SyncProblemNotificationSpec =
        SyncProblemNotificationSpec(
            id = NOTIFICATION_ID,
            generation = generation,
            title = resources.title,
            body = problem.safeText(resources),
            smallIconResourceId = resources.smallIconResourceId,
            ongoing = true,
        )

    private fun SyncProblem.safeText(resources: SyncProblemNotificationResources): String =
        when (this) {
            SyncProblem.CLIENT_CERTIFICATE -> resources.certificate
            SyncProblem.TLS -> resources.tls
            SyncProblem.ACCESS,
            SyncProblem.REDIRECT,
            -> resources.access
            SyncProblem.COMPATIBILITY,
            SyncProblem.UNSUPPORTED_PROVISIONING,
            -> resources.compatibility
            SyncProblem.PRIMARY_CALENDAR -> resources.primaryCalendar
            SyncProblem.REPEATED_INVALID_KEY,
            SyncProblem.PROTOCOL_DATA,
            -> resources.protocolData
            SyncProblem.CALENDAR_PERMISSION -> resources.calendarPermission
            SyncProblem.CALENDAR_PROVIDER,
            SyncProblem.BACKGROUND_SCHEDULING,
            -> resources.calendarProvider
            SyncProblem.TRANSIENT_EXHAUSTED -> resources.availability
        }
}

public class SyncProblemNotificationReporter internal constructor(
    private val permissions: SyncPermissionPort,
    private val stateRepository: SyncStateRepository,
    private val gateway: SyncProblemNotificationGateway,
    private val resources: SyncProblemNotificationResources,
) : SyncProblemReporterPort {
    private val mutationMutex = Mutex()

    public constructor(
        context: Context,
        permissions: SyncPermissionPort,
        stateRepository: SyncStateRepository,
        resources: SyncProblemNotificationResources,
        settingsActivityClass: Class<out Activity>,
    ) : this(
        permissions = permissions,
        stateRepository = stateRepository,
        gateway = AndroidSyncProblemNotificationGateway(context.applicationContext, settingsActivityClass),
        resources = resources,
    )

    public fun createChannel() {
        gateway.createChannel(resources)
    }

    override suspend fun show(
        generation: Long,
        problem: SyncProblem,
    ): Unit =
        mutationMutex.withLock {
            val (hasAccess, current) = observeNotificationPermission(generation)
            if (!hasAccess) return@withLock
            if (current.generation != generation || current.problem != problem) return@withLock
            gateway.post(SyncProblemNotificationPolicy.spec(generation, problem, resources))
        }

    override suspend fun clear(generation: Long): Unit =
        mutationMutex.withLock {
            val (_, current) = observeNotificationPermission(generation)
            if (current.generation != generation || current.problem != null) return@withLock
            gateway.clear(SyncProblemNotificationPolicy.NOTIFICATION_ID)
        }

    private suspend fun observeNotificationPermission(
        generation: Long,
    ): Pair<Boolean, net.mixalich7b.exchangesync.core.sync.SyncState> {
        val hasAccess = permissions.hasNotificationAccess()
        val snapshot = stateRepository.load()
        if (
            snapshot.generation != generation ||
            snapshot.notificationPermissionDenied != hasAccess
        ) {
            return hasAccess to snapshot
        }
        val observed =
            stateRepository.update { current ->
                if (
                    current.generation == generation &&
                    current.notificationPermissionDenied == hasAccess
                ) {
                    current.copy(notificationPermissionDenied = !hasAccess)
                } else {
                    current
                }
            }
        return hasAccess to observed
    }
}

private class AndroidSyncProblemNotificationGateway(
    private val context: Context,
    private val settingsActivityClass: Class<out Activity>,
) : SyncProblemNotificationGateway {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    override fun createChannel(resources: SyncProblemNotificationResources) {
        val channel =
            NotificationChannel(
                SyncProblemNotificationPolicy.CHANNEL_ID,
                resources.channelName,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = resources.channelDescription
            }
        notificationManager.createNotificationChannel(channel)
    }

    override fun post(spec: SyncProblemNotificationSpec) {
        val settingsIntent =
            Intent(context, settingsActivityClass).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                SETTINGS_REQUEST_CODE,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification.Builder(context, SyncProblemNotificationPolicy.CHANNEL_ID)
                .setSmallIcon(spec.smallIconResourceId)
                .setContentTitle(spec.title)
                .setContentText(spec.body)
                .setCategory(Notification.CATEGORY_ERROR)
                .setContentIntent(pendingIntent)
                .setOngoing(spec.ongoing)
                .setAutoCancel(false)
                .build()
        notificationManager.notify(spec.id, notification)
    }

    override fun clear(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    private companion object {
        const val SETTINGS_REQUEST_CODE: Int = 3_407
    }
}
