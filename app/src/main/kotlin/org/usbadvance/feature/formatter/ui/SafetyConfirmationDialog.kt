package org.usbadvance.feature.formatter.ui

import org.usbadvance.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.usbadvance.core.storage.api.IStorageDevice

@Composable
fun SafetyConfirmationDialog(
    device: IStorageDevice,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    requireStrictConfirmation: Boolean = true
) {
    var confirmationInput by remember { mutableStateOf("") }
    val keyword = stringResource(R.string.safety_dialog_keyword)
    val isConfirmed = !requireStrictConfirmation ||
            confirmationInput.trim().equals(keyword, ignoreCase = true) ||
            confirmationInput.trim().equals("FORMAT", ignoreCase = true) ||
            confirmationInput.trim().equals("FORMATAR", ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF131A29),
        shape = RoundedCornerShape(24.dp),
        icon = {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFF3D57).copy(alpha = 0.15f))
                    .border(1.5.dp, Color(0xFFFF3D57), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFF3D57),
                    modifier = Modifier.size(30.dp)
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.safety_dialog_title),
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                color = Color.White
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.safety_dialog_body),
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8),
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Drive information card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F172A))
                        .border(1.dp, Color(0xFF2E3D5B), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = stringResource(R.string.safety_dialog_device), fontSize = 11.sp, color = Color(0xFF64748B))
                            Text(text = device.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = stringResource(R.string.safety_dialog_capacity), fontSize = 11.sp, color = Color(0xFF64748B))
                            Text(text = device.geometry.getFormattedCapacity(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = stringResource(R.string.safety_dialog_usb_id), fontSize = 11.sp, color = Color(0xFF64748B))
                            Text(text = device.id, fontSize = 11.sp, color = Color(0xFF94A3B8))
                        }
                    }
                }

                if (requireStrictConfirmation) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.safety_dialog_prompt),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFF3D57)
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = confirmationInput,
                        onValueChange = { confirmationInput = it },
                        placeholder = { Text(keyword, color = Color(0xFF475569)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFF3D57),
                            unfocusedBorderColor = Color(0xFF2E3D5B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF0F172A),
                            unfocusedContainerColor = Color(0xFF0F172A)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = isConfirmed,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF3D57),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF2B1824),
                    disabledContentColor = Color(0xFF64748B)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.safety_dialog_confirm), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.safety_dialog_cancel), color = Color(0xFF94A3B8))
            }
        }
    )
}
