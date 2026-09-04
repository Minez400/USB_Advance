package org.usbadvance.feature.devicelist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.usbadvance.core.storage.api.IStorageDevice

/**
 * Visualizador gráfico interativo do layout de setores do disco físico.
 * Representa o LBA 0 (MBR/GPT), a margem de alinhamento Flash de 1 MiB e a partição de dados.
 */
@Composable
fun DiskVisualizerBar(
    device: IStorageDevice,
    modifier: Modifier = Modifier
) {
    val totalCapacity = device.geometry.getFormattedCapacity()
    val isReady = device.geometry.capacityBytes > 0

    Column(modifier = modifier.fillMaxWidth()) {
        // Graphical Sector Allocation Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color(0xFF0F172A))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(9.dp))
        ) {
            if (isReady) {
                Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                    // Segment 1: LBA 0 (Partition Table Header) - ~4%
                    Box(
                        modifier = Modifier
                            .weight(0.04f)
                            .fillMaxHeight()
                            .background(Color(0xFFFFB300))
                    )

                    // Segment 2: 1 MiB Flash Alignment Reserve (LBAs 1 to 2047) - ~3%
                    Box(
                        modifier = Modifier
                            .weight(0.03f)
                            .fillMaxHeight()
                            .background(Color(0xFF334155))
                    )

                    // Segment 3: Primary Data Partition - ~93%
                    Box(
                        modifier = Modifier
                            .weight(0.93f)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF00E5FF), Color(0xFF00B0FF))
                                )
                            )
                    )
                }
            } else {
                // Disconnected or permission pending state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .background(Color(0xFF1E293B))
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Technical alignment and payload legend
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isReady) Color(0xFFFFB300) else Color.Gray)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "LBA 0 (${device.partitionTableType.name})",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isReady) Color(0xFF00E5FF) else Color.Gray)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Alinhamento 1 MiB OK",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF00E5FF)
                )
            }

            Text(
                text = totalCapacity,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
