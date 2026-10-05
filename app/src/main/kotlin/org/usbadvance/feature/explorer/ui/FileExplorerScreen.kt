package org.usbadvance.feature.explorer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.usbadvance.core.storage.api.IStorageDevice
import org.usbadvance.core.transfer.TransferItem
import org.usbadvance.core.transfer.TransferStatus
import org.usbadvance.core.vfs.model.FsEntry
import org.usbadvance.feature.explorer.vm.FileExplorerViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    device: IStorageDevice,
    viewModel: FileExplorerViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var entryToDelete by remember { mutableStateOf<FsEntry?>(null) }
    var entryToExport by remember { mutableStateOf<FsEntry?>(null) }

    val importFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        viewModel.importFilesFromUris(uris, context.contentResolver)
    }

    val exportFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { destUri ->
        val target = entryToExport
        if (destUri != null && target != null) {
            viewModel.exportFileToUri(target, destUri, context.contentResolver)
        }
        entryToExport = null
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.transientMessage) {
        uiState.transientMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeTransientMessage()
        }
    }

    // System back: go up one folder first, leave the explorer only at the root
    BackHandler(enabled = uiState.currentPath != "/" && uiState.currentPath.isNotEmpty()) {
        viewModel.navigateUp()
    }

    LaunchedEffect(device.id) {
        viewModel.mountDevice(device)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.volumeLabel.ifEmpty { device.name },
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = Color.White
                        )
                        Text(
                            text = "${uiState.filesystemType.displayName.substringBefore(' ')} • ${formatSize(uiState.totalSpaceBytes)}${if (uiState.isReadOnly) " • Somente Leitura" else ""}",
                            fontSize = 12.sp,
                            color = if (uiState.isReadOnly) Color(0xFFFFB74D) else Color(0xFF94A3B8)
                        )
                    }
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
                actions = {
                    if (!uiState.isReadOnly) {
                        IconButton(onClick = { showNewFolderDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.CreateNewFolder,
                                contentDescription = "Nova Pasta",
                                tint = Color(0xFF00E5FF)
                            )
                        }
                        IconButton(onClick = { importFilesLauncher.launch(arrayOf("*/*")) }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Copiar do Celular",
                                tint = Color(0xFF00E5FF)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0B0F19))
            )
        },
        floatingActionButton = {
            if (!uiState.isReadOnly) {
                ExtendedFloatingActionButton(
                    onClick = { importFilesLauncher.launch(arrayOf("*/*")) },
                    containerColor = Color(0xFF00E5FF),
                    contentColor = Color(0xFF0B0F19),
                    shape = RoundedCornerShape(16.dp),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    text = {
                        Text(
                            text = "Copiar do Celular",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color(0xFF0B0F19)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Partition Selector Bar (if drive has multiple partitions)
            if (uiState.partitions.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F172A))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    uiState.partitions.forEachIndexed { index, part ->
                        val isSelected = index == uiState.currentPartitionIndex
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.2f) else Color(0xFF1E293B))
                                .border(1.dp, if (isSelected) Color(0xFF00E5FF) else Color(0xFF334155), RoundedCornerShape(8.dp))
                                .clickable { viewModel.switchPartition(index) }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "Partição ${index + 1}: ${part.filesystem?.name ?: "RAW"} (${formatSize(part.sizeBytes)})",
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            // Breadcrumbs Navigation Bar
            BreadcrumbBar(
                breadcrumbs = uiState.breadcrumbs,
                onBreadcrumbClick = { path ->
                    viewModel.loadDirectory(path)
                }
            )

            // Active Transfer Banner
            uiState.activeTransfer?.let { transfer ->
                ActiveTransferCard(
                    transfer = transfer,
                    onCancel = { viewModel.cancelActiveTransfer() }
                )
            }

            // Content Area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                when {
                    uiState.isMounting || uiState.isLoadingDirectory -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF00E5FF),
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = if (uiState.isMounting) "Montando sistema de arquivos USB..." else "Lendo pasta...",
                                color = Color(0xFF94A3B8),
                                fontSize = 14.sp
                            )
                        }
                    }

                    uiState.errorMessage != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = null,
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Não foi possível carregar",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = uiState.errorMessage ?: "",
                                color = Color(0xFF94A3B8),
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(18.dp))
                            Button(
                                onClick = { viewModel.retryMount(device) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                            ) {
                                Text("Tentar Novamente", color = Color(0xFF0B0F19), fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    uiState.entries.isEmpty() -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = Color(0xFF64748B),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Esta pasta está vazia",
                                color = Color(0xFF94A3B8),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Toque em 'Copiar do Celular' para adicionar arquivos",
                                color = Color(0xFF64748B),
                                fontSize = 12.sp
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(uiState.entries, key = { it.fullPath }) { entry ->
                                FileEntryRow(
                                    entry = entry,
                                    onClick = {
                                        if (entry.isDirectory) {
                                            viewModel.loadDirectory(entry.fullPath)
                                        } else {
                                            viewModel.selectEntry(entry)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // File Detail & Action Bottom Sheet
        uiState.selectedEntry?.let { entry ->
            FileActionBottomSheet(
                entry = entry,
                streamUrl = viewModel.getStreamUrl(entry),
                contiguityInfo = uiState.contiguityInfo,
                isCheckingContiguity = uiState.isCheckingContiguity,
                isReadOnly = uiState.isReadOnly,
                onDismiss = { viewModel.selectEntry(null) },
                onPlayStream = { url ->
                    playWithExternalPlayer(context, url, entry.name)
                },
                onExport = {
                    entryToExport = entry
                    exportFileLauncher.launch(entry.name)
                    viewModel.selectEntry(null)
                },
                onDelete = {
                    entryToDelete = entry
                    viewModel.selectEntry(null)
                }
            )
        }

        // Dialog: Create New Folder
        if (showNewFolderDialog) {
            NewFolderDialog(
                onDismiss = { showNewFolderDialog = false },
                onConfirm = { folderName ->
                    viewModel.createDirectory(folderName)
                    showNewFolderDialog = false
                }
            )
        }

        // Dialog: Confirm Delete
        entryToDelete?.let { entry ->
            AlertDialog(
                onDismissRequest = { entryToDelete = null },
                title = { Text(text = "Excluir Arquivo/Pasta", fontWeight = FontWeight.Bold, color = Color.White) },
                text = {
                    Text(
                        text = "Deseja realmente excluir permanentemente '${entry.name}' da unidade USB?",
                        color = Color(0xFF94A3B8)
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteEntry(entry)
                            entryToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                    ) {
                        Text("Excluir", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { entryToDelete = null }) {
                        Text("Cancelar", color = Color(0xFF94A3B8))
                    }
                },
                containerColor = Color(0xFF131A29)
            )
        }
    }
}

@Composable
fun ActiveTransferCard(
    transfer: TransferItem,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF131A29))
            .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (transfer.destinationPath.startsWith("Armazenamento")) Icons.Default.FileDownload else Icons.Default.FileUpload,
                    contentDescription = null,
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = transfer.sourceName,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancelar",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            LinearProgressIndicator(
                progress = { transfer.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Color(0xFF00E5FF),
                trackColor = Color(0xFF1E293B),
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${formatSize(transfer.transferredBytes)} de ${formatSize(transfer.totalBytes)}",
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )
                Text(
                    text = if (transfer.status == TransferStatus.COMPLETED) "Concluído!" else "${formatSize(transfer.speedBytesPerSec)}/s",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (transfer.status == TransferStatus.COMPLETED) Color(0xFF00E676) else Color(0xFF00E5FF)
                )
            }
        }
    }
}

@Composable
fun NewFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nova Pasta", fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Column {
                Text("Digite o nome da nova pasta:", color = Color(0xFF94A3B8), fontSize = 13.sp)
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("Nome da pasta", color = Color(0xFF64748B)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E5FF),
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
                enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
            ) {
                Text("Criar", fontWeight = FontWeight.Bold, color = Color(0xFF0B0F19))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = Color(0xFF94A3B8))
            }
        },
        containerColor = Color(0xFF131A29)
    )
}

@Composable
fun BreadcrumbBar(
    breadcrumbs: List<org.usbadvance.feature.explorer.vm.BreadcrumbItem>,
    onBreadcrumbClick: (String) -> Unit
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF131A29))
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        breadcrumbs.forEachIndexed { index, crumb ->
            val isLast = index == breadcrumbs.size - 1

            Text(
                text = crumb.name,
                color = if (isLast) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                fontSize = 13.sp,
                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onBreadcrumbClick(crumb.path) }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )

            if (!isLast) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = Color(0xFF475569),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun FileEntryRow(
    entry: FsEntry,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF131A29))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon
        val (icon, tint) = getEntryVisual(entry)
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and Info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (entry.isDirectory) "Pasta" else formatSize(entry.sizeBytes),
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )

                if (entry.isDiscImage) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (entry.isContiguous) Color(0xFF00E676).copy(alpha = 0.2f) else Color(0xFFFF5252).copy(alpha = 0.2f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = if (entry.isContiguous) "OPL OK (Contíguo)" else "Fragmentado",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (entry.isContiguous) Color(0xFF00E676) else Color(0xFFFF5252)
                        )
                    }
                }
            }
        }

        if (entry.isDirectory) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color(0xFF64748B),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileActionBottomSheet(
    entry: FsEntry,
    streamUrl: String?,
    contiguityInfo: org.usbadvance.core.vfs.model.ContiguityInfo?,
    isCheckingContiguity: Boolean,
    isReadOnly: Boolean = false,
    onDismiss: () -> Unit,
    onPlayStream: (String) -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF131A29),
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Text(
                text = entry.name,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${formatSize(entry.sizeBytes)} • Cluster ${entry.firstCluster}",
                fontSize = 12.sp,
                color = Color(0xFF94A3B8),
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
            )

            // Streaming Action for Media & Disc Images
            if (entry.isMediaFile || entry.isDiscImage) {
                streamUrl?.let { url ->
                    Button(
                        onClick = { onPlayStream(url) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF0B0F19))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Reproduzir (Streaming Direto)",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0B0F19)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            // PS2 Contiguity status card
            if (entry.isDiscImage) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0B0F19))
                        .border(
                            1.dp,
                            if (contiguityInfo?.isContiguous == true) Color(0xFF00E676) else Color(0xFFFF5252),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(14.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Diagnóstico OPL PS2:",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            if (isCheckingContiguity) {
                                CircularProgressIndicator(color = Color(0xFF00E5FF), modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Text(
                                    text = if (contiguityInfo?.isContiguous == true) "100% Contíguo (Pronto)" else "Fragmentado (${contiguityInfo?.fragmentCount ?: 1} partes)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (contiguityInfo?.isContiguous == true) Color(0xFF00E676) else Color(0xFFFF5252)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (contiguityInfo?.isContiguous == true)
                                "Este jogo pode ser executado perfeitamente no Open PS2 Loader via USB sem travamentos."
                            else
                                "O OPL pode travar na tela de carregamento devido à fragmentação de clusters.",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Export to Phone
            if (!entry.isDirectory) {
                OutlinedButton(
                    onClick = onExport,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                    border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Salvar no Celular (Download)", fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Delete entry
            if (!isReadOnly) {
                OutlinedButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252)),
                    border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Excluir da Unidade", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

private fun playWithExternalPlayer(context: Context, streamUrl: String, title: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(streamUrl), "video/*")
        putExtra(Intent.EXTRA_TITLE, title)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Abrir com").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (_: Exception) {}
}

private fun getEntryVisual(entry: FsEntry): Pair<ImageVector, Color> {
    return when {
        entry.isDirectory -> Icons.Default.Folder to Color(0xFF00E5FF)
        entry.isDiscImage -> Icons.Default.SportsEsports to Color(0xFFD500F9)
        entry.isMediaFile -> Icons.Default.Movie to Color(0xFFFF9100)
        entry.extension.lowercase(Locale.ROOT) in setOf("mp3", "flac", "wav", "aac") -> Icons.Default.Audiotrack to Color(0xFF00E676)
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to Color(0xFF94A3B8)
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
}
