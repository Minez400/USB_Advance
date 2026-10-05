package org.usbadvance

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.usbadvance.core.storage.api.IStorageDevice
import org.usbadvance.core.usb.detector.UsbHostDetector
import org.usbadvance.feature.devicelist.ui.DeviceHubScreen
import org.usbadvance.feature.devicelist.ui.DeviceListScreen
import org.usbadvance.feature.devicelist.vm.DeviceListViewModel
import org.usbadvance.feature.diagnostic.ui.DiagnosticScreen
import org.usbadvance.feature.diagnostic.ui.FakeDetectorScreen
import org.usbadvance.feature.formatter.ui.FormatWizardScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import org.usbadvance.feature.formatter.ui.IsoBurnerScreen
import org.usbadvance.feature.formatter.vm.FormatterViewModel
import org.usbadvance.feature.settings.data.SettingsManager
import org.usbadvance.feature.settings.ui.SettingsScreen
import org.usbadvance.feature.settings.vm.SettingsViewModel
import org.usbadvance.feature.explorer.ui.FileExplorerScreen
import org.usbadvance.feature.explorer.vm.FileExplorerViewModel
import org.usbadvance.feature.retro.ui.RetroHubScreen
import org.usbadvance.feature.retro.vm.RetroHubViewModel
import org.usbadvance.ui.overlay.DeveloperPerformanceOverlay
import org.usbadvance.ui.theme.UsbAdvanceTheme

/**
 * Holds process-lifetime objects that must survive Activity recreation
 * (rotation, dark mode, returning from the SAF picker under memory pressure).
 */
class AppSessionViewModel(app: android.app.Application) : androidx.lifecycle.AndroidViewModel(app) {
    val usbHostDetector = UsbHostDetector(app.applicationContext)
    var selectedDevice by mutableStateOf<IStorageDevice?>(null)

    override fun onCleared() {
        super.onCleared()
        usbHostDetector.stopListening()
    }
}

class MainActivity : AppCompatActivity() {

    private val session: AppSessionViewModel by viewModels()
    private val deviceListViewModel: DeviceListViewModel by viewModels {
        viewModelFactory { initializer { DeviceListViewModel(session.usbHostDetector) } }
    }
    private val formatterViewModel: FormatterViewModel by viewModels()
    private val fileExplorerViewModel: FileExplorerViewModel by viewModels()
    private val retroHubViewModel: RetroHubViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels {
        viewModelFactory { initializer { SettingsViewModel(SettingsManager.getInstance(applicationContext)) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val appSettings by settingsViewModel.settings.collectAsStateWithLifecycle()

            UsbAdvanceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        UsbAdvanceNavGraph(
                            session = session,
                            deviceListViewModel = deviceListViewModel,
                            formatterViewModel = formatterViewModel,
                            fileExplorerViewModel = fileExplorerViewModel,
                            retroHubViewModel = retroHubViewModel,
                            settingsViewModel = settingsViewModel
                        )

                        if (appSettings.developerMode) {
                            DeveloperPerformanceOverlay(
                                visible = true,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 10.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        session.usbHostDetector.refreshDevices()
    }
}

/** Renders [content] with the selected device, or returns to the main screen if it was lost. */
@Composable
private fun RequireDevice(
    device: IStorageDevice?,
    navController: androidx.navigation.NavHostController,
    content: @Composable (IStorageDevice) -> Unit
) {
    if (device != null) {
        content(device)
    } else {
        LaunchedEffect(Unit) {
            if (!navController.popBackStack("main_screen", inclusive = false)) {
                navController.navigate("main_screen")
            }
        }
    }
}

@Composable
fun UsbAdvanceNavGraph(
    session: AppSessionViewModel,
    deviceListViewModel: DeviceListViewModel,
    formatterViewModel: FormatterViewModel,
    fileExplorerViewModel: FileExplorerViewModel,
    retroHubViewModel: RetroHubViewModel,
    settingsViewModel: SettingsViewModel
) {
    val navController = rememberNavController()
    var selectedDevice by session::selectedDevice

    val deviceState by deviceListViewModel.uiState.collectAsStateWithLifecycle()
    val connectedDevices = deviceState.devices
    val appSettings by settingsViewModel.settings.collectAsStateWithLifecycle()

    // Automatically pop back to the main screen if the currently selected device is physically unplugged
    LaunchedEffect(connectedDevices, selectedDevice) {
        val current = selectedDevice
        if (current != null && !connectedDevices.any { it.id == current.id }) {
            selectedDevice = null
            navController.popBackStack("main_screen", inclusive = false)
        }
    }

    NavHost(
        navController = navController,
        startDestination = "main_screen"
    ) {
        composable("main_screen") {
            org.usbadvance.ui.MainScreen(
                deviceListViewModel = deviceListViewModel,
                settingsViewModel = settingsViewModel,
                rootNavController = navController,
                onDeviceSelected = { device ->
                    deviceListViewModel.selectDevice(device) { readyDevice ->
                        selectedDevice = readyDevice
                        formatterViewModel.selectDevice(
                            device = readyDevice,
                            preferredFs = appSettings.defaultFileSystem,
                            preferredQuickFormat = appSettings.defaultQuickFormat
                        )
                        navController.navigate("device_hub")
                    }
                },
                onNavigateToExplorer = { device ->
                    deviceListViewModel.selectDevice(device) { readyDevice ->
                        selectedDevice = readyDevice
                        navController.navigate("file_explorer")
                    }
                },
                onNavigateToBenchmark = { device ->
                    selectedDevice = device
                    navController.navigate("diagnostic")
                },
                onNavigateToFakeDetector = { device ->
                    selectedDevice = device
                    navController.navigate("fake_detector")
                }
            )
        }

        composable("device_hub") {
            RequireDevice(selectedDevice, navController) { dev ->
                DeviceHubScreen(
                    device = dev,
                    onNavigateToFormat = {
                        formatterViewModel.selectDevice(
                            device = dev,
                            preferredFs = appSettings.defaultFileSystem,
                            preferredQuickFormat = appSettings.defaultQuickFormat
                        )
                        navController.navigate("format_wizard")
                    },
                    onNavigateToIsoBurner = {
                        navController.navigate("iso_burner")
                    },
                    onNavigateToExplorer = {
                        navController.navigate("file_explorer")
                    },
                    onNavigateToRetroHub = {
                        navController.navigate("retro_hub")
                    },
                    onNavigateToFakeDetector = {
                        navController.navigate("fake_detector")
                    },
                    onNavigateToBenchmark = {
                        navController.navigate("diagnostic")
                    },
                    onEjectDevice = {
                        deviceListViewModel.ejectDevice(dev)
                    },
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable("file_explorer") {
            RequireDevice(selectedDevice, navController) { dev ->
                FileExplorerScreen(
                    device = dev,
                    viewModel = fileExplorerViewModel,
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable("retro_hub") {
            RequireDevice(selectedDevice, navController) { dev ->
                RetroHubScreen(
                    device = dev,
                    viewModel = retroHubViewModel,
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable("format_wizard") {
            FormatWizardScreen(
                viewModel = formatterViewModel,
                onBack = {
                    navController.popBackStack()
                },
                requireStrictConfirmation = appSettings.strictSafetyConfirmation
            )
        }

        composable("iso_burner") {
            RequireDevice(selectedDevice, navController) { dev ->
                IsoBurnerScreen(
                    device = dev,
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable("fake_detector") {
            RequireDevice(selectedDevice, navController) { dev ->
                FakeDetectorScreen(
                    device = dev,
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable("diagnostic") {
            RequireDevice(selectedDevice, navController) { dev ->
                DiagnosticScreen(
                    device = dev,
                    onBack = {
                        navController.popBackStack()
                    },
                    ioBlockSizeBytes = appSettings.ioBlockSizeBytes
                )
            }
        }

        composable("settings") {
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
