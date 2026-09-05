package org.usbadvance.feature.diagnostic.ui

import org.usbadvance.R

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.usbadvance.core.storage.api.IStorageDevice
import org.usbadvance.feature.diagnostic.engine.BenchmarkEngine
import org.usbadvance.feature.diagnostic.engine.BenchmarkResult
import org.usbadvance.feature.diagnostic.engine.BenchmarkStage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(
    device: IStorageDevice,
    benchmarkResult: BenchmarkResult? = null,
    onRunBenchmark: (() -> Unit)? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    ioBlockSizeBytes: Int = 1048576
) {
    val coroutineScope = rememberCoroutineScope()
    val engine = remember { BenchmarkEngine() }

    val readyStatus = stringResource(R.string.diagnostic_ready)
    val failedFormat = stringResource(R.string.diagnostic_failed)
    val readingMsg = stringResource(R.string.diagnostic_reading)
    val writingMsg = stringResource(R.string.diagnostic_writing)
    val restoringMsg = stringResource(R.string.diagnostic_restoring)
    val completedMsg = stringResource(R.string.diagnostic_completed)

    var isRunning by remember { mutableStateOf(false) }
    var progressPct by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf(readyStatus) }
    var currentResult by remember { mutableStateOf(benchmarkResult) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedBlockSize by remember { mutableIntStateOf(ioBlockSizeBytes) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostic_title), fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isRunning) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.diagnostic_back), tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0B0F19)
                )
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
            // Target device card
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
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.Storage, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(22.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = device.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        Text(text = "ID: ${device.id} • ${device.geometry.getFormattedCapacity()}", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    }
                }
            }

            // Benchmark metrics card
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
                        text = stringResource(R.string.diagnostic_transfer_rate_benchmark),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.diagnostic_benchmark_desc),
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.diagnostic_non_destructive_notice),
                        fontSize = 11.sp,
                        color = Color(0xFF10B981)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    AnimatedVisibility(visible = isRunning) {
                        Column(modifier = Modifier.padding(bottom = 16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = statusText, fontSize = 12.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.SemiBold)
                                Text(text = String.format("%.0f%%", progressPct), fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progressPct / 100f },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF00E5FF),
                                trackColor = Color(0xFF1E293B)
                            )
                        }
                    }

                    if (currentResult != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SpeedResultBadge(
                                label = stringResource(R.string.diagnostic_seq_read),
                                speedMb = currentResult!!.readSpeedMbPerSec,
                                color = Color(0xFF00E5FF),
                                modifier = Modifier.weight(1f)
                            )
                            SpeedResultBadge(
                                label = stringResource(R.string.diagnostic_seq_write),
                                speedMb = currentResult!!.writeSpeedMbPerSec,
                                color = Color(0xFF00E676),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    if (errorMessage != null) {
                        Text(
                            text = errorMessage ?: "",
                            color = Color(0xFFFF3D57),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }

                    val blockSizes = listOf(
                        512 to "512 B",
                        4096 to "4 KB",
                        65536 to "64 KB",
                        262144 to "256 KB",
                        1048576 to "1 MB",
                        4194304 to "4 MB",
                        16777216 to "16 MB",
                        67108864 to "64 MB"
                    )

                    Text(
                        text = stringResource(R.string.diagnostic_block_size_label),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        blockSizes.forEach { (sizeBytes, label) ->
                            val isSelected = selectedBlockSize == sizeBytes
                            FilterChip(
                                selected = isSelected,
                                enabled = !isRunning,
                                onClick = { selectedBlockSize = sizeBytes },
                                label = {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF00E5FF).copy(alpha = 0.2f),
                                    selectedLabelColor = Color(0xFF00E5FF),
                                    containerColor = Color(0xFF1E293B),
                                    labelColor = Color(0xFF94A3B8)
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSelected,
                                    borderColor = Color(0xFF2E3D5B),
                                    selectedBorderColor = Color(0xFF00E5FF)
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (onRunBenchmark != null) {
                                onRunBenchmark()
                            } else {
                                isRunning = true
                                errorMessage = null
                                coroutineScope.launch(Dispatchers.IO) {
                                    var blockDevice: org.usbadvance.core.storage.api.IBlockDevice? = null
                                    try {
                                        blockDevice = device.openBlockDevice()
                                        val res = engine.runBenchmark(
                                            blockDevice,
                                            testSizeMb = 32,
                                            blockSizeBytes = selectedBlockSize
                                        ) { pct, stage ->
                                            coroutineScope.launch(Dispatchers.Main) {
                                                progressPct = pct
                                                statusText = when (stage) {
                                                    BenchmarkStage.READING -> readingMsg
                                                    BenchmarkStage.WRITING -> writingMsg
                                                    BenchmarkStage.RESTORING -> restoringMsg
                                                    BenchmarkStage.COMPLETED -> completedMsg
                                                }
                                            }
                                        }
                                        withContext(Dispatchers.Main) {
                                            currentResult = res
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            errorMessage = String.format(failedFormat, e.message ?: "")
                                        }
                                    } finally {
                                        try {
                                            blockDevice?.close()
                                        } catch (_: Exception) {}
                                        withContext(Dispatchers.Main) {
                                            isRunning = false
                                        }
                                    }
                                }
                            }
                        },
                        enabled = !isRunning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E5FF),
                            contentColor = Color(0xFF00363D)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        if (isRunning) {
                            CircularProgressIndicator(color = Color(0xFF00363D), modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.diagnostic_running_test), fontWeight = FontWeight.Bold)
                        } else {
                            Icon(imageVector = Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (currentResult == null) stringResource(R.string.diagnostic_start_benchmark) else stringResource(R.string.diagnostic_repeat_benchmark), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            OutlinedButton(
                onClick = onBack,
                enabled = !isRunning,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(stringResource(R.string.diagnostic_back), color = Color(0xFF94A3B8))
            }
        }
    }
}

@Composable
private fun SpeedResultBadge(label: String, speedMb: Double, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Column {
            Text(text = label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 0.5.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = String.format("%.1f MB/s", speedMb), fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
    }
}
