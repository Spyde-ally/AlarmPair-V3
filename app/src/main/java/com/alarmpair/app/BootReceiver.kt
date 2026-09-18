package com.alarmpair.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences("alarmpair_v3", Context.MODE_PRIVATE)
        val target = prefs.getLong("alarmTargetAt", 0L)
        if (target <= System.currentTimeMillis()) return
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(context, AlarmReceiver.NOTIF_ID_ALARM, Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_ALARM), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, pi) else am.setExact(AlarmManager.RTC_WAKEUP, target, pi)
        } catch (_: SecurityException) { am.set(AlarmManager.RTC_WAKEUP, target, pi) }
    }
}
