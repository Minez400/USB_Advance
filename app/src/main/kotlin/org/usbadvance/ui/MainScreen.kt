package org.usbadvance.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.usbadvance.R
import org.usbadvance.core.storage.api.IStorageDevice
import org.usbadvance.feature.devicelist.ui.DeviceListScreen
import org.usbadvance.feature.devicelist.vm.DeviceListViewModel
import org.usbadvance.feature.diagnostic.ui.ToolsHubScreen
import org.usbadvance.feature.settings.ui.SettingsScreen
import org.usbadvance.feature.settings.vm.SettingsViewModel

sealed class BottomNavItem(val route: String, val titleRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Devices : BottomNavItem("device_list", R.string.bottom_nav_devices, Icons.AutoMirrored.Filled.List)
    object Tools : BottomNavItem("tools_hub", R.string.tools_nav_title, Icons.Default.Build)
    object Settings : BottomNavItem("settings", R.string.settings_title, Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    deviceListViewModel: DeviceListViewModel,
    settingsViewModel: SettingsViewModel,
    rootNavController: NavController,
    onDeviceSelected: (IStorageDevice) -> Unit,
    onNavigateToBenchmark: (IStorageDevice) -> Unit,
    onNavigateToFakeDetector: (IStorageDevice) -> Unit
) {
    val navController = rememberNavController()
    val items = listOf(BottomNavItem.Devices, BottomNavItem.Tools, BottomNavItem.Settings)

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        topBar = {
            val item = items.find { it.route == currentRoute }
            val title = if (item != null) stringResource(item.titleRes) else stringResource(R.string.app_name)
            TopAppBar(
                title = { Text(text = title, fontWeight = FontWeight.Bold, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0B0F19),
                    scrolledContainerColor = Color(0xFF0B0F19),
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF131A29),
                contentColor = Color.White,
                tonalElevation = 0.dp
            ) {
                items.forEach { item ->
                    val isSelected = currentRoute == item.route
                    val label = stringResource(item.titleRes)
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = label) },
                        label = {
                            Text(
                                text = label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        selected = isSelected,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF00E5FF),
                            unselectedIconColor = Color(0xFF94A3B8),
                            selectedTextColor = Color(0xFF00E5FF),
                            unselectedTextColor = Color(0xFF94A3B8),
                            indicatorColor = Color(0xFF00E5FF).copy(alpha = 0.20f)
                        )
                    )
                }
            }
        },
        containerColor = Color(0xFF0B0F19)
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = BottomNavItem.Devices.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(BottomNavItem.Devices.route) {
                DeviceListScreen(viewModel = deviceListViewModel, onDeviceSelected = onDeviceSelected)
            }
            composable(BottomNavItem.Tools.route) {
                ToolsHubScreen(
                    deviceListViewModel = deviceListViewModel,
                    onNavigateToBenchmark = onNavigateToBenchmark,
                    onNavigateToFakeDetector = onNavigateToFakeDetector
                )
            }
            composable(BottomNavItem.Settings.route) {
                SettingsScreen(viewModel = settingsViewModel, onBack = null)
            }
        }
    }
}
