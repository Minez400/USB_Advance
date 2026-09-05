package org.usbadvance.feature.diagnostic.ui

import org.usbadvance.R

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.usbadvance.core.storage.api.IStorageDevice
import org.usbadvance.feature.diagnostic.engine.FakeCapacityDetector
import org.usbadvance.feature.diagnostic.engine.FakeDetectionResult

enum class FakeScanMode {
    H2TESTW,
    QUICK_PROBE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FakeDetectorScreen(
    device: IStorageDevice,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val detector = remember { FakeCapacityDetector() }

    val readyStatus = stringResource(R.string.fake_detector_ready)
    var isRunning by remember { mutableStateOf(false) }
    var progressPct by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf(readyStatus) }
    var result by remember { mutableStateOf<FakeDetectionResult?>(null) }
    var scanMode by remember { mutableStateOf(FakeScanMode.QUICK_PROBE) }
    var selectedBlockSize by remember { mutableIntStateOf(1048576) }
    var selectedLimitGb by remember { mutableLongStateOf(0L) }

    val blockSizes = remember {
        listOf(
            65536 to "64 KB",
            262144 to "256 KB",
            1048576 to "1 MB",
            4194304 to "4 MB",
            16777216 to "16 MB"
        )
    }

    val availableLimits = remember(device.geometry.capacityBytes) {
        val driveGb = device.geometry.capacityBytes / (1024.0 * 1024.0 * 1024.0)
        val list = mutableListOf(0L to "100% Total")
        listOf(4L, 8L, 16L, 32L, 64L, 128L).forEach { gb ->
            if (gb < driveGb) {
                list.add(gb to "${gb} GB")
            }
        }
        list
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.fake_detector_title), fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isRunning) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.fake_detector_back), tint = Color.White)
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
                        Text(text = stringResource(R.string.fake_detector_announced_capacity, device.geometry.getFormattedCapacity()), fontSize = 12.sp, color = Color(0xFF94A3B8))
                    }
                }
            }

            // Technical explanation card
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
                        Text(text = stringResource(R.string.fake_detector_how_it_works), fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.fake_detector_explanation),
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8),
                        lineHeight = 17.sp
                    )
                }
            }

            // Scan Mode & Configuration Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF131A29))
                    .border(1.dp, Color(0xFF2E3D5B), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.fake_detector_mode_label),
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // H2testw option
                        val isH2 = scanMode == FakeScanMode.H2TESTW
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isH2) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color(0xFF1E293B))
                                .border(
                                    1.dp,
                                    if (isH2) Color(0xFF00E5FF) else Color(0xFF2E3D5B),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable(enabled = !isRunning) { scanMode = FakeScanMode.H2TESTW }
                                .padding(12.dp)
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.fake_detector_mode_h2testw),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (isH2) Color(0xFF00E5FF) else Color.White
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.fake_detector_mode_h2testw_desc),
                                    fontSize = 10.sp,
                                    color = Color(0xFF94A3B8),
                                    lineHeight = 14.sp
                                )
                            }
                        }

                        // Quick probe option
                        val isProbe = scanMode == FakeScanMode.QUICK_PROBE
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isProbe) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color(0xFF1E293B))
                                .border(
                                    1.dp,
                                    if (isProbe) Color(0xFF00E5FF) else Color(0xFF2E3D5B),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable(enabled = !isRunning) { scanMode = FakeScanMode.QUICK_PROBE }
                                .padding(12.dp)
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.fake_detector_mode_probe),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (isProbe) Color(0xFF00E5FF) else Color.White
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.fake_detector_mode_probe_desc),
                                    fontSize = 10.sp,
                                    color = Color(0xFF94A3B8),
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }

                    if (scanMode == FakeScanMode.H2TESTW) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.fake_detector_block_size_label),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
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
                                    label = { Text(text = label, fontSize = 11.sp) },
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

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.fake_detector_limit_label),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            availableLimits.forEach { (limitGb, label) ->
                                val isSelected = selectedLimitGb == limitGb
                                FilterChip(
                                    selected = isSelected,
                                    enabled = !isRunning,
                                    onClick = { selectedLimitGb = limitGb },
                                    label = { Text(text = label, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF10B981).copy(alpha = 0.2f),
                                        selectedLabelColor = Color(0xFF10B981),
                                        containerColor = Color(0xFF1E293B),
                                        labelColor = Color(0xFF94A3B8)
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = isSelected,
                                        borderColor = Color(0xFF2E3D5B),
                                        selectedBorderColor = Color(0xFF10B981)
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.fake_detector_warning_destructive),
                            fontSize = 11.sp,
                            color = Color(0xFFFFB300),
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            // Test progress panel
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

            // Probe results card
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
                                    text = if (res.isAuthentic) stringResource(R.string.fake_detector_device_authentic) else stringResource(R.string.fake_detector_device_counterfeit),
                                    fontWeight = FontWeight.Black,
                                    fontSize = 16.sp,
                                    color = Color.White
                                )
                                Text(
                                    text = if (res.isAuthentic) stringResource(R.string.fake_detector_verified) else stringResource(R.string.fake_detector_tampered),
                                    fontSize = 12.sp,
                                    color = cardBorderColor
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        val detailsText = if (res.isAuthentic) {
                            val declaredGb = res.declaredCapacityBytes / (1024.0 * 1024.0 * 1024.0)
                            stringResource(R.string.fake_detector_details_authentic, res.testedCheckpoints, declaredGb)
                        } else {
                            val declaredGb = res.declaredCapacityBytes / (1024.0 * 1024.0 * 1024.0)
                            val realGb = res.realCapacityBytes / (1024.0 * 1024.0 * 1024.0)
                            stringResource(R.string.fake_detector_details_counterfeit, declaredGb, realGb)
                        }
                        Text(text = detailsText, fontSize = 13.sp, color = Color(0xFFE2E8F0), lineHeight = 18.sp)

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = stringResource(R.string.fake_detector_checkpoints_tested, res.testedCheckpoints), fontSize = 11.sp, color = Color(0xFF94A3B8))
                            Text(
                                text = stringResource(R.string.fake_detector_checkpoints_failed, res.corruptedCheckpoints),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (res.corruptedCheckpoints == 0) Color(0xFF00E676) else Color(0xFFFF3D57)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Action buttons
            if (!isRunning) {
                Button(
                    onClick = {
                        isRunning = true
                        result = null
                        coroutineScope.launch(Dispatchers.IO) {
                            var blockDevice: org.usbadvance.core.storage.api.IBlockDevice? = null
                            try {
                                blockDevice = device.openBlockDevice()
                                val probeResult = if (scanMode == FakeScanMode.H2TESTW) {
                                    val maxBytes = if (selectedLimitGb <= 0L) device.geometry.capacityBytes else selectedLimitGb * 1024L * 1024L * 1024L
                                    detector.runFullFillTest(
                                        blockDevice,
                                        blockSizeBytes = selectedBlockSize,
                                        maxTestBytes = maxBytes
                                    ) { pct, desc ->
                                        coroutineScope.launch(Dispatchers.Main) {
                                            progressPct = pct
                                            statusText = desc
                                        }
                                    }
                                } else {
                                    detector.runQuickProbe(blockDevice) { pct, desc ->
                                        coroutineScope.launch(Dispatchers.Main) {
                                            progressPct = pct
                                            statusText = desc
                                        }
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    result = probeResult
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    statusText = e.localizedMessage ?: (e.message ?: "Error during test")
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
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF00363D)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(imageVector = Icons.Default.FindInPage, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    val btnText = if (result != null) {
                        stringResource(R.string.fake_detector_repeat_test)
                    } else if (scanMode == FakeScanMode.H2TESTW) {
                        stringResource(R.string.fake_detector_start_h2testw)
                    } else {
                        stringResource(R.string.fake_detector_start_probe)
                    }
                    Text(btnText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                OutlinedButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(stringResource(R.string.fake_detector_back_to_hub), color = Color(0xFF94A3B8))
                }
            }
        }
    }
}
