package net.mixalich7b.exchangesync

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppCompositionTest {
    @Test
    fun `one application DataStore composes every synchronization adapter and worker action`() {
        val source = File("src/main/kotlin/net/mixalich7b/exchangesync/AppContainer.kt").readText()

        assertEquals(1, source.windowed("preferencesDataStore(".length).count { it == "preferencesDataStore(" })
        assertTrue(
            setOf(
                "DataStoreConnectionProfileRepository",
                "DataStoreSynchronizationStateRepository",
                "AndroidActiveSyncProcessRuntime",
                "AndroidOwnedCalendarAdapter",
                "AndroidSyncPermissionPort",
                "WorkManagerSyncScheduler",
                "SyncProblemNotificationReporter",
                "SynchronizationLifecycle",
                "RequestSynchronizationNow",
                "RequestPeriodicSynchronization",
                "ExecuteSynchronizationSlice",
                "SyncWorkerFactory",
            ).all(source::contains),
        )
    }

    @Test
    fun `one ActiveSync process runtime serves connection verification and calendar synchronization`() {
        val source = File("src/main/kotlin/net/mixalich7b/exchangesync/AppContainer.kt").readText()

        assertEquals(
            1,
            source.windowed("AndroidActiveSyncProcessRuntime(".length)
                .count { candidate -> candidate == "AndroidActiveSyncProcessRuntime(" },
        )
        assertTrue("activeSyncRuntime.connectionVerifier" in source)
        assertTrue("activeSyncRuntime.remoteCalendar" in source)
    }

    @Test
    fun `application owns WorkerFactory and startup notification channel`() {
        val source = File("src/main/kotlin/net/mixalich7b/exchangesync/ExchangeSyncApplication.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue("Configuration.Provider" in source)
        assertTrue("setWorkerFactory(container.workerFactory)" in source)
        assertTrue("container.syncProblems.createChannel()" in source)
        assertTrue("android:name=\".ExchangeSyncApplication\"" in manifest)
    }

    @Test
    fun `activity owns permission launchers and passes only callbacks into settings feature`() {
        val source = File("src/main/kotlin/net/mixalich7b/exchangesync/MainActivity.kt").readText()

        assertTrue("ActivityResultContracts.RequestMultiplePermissions" in source)
        assertTrue("ActivityResultContracts.RequestPermission" in source)
        assertTrue("private val notificationPermissionSettingsLauncher" in source)
        assertTrue("notificationPermissionSettingsLauncher.launch(" in source)
        assertTrue("onRequestCalendarPermission = ::requestCalendarPermissions" in source)
        assertTrue("onRequestNotificationPermission = ::requestNotificationPermission" in source)
        assertTrue("synchronizationStateRepository = container.synchronizationStateRepository" in source)
    }
}
