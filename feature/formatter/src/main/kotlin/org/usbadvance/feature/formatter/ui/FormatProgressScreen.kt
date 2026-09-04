package org.usbadvance.feature.formatter.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.usbadvance.core.storage.model.FormatResult
import org.usbadvance.feature.formatter.vm.FormatterStep
import org.usbadvance.feature.formatter.vm.FormatterUiState

@Composable
fun FormatProgressScreen(
    state: FormatterUiState,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = state.progress.percentage / 100.0f,
        animationSpec = tween(400),
        label = "ProgressAnim"
    )

    Scaffold(
        containerColor = Color(0xFF0B0F19),
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val step = state.step) {
                is FormatterStep.Executing -> {
                    // Header and Warning Banner
                    Text(
                        text = "Formatando Armazenamento",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF3B1824))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFF3D57),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Não desconecte o cabo USB OTG",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF3D57)
                        )
                    }

                    Spacer(modifier = Modifier.height(36.dp))

                    // Digital Circular Tachometer
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(190.dp)
                    ) {
                        // Background radial glow halo
                        Box(
                            modifier = Modifier
                                .size(170.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(Color(0xFF00E5FF).copy(alpha = 0.1f), Color.Transparent)
                                    )
                                )
                        )

                        // Static background track
                        CircularProgressIndicator(
                            progress = { 1f },
                            modifier = Modifier.size(160.dp),
                            color = Color(0xFF1E293B),
                            strokeWidth = 10.dp,
                            trackColor = Color.Transparent
                        )

                        // Progress indicator with Cyber Cyan stroke
                        CircularProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.size(160.dp),
                            color = Color(0xFF00E5FF),
                            strokeWidth = 10.dp,
                            trackColor = Color.Transparent
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${state.progress.percentage.toInt()}%",
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                            Text(
                                text = "PROCESSANDO",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E5FF),
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // Current Stage Description
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF131A29))
                            .border(1.dp, Color(0xFF2E3D5B), RoundedCornerShape(14.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = state.progress.stageDescription,
                            fontSize = 13.sp,
                            color = Color(0xFFE2E8F0),
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }

                    // Live Real-Time Speed Tachometer (MB/s)
                    if (state.progress.currentSpeedBytesPerSec > 0) {
                        Spacer(modifier = Modifier.height(16.dp))
                        val speedMb = state.progress.currentSpeedBytesPerSec / (1024.0 * 1024.0)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xFF00E676).copy(alpha = 0.1f))
                                .border(1.dp, Color(0xFF00E676).copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = Color(0xFF00E676),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = String.format("%.1f MB/s", speedMb),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E676)
                            )
                        }
                    }
                }

                is FormatterStep.Completed -> {
                    when (val res = step.result) {
                        is FormatResult.Success -> {
                            Box(
                                modifier = Modifier
                                    .size(90.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00E676).copy(alpha = 0.15f))
                                    .border(2.dp, Color(0xFF00E676), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Sucesso",
                                    tint = Color(0xFF00E676),
                                    modifier = Modifier.size(52.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = "Formatação Concluída!",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "A unidade foi particionada e formatada com sucesso.",
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp,
                                color = Color(0xFF94A3B8)
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            // Operation Technical Summary
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF131A29))
                                    .border(1.dp, Color(0xFF2E3D5B), RoundedCornerShape(16.dp))
                                    .padding(16.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SummaryRow("Sistema de Arquivos", res.filesystem.displayName)
                                    SummaryRow("Tabela de Partição", res.partitionTable.displayName)
                                    SummaryRow("Rótulo do Volume", res.volumeLabel)
                                    SummaryRow("Tempo Decorrido", String.format("%.1f segundos", res.totalTimeMs / 1000.0))
                                    SummaryRow("Alinhamento Flash", "1 MiB (LBA 2048) OK")
                                }
                            }

                            Spacer(modifier = Modifier.height(32.dp))

                            Button(
                                onClick = onFinish,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF00E5FF),
                                    contentColor = Color(0xFF00363D)
                                ),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                            ) {
                                Text("Concluir e Voltar", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }

                        is FormatResult.Failure -> {
                            Box(
                                modifier = Modifier
                                    .size(90.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF3D57).copy(alpha = 0.15f))
                                    .border(2.dp, Color(0xFFFF3D57), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = "Erro",
                                    tint = Color(0xFFFF3D57),
                                    modifier = Modifier.size(52.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = "Falha na Formatação",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFFFF3D57)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = res.errorMessage,
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp,
                                color = Color(0xFFCBD5E1)
                            )

                            Spacer(modifier = Modifier.height(32.dp))

                            Button(
                                onClick = onFinish,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1E293B),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                            ) {
                                Text("Voltar ao Início", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 12.sp, color = Color(0xFF94A3B8))
        Text(text = value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}
