package org.usbadvance.feature.formatter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
                title = { Text("Configurar Formatação", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                }
            )
        },
        bottomBar = {
            Button(
                onClick = { viewModel.proceedToConfirmation() },
                enabled = state.validationResult.isValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(52.dp)
            ) {
                Text("Avançar para Formatação", style = MaterialTheme.typography.titleMedium)
            }
        },
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
            // Card com resumo do dispositivo selecionado
            state.device?.let { dev ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = dev.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Capacidade: ${dev.geometry.getFormattedCapacity()} • Setor: ${dev.geometry.sectorSize}B",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Seleção de Sistema de Arquivos
            Text(
                text = "Sistema de Arquivos",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(FilesystemType.FAT32, FilesystemType.EXFAT, FilesystemType.EXT4, FilesystemType.FAT16).forEach { fs ->
                    FilterChip(
                        selected = state.options.filesystemType == fs,
                        onClick = { viewModel.updateFilesystem(fs) },
                        label = { Text(fs.name) }
                    )
                }
            }

            // Nome / Rótulo do Volume
            OutlinedTextField(
                value = state.options.volumeLabel,
                onValueChange = { viewModel.updateVolumeLabel(it) },
                label = { Text("Nome do Volume (Label)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Tabela de Partições (MBR vs GPT)
            Text(
                text = "Tabela de Partições",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.options.partitionTableType == PartitionTableType.MBR,
                    onClick = { viewModel.updatePartitionTable(PartitionTableType.MBR) },
                    label = { Text("MBR (Universal)") }
                )
                FilterChip(
                    selected = state.options.partitionTableType == PartitionTableType.GPT,
                    onClick = { viewModel.updatePartitionTable(PartitionTableType.GPT) },
                    label = { Text("GPT (Moderno)") }
                )
            }

            // Opção de Formatação Rápida vs Limpeza Completa
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Formatação Rápida",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Apenas recria as tabelas de arquivos (segundos). Se desativado, preenche todo o disco com zeros.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                Switch(
                    checked = state.options.quickFormat,
                    onCheckedChange = { viewModel.updateQuickFormat(it) }
                )
            }

            // Avisos e Validações
            if (!state.validationResult.isValid) {
                state.validationResult.errors.forEach { err ->
                    Text(text = "❌ $err", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            state.validationResult.warnings.forEach { warn ->
                Text(text = "⚠️ $warn", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
