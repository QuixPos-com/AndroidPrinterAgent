package com.printeragentapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject

class PrinterForegroundService : Service(), AgentEngine.AgentListener {

    companion object {
        const val CHANNEL_ID = "zebra_agent_status_channel"
        const val NOTIFICATION_ID = 41783
        const val ACTION_START = "com.printeragentapp.ACTION_START"
        const val ACTION_STOP = "com.printeragentapp.ACTION_STOP"
        const val ACTION_RELOAD = "com.printeragentapp.ACTION_RELOAD"

        @Volatile
        var isForegroundRunning: Boolean = false
            private set

        fun startService(context: Context) {
            val intent = Intent(context, PrinterForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, PrinterForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private lateinit var engine: AgentEngine

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        engine = AgentEngine.getInstance(applicationContext)
        engine.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                isForegroundRunning = false
                engine.removeListener(this)
                engine.stop()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                Thread { engine.reloadFromStorage() }.start()
            }
            else -> {
                isForegroundRunning = true
                val notification = buildStatusNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val fgsType = if (BlePrinterManager.hasBluetoothPermissions(applicationContext)) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    }
                    try {
                        startForeground(NOTIFICATION_ID, notification, fgsType)
                    } catch (_: Exception) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                    }
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                engine.start()
                updateNotification()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isForegroundRunning = false
        engine.removeListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLog(line: String) {
        // Notification only needs to update on state changes
    }

    override fun onStateChanged(snapshot: JSONObject) {
        updateNotification()
    }

    override fun onEvent(type: String, data: Any?) {
        updateNotification()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zebra Printer Agent Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays real-time connection and connected ZPL printers status in the notification bar"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildStatusNotification(): Notification {
        val (title, body) = engine.getNotificationSummary()

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val reloadIntent = Intent(this, PrinterForegroundService::class.java).apply {
            action = ACTION_RELOAD
        }
        val pendingReload = PendingIntent.getService(
            this,
            1,
            reloadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, PrinterForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingOpen)
            .addAction(0, "Reconnect", pendingReload)
            .addAction(0, "Stop Agent", pendingStop)
            .build()
    }

    private fun updateNotification() {
        if (!isForegroundRunning) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildStatusNotification())
        } catch (_: Exception) {
        }
    }
}
