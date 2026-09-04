package org.usbadvance.feature.formatter.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.usbadvance.core.storage.api.IStorageDevice
import org.usbadvance.feature.formatter.engine.IsoBurnProgress
import org.usbadvance.feature.formatter.engine.IsoBurnResult
import org.usbadvance.feature.formatter.engine.IsoWriterEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IsoBurnerScreen(
    device: IStorageDevice,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isoEngine = remember { IsoWriterEngine() }

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var fileSizeBytes by remember { mutableLongStateOf(0L) }

    var isBurning by remember { mutableStateOf(false) }
    var burnProgress by remember { mutableStateOf<IsoBurnProgress?>(null) }
    var burnResult by remember { mutableStateOf<IsoBurnResult?>(null) }
    var burnJob by remember { mutableStateOf<Job?>(null) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            val (name, size) = IsoWriterEngine.queryFileInfo(context.contentResolver, uri)
            fileName = name
            fileSizeBytes = size
            burnResult = null
            burnProgress = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gravar Imagem ISO / IMG", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isBurning) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0B0F19))
            )
        },
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
            // Target device summary card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF131A29))
                    .border(1.dp, Color(0xFF2E3D5B), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.Storage, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(text = "DESTINO: ${device.name}", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        Text(
                            text = "Capacidade: ${device.geometry.getFormattedCapacity()} • ${device.geometry.sectorSize} B/setor",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }

            // ISO/IMG image file picker card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF131A29))
                    .border(1.dp, Color(0xFF2E3D5B), RoundedCornerShape(16.dp))
                    .padding(18.dp)
            ) {
                Column {
                    Text(
                        text = "Imagem do Sistema Operacional",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Selecione uma imagem (.iso, .img, .bin) para gravar diretamente nos setores do pendrive.",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    if (fileName != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1A2234))
                                .border(1.dp, Color(0xFF3B82F6).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.UploadFile, contentDescription = null, tint = Color(0xFFFF9100), modifier = Modifier.size(28.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = fileName ?: "", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                                    val sizeMb = fileSizeBytes / (1024.0 * 1024.0)
                                    val sizeStr = if (sizeMb >= 1024) String.format("%.2f GB", sizeMb / 1024.0) else String.format("%.1f MB", sizeMb)
                                    Text(text = "Tamanho: $sizeStr", fontSize = 12.sp, color = Color(0xFF00E5FF))
                                }
                            }
                        }

                        // Storage capacity validation
                        if (fileSizeBytes > device.geometry.capacityBytes) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.Dangerous, contentDescription = null, tint = Color(0xFFFF3D57), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "A imagem é maior que o pendrive! Escolha outro dispositivo.",
                                    color = Color(0xFFFF3D57),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            filePickerLauncher.launch(arrayOf("*/*"))
                        },
                        enabled = !isBurning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1E293B),
                            contentColor = Color(0xFF00E5FF)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(imageVector = Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (fileName == null) "Escolher Arquivo ISO / IMG" else "Trocar Imagem", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Data overwrite critical warning
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF2E1A1A))
                    .border(1.dp, Color(0xFFFF3D57).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF3D57), modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Aviso Crítico: Gravar uma ISO substituirá totalmente a tabela de partição e apagará todos os arquivos no pendrive.",
                        color = Color(0xFFFFCDD2),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            // Raw stream burn progress
            AnimatedVisibility(visible = isBurning || burnProgress != null) {
                burnProgress?.let { prog ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF131A29))
                            .border(1.dp, Color(0xFFFF9100).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                            .padding(18.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = prog.stageMessage,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color.White
                                )
                                Text(
                                    text = String.format("%.1f%%", prog.progressPct),
                                    fontWeight = FontWeight.Black,
                                    fontSize = 16.sp,
                                    color = Color(0xFFFF9100)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            LinearProgressIndicator(
                                progress = { prog.progressPct / 100f },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = Color(0xFFFF9100),
                                trackColor = Color(0xFF1E293B),
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                SpeedMetricCard(
                                    icon = Icons.Default.Speed,
                                    label = "VELOCIDADE",
                                    value = String.format("%.1f MB/s", prog.speedMbPerSec),
                                    color = Color(0xFF00E5FF),
                                    modifier = Modifier.weight(1f)
                                )
                                val etaStr = if (prog.remainingSeconds > 60) "${prog.remainingSeconds / 60}m ${prog.remainingSeconds % 60}s" else "${prog.remainingSeconds}s"
                                SpeedMetricCard(
                                    icon = Icons.Default.HourglassBottom,
                                    label = "TEMPO ESTIMADO",
                                    value = if (prog.remainingSeconds > 0) etaStr else "--",
                                    color = Color(0xFFFF9100),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // Final burn result
            burnResult?.let { res ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (res.success) Color(0xFF10281E) else Color(0xFF2A1015))
                        .border(1.dp, if (res.success) Color(0xFF00E676) else Color(0xFFFF3D57), RoundedCornerShape(16.dp))
                        .padding(18.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (res.success) Icons.Default.CheckCircle else Icons.Default.Dangerous,
                                contentDescription = null,
                                tint = if (res.success) Color(0xFF00E676) else Color(0xFFFF3D57),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (res.success) "Pendrive Criado com Sucesso!" else "Falha na Gravação",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (res.success) {
                            val totalMb = res.totalBytesWritten / (1024.0 * 1024.0)
                            Text(
                                text = "Gravados ${String.format("%.1f MB", totalMb)} em ${String.format("%.0f s", res.durationSeconds)} (Média: ${String.format("%.1f MB/s", res.averageSpeedMbPerSec)}). O pendrive já está pronto e inicializável.",
                                fontSize = 12.sp,
                                color = Color(0xFFE0E0E0),
                                lineHeight = 16.sp
                            )
                        } else {
                            Text(
                                text = res.errorMessage ?: "Erro desconhecido ao gravar blocos.",
                                fontSize = 12.sp,
                                color = Color(0xFFFFCDD2)
                            )
                        }
                    }
                }
            }

            // Main action button
            val canStartBurn = selectedUri != null && fileSizeBytes > 0 && fileSizeBytes <= device.geometry.capacityBytes && !isBurning

            Button(
                onClick = { showConfirmDialog = true },
                enabled = canStartBurn,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF9100),
                    contentColor = Color(0xFF1A0C00)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isBurning) {
                    CircularProgressIndicator(color = Color(0xFF1A0C00), modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Gravando Imagem...", fontWeight = FontWeight.Bold)
                } else {
                    Icon(imageVector = Icons.Default.FlashOn, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Gravar Imagem no Pendrive", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            if (isBurning) {
                OutlinedButton(
                    onClick = {
                        burnJob?.cancel()
                        isBurning = false
                        burnResult = IsoBurnResult(false, 0, 0.0, 0.0, "Operação cancelada pelo usuário.")
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF3D57)),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Cancelar Gravação")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // Safety burn confirmation dialog
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = {
                Text("Confirmar Gravação de Imagem", fontWeight = FontWeight.Bold, color = Color.White)
            },
            text = {
                Text(
                    "Tem certeza de que deseja gravar a imagem \"$fileName\" no dispositivo \"${device.name}\"?\n\nTodos os dados e partições existentes serão destruídos irreversivelmente.",
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        val uri = selectedUri ?: return@Button
                        isBurning = true
                        burnResult = null
                        burnJob = coroutineScope.launch {
                            try {
                                val blockDevice = device.openBlockDevice()
                                val res = isoEngine.burnImage(
                                    contentResolver = context.contentResolver,
                                    imageUri = uri,
                                    imageSizeBytes = fileSizeBytes,
                                    blockDevice = blockDevice,
                                    onProgress = { prog ->
                                        burnProgress = prog
                                    }
                                )
                                burnResult = res
                            } catch (e: Exception) {
                                burnResult = IsoBurnResult(
                                    success = false,
                                    totalBytesWritten = 0,
                                    durationSeconds = 0.0,
                                    averageSpeedMbPerSec = 0.0,
                                    errorMessage = e.message ?: "Falha ao gravar imagem."
                                )
                            } finally {
                                isBurning = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3D57), contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Gravar Agora", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancelar", color = Color(0xFF94A3B8))
                }
            },
            containerColor = Color(0xFF1E293B),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun SpeedMetricCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 0.5.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
    }
}
