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
}
