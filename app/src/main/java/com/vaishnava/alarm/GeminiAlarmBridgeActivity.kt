package com.vaishnava.alarm

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock
import android.widget.Toast

class GeminiAlarmBridgeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            AlarmClock.ACTION_SET_ALARM -> handleSetAlarm(intent)
            AlarmClock.ACTION_SHOW_ALARMS -> { /* nothing to open — no UI required */ }
        }
        finish() // no setContent()/window ever drawn — Theme.NoDisplay requires this before onResume
    }

    private fun handleSetAlarm(intent: Intent) {
        val hour = intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(AlarmClock.EXTRA_MINUTES, 0)
        if (hour !in 0..23) {
            Toast.makeText(this, "Couldn't understand that alarm time", Toast.LENGTH_LONG).show()
            return
        }
        val days = intent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS)?.toSet() ?: emptySet()

        val requestedRingtoneUri: android.net.Uri? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(AlarmClock.EXTRA_RINGTONE, android.net.Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(AlarmClock.EXTRA_RINGTONE)
        }
        val resolvedRingtoneUri: android.net.Uri = if (
            requestedRingtoneUri != null &&
            requestedRingtoneUri.toString() != AlarmClock.VALUE_RINGTONE_SILENT
        ) {
            requestedRingtoneUri
        } else {
            android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
        }

        val alarmStorage = AlarmStorage(applicationContext)
        val alarmScheduler = AndroidAlarmScheduler(applicationContext)
        if (!MainActivity.hasAllRequiredPermissions(applicationContext)) {
            Toast.makeText(this, "Open Chrona once to grant alarm permissions", Toast.LENGTH_LONG).show()
            return
        }

        val alarm = com.vaishnava.alarm.data.Alarm(
            id = alarmStorage.getNextAlarmId(),
            hour = hour,
            minute = minute,
            isEnabled = true,
            ringtoneUri = resolvedRingtoneUri,
            days = days.toList(),
            isHidden = false,
            isProtected = false
        )
        alarmStorage.addAlarm(alarm)
        alarmScheduler.schedule(alarm)

        sendBroadcast(Intent("com.vaishnava.alarm.REFRESH_ALARMS").apply {
            setPackage(packageName)
            putExtra("alarms_restored", false)
        })

        Toast.makeText(this, "Alarm set for ${formatTime12Hour(hour, minute)}", Toast.LENGTH_LONG).show()
    }
}
