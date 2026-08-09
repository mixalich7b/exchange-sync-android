package net.mixalich7b.exchangesync

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CalendarSyncArchitectureGuardTest {
    private val root = File("..").canonicalFile

    @Test
    fun `manifest has no built-in Exchange SyncAdapter foreground service or exact alarm path`() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val forbidden =
            setOf(
                "android.accounts.AccountAuthenticator",
                "android.content.SyncAdapter",
                "SCHEDULE_EXACT_ALARM",
                "USE_EXACT_ALARM",
            )

        assertFalse(forbidden.any(manifest::contains))
        assertTrue("android.permission.READ_CALENDAR" in manifest)
        assertTrue("android.permission.WRITE_CALENDAR" in manifest)
        assertTrue("android.permission.POST_NOTIFICATIONS" in manifest)
        assertTrue("androidx.startup.InitializationProvider" in manifest)
        assertTrue("android.permission.FOREGROUND_SERVICE" in manifest)
        assertTrue("androidx.work.impl.foreground.SystemForegroundService" in manifest)
        assertTrue("tools:node=\"remove\"" in manifest)
    }

    @Test
    fun `packaged manifest strips WorkManager foreground service and permission`() {
        val manifest =
            File(
                root,
                "app/build/intermediates/packaged_manifests/debug/processDebugManifestForPackage/AndroidManifest.xml",
            ).readText()

        assertFalse("android.permission.FOREGROUND_SERVICE" in manifest)
        assertFalse("androidx.work.impl.foreground.SystemForegroundService" in manifest)
    }

    @Test
    fun `only the accepted four modules exist and settings has no infrastructure dependency`() {
        val settings = File(root, "settings.gradle.kts").readText()
        val includes = Regex("""include\("(:[^"]+)"\)""").findAll(settings).map { it.groupValues[1] }.toSet()
        val featureBuild = File(root, "feature/settings/build.gradle.kts").readText()
        val featureSources =
            File(root, "feature/settings/src/main").walkTopDown().filter(File::isFile).joinToString("\n") { it.readText() }

        assertEquals(setOf(":app", ":core", ":feature:settings", ":infrastructure"), includes)
        assertFalse(":infrastructure" in featureBuild)
        assertFalse("net.mixalich7b.exchangesync.infrastructure" in featureSources)
    }

    @Test
    fun `production source contains no account framework SyncAdapter or foreground worker API`() {
        val production =
            listOf("app/src/main", "core/src/main", "feature/settings/src/main", "infrastructure/src/main")
                .flatMap { relative -> File(root, relative).walkTopDown().filter(File::isFile).toList() }
                .joinToString("\n") { it.readText() }
        val forbidden =
            setOf(
                "AbstractThreadedSyncAdapter",
                "AccountAuthenticator",
                "AccountManager",
                "setForeground(",
                "setForegroundAsync(",
                "AlarmManager",
            )

        assertFalse(forbidden.any(production::contains))
    }
}
