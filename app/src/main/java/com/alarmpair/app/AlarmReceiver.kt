package com.alarmpair.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ALARM = "com.alarmpair.app.ALARM"
        const val ACTION_MANUAL_WAKE = "com.alarmpair.app.MANUAL_WAKE"
        const val ACTION_STOP = "com.alarmpair.app.STOP"
        const val CHANNEL_ID = "alarmpair_alarm"
        const val NOTIF_ID_ALARM = 7001
        const val NOTIF_ID_MANUAL = 7002
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ALARM -> startRing(context, "⏰ AlarmPair — time to wake up!")
            ACTION_MANUAL_WAKE -> startRing(context, "🔔 Wake up! ${intent.getStringExtra("triggeredBy") ?: "Your partner"} is calling you!")
            ACTION_STOP -> {
                val stop = Intent(context, AlarmService::class.java).setAction(AlarmService.ACTION_STOP)
                context.startService(stop)
            }
        }
    }

    private fun startRing(context: Context, title: String) {
        ensureChannel(context)
        val serviceIntent = Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_START)
            .putExtra("title", title)
        try { context.startForegroundService(serviceIntent) } catch (_: Exception) { context.startService(serviceIntent) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "AlarmPair Alarms", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
            ch.enableVibration(true)
            nm.createNotificationChannel(ch)
        }
    }
}
