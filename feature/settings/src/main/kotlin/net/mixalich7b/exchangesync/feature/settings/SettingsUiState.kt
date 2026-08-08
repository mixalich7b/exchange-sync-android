package net.mixalich7b.exchangesync.feature.settings

import net.mixalich7b.exchangesync.core.BootstrapState

public data class SettingsUiState(
    public val applicationName: String,
    public val statusMessage: String,
)

public fun toSettingsUiState(
    applicationName: String,
    bootstrapState: BootstrapState,
): SettingsUiState = when (bootstrapState) {
    BootstrapState.Unconfigured -> SettingsUiState(
        applicationName = applicationName,
        statusMessage = "Exchange connection is not configured",
    )
}
