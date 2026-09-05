package org.usbadvance

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
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
import org.usbadvance.ui.overlay.DeveloperPerformanceOverlay
import org.usbadvance.ui.theme.UsbAdvanceTheme

class MainActivity : AppCompatActivity() {

    private lateinit var usbHostDetector: UsbHostDetector
    private lateinit var deviceListViewModel: DeviceListViewModel
    private lateinit var formatterViewModel: FormatterViewModel
    private lateinit var settingsManager: SettingsManager
    private lateinit var settingsViewModel: SettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsManager = SettingsManager.getInstance(applicationContext)
        settingsViewModel = SettingsViewModel(settingsManager)

        usbHostDetector = UsbHostDetector(applicationContext)
        deviceListViewModel = DeviceListViewModel(usbHostDetector)
        formatterViewModel = FormatterViewModel()

        setContent {
            val appSettings by settingsViewModel.settings.collectAsStateWithLifecycle()

            LaunchedEffect(appSettings.enableFakeUsbDrive) {
                deviceListViewModel.setEnableFakeUsb(appSettings.enableFakeUsbDrive)
            }

            UsbAdvanceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        UsbAdvanceNavGraph(
                            deviceListViewModel = deviceListViewModel,
                            formatterViewModel = formatterViewModel,
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
        if (::usbHostDetector.isInitialized) {
            usbHostDetector.refreshDevices()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::usbHostDetector.isInitialized) {
            usbHostDetector.stopListening()
        }
    }
}

@Composable
fun UsbAdvanceNavGraph(
    deviceListViewModel: DeviceListViewModel,
    formatterViewModel: FormatterViewModel,
    settingsViewModel: SettingsViewModel
) {
    val navController = rememberNavController()
    var selectedDevice by remember { mutableStateOf<IStorageDevice?>(null) }

    val deviceState by deviceListViewModel.uiState.collectAsStateWithLifecycle()
    val connectedDevices = deviceState.devices
    val appSettings by settingsViewModel.settings.collectAsStateWithLifecycle()

    // Automatically pop back to device list if the currently selected device is physically unplugged
    LaunchedEffect(connectedDevices, selectedDevice) {
        val current = selectedDevice
        if (current != null && !connectedDevices.any { it.id == current.id }) {
            selectedDevice = null
            navController.popBackStack("device_list", inclusive = false)
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
            selectedDevice?.let { dev ->
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
            selectedDevice?.let { dev ->
                IsoBurnerScreen(
                    device = dev,
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable("fake_detector") {
            selectedDevice?.let { dev ->
                FakeDetectorScreen(
                    device = dev,
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable("diagnostic") {
            selectedDevice?.let { dev ->
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
