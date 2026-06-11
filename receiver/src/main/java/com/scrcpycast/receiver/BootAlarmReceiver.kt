package com.scrcpycast.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootAlarmReceiver"
        private const val ALARM_INTERVAL_MS = 30_000L

        fun scheduleBootAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, BootAlarmReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)

            val triggerMs = System.currentTimeMillis() + ALARM_INTERVAL_MS
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot set exact alarm, falling back to inexact")
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            }
            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                triggerMs + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                pendingIntent
            )
            Log.d(TAG, "Boot alarm scheduled, first in 30s, repeating every 15min")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Alarm fired, starting BootService")
        val serviceIntent = Intent(context, BootService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        BootJobService.markStarted()
        BootJobService.scheduleNextBoot(context)
        scheduleBootAlarm(context)
    }
}
