package net.mixalich7b.exchangesync.feature.settings

import net.mixalich7b.exchangesync.core.BootstrapState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingsUiStateMapperTest {
    @Test
    fun `unconfigured state maps to application identity and status message`() {
        val uiState = toSettingsUiState(
            applicationName = "Exchange Sync",
            bootstrapState = BootstrapState.Unconfigured,
        )

        assertEquals("Exchange Sync", uiState.applicationName)
        assertEquals("Exchange connection is not configured", uiState.statusMessage)
    }
}
