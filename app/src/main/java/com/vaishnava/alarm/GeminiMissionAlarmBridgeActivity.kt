package com.vaishnava.alarm

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.widget.Toast
import com.vaishnava.alarm.data.Alarm

class GeminiMissionAlarmBridgeActivity : Activity() {

    companion object {
        // In-memory dedup guard: if Gemini/Assistant re-fires the same
        // SET_ALARM request within this window (retries, double-taps on
        // the mic, etc.), we reuse instead of creating a duplicate alarm.
        private var lastSignature: String? = null
        private var lastCreatedAtMillis: Long = 0L
        private const val DEDUP_WINDOW_MS = 5000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent.action) {
            AlarmClock.ACTION_SET_ALARM -> handleSetAlarm(intent)
            Intent.ACTION_VIEW -> handleMissionDeepLink(intent)
            else -> {
                Toast.makeText(this, "Couldn't understand that alarm request", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // ---------------------------------------------------------------
    // Path 1: plain "Hey Google, set an alarm for 5am" -> SET_ALARM
    // ---------------------------------------------------------------
    private fun handleSetAlarm(intent: Intent) {
        val hour = intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(AlarmClock.EXTRA_MINUTES, 0)

        if (hour !in 0..23) {
            Toast.makeText(this, "Couldn't understand that alarm time", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val days = intent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS)?.toSet() ?: emptySet()

        // Ringtone: only filled if the spoken phrase named one
        // ("...with my Marimba ringtone"). Falls back to default otherwise.
        val requestedRingtoneUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(AlarmClock.EXTRA_RINGTONE, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(AlarmClock.EXTRA_RINGTONE)
        }
        val resolvedRingtoneUri: Uri = if (
            requestedRingtoneUri != null &&
            requestedRingtoneUri.toString() != AlarmClock.VALUE_RINGTONE_SILENT
        ) {
            requestedRingtoneUri
        } else {
            RingtoneHelper.getDefaultAlarmUri(applicationContext)
        }

        if (!MainActivity.hasAllRequiredPermissions(applicationContext)) {
            Toast.makeText(this, "Open Chrona once to grant alarm permissions", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // --- DEDUP GUARD ---
        val signature = "$hour:$minute:${days.sorted()}:$resolvedRingtoneUri"
        val now = System.currentTimeMillis()
        if (signature == lastSignature && (now - lastCreatedAtMillis) < DEDUP_WINDOW_MS) {
            Toast.makeText(this, "Alarm already set for %02d:%02d".format(hour, minute), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        lastSignature = signature
        lastCreatedAtMillis = now

        val alarmStorage = AlarmStorage(applicationContext)
        val alarmScheduler = AndroidAlarmScheduler(applicationContext)

        val alarm = Alarm(
            id = alarmStorage.getNextAlarmId(),
            hour = hour,
            minute = minute,
            isEnabled = true,
            ringtoneUri = resolvedRingtoneUri,
            days = days.toList(),
            isHidden = false,
            isProtected = false,
            missionType = null,
            missionPassword = null
        )

        alarmStorage.addAlarm(alarm)
        alarmScheduler.schedule(alarm)

        sendBroadcast(Intent("com.vaishnava.alarm.REFRESH_ALARMS").apply {
            setPackage(packageName)
            putExtra("alarms_restored", false)
        })

        Toast.makeText(this, "Alarm set for %02d:%02d".format(hour, minute), Toast.LENGTH_LONG).show()
        finish()
    }

    // ---------------------------------------------------------------
    // Path 2: custom App Action deep link -> chrona://createAlarmWithMission or chrona://setAlarm
    // ---------------------------------------------------------------
    private fun handleMissionDeepLink(intent: Intent) {
        val uri = intent.data
        if (uri == null) {
            Toast.makeText(this, "Couldn't read alarm request", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Handle basic setAlarm deep link (from Gemini)
        if (uri.host == "setAlarm") {
            val hour = uri.getQueryParameter("alarm_hours")?.toIntOrNull()
            val minute = uri.getQueryParameter("alarm_minutes")?.toIntOrNull() ?: 0

            if (hour == null || hour !in 0..23) {
                Toast.makeText(this, "Couldn't understand that alarm time", Toast.LENGTH_LONG).show()
                finish()
                return
            }

            if (!MainActivity.hasAllRequiredPermissions(applicationContext)) {
                Toast.makeText(this, "Open Chrona once to grant alarm permissions", Toast.LENGTH_LONG).show()
                finish()
                return
            }

            val alarmStorage = AlarmStorage(applicationContext)
            val alarmScheduler = AndroidAlarmScheduler(applicationContext)
            val alarm = Alarm(
                id = alarmStorage.getNextAlarmId(),
                hour = hour,
                minute = minute,
                isEnabled = true,
                ringtoneUri = RingtoneHelper.getDefaultAlarmUri(applicationContext),
                days = emptyList(),
                isHidden = false,
                isProtected = false,
                missionType = null,
                missionPassword = null
            )
            alarmStorage.addAlarm(alarm)
            alarmScheduler.schedule(alarm)
            sendBroadcast(Intent("com.vaishnava.alarm.REFRESH_ALARMS").apply {
                setPackage(packageName)
                putExtra("alarms_restored", false)
            })
            Toast.makeText(this, "Alarm set for %02d:%02d".format(hour, minute), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Handle mission-based deep link
        val hour = uri.getQueryParameter("hour")?.toIntOrNull()
        val minute = uri.getQueryParameter("minute")?.toIntOrNull() ?: 0
        val missionParam = uri.getQueryParameter("missionType")

        if (hour == null || hour !in 0..23) {
            Toast.makeText(this, "Couldn't understand that alarm time", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (!MainActivity.hasAllRequiredPermissions(applicationContext)) {
            Toast.makeText(this, "Open Chrona once to grant alarm permissions", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val missionType = when (missionParam?.lowercase()) {
            "math" -> "math"
            "tap" -> "tap"
            "password" -> "password"
            "sequencer" -> "sequencer"
            else -> null
        }

        val alarmStorage = AlarmStorage(applicationContext)
        val alarmScheduler = AndroidAlarmScheduler(applicationContext)

        val alarm = Alarm(
            id = alarmStorage.getNextAlarmId(),
            hour = hour,
            minute = minute,
            isEnabled = true,
            ringtoneUri = RingtoneHelper.getDefaultAlarmUri(applicationContext),
            days = emptyList(),
            isHidden = false,
            isProtected = false,
            missionType = missionType,
            missionPassword = if (missionType == "password") "IfYouWantYouCanSleep" else null
        )

        alarmStorage.addAlarm(alarm)
        alarmScheduler.schedule(alarm)

        sendBroadcast(Intent("com.vaishnava.alarm.REFRESH_ALARMS").apply {
            setPackage(packageName)
            putExtra("alarms_restored", false)
        })

        val label = missionType?.let { " with $missionParam mission" } ?: ""
        Toast.makeText(this, "Alarm set for %02d:%02d%s".format(hour, minute, label), Toast.LENGTH_LONG).show()
        finish()
    }
}
