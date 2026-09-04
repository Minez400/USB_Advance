package org.usbadvance.feature.formatter.ui

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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.storage.model.PartitionTableType
import org.usbadvance.feature.formatter.vm.FormatterStep
import org.usbadvance.feature.formatter.vm.FormatterViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatWizardScreen(
    viewModel: FormatterViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    if (state.step is FormatterStep.SafetyConfirmation) {
        state.device?.let { device ->
            SafetyConfirmationDialog(
                device = device,
                onConfirm = { viewModel.startFormat(context) },
                onDismiss = { viewModel.cancelConfirmation() }
            )
        }
    }

    if (state.step is FormatterStep.Executing || state.step is FormatterStep.Completed) {
        FormatProgressScreen(
            state = state,
            onFinish = onBack
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Configurar Formatação",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0B0F19)
                )
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B0F19))
                    .padding(16.dp)
            ) {
                Button(
                    onClick = { viewModel.proceedToConfirmation() },
                    enabled = state.validationResult.isValid,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E5FF),
                        contentColor = Color(0xFF00363D),
                        disabledContainerColor = Color(0xFF1E293B),
                        disabledContentColor = Color(0xFF64748B)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "Avançar para Formatação",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        },
        containerColor = Color(0xFF0B0F19),
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Target device summary card
            state.device?.let { dev ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF131A29))
                        .border(1.dp, Color(0xFF2E3D5B), RoundedCornerShape(18.dp))
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
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = null,
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = dev.name,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${dev.geometry.getFormattedCapacity()} • Setor ${dev.geometry.sectorSize}B • Alinhado 1 MiB",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            // Filesystem selection cards
            Text(
                text = "Escolha o Sistema de Arquivos",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            FilesystemOptionCard(
                fs = FilesystemType.EXFAT,
                title = "exFAT",
                subtitle = "Arquivos sem limite de tamanho (> 4 GB). Padrão moderno para PCs, Macs e TVs.",
                badge = "MODERNO",
                badgeColor = Color(0xFF00E5FF),
                isSelected = state.options.filesystemType == FilesystemType.EXFAT,
                onSelect = { viewModel.updateFilesystem(FilesystemType.EXFAT) }
            )

            FilesystemOptionCard(
                fs = FilesystemType.FAT32,
                title = "FAT32",
                subtitle = "Compatibilidade máxima com qualquer aparelho. Limite de 4 GB por arquivo.",
                badge = "UNIVERSAL",
                badgeColor = Color(0xFF00E676),
                isSelected = state.options.filesystemType == FilesystemType.FAT32,
                onSelect = { viewModel.updateFilesystem(FilesystemType.FAT32) }
            )

            FilesystemOptionCard(
                fs = FilesystemType.EXT4,
                title = "ext4",
                subtitle = "Sistema nativo do Linux/Android. Permissões POSIX e integridade de dados.",
                badge = "LINUX",
                badgeColor = Color(0xFFFFB300),
                isSelected = state.options.filesystemType == FilesystemType.EXT4,
                onSelect = { viewModel.updateFilesystem(FilesystemType.EXT4) }
            )

            FilesystemOptionCard(
                fs = FilesystemType.FAT16,
                title = "FAT16",
                subtitle = "Para equipamentos eletrônicos clássicos ou mídias antigas menores que 2 GB.",
                badge = "LEGADO",
                badgeColor = Color(0xFF94A3B8),
                isSelected = state.options.filesystemType == FilesystemType.FAT16,
                onSelect = { viewModel.updateFilesystem(FilesystemType.FAT16) }
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Volume label (Name)
            Text(
                text = "Rótulo do Volume (Label)",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            OutlinedTextField(
                value = state.options.volumeLabel,
                onValueChange = { viewModel.updateVolumeLabel(it) },
                placeholder = { Text("USB_DRIVE") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { viewModel.updateVolumeLabel("USB_${System.currentTimeMillis() % 10000}") }) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Gerar Nome", tint = Color(0xFF00E5FF))
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00E5FF),
                    unfocusedBorderColor = Color(0xFF2E3D5B),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF131A29),
                    unfocusedContainerColor = Color(0xFF131A29)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            // Partition scheme selection (MBR vs GPT)
            Text(
                text = "Esquema de Partição",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PartitionTypeCard(
                    title = "MBR",
                    description = "Compatível com tudo",
                    isSelected = state.options.partitionTableType == PartitionTableType.MBR,
                    onClick = { viewModel.updatePartitionTable(PartitionTableType.MBR) },
                    modifier = Modifier.weight(1f)
                )
                PartitionTypeCard(
                    title = "GPT",
                    description = "UEFI & Discos > 2 TB",
                    isSelected = state.options.partitionTableType == PartitionTableType.GPT,
                    onClick = { viewModel.updatePartitionTable(PartitionTableType.GPT) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Formatting mode (Quick vs Full Zero Wipe)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF131A29))
                    .border(1.dp, Color(0xFF2E3D5B), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Formatação Rápida (Recomendado)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (state.options.quickFormat)
                                "Recria metadados e boot sectors em 3 segundos."
                            else
                                "Zera todos os setores (Full Zero Wipe). Leva vários minutos.",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Switch(
                        checked = state.options.quickFormat,
                        onCheckedChange = { viewModel.updateQuickFormat(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00363D),
                            checkedTrackColor = Color(0xFF00E5FF),
                            uncheckedThumbColor = Color(0xFF94A3B8),
                            uncheckedTrackColor = Color(0xFF1E293B)
                        )
                    )
                }
            }

            // Real-time validation errors and warnings
            if (!state.validationResult.isValid) {
                state.validationResult.errors.forEach { err ->
                    Text(text = "❌ $err", color = Color(0xFFFF3D57), fontSize = 12.sp)
                }
            }
            state.validationResult.warnings.forEach { warn ->
                Text(text = "⚠️ $warn", color = Color(0xFFFFB300), fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FilesystemOptionCard(
    fs: FilesystemType,
    title: String,
    subtitle: String,
    badge: String,
    badgeColor: Color,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) Color(0xFF162238) else Color(0xFF131A29))
            .border(
                1.5.dp,
                if (isSelected) Color(0xFF00E5FF) else Color(0xFF2E3D5B),
                RoundedCornerShape(16.dp)
            )
            .clickable { onSelect() }
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color(0xFF00E5FF) else Color(0xFF1E293B))
                    .border(1.dp, if (isSelected) Color(0xFF00E5FF) else Color(0xFF475569), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color(0xFF00363D),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(badgeColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badge,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun PartitionTypeCard(
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) Color(0xFF162238) else Color(0xFF131A29))
            .border(
                1.5.dp,
                if (isSelected) Color(0xFF00E5FF) else Color(0xFF2E3D5B),
                RoundedCornerShape(14.dp)
            )
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Column {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = if (isSelected) Color(0xFF00E5FF) else Color.White
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 11.sp,
                color = Color(0xFF94A3B8)
            )
        }
    }
}
