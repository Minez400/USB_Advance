package org.usbadvance.feature.devicelist.ui

import org.usbadvance.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.usbadvance.core.storage.api.IStorageDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceCard(
    device: IStorageDevice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardBorderBrush = remember {
        Brush.horizontalGradient(
            listOf(
                Color(0xFF2E3D5B),
                Color(0xFF00E5FF).copy(alpha = 0.4f),
                Color(0xFF2E3D5B)
            )
        )
    }
    val iconBgBrush = remember {
        Brush.linearGradient(
            listOf(Color(0xFF00E5FF).copy(alpha = 0.2f), Color(0xFF00B0FF).copy(alpha = 0.1f))
        )
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, cardBorderBrush, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF131A29)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // USB Icon in gradient circular container
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(iconBgBrush)
                        .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Usb,
                        contentDescription = stringResource(R.string.device_card_usb_icon),
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${device.vendor} • ${device.product}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }

                // Active connection status LED indicator with memoized state
                val isReady = remember(device.geometry.capacityBytes) { device.geometry.capacityBytes > 0 }
                val lockedStr = stringResource(R.string.device_card_locked)
                val connectingStr = stringResource(R.string.device_card_connecting)
                val activeStr = stringResource(R.string.device_card_active)
                val statusText = remember(device.isWriteProtected, isReady, lockedStr, connectingStr, activeStr) {
                    when {
                        device.isWriteProtected -> lockedStr
                        !isReady -> connectingStr
                        else -> activeStr
                    }
                }
                val statusColor = remember(device.isWriteProtected, isReady) {
                    when {
                        device.isWriteProtected -> Color(0xFFFF3D57)
                        !isReady -> Color(0xFF00E5FF)
                        else -> Color(0xFF00E676)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F172A))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    StatusLed(isWriteProtected = device.isWriteProtected, isReady = isReady)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = statusText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Graphical disk sector visualizer bar
            DiskVisualizerBar(device = device)

            Spacer(modifier = Modifier.height(14.dp))

            // Low-level technical metadata badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TechBadge(text = "SCSI BOT")
                    TechBadge(text = stringResource(R.string.device_card_sector, device.geometry.sectorSize))
                    if (device.isWriteProtected) {
                        TechBadge(
                            text = stringResource(R.string.device_card_wp_active),
                            icon = Icons.Default.Lock,
                            isWarning = true
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = stringResource(R.string.device_card_configure),
                    tint = Color(0xFF00E5FF)
                )
            }
        }
    }
}

@Composable
private fun TechBadge(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    isWarning: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isWarning) Color(0xFF3B1824) else Color(0xFF1E293B))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isWarning) Color(0xFFFF3D57) else Color(0xFF94A3B8),
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = text,
            fontSize = 11.sp,
            color = if (isWarning) Color(0xFFFF3D57) else Color(0xFFE2E8F0),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun StatusLed(
    isWriteProtected: Boolean,
    isReady: Boolean,
    modifier: Modifier = Modifier
) {
    val color = when {
        isWriteProtected -> Color(0xFFFF3D57)
        !isReady -> Color(0xFF00E5FF)
        else -> Color(0xFF00E676)
    }
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, color.copy(alpha = 0.5f), CircleShape)
    )
}
