package net.mixalich7b.exchangesync

import android.net.Uri
import android.os.Bundle
import android.security.KeyChain
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import net.mixalich7b.exchangesync.feature.settings.SettingsRoute
import net.mixalich7b.exchangesync.feature.settings.SettingsViewModel

public class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = AppContainer(applicationContext)
        val factory =
            viewModelFactory {
                initializer {
                    SettingsViewModel(
                        repository = container.repository,
                        saveConnection = container.saveConnection,
                        verifyConnection = container.verifyConnection,
                    )
                }
            }
        val viewModel = ViewModelProvider(this, factory)[SettingsViewModel::class.java]

        setContent {
            MaterialTheme {
                SettingsRoute(
                    viewModel = viewModel,
                    onSelectCertificate = { chooseClientCertificate(viewModel) },
                )
            }
        }
    }

    private fun chooseClientCertificate(viewModel: SettingsViewModel) {
        val state = viewModel.state.value
        if (!state.isCertificateSelectionEnabled) return
        val serverUri =
            state.serverHost
                .takeIf(String::isNotBlank)
                ?.let { host ->
                    Uri.Builder()
                        .scheme("https")
                        .encodedAuthority("$host:443")
                        .build()
                }

        KeyChain.choosePrivateKeyAlias(
            this,
            { alias -> runOnUiThread { viewModel.onCertificateSelected(alias) } },
            null,
            null,
            serverUri,
            state.clientCertificateAlias,
        )
    }
}
