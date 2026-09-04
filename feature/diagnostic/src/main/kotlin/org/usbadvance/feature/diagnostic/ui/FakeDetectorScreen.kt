package org.usbadvance.feature.diagnostic.ui

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
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.usbadvance.core.storage.api.IStorageDevice
import org.usbadvance.feature.diagnostic.engine.FakeCapacityDetector
import org.usbadvance.feature.diagnostic.engine.FakeDetectionResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FakeDetectorScreen(
    device: IStorageDevice,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val detector = remember { FakeCapacityDetector() }

    var isRunning by remember { mutableStateOf(false) }
    var progressPct by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("Pronto para testar") }
    var result by remember { mutableStateOf<FakeDetectionResult?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detector de Pendrive Falso", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isRunning) {
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
            // Card do Dispositivo
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
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.Storage, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = device.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        Text(text = "Capacidade anunciada: ${device.geometry.getFormattedCapacity()}", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    }
                }
            }

            // Explicação Técnica
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF131A29))
                    .border(1.dp, Color(0xFF2E3D5B), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Shield, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Como funciona o teste anti-fraude?", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Pendrives falsificados possuem firmware adulterado que reporta capacidades gigantes (ex: 2 TB), mas gravam em loop sobre um chip pequeno (16 GB), destruindo seus arquivos. O teste rápido verifica fronteiras de memória física com assinaturas criptográficas para confirmar se a memória realmente retém os dados.",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8),
                        lineHeight = 17.sp
                    )
                }
            }

            // Painel de Teste / Progresso
            if (isRunning) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF131A29))
                        .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            progress = { progressPct / 100f },
                            modifier = Modifier.size(80.dp),
                            color = Color(0xFF00E5FF),
                            strokeWidth = 6.dp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = "${progressPct.toInt()}%", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = statusText, fontSize = 12.sp, color = Color(0xFFCBD5E1), textAlign = TextAlign.Center)
                    }
                }
            }

            // Card de Resultado
            result?.let { res ->
                val cardBorderColor = if (res.isAuthentic) Color(0xFF00E676) else Color(0xFFFF3D57)
                val cardBgColor = if (res.isAuthentic) Color(0xFF0E2E1D) else Color(0xFF38151D)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cardBgColor)
                        .border(1.5.dp, cardBorderColor, RoundedCornerShape(16.dp))
                        .padding(20.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (res.isAuthentic) Icons.Default.CheckCircle else Icons.Default.Dangerous,
                                contentDescription = null,
                                tint = cardBorderColor,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (res.isAuthentic) "DISPOSITIVO AUTÊNTICO" else "ALERTA DE FALSIFICAÇÃO",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 16.sp,
                                    color = Color.White
                                )
                                Text(
                                    text = if (res.isAuthentic) "Memória física validada" else "Capacidade adulterada",
                                    fontSize = 12.sp,
                                    color = cardBorderColor
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(text = res.details, fontSize = 13.sp, color = Color(0xFFE2E8F0), lineHeight = 18.sp)

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Fronteiras testadas: ${res.testedCheckpoints}", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            Text(
                                text = "Falhas: ${res.corruptedCheckpoints}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (res.corruptedCheckpoints == 0) Color(0xFF00E676) else Color(0xFFFF3D57)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Botões de Ação
            if (!isRunning) {
                Button(
                    onClick = {
                        isRunning = true
                        result = null
                        coroutineScope.launch {
                            try {
                                val blockDevice = device.openBlockDevice()
                                val probeResult = detector.runQuickProbe(blockDevice) { pct, desc ->
                                    progressPct = pct
                                    statusText = desc
                                }
                                blockDevice.close()
                                result = probeResult
                            } catch (e: Exception) {
                                statusText = "Erro: ${e.message}"
                            } finally {
                                isRunning = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF00363D)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(imageVector = Icons.Default.FindInPage, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (result == null) "Iniciar Teste Rápido (~45s)" else "Repetir Teste", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                OutlinedButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Voltar ao Painel", color = Color(0xFF94A3B8))
                }
            }
        }
    }
}
