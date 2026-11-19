package com.vaishnava.alarm.shift

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vaishnava.alarm.AlarmReceiver
import com.vaishnava.alarm.AlarmStorage
import com.vaishnava.alarm.AndroidAlarmScheduler
import com.vaishnava.alarm.MainActivity
import com.vaishnava.alarm.R
import com.vaishnava.alarm.data.Alarm
import kotlin.random.Random
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ShiftRotationCoordinator(
    private val context: Context,
    private val store: ShiftRotationStore = ShiftRotationStore(context),
    private val planner: ShiftRotationPlanner = ShiftRotationPlanner
) {
    data class Result(val success: Boolean, val message: String)

    fun saveCalibratedConfig(
        config: ShiftRotationConfig,
        currentRotationIndex: Int,
        nowMillis: Long = System.currentTimeMillis(),
        generateAlarms: Boolean = true
    ): Result {
        if (currentRotationIndex !in config.rotations.indices) {
            return Result(false, "Current shift selection is required")
        }
        val weekStart = planner.mondayWeekStartMillis(nowMillis)
        val calibrated = config.copy(
            enabled = true,
            anchorWeekStartMillis = weekStart,
            anchorRotationIndex = currentRotationIndex,
            pendingReassignment = null
        )
        val errors = planner.validate(calibrated)
        if (errors.isNotEmpty()) return Result(false, errors.joinToString("; "))
        if (!store.save(calibrated)) {
            return Result(false, "Could not persist the shift rotation")
        }
        
        if (config.pendingReassignment != null) {
            cancelPendingSideEffects()
        }
        
        if (generateAlarms) {
            return reconcileSchedule(nowMillis, forceRegenerateCurrentWeek = true)
        } else {
            return Result(true, "Calibration saved without generating alarms yet")
        }
    }

    fun recoverAfterRestart(nowMillis: Long = System.currentTimeMillis()): Result {
        val activationResult = applyPendingReassignmentIfDue(nowMillis)
        val scheduleResult = reconcileSchedule(nowMillis, forceRegenerateCurrentWeek = false)
        schedulePendingSideEffects(nowMillis)
        return if (!activationResult.success) activationResult else scheduleResult
    }

    fun requestCompanyReassignment(
        requestedRotationIndex: Int,
        userExplicitlyConfirmedWarning: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): Result {
        if (!userExplicitlyConfirmedWarning) {
            return Result(false, "Company reassignment was not confirmed")
        }
        val config = store.read() ?: return Result(false, "Shift rotation is not configured")
        if (!config.enabled) return Result(false, "Shift rotation is disabled")
        if (requestedRotationIndex !in config.rotations.indices) {
            return Result(false, "Requested shift is invalid")
        }
        val pending = planner.createPendingReassignment(
            config = config,
            requestedRotationIndex = requestedRotationIndex,
            nowMillis = nowMillis
        )
        val updated = config.copy(pendingReassignment = pending)
        if (!store.save(updated)) return Result(false, "Could not save the pending reassignment")
        schedulePendingSideEffects(nowMillis)
        return Result(true, "Company reassignment is pending")
    }

    fun cancelPendingReassignment(): Result {
        val config = store.read() ?: return Result(false, "Shift rotation is not configured")
        val updated = planner.cancelPendingReassignment(config)
        if (!store.save(updated)) return Result(false, "Could not cancel the pending reassignment")
        cancelPendingSideEffects()
        return Result(true, "Pending reassignment cancelled")
    }

    fun keepPendingReassignment(nowMillis: Long = System.currentTimeMillis()): Result {
        val config = store.read() ?: return Result(false, "Shift rotation is not configured")
        val pending = config.pendingReassignment ?: return Result(false, "No pending reassignment")
        val nextReminder = planner.nextRandomReminderAt(
            config = config,
            afterMillis = nowMillis,
            previousMinuteOfDay = pending.previousReminderMinuteOfDay
        )
        val updatedPending = pending.copy(
            scheduledReminderAtMillis = nextReminder.first,
            previousReminderMinuteOfDay = nextReminder.second
        )
        if (!store.save(config.copy(pendingReassignment = updatedPending))) {
            return Result(false, "Could not update the pending reassignment")
        }
        schedulePendingSideEffects(nowMillis)
        return Result(true, "Pending reassignment kept")
    }

    fun applyPendingReassignmentIfDue(nowMillis: Long = System.currentTimeMillis()): Result {
        val config = store.read() ?: return Result(true, "No shift rotation configured")
        val pending = config.pendingReassignment ?: return Result(true, "No pending reassignment")
        if (nowMillis < pending.effectiveAtMillis) {
            schedulePendingSideEffects(nowMillis)
            return Result(true, "Pending reassignment is not due")
        }
        val updated = planner.applyPendingReassignmentIfDue(config, nowMillis)
        if (!store.save(updated)) return Result(false, "Could not apply the pending reassignment")
        cancelPendingSideEffects()
        return reconcileSchedule(nowMillis, forceRegenerateCurrentWeek = true)
    }

    fun reconcileSchedule(
        nowMillis: Long = System.currentTimeMillis(),
        forceRegenerateCurrentWeek: Boolean
    ): Result {
        val config = store.read() ?: return Result(true, "No shift rotation configured")
        if (!config.enabled) return Result(true, "Shift rotation disabled")
        val errors = planner.validate(config)
        if (errors.isNotEmpty()) return Result(false, errors.joinToString("; "))

        val currentWeekStart = planner.mondayWeekStartMillis(nowMillis)
        var targetWeekStart = currentWeekStart
        var rotationIndex = planner.rotationIndexForWeek(config, targetWeekStart)
        var planned = planner.plannedAlarmsForWeek(config, targetWeekStart, nowMillis)
        var lookaheadWeeks = 0
        while (planned.isEmpty() && lookaheadWeeks < 8) {
            targetWeekStart += MILLIS_PER_WEEK_MS
            rotationIndex = planner.rotationIndexForWeek(config, targetWeekStart)
            planned = planner.plannedAlarmsForWeek(config, targetWeekStart, Long.MIN_VALUE)
            lookaheadWeeks++
        }
        val generatedCardCount = planned.map { it.hour to it.minute }.distinct().size

        if (!forceRegenerateCurrentWeek &&
            config.runtime.generatedForWeekStartMillis == targetWeekStart &&
            config.runtime.generatedRotationIndex == rotationIndex
        ) {
            val alarmStorage = AlarmStorage(context)
            val existingAlarms = alarmStorage.getAlarms()
            val existingGeneratedCount = existingAlarms.count { it.id in config.runtime.generatedAlarmIds }
            val existingShiftCount = existingAlarms.count { it.isShift }
            if (existingGeneratedCount == generatedCardCount && existingShiftCount == generatedCardCount) {
                scheduleRotationReconciliation(nowMillis)
                return Result(true, "Current rotation week already generated")
            }
        }

        synchronized(AlarmStorage.LOCK) {
            val alarmStorage = AlarmStorage(context)
            val scheduler = AndroidAlarmScheduler(context)
            val existing = alarmStorage.getAlarms().toMutableList()
            val generatedIds = config.runtime.generatedAlarmIds.toSet()
            
            // Additional check: prevent duplicates by checking if alarms for this week already exist
            val existingForThisWeek = existing.filter { 
                it.id in generatedIds && 
                it.alarmTime >= targetWeekStart && 
                it.alarmTime < targetWeekStart + MILLIS_PER_WEEK_MS
            }
            if (existingForThisWeek.isNotEmpty() && !forceRegenerateCurrentWeek) {
                scheduleRotationReconciliation(nowMillis)
                return Result(true, "Shift alarms for this week already exist")
            }
            
            // Runtime IDs are persisted, but a locked-boot recovery can have a
            // newer DPS copy. Remove every generated shift alarm before rebuilding
            // so the authoritative current rotation cannot duplicate it.
            val staleShiftAlarms = existing.filter { it.id in generatedIds || it.isShift }
            val kept = existing.filterNot { it.id in generatedIds || it.isShift }.toMutableList()
            staleShiftAlarms.forEach { oldAlarm ->
                runCatching { scheduler.cancel(oldAlarm) }
            }
    
            val nextIdStart = alarmStorage.getNextAlarmId()
            var nextId = maxOf(nextIdStart, ((kept.maxOfOrNull { it.id } ?: 0) + 1).coerceAtLeast(1))
            
            // Shift alarms are dated one-shots. Equal clock times stay grouped in
            // one card through selectedDates, preserving the existing list UI.
            val generated = planned
                .groupBy { it.hour to it.minute }
                .map { (time, plans) ->
                    val firstPlan = plans.minBy { it.alarmAtMillis }
                Alarm(
                    id = nextId++,
                    hour = time.first,
                    minute = time.second,
                    isEnabled = true,
                    ringtoneUri = config.alarmPattern.ringtoneUri?.let(Uri::parse),
                    days = emptyList(),
                    alarmTime = firstPlan.alarmAtMillis,
                    selectedDates = plans.map { it.alarmAtMillis }.sorted(),
                    isShift = true,
                    isProtected = config.alarmPattern.protectedAlarm,
                    missionType = config.alarmPattern.missionType,
                    missionPassword = config.alarmPattern.missionPassword,
                    wakeCheckEnabled = config.alarmPattern.wakeCheckEnabled,
                    wakeCheckMinutes = config.alarmPattern.wakeCheckMinutes,
                    repeatDaily = false,
                    createdTime = nowMillis
                )
                }
    
            alarmStorage.saveAlarms(kept + generated, synchronous = true)
            alarmStorage.triggerAutoBackup()
            generated.forEach { alarm ->
                runCatching { scheduler.schedule(alarm) }
                    .onFailure { Log.e(TAG, "SHIFT_SCHEDULE_ALARM_FAILED: alarmId=${alarm.id} ${it.message}") }
            }
    
            val updatedRuntime = ShiftRotationRuntimeState(
                generatedAlarmIds = generated.map { it.id },
                generatedForWeekStartMillis = targetWeekStart,
                generatedRotationIndex = rotationIndex,
                lastUpdatedAtMillis = nowMillis
            )
            store.save(config.copy(runtime = updatedRuntime))
            scheduleRotationReconciliation(nowMillis)
            schedulePendingSideEffects(nowMillis)
    
            val nextAlarmMillis = generated.minOfOrNull { it.alarmTime }
                ?: findNextAlarmBeyondThisWeek(config, targetWeekStart, nowMillis)
            val whenText = nextAlarmMillis?.let {
                SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault()).format(Date(it))
            }
            val message = when {
                generated.isNotEmpty() -> "Generated ${generated.size} shift alarms. Next: $whenText"
                whenText != null -> "No alarms remain for this week (all times already passed or that day is disabled). Next alarm: $whenText"
                else -> "No shift alarms could be scheduled — check that at least one day is enabled in this rotation"
            }
            return Result(true, message)
        }
    }

    fun schedulePendingSideEffects(nowMillis: Long = System.currentTimeMillis()) {
        val config = store.read() ?: return
        val pending = config.pendingReassignment ?: return
        scheduleApplyPending(pending)
        val reminderAt = if (pending.scheduledReminderAtMillis > nowMillis) {
            pending.scheduledReminderAtMillis
        } else {
            val next = planner.nextRandomReminderAt(
                config = config,
                afterMillis = nowMillis,
                previousMinuteOfDay = pending.previousReminderMinuteOfDay,
                random = Random.Default
            )
            val updatedPending = pending.copy(
                scheduledReminderAtMillis = next.first,
                previousReminderMinuteOfDay = next.second
            )
            store.save(config.copy(pendingReassignment = updatedPending))
            next.first
        }
        if (reminderAt < pending.effectiveAtMillis) {
            scheduleReminder(reminderAt, pending.token)
        }
    }

    fun showPendingReminderAndReschedule(nowMillis: Long = System.currentTimeMillis()) {
        val config = store.read() ?: return
        val pending = config.pendingReassignment ?: return
        if (nowMillis >= pending.effectiveAtMillis) {
            applyPendingReassignmentIfDue(nowMillis)
            return
        }
        showPendingReminderNotification(pending)
        val next = planner.nextRandomReminderAt(
            config = config,
            afterMillis = nowMillis,
            previousMinuteOfDay = pending.previousReminderMinuteOfDay
        )
        store.save(
            config.copy(
                pendingReassignment = pending.copy(
                    scheduledReminderAtMillis = next.first,
                    previousReminderMinuteOfDay = next.second
                )
            )
        )
        schedulePendingSideEffects(nowMillis)
    }

    private fun cancelPendingSideEffects() {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        listOf(
            ACTION_APPLY_REASSIGNMENT to REQUEST_APPLY_REASSIGNMENT,
            ACTION_SAFETY_REMINDER to REQUEST_SAFETY_REMINDER
        ).forEach { (action, requestCode) ->
            val pi = PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, ShiftRotationReceiver::class.java).apply { this.action = action },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) {
                am.cancel(pi)
                pi.cancel()
            }
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(NOTIFICATION_ID_PENDING_REASSIGNMENT)
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleApplyPending(pending: PendingShiftReassignment) {
        val triggerAt = pending.effectiveAtMillis
        val intent = Intent(context, ShiftRotationReceiver::class.java).apply {
            action = ACTION_APPLY_REASSIGNMENT
            putExtra(EXTRA_REASSIGNMENT_TOKEN, pending.token)
        }
        scheduleExact(REQUEST_APPLY_REASSIGNMENT, triggerAt, intent)
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleReminder(triggerAt: Long, token: Long) {
        val intent = Intent(context, ShiftRotationReceiver::class.java).apply {
            action = ACTION_SAFETY_REMINDER
            putExtra(EXTRA_REASSIGNMENT_TOKEN, token)
        }
        scheduleExact(REQUEST_SAFETY_REMINDER, triggerAt, intent)
    }

    private fun scheduleRotationReconciliation(nowMillis: Long) {
        val config = store.read() ?: return
        val transition = planner.nextRotationTransitionAt(config, nowMillis) ?: return
        val intent = Intent(context, ShiftRotationReceiver::class.java).apply {
            action = ACTION_RECONCILE_ROTATION
        }
        scheduleExact(REQUEST_RECONCILE_ROTATION, transition.first, intent)
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleExact(requestCode: Int, triggerAt: Long, intent: Intent) {
        if (triggerAt <= System.currentTimeMillis()) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: Exception) {
            Log.e(TAG, "SHIFT_SCHEDULE_EXACT_FAILED: action=${intent.action} ${e.message}")
        }
    }

    private fun showPendingReminderNotification(pending: PendingShiftReassignment) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SHIFT_SAFETY,
                    "Shift Change Safety",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val keep = PendingIntent.getBroadcast(
            context,
            REQUEST_KEEP_REASSIGNMENT,
            Intent(context, ShiftRotationReceiver::class.java).apply {
                action = ACTION_CONFIRM_STILL_REAL
                putExtra(EXTRA_REASSIGNMENT_TOKEN, pending.token)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getBroadcast(
            context,
            REQUEST_CANCEL_REASSIGNMENT,
            Intent(context, ShiftRotationReceiver::class.java).apply {
                action = ACTION_CANCEL_REASSIGNMENT
                putExtra(EXTRA_REASSIGNMENT_TOKEN, pending.token)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_SHIFT_SAFETY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Pending shift change")
            .setContentText("You have a pending shift change scheduled for the upcoming rotation. Is this still a real company shift change?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp)
            .setAutoCancel(false)
            .setOngoing(false)
            .addAction(R.drawable.ic_alarm, "Yes", keep)
            .addAction(R.drawable.ic_alarm, "Cancel", cancel)
            .build()
        nm.notify(NOTIFICATION_ID_PENDING_REASSIGNMENT, notification)
    }

    private fun findNextAlarmBeyondThisWeek(
        config: ShiftRotationConfig,
        thisWeekStart: Long,
        nowMillis: Long
    ): Long? {
        var candidateWeek = thisWeekStart + MILLIS_PER_WEEK_MS
        repeat(8) {
            val rotationIndex = planner.rotationIndexForWeek(config, candidateWeek)
            val upcoming = planner.plannedAlarmsForRotationWeek(
                config = config,
                rotationIndex = rotationIndex,
                weekStartMillis = candidateWeek,
                nowMillis = nowMillis,
                timeZone = TimeZone.getDefault()
            )
            upcoming.minOfOrNull { it.alarmAtMillis }?.let { return it }
            candidateWeek += MILLIS_PER_WEEK_MS
        }
        return null
    }

    companion object {
        const val COMPANY_REASSIGNMENT_WARNING =
            "This change will affect your future shift rotation and alarm pattern. If this was entered incorrectly, your future alarms may follow the wrong shift and you may face the consequences later. Proceed only if this is a real and confirmed company shift change."

        const val ACTION_APPLY_REASSIGNMENT = "com.vaishnava.alarm.shift.APPLY_REASSIGNMENT"
        const val ACTION_SAFETY_REMINDER = "com.vaishnava.alarm.shift.SAFETY_REMINDER"
        const val ACTION_CONFIRM_STILL_REAL = "com.vaishnava.alarm.shift.CONFIRM_STILL_REAL"
        const val ACTION_CANCEL_REASSIGNMENT = "com.vaishnava.alarm.shift.CANCEL_REASSIGNMENT"
        const val ACTION_RECOVER = "com.vaishnava.alarm.shift.RECOVER"
        const val ACTION_RECONCILE_ROTATION = "com.vaishnava.alarm.shift.RECONCILE_ROTATION"
        const val EXTRA_REASSIGNMENT_TOKEN = "shift_reassignment_token"

        private const val TAG = "ShiftRotation"
        private const val CHANNEL_SHIFT_SAFETY = "shift_safety_channel"
        private const val NOTIFICATION_ID_PENDING_REASSIGNMENT = 41002
        private const val REQUEST_APPLY_REASSIGNMENT = 41020
        private const val REQUEST_SAFETY_REMINDER = 41021
        private const val REQUEST_KEEP_REASSIGNMENT = 41022
        private const val REQUEST_CANCEL_REASSIGNMENT = 41023
        private const val REQUEST_OPEN_APP = 41024
        private const val REQUEST_RECONCILE_ROTATION = 41025
        private const val MILLIS_PER_WEEK_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
