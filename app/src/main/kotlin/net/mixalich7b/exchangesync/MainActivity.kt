package net.mixalich7b.exchangesync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import net.mixalich7b.exchangesync.core.BootstrapState
import net.mixalich7b.exchangesync.feature.settings.SettingsScreen
import net.mixalich7b.exchangesync.feature.settings.toSettingsUiState

public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uiState = toSettingsUiState(
            applicationName = getString(R.string.app_name),
            bootstrapState = BootstrapState.initial(),
        )

        setContent {
            MaterialTheme {
                SettingsScreen(uiState = uiState)
            }
        }
    }
}
