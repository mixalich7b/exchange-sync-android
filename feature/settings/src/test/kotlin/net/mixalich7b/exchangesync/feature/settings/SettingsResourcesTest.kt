package net.mixalich7b.exchangesync.feature.settings

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsResourcesTest {
    @Test
    fun `domain login examples escape the backslash for Android resources`() {
        val stringsXml = File("src/main/res/values/strings.xml").readText()
        val accountExamples =
            Regex("""<string name="(?:account_label|error_account)">([^<]+)</string>""")
                .findAll(stringsXml)
                .map { match -> match.groupValues[1] }
                .toList()

        assertTrue(accountExamples.isNotEmpty())
        assertTrue(accountExamples.all { example -> "domain\\\\login" in example })
    }

    @Test
    fun `connection recheck and TLS diagnostics have localized safe labels`() {
        val stringsXml = File("src/main/res/values/strings.xml").readText()
        val names =
            Regex("""<string name="([^"]+)">""")
                .findAll(stringsXml)
                .map { match -> match.groupValues[1] }
                .toSet()

        assertTrue(
            setOf(
                "recheck_connection",
                "save_changes_before_recheck",
                "connection_rechecking",
                "tls_diagnostics_title",
                "tls_terminal_host",
                "tls_certificate_number",
                "tls_subject",
                "tls_issuer",
                "tls_serial_number",
                "tls_valid_from",
                "tls_valid_until",
                "tls_fingerprint",
                "error_server_certificate_diagnostics",
            ).all(names::contains),
        )
        assertTrue("<string name=\"tls_pem\"" !in stringsXml)
        assertTrue("<string name=\"tls_der\"" !in stringsXml)
        assertTrue("<string name=\"tls_private_key\"" !in stringsXml)
    }

    @Test
    fun `synchronization controls phases permissions and safe problems are localized`() {
        val stringsXml = File("src/main/res/values/strings.xml").readText()
        val names =
            Regex("""<string name="([^"]+)">""")
                .findAll(stringsXml)
                .map { it.groupValues[1] }
                .toSet()

        assertTrue(
            setOf(
                "sync_title",
                "sync_disabled",
                "sync_idle",
                "sync_queued",
                "sync_discovering_protocol",
                "sync_discovering_folders",
                "sync_downloading",
                "sync_applying",
                "sync_cancelling",
                "sync_blocked",
                "sync_last_success",
                "sync_now",
                "sync_cancel",
                "sync_disable",
                "sync_enable",
                "sync_grant_calendar_access",
                "sync_open_notification_settings",
                "sync_grant_notification_access",
                "sync_problem_certificate",
                "sync_problem_tls",
                "sync_problem_access",
                "sync_problem_compatibility",
                "sync_problem_primary_calendar",
                "sync_problem_protocol_data",
                "sync_problem_calendar_permission",
                "sync_problem_calendar_provider",
                "sync_problem_availability",
            ).all(names::contains),
        )
    }
}
