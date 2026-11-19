package com.vaishnava.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService
import com.vaishnava.alarm.data.Alarm
import com.vaishnava.alarm.sequencer.DpsMissionMirror
import com.vaishnava.alarm.util.PermissionUtils
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {

    private val alarmManager = context.getSystemService<AlarmManager>()!!

    override fun schedule(alarm: Alarm) {
        Log.d("AlarmScheduler", "Scheduling alarm ID: ${alarm.id} for ${alarm.hour}:${alarm.minute}")
        Log.d("AlarmScheduler", "Alarm details - ID: ${alarm.id}, Days: ${alarm.days}, IsEnabled: ${alarm.isEnabled}, repeatDaily=${alarm.repeatDaily}")

        // Cancel existing with same ID to prevent duplicates
        cancelExistingAlarm(alarm.id)

        // A completed DPS alarm must not lose its completion truth during
        // locked-boot restore. Generic reschedule() is used by both normal CPS
        // edits and DPS boot recovery, so skip marker clearing while the direct
        // boot rescheduler is actively rebuilding alarms.
        try {
            val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext() ?: context
            } else {
                context
            }
            val bootRestoreInProgress = dpsContext
                .getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
                .getBoolean(DirectBootRestoreReceiver.KEY_BOOT_RESCHEDULE_IN_PROGRESS, false)
            if (bootRestoreInProgress) {
                Log.d("AlarmScheduler", "Preserving DPS completion marker during locked-boot reschedule for alarm ID: ${alarm.id}")
            } else {
                dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                    .edit()
                    .remove("sequencer_completed_${alarm.id}")
                    .commit()
                dpsContext.getSharedPreferences("dps_completion_sync", Context.MODE_PRIVATE)
                    .edit()
                    .remove("alarm_completed_in_dps_${alarm.id}")
                    .commit()
                DpsMissionMirror(dpsContext).clearSnapshot(alarm.id)
                Log.d("AlarmScheduler", "Cleared DPS completion marker for alarm ID: ${alarm.id}")
            }
        } catch (e: Exception) {
            Log.w("AlarmScheduler", "Failed clearing DPS completion marker for alarm ID ${alarm.id}: ${e.message}")
        }

        // Check exact alarm permission status
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }
        Log.d("AlarmScheduler", "canScheduleExactAlarms() = $canExact")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canExact) {
            Log.w("AlarmScheduler", "⚠️ EXACT ALARM PERMISSION NOT GRANTED - Falling back to idle trigger")
            appendAlarmLog("exact_permission_missing_fallback id=${alarm.id}")
        }

        val action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
        val receiverClass = AlarmReceiver::class.java

        val intent = Intent(context, receiverClass).apply {
            this.action = action
            data = android.net.Uri.parse("alarm://${alarm.id}")
            putExtra(AlarmReceiver.ALARM_ID, alarm.id)
            putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, alarm.ringtoneUri)
            // convert days to intArray for extras
            putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, alarm.days?.toIntArray())
            putExtra("hour", alarm.hour)
            putExtra("minute", alarm.minute)
            putExtra("repeatDaily", alarm.repeatDaily)
            
            // CRITICAL FIX: Include mission information in the pending intent
            putExtra("mission_type", alarm.missionType ?: "")
            putExtra("mission_password", alarm.missionPassword ?: "")
            putExtra("is_protected", alarm.isProtected)
            putExtra("wake_check_enabled", alarm.wakeCheckEnabled)
            putExtra("wake_check_minutes", alarm.wakeCheckMinutes)

            // CRITICAL FIX: Include snooze settings in the pending intent
            putExtra("enable_snooze", alarm.enableSnooze)
            putExtra("snooze_duration_minutes", alarm.wakeCheckMinutes)
        }

        Log.d(
            "AlarmScheduler",
            "PI_SCHEDULE: id=${alarm.id} action=${intent.action} data=${intent.data} extrasKeys=${intent.extras?.keySet()?.joinToString()}"
        )

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        var triggerAtMillis = computeNextTriggerMillis(now, alarm)
        Log.d("AlarmScheduler", "Computed next trigger: ${Date(triggerAtMillis)} (now=${Date(now)})")
        appendAlarmLog((if (alarm.days.isNullOrEmpty() && !alarm.repeatDaily) "one-time" else "repeating") + " id=${alarm.id} next=${Date(triggerAtMillis)}")

        // showIntent for setAlarmClock's status-bar "next alarm" affordance (also the
        // activity it launches if the user taps that status-bar icon). Sequencer alarms
        // are handled by MissionSequencer itself, so they skip setAlarmClock entirely -
        // its showIntent would launch AlarmActivity directly, bypassing the sequencer -
        // and use setExactAndAllowWhileIdle only, matching prior behavior for those.
        val missionSignature = "${alarm.id}_${alarm.missionType}_${alarm.missionPassword ?: ""}"
        val uniqueRequestCode = missionSignature.hashCode()
        val showIntent: PendingIntent? = if (alarm.missionType != "sequencer") {
            val showActivityIntent = Intent(context, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(AlarmReceiver.ALARM_ID, alarm.id)
                putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, alarm.ringtoneUri)
                putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, alarm.days?.toIntArray())
                // CRITICAL FIX: Block "none" missions from being launched via AndroidAlarmScheduler
                putExtra("mission_type", alarm.missionType?.let { if (it == "none") "" else it } ?: "")
                putExtra("mission_password", alarm.missionPassword ?: "")
                putExtra("mission_signature", missionSignature)
            }
            Log.d(
                "AlarmScheduler",
                "PI_SHOW: id=${alarm.id} missionType=${alarm.missionType} missionPassword=${alarm.missionPassword} signature=$missionSignature requestCode=$uniqueRequestCode extrasKeys=${showActivityIntent.extras?.keySet()?.joinToString()}"
            )
            PendingIntent.getActivity(
                context,
                uniqueRequestCode,
                showActivityIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            null
        }

        fun trackScheduledAlarmForMissedAlarmPrevention() {
            try {
                val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    context.createDeviceProtectedStorageContext()
                } else {
                    context
                }
                val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                prefs.edit()
                    .putInt("last_scheduled_alarm_id", alarm.id)
                    .putLong("last_scheduled_time", System.currentTimeMillis())
                    .commit()
                Log.d("AlarmScheduler", "Tracked scheduled alarm ID ${alarm.id} for missed alarm prevention")
            } catch (_: Exception) { }
        }

        try {
            // PRIMARY: setAlarmClock() is exempt from the Doze/App-Standby delivery
            // throttling that setExactAndAllowWhileIdle is subject to - confirmed via
            // forensic snapshot comparison (an alarm left overnight through Battery
            // Saver arrived 8 minutes late; an identical alarm with Battery Saver off
            // fired within 4 seconds of schedule). Already proven safe elsewhere in this
            // app with this exact single-trigger pattern: DirectBootRestoreReceiver's
            // boot-time re-ring and AlarmActivity's scheduleWakeCheckFollowUp() both use
            // setAlarmClock() as their primary method with no dual-audio issue. The
            // historical dual-audio bug here was a redundant immediate broadcast fired
            // alongside the scheduled trigger (see DirectBootRestoreReceiver.kt's
            // "CRITICAL FIX: Remove immediate broadcast" comment), not setAlarmClock()
            // itself - this scheduler only ever fires one trigger, so it's safe.
            if (showIntent != null && canExact) {
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
                Log.d("AlarmScheduler", "✅ Alarm ID ${alarm.id} scheduled via setAlarmClock (Doze-exempt)")
                appendAlarmLog("scheduled id=${alarm.id} at=${Date(triggerAtMillis)} via=setAlarmClock")
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    Log.d("AlarmScheduler", "✅ Alarm ID ${alarm.id} scheduled via setExactAndAllowWhileIdle")
                    appendAlarmLog("scheduled id=${alarm.id} at=${Date(triggerAtMillis)} via=setExactAndAllowWhileIdle")
                } catch (_: SecurityException) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    Log.d("AlarmScheduler", "⚠️ Alarm ID ${alarm.id} scheduled via setAndAllowWhileIdle (permission fallback)")
                    appendAlarmLog("scheduled id=${alarm.id} at=${Date(triggerAtMillis)} via=setAndAllowWhileIdle")
                }
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                Log.d("AlarmScheduler", "✅ Alarm ID ${alarm.id} scheduled via setExact")
                appendAlarmLog("scheduled id=${alarm.id} at=${Date(triggerAtMillis)} via=setExact")
            }
            trackScheduledAlarmForMissedAlarmPrevention()
        } catch (se: SecurityException) {
            // Explicit handling for exact-alarms permission issues
            Log.e("AlarmScheduler", "❌ SecurityException scheduling alarm ID ${alarm.id}: ${se.message}", se)
            appendAlarmLog("security_exception id=${alarm.id} msg=${se.message}")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    appendAlarmLog("scheduled id=${alarm.id} at=${Date(triggerAtMillis)} via=setAndAllowWhileIdle(se_fallback)")
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    appendAlarmLog("scheduled id=${alarm.id} at=${Date(triggerAtMillis)} via=set(se_fallback)")
                }
                trackScheduledAlarmForMissedAlarmPrevention()
            } catch (e: Exception) {
                Log.e("AlarmScheduler", "❌ Fallback scheduling failed for alarm ID ${alarm.id}: ${e.message}", e)
            }
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "❌ Failed to schedule alarm ID ${alarm.id} via setAlarmClock: ${e.message}", e)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    Log.d("AlarmScheduler", "⚠️ Alarm ID ${alarm.id} scheduled via setExactAndAllowWhileIdle (fallback)")
                    appendAlarmLog("scheduled id=${alarm.id} at=${Date(triggerAtMillis)} via=setExactAndAllowWhileIdle(fallback)")
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    appendAlarmLog("scheduled id=${alarm.id} at=${Date(triggerAtMillis)} via=setExact(fallback)")
                }
                trackScheduledAlarmForMissedAlarmPrevention()
            } catch (e2: Exception) {
                Log.e("AlarmScheduler", "❌ setExactAndAllowWhileIdle fallback failed for alarm ID ${alarm.id}: ${e2.message}", e2)
                try {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    appendAlarmLog("scheduled id=${alarm.id} at=${Date(triggerAtMillis)} via=set")
                } catch (e3: Exception) {
                    Log.e("AlarmScheduler", "❌ Last-resort set() failed for alarm ID ${alarm.id}: ${e3.message}", e3)
                }
            }
        }

        Log.d("AlarmApp", "Alarm scheduled for: ${Date(triggerAtMillis)} with ID: ${alarm.id} and ringtone: ${alarm.ringtoneUri}")
    }

    override fun cancel(alarm: Alarm) {
        cancelFromReceiver(alarm, AlarmReceiver::class.java, "com.vaishnava.alarm.DIRECT_BOOT_ALARM")
    }

    private fun cancelFromReceiver(alarm: Alarm, receiverClass: Class<*>, action: String) {
        val intent = Intent(context, receiverClass).apply {
            this.action = action
            data = android.net.Uri.parse("alarm://${alarm.id}")
            putExtra(AlarmReceiver.ALARM_ID, alarm.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d("AlarmApp", "Alarm with ID ${alarm.id} cancelled from ${receiverClass.simpleName}.")
        }
    }

    private fun cancelExistingAlarm(alarmId: Int) {
        try {
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
                data = android.net.Uri.parse("alarm://$alarmId")
                putExtra(AlarmReceiver.ALARM_ID, alarmId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                alarmId,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                Log.d("AlarmScheduler", "Cancelled existing alarm with ID: $alarmId from AlarmReceiver")
            }
        } catch (e: Exception) {
            Log.e("AlarmScheduler", "Error cancelling existing alarm: ${e.message}", e)
        }

        // ---------------------------------------------------------
        // CRITICAL FIX 1: WUC ASSASSINATION GUARD
        // Do not destroy the WUC intent if it is actively pending!
        // ---------------------------------------------------------
        try {
            val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext() ?: context
            } else context
            val dpsPrefs = dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            
            val isCompleted = dpsPrefs.getBoolean("sequencer_completed_${alarmId}", false)
            val wucFinalized = dpsPrefs.getBoolean("wakecheck_finalized_${alarmId}", false)
            val wucInterval = dpsPrefs.getInt("wakecheck_interval_${alarmId}", 0)
            
            val isShiftAlarm = try {
                val storage = AlarmStorage(context)
                storage.getAlarm(alarmId)?.isShift == true
            } catch (e: Exception) {
                false
            }

            if (!isShiftAlarm && isCompleted && !wucFinalized && wucInterval > 0) {
                android.util.Log.d("WUC_AUDIT", "FORENSIC_GUARD: Preserving active WUC intent for Alarm ${alarmId} during reschedule event.")
            } else {
                // Safe to eradicate stale WUC token
                val wucIntent = Intent(context, com.vaishnava.alarm.AlarmReceiver::class.java).apply {
                    putExtra(com.vaishnava.alarm.AlarmReceiver.ALARM_ID, alarmId)
                    putExtra("IS_WAKE_CHECK", true)
                }
                val wucPending = PendingIntent.getBroadcast(
                    context,
                    alarmId + 100000,
                    wucIntent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (wucPending != null) {
                    alarmManager.cancel(wucPending)
                    wucPending.cancel()
                    android.util.Log.d("WUC_AUDIT", "FORENSIC_GUARD: Assassinated stale WUC token for alarm ${alarmId}")
                }
            }
        } catch(e: Exception) {
            android.util.Log.e("WUC_AUDIT", "WUC Guard Error: ${e.message}")
        }
        // ---------------------------------------------------------
    }

    /** Next fire time for [alarm] in epoch millis, using the same logic as scheduling. */
    fun nextTriggerMillis(alarm: Alarm, nowMillis: Long = System.currentTimeMillis()): Long =
        computeNextTriggerMillis(nowMillis, alarm)

    private fun computeNextTriggerMillis(nowMillis: Long, alarm: Alarm): Long {
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }

        // If selectedDates has entries, find the next upcoming date from the list
        if (!alarm.selectedDates.isNullOrEmpty()) {
            val futureTriggers = alarm.selectedDates.mapNotNull { dateMillis ->
                val cal = Calendar.getInstance().apply {
                    timeInMillis = dateMillis
                    set(Calendar.HOUR_OF_DAY, alarm.hour)
                    set(Calendar.MINUTE, alarm.minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (cal.timeInMillis > nowMillis) cal.timeInMillis else null
            }
            if (futureTriggers.isNotEmpty()) {
                return futureTriggers.minOrNull() ?: (nowMillis + 60_000L)
            }
        }

        // Build allowedDays: if alarm.days present use it; else if repeatDaily -> all days; else empty
        val allowedDays: IntArray = when {
            !alarm.days.isNullOrEmpty() -> alarm.days!!.toIntArray()
            alarm.repeatDaily -> intArrayOf(
                Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY
            )
            else -> intArrayOf()
        }

        fun calForOffset(offsetDays: Int): Calendar {
            return Calendar.getInstance().apply {
                timeInMillis = nowMillis
                add(Calendar.DAY_OF_YEAR, offsetDays)
                set(Calendar.HOUR_OF_DAY, alarm.hour)
                set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }

        // No allowed days -> one-shot semantics (today if future else next day)
        if (allowedDays.isEmpty()) {
            if (alarm.alarmTime > nowMillis) {
                return alarm.alarmTime
            }
            val todayCal = calForOffset(0)
            return if (todayCal.timeInMillis > nowMillis) {
                todayCal.timeInMillis
            } else {
                calForOffset(1).timeInMillis
            }
        }

        // Search next 14 days for nearest allowed day (handles edge-cases)
        for (offset in 0..13) {
            val c = calForOffset(offset)
            val dow = c.get(Calendar.DAY_OF_WEEK)
            if (allowedDays.any { it == dow }) {
                if (offset == 0) {
                    // today -> only accept if time not passed
                    if (c.timeInMillis > nowMillis) return c.timeInMillis
                } else {
                    return c.timeInMillis
                }
            }
        }

        // fallback: next day at alarm time
        return calForOffset(1).timeInMillis
    }

    private fun appendAlarmLog(message: String) {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val line = "${sdf.format(Date())} | $message\n"
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val file = java.io.File(base, "alarm_debug.log")
            java.io.FileOutputStream(file, true).use { it.write(line.toByteArray()) }
        } catch (_: Exception) { }
    }

    // helper extension (always returns a non-null array)
    private fun List<Int>?.toIntArray(): IntArray =
        if (this == null) IntArray(0) else IntArray(this.size) { i -> this[i] }
}
