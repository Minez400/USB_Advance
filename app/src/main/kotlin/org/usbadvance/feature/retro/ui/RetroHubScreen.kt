package org.usbadvance.feature.retro.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.usbadvance.core.retro.ps2.UlMediaType
import org.usbadvance.core.storage.api.IStorageDevice
import org.usbadvance.feature.retro.vm.RetroGameItem
import org.usbadvance.feature.retro.vm.RetroHubViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetroHubScreen(
    device: IStorageDevice,
    viewModel: RetroHubViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(device.id) {
        viewModel.loadGames(device)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "PS2 Retro Hub (OPL)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = Color.White
                        )
                        Text(
                            text = device.name,
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
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
                    IconButton(onClick = { viewModel.loadGames(device) }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Recarregar",
                            tint = Color(0xFF00E5FF)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0B0F19))
            )
        },
        containerColor = Color(0xFF0B0F19)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Stats Header
            RetroStatsHeader(
                totalGames = uiState.totalGamesCount,
                ulGames = uiState.ulGamesCount,
                isoGames = uiState.isoGamesCount,
                fragmented = uiState.fragmentedCount
            )

            // Content Area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                when {
                    uiState.isLoading -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFFD500F9),
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Buscando jogos de PS2 (ul.cfg / ISOs)...",
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
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Erro ao carregar jogos",
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
                        }
                    }

                    uiState.games.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.SportsEsports,
                                contentDescription = null,
                                tint = Color(0xFF64748B),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Nenhum jogo encontrado",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Coloque jogos em formato ISO nas pastas /DVD ou /CD, ou instale via USBUtil (ul.cfg).",
                                color = Color(0xFF94A3B8),
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            items(uiState.games) { game ->
                                RetroGameRow(
                                    game = game,
                                    onClick = { viewModel.selectGame(game) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Game Detail Bottom Sheet
        uiState.selectedGame?.let { game ->
            RetroGameDetailSheet(
                game = game,
                onDismiss = { viewModel.selectGame(null) }
            )
        }
    }
}

@Composable
fun RetroStatsHeader(
    totalGames: Int,
    ulGames: Int,
    isoGames: Int,
    fragmented: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard(
            label = "Total",
            value = totalGames.toString(),
            color = Color(0xFFD500F9),
            modifier = Modifier.weight(1f)
        )
        StatCard(
            label = "ul.cfg",
            value = ulGames.toString(),
            color = Color(0xFF00E5FF),
            modifier = Modifier.weight(1f)
        )
        StatCard(
            label = "ISOs (/DVD)",
            value = isoGames.toString(),
            color = Color(0xFFFF9100),
            modifier = Modifier.weight(1f)
        )
        StatCard(
            label = "Fragmentados",
            value = fragmented.toString(),
            color = if (fragmented > 0) Color(0xFFFF5252) else Color(0xFF00E676),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun StatCard(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF131A29))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = value, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = color)
            Text(text = label, fontSize = 10.sp, color = Color(0xFF94A3B8))
        }
    }
}

@Composable
fun RetroGameRow(
    game: RetroGameItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF131A29))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0xFFD500F9).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.SportsEsports,
                contentDescription = null,
                tint = Color(0xFFD500F9),
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = game.title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Media badge
                val mediaLabel = when (game) {
                    is RetroGameItem.UlGame -> if (game.entry.mediaType == UlMediaType.DVD) "DVD" else "CD"
                    is RetroGameItem.IsoGame -> "ISO"
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF00E5FF).copy(alpha = 0.18f))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(text = mediaLabel, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF))
                }

                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatSize(game.sizeBytes),
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )

                // Contiguity status
                when (game) {
                    is RetroGameItem.UlGame -> {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${game.entry.partsCount} partes",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    is RetroGameItem.IsoGame -> {
                        Spacer(modifier = Modifier.width(8.dp))
                        val isContiguous = game.contiguity?.isContiguous ?: true
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isContiguous) Color(0xFF00E676).copy(alpha = 0.18f) else Color(0xFFFF5252).copy(alpha = 0.18f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = if (isContiguous) "OPL OK" else "Fragmentado",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isContiguous) Color(0xFF00E676) else Color(0xFFFF5252)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetroGameDetailSheet(
    game: RetroGameItem,
    onDismiss: () -> Unit
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
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = game.title,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(12.dp))

            when (game) {
                is RetroGameItem.UlGame -> {
                    DetailRow(label = "Formato:", value = "USBUtil (ul.cfg)")
                    DetailRow(label = "Tamanho Total:", value = formatSize(game.entry.sizeBytes))
                    DetailRow(label = "Segmentos de 1 GiB:", value = "${game.entry.partsCount} partes")
                    DetailRow(label = "Prefixo dos Arquivos:", value = game.entry.imagePrefix)
                    DetailRow(label = "Mídia de Origem:", value = if (game.entry.mediaType == UlMediaType.DVD) "DVD (4.7 GB)" else "CD (700 MB)")
                }
                is RetroGameItem.IsoGame -> {
                    DetailRow(label = "Formato:", value = "Imagem ISO Direta (/DVD)")
                    DetailRow(label = "Caminho:", value = game.fsEntry.fullPath)
                    DetailRow(label = "Tamanho:", value = formatSize(game.fsEntry.sizeBytes))
                    DetailRow(label = "Cluster Inicial:", value = "${game.fsEntry.firstCluster}")

                    Spacer(modifier = Modifier.height(10.dp))
                    val isContiguous = game.contiguity?.isContiguous ?: true
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0B0F19))
                            .border(1.dp, if (isContiguous) Color(0xFF00E676) else Color(0xFFFF5252), RoundedCornerShape(10.dp))
                            .padding(14.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isContiguous) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (isContiguous) Color(0xFF00E676) else Color(0xFFFF5252),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isContiguous) "Compatibilidade OPL: 100% OK" else "Alerta: Jogo Fragmentado!",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (isContiguous) Color(0xFF00E676) else Color(0xFFFF5252)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isContiguous)
                                    "O arquivo é 100% contíguo no disco. O Open PS2 Loader executará o jogo na velocidade máxima."
                                else
                                    "O arquivo possui ${game.contiguity?.fragmentCount ?: 2} fragmentos. O OPL pode apresentar tela preta ou congelamento em FMVs.",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 13.sp, color = Color(0xFF94A3B8))
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
}
