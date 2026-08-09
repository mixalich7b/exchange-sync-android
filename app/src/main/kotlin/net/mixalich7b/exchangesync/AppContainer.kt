package net.mixalich7b.exchangesync

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import net.mixalich7b.exchangesync.core.connection.ConnectionProfileRepository
import net.mixalich7b.exchangesync.core.connection.SaveConnection
import net.mixalich7b.exchangesync.core.connection.SaveConnectionAction
import net.mixalich7b.exchangesync.core.connection.VerifyConnection
import net.mixalich7b.exchangesync.core.connection.VerifyConnectionAction
import net.mixalich7b.exchangesync.infrastructure.activesync.AndroidActiveSyncProcessRuntime
import net.mixalich7b.exchangesync.infrastructure.diagnostics.AndroidSyncDiagnostics
import net.mixalich7b.exchangesync.infrastructure.calendar.AndroidOwnedCalendarAdapter
import net.mixalich7b.exchangesync.infrastructure.notification.SyncProblemNotificationReporter
import net.mixalich7b.exchangesync.infrastructure.permission.AndroidSyncPermissionPort
import net.mixalich7b.exchangesync.infrastructure.persistence.DataStoreConnectionProfileRepository
import net.mixalich7b.exchangesync.infrastructure.persistence.DataStoreSynchronizationStateRepository
import net.mixalich7b.exchangesync.infrastructure.work.WorkManagerSyncScheduler
import net.mixalich7b.exchangesync.infrastructure.work.AndroidSyncClock
import net.mixalich7b.exchangesync.infrastructure.work.SyncWorkerFactory
import net.mixalich7b.exchangesync.core.sync.ExecuteSynchronizationSlice
import net.mixalich7b.exchangesync.core.sync.RequestPeriodicSynchronization
import net.mixalich7b.exchangesync.core.sync.RequestSynchronizationNow
import net.mixalich7b.exchangesync.core.sync.SynchronizationLifecycle
import net.mixalich7b.exchangesync.core.sync.SynchronizationMutationLock
import net.mixalich7b.exchangesync.core.sync.ReconcileSynchronizationScheduling

private val Context.applicationDataStore by preferencesDataStore(name = "connection_profile")

internal class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext
    private val dataStore = applicationContext.applicationDataStore
    private val synchronizationMutationLock = SynchronizationMutationLock()
    private val activeSyncRuntime = AndroidActiveSyncProcessRuntime(applicationContext)
    private val syncDiagnostics = AndroidSyncDiagnostics()

    val syncNotificationResources: SyncNotificationResources =
        SyncNotificationResources.from(applicationContext)

    val repository: ConnectionProfileRepository =
        DataStoreConnectionProfileRepository(dataStore)

    val synchronizationStateRepository =
        DataStoreSynchronizationStateRepository(dataStore, mutationLock = synchronizationMutationLock)

    val syncPermissions = AndroidSyncPermissionPort(applicationContext)

    val syncScheduler = WorkManagerSyncScheduler(applicationContext)

    val ownedCalendar =
        AndroidOwnedCalendarAdapter(
            context = applicationContext,
            profileRepository = repository,
            stateRepository = synchronizationStateRepository,
            mutationLock = synchronizationMutationLock,
        )

    val syncProblems =
        SyncProblemNotificationReporter(
            context = applicationContext,
            permissions = syncPermissions,
            stateRepository = synchronizationStateRepository,
            resources = syncNotificationResources.toInfrastructureResources(),
            settingsActivityClass = MainActivity::class.java,
        )

    val synchronizationLifecycle =
        SynchronizationLifecycle(
            stateRepository = synchronizationStateRepository,
            scheduler = syncScheduler,
            permissions = syncPermissions,
            ownedCalendar = ownedCalendar,
            problems = syncProblems,
            profileActivationCommitter = synchronizationStateRepository,
            mutationLock = synchronizationMutationLock,
        )

    val requestSynchronization =
        RequestSynchronizationNow(
            stateRepository = synchronizationStateRepository,
            scheduler = syncScheduler,
        )

    val requestPeriodicSynchronization =
        RequestPeriodicSynchronization(
            stateRepository = synchronizationStateRepository,
            scheduler = syncScheduler,
        )

    val reconcileSynchronizationScheduling =
        ReconcileSynchronizationScheduling(
            stateRepository = synchronizationStateRepository,
            scheduler = syncScheduler,
            problems = syncProblems,
            resumePendingCalendarCleanup = {
                synchronizationLifecycle.onCalendarPermissionResult()
            },
        )

    private val remoteCalendar = activeSyncRuntime.remoteCalendar

    val executeSynchronization =
        ExecuteSynchronizationSlice(
            stateRepository = synchronizationStateRepository,
            profileRepository = repository,
            remoteCalendar = remoteCalendar,
            ownedCalendar = ownedCalendar,
            scheduler = syncScheduler,
            permissions = syncPermissions,
            problems = syncProblems,
            clock = AndroidSyncClock,
            diagnostics = syncDiagnostics,
        )

    val workerFactory =
        SyncWorkerFactory(
            requestPeriodicSynchronization = requestPeriodicSynchronization,
            executeSynchronization = executeSynchronization,
        )

    private val verifier = activeSyncRuntime.connectionVerifier

    val verifyConnection: VerifyConnectionAction = VerifyConnection(verifier)

    val saveConnection: SaveConnectionAction =
        SaveConnection(
            repository = repository,
            verifyConnection = verifyConnection,
            synchronizationActivator = synchronizationLifecycle,
        )
}
