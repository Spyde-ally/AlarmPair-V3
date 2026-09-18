package com.alarmpair.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject

class AlarmService : Service() {
    companion object {
        const val ACTION_START = "com.alarmpair.app.ALARM_SERVICE_START"
        const val ACTION_STOP = "com.alarmpair.app.ALARM_SERVICE_STOP"
        private const val CHANNEL = AlarmReceiver.CHANNEL_ID
    }
    private var ringtone: Ringtone? = null
    private var stopSent = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                sendStopToServer()
                stopRing()
                stopSelf()
            }
            ACTION_START -> {
                val title = intent.getStringExtra("title") ?: "⏰ AlarmPair — time to wake up!"
                startRinging(title)
            }
        }
        return START_NOT_STICKY
    }

    private fun startRinging(title: String) {
        val open = Intent(this, AlarmRingActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pi = PendingIntent.getActivity(this, AlarmReceiver.NOTIF_ID_ALARM, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = Intent(this, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_STOP)
        val stopPi = PendingIntent.getBroadcast(this, 7101, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText("Press STOP / I'M UP when you are awake.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(pi, true)
            .addAction(android.R.drawable.ic_media_pause, "STOP / I'M UP", stopPi)
            .build()
        startForeground(AlarmReceiver.NOTIF_ID_ALARM, n)
        try {
            ringtone?.stop()
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            if (Build.VERSION.SDK_INT >= 21) ringtone?.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            ringtone?.play()
        } catch (_: Exception) {}
    }

    private fun sendStopToServer() {
        if (stopSent) return
        stopSent = true
        val token = getSharedPreferences("alarmpair_v3", MODE_PRIVATE).getString("deviceToken", "") ?: ""
        if (token.isBlank()) return
        val ws = WsManager(BuildConfig.SERVER_URL,
            onMessage = {}, onConnected = {}, onDisconnected = {})
        ws.setDeviceToken(token)
        ws.connect { ws.send(JSONObject().put("type", "resume").put("deviceToken", token)); ws.send(JSONObject().put("type", "stop")) }
    }

    private fun stopRing() {
        ringtone?.stop(); ringtone = null
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(AlarmReceiver.NOTIF_ID_ALARM)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL, "AlarmPair Alarms", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            ch.enableVibration(true)
            nm.createNotificationChannel(ch)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { stopRing(); super.onDestroy() }
}
