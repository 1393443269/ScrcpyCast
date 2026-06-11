package com.scrcpycast.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat

class BootService : Service() {

    companion object {
        private const val TAG = "BootService"
        private const val CHANNEL_ID = "cast_receiver"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var instance: BootService? = null
            private set
    }

    val clientManager = ClientManager.instance
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listenerAdded = false
    private var cachedPendingIntent: PendingIntent? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "onCreate")
        createChannel()
        // Auto-start WiFi hotspot on boot for k2b devices
        try {
            Runtime.getRuntime().exec("cmd wifi start-softap AndroidAP_8736 wpa2 12345678")
            Log.d(TAG, "Hotspot start command sent")
        } catch (e: Exception) {
            Log.w(TAG, "Hotspot auto-start failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        if (!listenerAdded) {
            listenerAdded = true
            clientManager.addListener { state ->
                val text = when {
                    state.isConnected -> "投屏中 ${state.width}x${state.height}"
                    state.isListening -> "等待投屏连接 ${state.localIp}:${state.port}"
                    else -> "接收端就绪"
                }
                mainHandler.post { updateNotification(text) }
            }
        }
        if (!clientManager.getState().isListening) {
            clientManager.startListening()
            clientManager.registerMdns(this)
        }
        BootJobService.markStarted()
        BootJobService.scheduleNextBoot(this)
        BootAlarmReceiver.scheduleBootAlarm(this)
        // Bring ClientActivity to foreground so TextureView surface is created
        mainHandler.post {
            try {
                val intent = Intent(this, ClientActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch activity", e)
            }
        }
        return START_STICKY
    }

    fun setDisplaySurface(surface: Surface?) {
        clientManager.displaySurface = surface
        if (surface != null && clientManager.getState().isConnected) {
            clientManager.reconfigureDecoder()
        }
    }

    private fun updateNotification(text: String) {
        if (cachedPendingIntent == null) {
            cachedPendingIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, ClientActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("阿系留接收端")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setContentIntent(cachedPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "投屏接收", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "屏幕镜像接收服务"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stopForeground(STOP_FOREGROUND_REMOVE)
        clientManager.destroy()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
