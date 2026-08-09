package net.mixalich7b.exchangesync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.security.KeyChain
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import net.mixalich7b.exchangesync.feature.settings.SettingsRoute
import net.mixalich7b.exchangesync.feature.settings.SettingsViewModel
import kotlinx.coroutines.launch

public class MainActivity : ComponentActivity() {
    private lateinit var container: AppContainer
    private lateinit var calendarPermissionRequestTracker: CalendarPermissionRequestTracker
    private val calendarPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            lifecycleScope.launch { container.synchronizationLifecycle.onCalendarPermissionResult() }
        }
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            lifecycleScope.launch { container.synchronizationLifecycle.onNotificationPermissionResult() }
        }
    private val calendarPermissionSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            lifecycleScope.launch { container.synchronizationLifecycle.onCalendarPermissionResult() }
        }
    private val notificationPermissionSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            lifecycleScope.launch { container.synchronizationLifecycle.onNotificationPermissionResult() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = (application as ExchangeSyncApplication).container
        calendarPermissionRequestTracker =
            CalendarPermissionRequestTracker(
                savedInstanceState
                    ?.takeIf { state -> state.containsKey(CALENDAR_PERMISSION_REQUEST_GENERATION) }
                    ?.getLong(CALENDAR_PERMISSION_REQUEST_GENERATION),
            )
        val factory =
            viewModelFactory {
                initializer {
                    SettingsViewModel(
                        repository = container.repository,
                        saveConnection = container.saveConnection,
                        verifyConnection = container.verifyConnection,
                        synchronizationStateRepository = container.synchronizationStateRepository,
                        requestSynchronization = container.requestSynchronization,
                        synchronizationLifecycle = container.synchronizationLifecycle,
                    )
                }
            }
        val viewModel = ViewModelProvider(this, factory)[SettingsViewModel::class.java]

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.synchronizationStateRepository.states.collect { syncState ->
                    if (calendarPermissionRequestTracker.shouldRequest(syncState)) {
                        launchCalendarPermissionDialog()
                    }
                }
            }
        }

        setContent {
            MaterialTheme {
                SettingsRoute(
                    viewModel = viewModel,
                    onSelectCertificate = { chooseClientCertificate(viewModel) },
                    onRequestCalendarPermission = ::requestCalendarPermissions,
                    onRequestNotificationPermission = ::requestNotificationPermission,
                    onOpenNotificationSettings = ::openNotificationSettings,
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        calendarPermissionRequestTracker.lastRequestedGeneration?.let { generation ->
            outState.putLong(CALENDAR_PERMISSION_REQUEST_GENERATION, generation)
        }
        super.onSaveInstanceState(outState)
    }

    private fun requestCalendarPermissions() {
        val destination =
            calendarPermissionRequestTracker.destinationForManualRequest(
                readGranted =
                    checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED,
                writeGranted =
                    checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED,
                shouldShowReadRationale = shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR),
                shouldShowWriteRationale = shouldShowRequestPermissionRationale(Manifest.permission.WRITE_CALENDAR),
            )
        if (destination == CalendarPermissionDestination.APPLICATION_SETTINGS) {
            calendarPermissionSettingsLauncher.launch(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ),
            )
        } else {
            launchCalendarPermissionDialog()
        }
    }

    private fun launchCalendarPermissionDialog() {
        calendarPermissionLauncher.launch(
            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        )
    }

    private fun requestNotificationPermission() {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openNotificationSettings() {
        notificationPermissionSettingsLauncher.launch(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            },
        )
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

    private companion object {
        const val CALENDAR_PERMISSION_REQUEST_GENERATION: String = "calendar_permission_request_generation"
    }
}
