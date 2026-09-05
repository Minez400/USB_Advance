package org.usbadvance.feature.devicelist.ui

import org.usbadvance.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.usbadvance.core.storage.api.IStorageDevice
import org.usbadvance.feature.devicelist.vm.DeviceListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    viewModel: DeviceListViewModel,
    onDeviceSelected: (IStorageDevice) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToGameTools: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val emptyRadialBrush = remember {
        Brush.radialGradient(
            listOf(Color(0xFF00E5FF).copy(alpha = 0.15f), Color.Transparent)
        )
    }
    val bannerGradientBrush = remember {
        Brush.linearGradient(
            listOf(Color(0xFF131A29), Color(0xFF1A2438))
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F19))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            if (state.isLoading && state.devices.isNotEmpty()) {
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.TopCenter),
                    color = Color(0xFF00E5FF),
                    trackColor = Color.Transparent
                )
            }

            when {
                state.isLoading && state.devices.isEmpty() -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color(0xFF00E5FF)
                    )
                }
                state.devices.isEmpty() -> {
                    // Styled empty state
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .clip(CircleShape)
                                .background(emptyRadialBrush)
                                .border(1.dp, Color(0xFF2E3D5B), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cable,
                                contentDescription = stringResource(R.string.device_list_empty_icon),
                                modifier = Modifier.size(54.dp),
                                tint = Color(0xFF00E5FF)
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = stringResource(R.string.device_list_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.device_list_empty_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF94A3B8),
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )

                        Spacer(modifier = Modifier.height(28.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            StatusBadge(icon = Icons.Default.Security, label = stringResource(R.string.device_list_secure_isolation))
                            StatusBadge(icon = Icons.Default.Speed, label = stringResource(R.string.device_list_low_level_scsi))
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            // Active USB controller status banner
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(bannerGradientBrush)
                                    .border(1.dp, Color(0xFF2E3D5B), RoundedCornerShape(16.dp))
                                    .padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = stringResource(R.string.device_list_controller_connected),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = Color(0xFF00E5FF)
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = stringResource(R.string.device_list_devices_detected, state.devices.size),
                                            fontSize = 12.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF00E676).copy(alpha = 0.15f))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "16KB READY",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF00E676)
                                        )
                                    }
                                }
                            }
                        }

                        items(state.devices, key = { it.id }) { device ->
                            DeviceCard(
                                device = device,
                                onClick = { onDeviceSelected(device) }
                            )
                        }
                    }
                }
            }
        } // End inner Box

        // Manual Floating Action Button
        FloatingActionButton(
            onClick = { viewModel.refresh() },
            containerColor = Color(0xFF00E5FF),
            contentColor = Color(0xFF00363D),
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.device_list_refresh)
            )
        }
    } // End outer Box
}

@Composable
private fun StatusBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF131A29))
            .border(1.dp, Color(0xFF2E3D5B), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, fontSize = 11.sp, color = Color(0xFFCBD5E1), fontWeight = FontWeight.Medium)
    }
}
