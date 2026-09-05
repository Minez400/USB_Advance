package org.usbadvance.feature.diagnostic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.usbadvance.R
import org.usbadvance.core.storage.api.IStorageDevice
import org.usbadvance.feature.devicelist.vm.DeviceListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsHubScreen(
    deviceListViewModel: DeviceListViewModel,
    onNavigateToBenchmark: (IStorageDevice) -> Unit,
    onNavigateToFakeDetector: (IStorageDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by deviceListViewModel.uiState.collectAsStateWithLifecycle()
    val devices = state.devices

    var selectedDeviceId by remember(devices) {
        mutableStateOf(devices.firstOrNull()?.id)
    }

    val currentDevice = devices.firstOrNull { it.id == selectedDeviceId } ?: devices.firstOrNull()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F19))
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        if (devices.isEmpty()) {
            // Empty state when no USB drive is connected
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF131A29))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(20.dp))
                    .padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.UsbOff,
                            contentDescription = null,
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Text(
                        text = stringResource(R.string.tools_no_device_title),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Text(
                        text = stringResource(R.string.tools_no_device_desc),
                        fontSize = 13.sp,
                        color = Color(0xFF94A3B8),
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            }
        } else {
            // Target Device Selector Card
            Text(
                text = stringResource(R.string.tools_target_device_header),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF64748B),
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 4.dp)
            )

            if (devices.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    devices.forEach { dev ->
                        val isSelected = dev.id == currentDevice?.id
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedDeviceId = dev.id },
                            label = { Text(dev.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00E5FF).copy(alpha = 0.2f),
                                selectedLabelColor = Color(0xFF00E5FF),
                                containerColor = Color(0xFF131A29),
                                labelColor = Color(0xFF94A3B8)
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                selectedBorderColor = Color(0xFF00E5FF),
                                borderColor = Color(0xFF2E3D5B)
                            )
                        )
                    }
                }
            }

            currentDevice?.let { dev ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF131A29), Color(0xFF162035))
                            )
                        )
                        .border(1.dp, Color(0xFF2E3D5B), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E5FF).copy(alpha = 0.12f))
                                .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Usb,
                                contentDescription = null,
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = dev.name,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(
                                    R.string.hub_device_info,
                                    dev.geometry.getFormattedCapacity(),
                                    dev.geometry.sectorSize
                                ),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF00E5FF)
                            )
                        }
                    }
                }
            }
        }

        // Diagnostic & Benchmark Tools Section
        Text(
            text = stringResource(R.string.tools_section_diagnostics),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF64748B),
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
        )

        // Tool 1: Fake Flash Drive Detector
        ToolFeatureCard(
            icon = Icons.Default.Shield,
            title = stringResource(R.string.tools_fake_title),
            subtitle = stringResource(R.string.tools_fake_desc),
            badge = stringResource(R.string.tools_fake_badge),
            accentColor = Color(0xFFD500F9),
            enabled = currentDevice != null,
            onClick = {
                currentDevice?.let { dev ->
                    deviceListViewModel.selectDevice(dev) { readyDevice ->
                        onNavigateToFakeDetector(readyDevice)
                    }
                }
            }
        )

        // Tool 2: Speed Benchmark
        ToolFeatureCard(
            icon = Icons.Default.Speed,
            title = stringResource(R.string.tools_bench_title),
            subtitle = stringResource(R.string.tools_bench_desc),
            badge = stringResource(R.string.tools_bench_badge),
            accentColor = Color(0xFF00E676),
            enabled = currentDevice != null,
            onClick = {
                currentDevice?.let { dev ->
                    deviceListViewModel.selectDevice(dev) { readyDevice ->
                        onNavigateToBenchmark(readyDevice)
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ToolFeatureCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String,
    accentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.45f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF131A29).copy(alpha = alpha))
            .border(1.dp, Color(0xFF2E3D5B).copy(alpha = alpha), RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(18.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(accentColor.copy(alpha = 0.15f))
                        .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(accentColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badge,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = if (enabled) Color(0xFF94A3B8) else Color(0xFF475569),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = Color(0xFF94A3B8),
                lineHeight = 17.sp
            )
        }
    }
}
