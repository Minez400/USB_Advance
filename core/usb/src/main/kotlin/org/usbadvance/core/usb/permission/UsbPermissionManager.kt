package org.usbadvance.core.usb.permission

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Gerenciador de permissões USB assíncrono baseado em Kotlin Coroutines.
 */
class UsbPermissionManager(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    companion object {
        private const val ACTION_USB_PERMISSION = "org.usbadvance.USB_PERMISSION"
    }

    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) {
            return true
        }

        return suspendCancellableCoroutine { continuation ->
            var isUnregistered = false

            fun safeUnregister(receiver: BroadcastReceiver) {
                synchronized(this) {
                    if (!isUnregistered) {
                        isUnregistered = true
                        try {
                            context.unregisterReceiver(receiver)
                        } catch (ignored: Exception) {}
                    }
                }
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == ACTION_USB_PERMISSION) {
                        safeUnregister(this)

                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (continuation.isActive) {
                            continuation.resume(granted)
                        }
                    }
                }
            }

            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } catch (e: Exception) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                }
            } else {
                context.registerReceiver(receiver, filter)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }

            val permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                flags
            )

            try {
                usbManager.requestPermission(device, permissionIntent)
            } catch (e: Exception) {
                safeUnregister(receiver)
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }

            continuation.invokeOnCancellation {
                safeUnregister(receiver)
            }
        }
    }
}
