package org.usbadvance.feature.devicelist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.usbadvance.core.storage.api.IStorageDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceHubScreen(
    device: IStorageDevice,
    onNavigateToFormat: () -> Unit,
    onNavigateToIsoBurner: () -> Unit,
    onNavigateToFakeDetector: () -> Unit,
    onNavigateToBenchmark: () -> Unit,
    onEjectDevice: suspend () -> Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showEjectConfirm by remember { mutableStateOf(false) }
    var isEjecting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Painel do Dispositivo", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0B0F19))
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color(0xFF0B0F19),
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Card: Device overview and summary
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF131A29), Color(0xFF162035))
                        )
                    )
                    .border(1.dp, Color(0xFF2E3D5B), RoundedCornerShape(18.dp))
                    .padding(18.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E5FF).copy(alpha = 0.12f))
                                .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Usb,
                                contentDescription = null,
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = device.name,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${device.geometry.getFormattedCapacity()} • ${device.geometry.sectorSize} B/setor",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF00E5FF)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Safe drive ejection action
                    OutlinedButton(
                        onClick = { showEjectConfirm = true },
                        enabled = !isEjecting,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFFF9100)
                        ),
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) {
                        if (isEjecting) {
                            CircularProgressIndicator(color = Color(0xFFFF9100), modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Ejetando com segurança...", fontSize = 13.sp)
                        } else {
                            Text("⏏  Ejetar Unidade com Segurança", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                }
            }

            // Available Operations section header
            Text(
                text = "OPERAÇÕES DISPONÍVEIS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF64748B),
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )

            // Operation 1: Low-level drive format
            OperationActionCard(
                icon = Icons.Default.Storage,
                title = "Formatar Unidade",
                subtitle = "Criar novos sistemas FAT32, exFAT, ext4 ou MBR/GPT.",
                accentColor = Color(0xFF00E5FF),
                onClick = onNavigateToFormat
            )

            // Operation 2: Flash ISO/IMG image
            OperationActionCard(
                icon = Icons.Default.FlashOn,
                title = "Gravar Imagem ISO / IMG",
                subtitle = "Criar pendrive inicializável de Windows, Linux ou LiveCD.",
                accentColor = Color(0xFFFF9100),
                onClick = onNavigateToIsoBurner
            )

            // Operation 3: Fake capacity detector
            OperationActionCard(
                icon = Icons.Default.Shield,
                title = "Detector de Pendrive Falso",
                subtitle = "Verificar integridade e testar a capacidade real da memória Flash.",
                accentColor = Color(0xFFD500F9),
                onClick = onNavigateToFakeDetector
            )

            // Operation 4: Speed benchmark
            OperationActionCard(
                icon = Icons.Default.Speed,
                title = "Benchmark de Velocidade",
                subtitle = "Medir taxa real de leitura e escrita sequencial em MB/s.",
                accentColor = Color(0xFF00E676),
                onClick = onNavigateToBenchmark
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Safe ejection confirmation dialog
    if (showEjectConfirm) {
        AlertDialog(
            onDismissRequest = { showEjectConfirm = false },
            title = {
                Text("Ejetar com Segurança?", fontWeight = FontWeight.Bold, color = Color.White)
            },
            text = {
                Text(
                    "O aplicativo descarregará todos os dados pendentes na memória Flash (SCSI SYNCHRONIZE CACHE) e desconectará o dispositivo com segurança.",
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEjectConfirm = false
                        isEjecting = true
                        coroutineScope.launch {
                            val ejected = onEjectDevice()
                            isEjecting = false
                            if (ejected) {
                                snackbarHostState.showSnackbar("Dispositivo ejetado com segurança.")
                                onBack()
                            } else {
                                snackbarHostState.showSnackbar("Dispositivo ejetado.")
                                onBack()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9100), contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Ejetar Agora", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEjectConfirm = false }) {
                    Text("Cancelar", color = Color(0xFF94A3B8))
                }
            },
            containerColor = Color(0xFF1E293B),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun OperationActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF131A29))
            .border(1.dp, Color(0xFF2E3D5B), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentColor.copy(alpha = 0.12f))
                    .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
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
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color(0xFF64748B),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
