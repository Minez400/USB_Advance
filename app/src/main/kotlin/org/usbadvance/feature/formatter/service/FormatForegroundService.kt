package org.usbadvance.feature.formatter.service

import org.usbadvance.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat


/**
 * Foreground Service ensuring Android does not terminate low-level I/O processes
 * during physical formatting or sector wiping operations.
 * Strictly compliant with Android 14 (API 34) Foreground Service type policies.
 */
class FormatForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "usb_format_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "org.usbadvance.action.START_FORMAT"
        const val ACTION_STOP = "org.usbadvance.action.STOP_FORMAT"
        const val EXTRA_DEVICE_NAME = "extra_device_name"

        fun start(context: Context, deviceName: String) {
            val intent = Intent(context, FormatForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_NAME, deviceName)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Prevents crash from ForegroundServiceStartNotAllowedException
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FormatForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (ignored: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val initialNotification = buildNotification(getString(R.string.service_formatting), getString(R.string.service_init))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            // Guard against system restriction exceptions
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "UsbAdvance::FormatWakeLock"
        ).apply {
            acquire(120 * 60 * 1000L) // 2-hour safety ceiling
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "USB"
                val notification = buildNotification(
                    getString(R.string.service_formatting_device, deviceName),
                    getString(R.string.service_in_progress)
                )
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            }
            ACTION_STOP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    fun updateProgress(percentage: Int, stageDescription: String) {
        val notification = buildNotification(getString(R.string.service_progress, percentage), stageDescription)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.service_channel_desc)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (ignored: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
