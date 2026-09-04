package org.usbadvance

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import org.usbadvance.feature.formatter.ui.IsoBurnerScreen
import org.usbadvance.feature.formatter.vm.FormatterViewModel
import org.usbadvance.ui.theme.UsbAdvanceTheme

class MainActivity : ComponentActivity() {

    private lateinit var usbHostDetector: UsbHostDetector
    private lateinit var deviceListViewModel: DeviceListViewModel
    private lateinit var formatterViewModel: FormatterViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbHostDetector = UsbHostDetector(applicationContext)
        deviceListViewModel = DeviceListViewModel(usbHostDetector)
        formatterViewModel = FormatterViewModel()

        setContent {
            UsbAdvanceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UsbAdvanceNavGraph(
                        deviceListViewModel = deviceListViewModel,
                        formatterViewModel = formatterViewModel
                    )
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
    formatterViewModel: FormatterViewModel
) {
    val navController = rememberNavController()
    var selectedDevice by remember { mutableStateOf<IStorageDevice?>(null) }

    val deviceState by deviceListViewModel.uiState.collectAsState()
    val connectedDevices = deviceState.devices

    // Retorna automaticamente para a lista se o dispositivo selecionado for desconectado fisicamente
    LaunchedEffect(connectedDevices, selectedDevice) {
        val current = selectedDevice
        if (current != null && !connectedDevices.any { it.id == current.id }) {
            selectedDevice = null
            navController.popBackStack("device_list", inclusive = false)
        }
    }

    NavHost(
        navController = navController,
        startDestination = "device_list"
    ) {
        composable("device_list") {
            DeviceListScreen(
                viewModel = deviceListViewModel,
                onDeviceSelected = { device ->
                    deviceListViewModel.selectDevice(device) { readyDevice ->
                        selectedDevice = readyDevice
                        formatterViewModel.selectDevice(readyDevice)
                        navController.navigate("device_hub")
                    }
                }
            )
        }

        composable("device_hub") {
            selectedDevice?.let { dev ->
                DeviceHubScreen(
                    device = dev,
                    onNavigateToFormat = {
                        formatterViewModel.selectDevice(dev)
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
                }
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
                    }
                )
            }
        }
    }
}
