package com.vaishnava.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

enum class WakeCheckCycleState {
    INITIAL_ALARM_PENDING,
    INITIAL_ALARM_RINGING,
    INITIAL_ALARM_COMPLETED,
    WAKEUP_CHECK_SCHEDULED,
    WAKEUP_CHECK_SHOWING,
    WAKEUP_CHECK_PASSED,
    WAKEUP_CHECK_MISSED,
    ALARM_RETRIGGERED,
    ALARM_CANCELLED_OR_INVALID
}

data class WakeCheckCycle(
    val cycleId: Long,
    val state: WakeCheckCycleState,
    val initialCompleted: Boolean,
    val scheduledAt: Long,
    val dueAt: Long,
    val triggeredAt: Long,
    val expiryAt: Long,
    val retriggerCount: Int,
    val updatedAt: Long
)

object WakeCheckCycleStore {
    private const val TAG = "WakeCheckCycleStore"
    private const val PREFS_NAME = "alarm_dps"
    private const val KEY_CYCLE_ID = "wakecheck_cycle_id_"
    private const val KEY_STATE = "wakecheck_cycle_state_"
    private const val KEY_INITIAL_COMPLETED = "wakecheck_initial_completed_"
    private const val KEY_SCHEDULED_AT = "wakecheck_scheduled_at_"
    private const val KEY_DUE_AT = "wakecheck_due_at_"
    private const val KEY_TRIGGERED_AT = "wakecheck_triggered_at_"
    private const val KEY_EXPIRY_AT = "wakecheck_expiry_at_"
    private const val KEY_RETRIGGER_COUNT = "wakecheck_retrigger_count_"
    private const val KEY_UPDATED_AT = "wakecheck_updated_at_"

    fun wakeCheckRequestCode(alarmId: Int): Int = alarmId xor 0x9C0A

    fun read(context: Context, alarmId: Int): WakeCheckCycle? {
        val prefs = prefs(context)
        val cycleId = prefs.getLong("$KEY_CYCLE_ID$alarmId", 0L)
        val state = prefs.getString("$KEY_STATE$alarmId", null)?.toState()
        if (cycleId <= 0L && state == null) return null
        return WakeCheckCycle(
            cycleId = cycleId,
            state = state ?: WakeCheckCycleState.ALARM_CANCELLED_OR_INVALID,
            initialCompleted = prefs.getBoolean("$KEY_INITIAL_COMPLETED$alarmId", false),
            scheduledAt = prefs.getLong("$KEY_SCHEDULED_AT$alarmId", 0L),
            dueAt = prefs.getLong("$KEY_DUE_AT$alarmId", 0L),
            triggeredAt = prefs.getLong("$KEY_TRIGGERED_AT$alarmId", 0L),
            expiryAt = prefs.getLong("$KEY_EXPIRY_AT$alarmId", 0L),
            retriggerCount = prefs.getInt("$KEY_RETRIGGER_COUNT$alarmId", 0),
            updatedAt = prefs.getLong("$KEY_UPDATED_AT$alarmId", 0L)
        )
    }

    fun currentCycleId(context: Context, alarmId: Int): Long = read(context, alarmId)?.cycleId ?: 0L

    fun remainingWakeCheckMs(context: Context, alarmId: Int, now: Long = System.currentTimeMillis()): Long {
        val cycle = read(context, alarmId) ?: return 0L
        if (cycle.state != WakeCheckCycleState.WAKEUP_CHECK_SHOWING || cycle.expiryAt <= 0L) {
            return 0L
        }
        return (cycle.expiryAt - now).coerceAtLeast(0L)
    }

    fun hasNonTerminalCycle(context: Context, alarmId: Int): Boolean {
        val cycle = read(context, alarmId) ?: return false
        return cycle.cycleId > 0L &&
            cycle.state != WakeCheckCycleState.WAKEUP_CHECK_PASSED &&
            cycle.state != WakeCheckCycleState.ALARM_CANCELLED_OR_INVALID
    }

    fun isDue(context: Context, alarmId: Int, now: Long = System.currentTimeMillis()): Boolean {
        val cycle = read(context, alarmId) ?: return false
        return cycle.state == WakeCheckCycleState.WAKEUP_CHECK_SCHEDULED &&
            cycle.dueAt > 0L &&
            cycle.dueAt <= now
    }

    fun beginInitialAlarmRing(
        context: Context,
        alarmId: Int,
        cycleId: Long = System.currentTimeMillis()
    ): WakeCheckCycle {
        cancelScheduledWakeCheckAlarm(context, alarmId)
        val previous = read(context, alarmId)
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putLong("$KEY_CYCLE_ID$alarmId", cycleId)
            .putString("$KEY_STATE$alarmId", WakeCheckCycleState.INITIAL_ALARM_RINGING.name)
            .putBoolean("$KEY_INITIAL_COMPLETED$alarmId", false)
            .putLong("$KEY_SCHEDULED_AT$alarmId", 0L)
            .putLong("$KEY_DUE_AT$alarmId", 0L)
            .putLong("$KEY_TRIGGERED_AT$alarmId", 0L)
            .putLong("$KEY_EXPIRY_AT$alarmId", 0L)
            .putInt("$KEY_RETRIGGER_COUNT$alarmId", 0)
            .putLong("$KEY_UPDATED_AT$alarmId", now)
            .putBoolean("wakecheck_pending_$alarmId", false)
            .putBoolean("wakecheck_gate_active_$alarmId", false)
            .putBoolean("wakecheck_finalized_$alarmId", false)
            .putBoolean("wakecheck_resume_pending_$alarmId", false)
            .putLong("wakecheck_ack_ts_$alarmId", 0L)
            .commit()
        val updated = read(context, alarmId)
            ?: WakeCheckCycle(cycleId, WakeCheckCycleState.INITIAL_ALARM_RINGING, false, 0L, 0L, 0L, 0L, 0, now)
        logStateTransition("beginInitialAlarmRing", alarmId, previous, updated)
        return updated
    }

    fun scheduleAfterCompletion(
        context: Context,
        alarmId: Int,
        intervalMinutes: Int
    ): WakeCheckCycle? {
        if (intervalMinutes <= 0) return null
        val existing = read(context, alarmId) ?: return null
        if (existing.cycleId <= 0L) return null
        if (existing.state == WakeCheckCycleState.WAKEUP_CHECK_PASSED ||
            existing.state == WakeCheckCycleState.ALARM_CANCELLED_OR_INVALID
        ) {
            return null
        }

        val now = System.currentTimeMillis()
        val dueAt = now + intervalMinutes * 60_000L
        prefs(context).edit()
            .putLong("$KEY_CYCLE_ID$alarmId", existing.cycleId)
            .putString("$KEY_STATE$alarmId", WakeCheckCycleState.WAKEUP_CHECK_SCHEDULED.name)
            .putBoolean("$KEY_INITIAL_COMPLETED$alarmId", true)
            .putLong("$KEY_SCHEDULED_AT$alarmId", now)
            .putLong("$KEY_DUE_AT$alarmId", dueAt)
            .putLong("$KEY_TRIGGERED_AT$alarmId", 0L)
            .putLong("$KEY_EXPIRY_AT$alarmId", 0L)
            .putInt("$KEY_RETRIGGER_COUNT$alarmId", existing.retriggerCount)
            .putLong("$KEY_UPDATED_AT$alarmId", now)
            .putBoolean("wakecheck_pending_$alarmId", true)
            .putBoolean("wakecheck_gate_active_$alarmId", false)
            .putBoolean("wakecheck_finalized_$alarmId", false)
            .putBoolean("wakecheck_resume_pending_$alarmId", false)
            .putLong("wakecheck_ack_ts_$alarmId", 0L)
            .commit()
        val updated = read(context, alarmId)
        if (updated != null) {
            logStateTransition("scheduleAfterCompletion", alarmId, existing, updated)
        }
        return updated
    }

    fun canFireScheduledWakeCheck(context: Context, alarmId: Int, cycleId: Long): Boolean {
        val existing = read(context, alarmId) ?: return false
        return existing.cycleId > 0L &&
            existing.cycleId == cycleId &&
            existing.initialCompleted &&
            existing.state == WakeCheckCycleState.WAKEUP_CHECK_SCHEDULED
    }

    fun markWakeCheckShowing(context: Context, alarmId: Int, cycleId: Long): Boolean {
        val existing = read(context, alarmId) ?: return false
        if (existing.cycleId <= 0L || existing.cycleId != cycleId || !existing.initialCompleted) {
            logInvariantFailure(
                "markWakeCheckShowing",
                alarmId,
                existing,
                "cycle_id_mismatch_or_initial_incomplete"
            )
            return false
        }
        if (existing.state == WakeCheckCycleState.WAKEUP_CHECK_SHOWING) {
            if (existing.expiryAt > 0L) {
                Log.d(
                    TAG,
                    "STATE_TRANSITION source=markWakeCheckShowing alarmId=$alarmId cycleId=${existing.cycleId} " +
                        "from=${existing.state} to=${existing.state} scheduledAt=${existing.scheduledAt} dueAt=${existing.dueAt} " +
                        "triggeredAt=${existing.triggeredAt} expiryAt=${existing.expiryAt} action=resume_existing_showing"
                )
                return true
            }
            logInvariantFailure(
                "markWakeCheckShowing",
                alarmId,
                existing,
                "missing_expiry_for_showing_cycle"
            )
            return false
        }
        if (existing.state != WakeCheckCycleState.WAKEUP_CHECK_SCHEDULED) {
            logInvariantFailure(
                "markWakeCheckShowing",
                alarmId,
                existing,
                "unexpected_state_${existing.state}"
            )
            return false
        }
        val now = System.currentTimeMillis()
        val expiryAt = now + 20_000L
        prefs(context).edit()
            .putString("$KEY_STATE$alarmId", WakeCheckCycleState.WAKEUP_CHECK_SHOWING.name)
            .putLong("$KEY_TRIGGERED_AT$alarmId", now)
            .putLong("$KEY_EXPIRY_AT$alarmId", expiryAt)
            .putLong("$KEY_UPDATED_AT$alarmId", now)
            .putBoolean("wakecheck_pending_$alarmId", false)
            .putBoolean("wakecheck_gate_active_$alarmId", true)
            .commit()
        logStateTransition("markWakeCheckShowing", alarmId, existing, read(context, alarmId))
        return true
    }

    fun markWakeCheckMissed(context: Context, alarmId: Int, cycleId: Long): Boolean {
        val existing = read(context, alarmId) ?: return false
        if (existing.cycleId <= 0L || existing.cycleId != cycleId || !existing.initialCompleted) {
            return false
        }
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putString("$KEY_STATE$alarmId", WakeCheckCycleState.WAKEUP_CHECK_MISSED.name)
            .putLong("$KEY_SCHEDULED_AT$alarmId", 0L)
            .putLong("$KEY_DUE_AT$alarmId", 0L)
            .putLong("$KEY_TRIGGERED_AT$alarmId", 0L)
            .putLong("$KEY_EXPIRY_AT$alarmId", 0L)
            .putLong("$KEY_UPDATED_AT$alarmId", now)
            .putBoolean("wakecheck_pending_$alarmId", false)
            .putBoolean("wakecheck_gate_active_$alarmId", false)
            .putBoolean("wakecheck_resume_pending_$alarmId", false)
            .commit()
        logStateTransition("markWakeCheckMissed", alarmId, existing, read(context, alarmId))
        return true
    }

    fun markAlarmRetriggered(context: Context, alarmId: Int, cycleId: Long): Boolean {
        val existing = read(context, alarmId) ?: return false
        if (existing.cycleId <= 0L || existing.cycleId != cycleId || !existing.initialCompleted) {
            return false
        }
        if (existing.state == WakeCheckCycleState.ALARM_RETRIGGERED) {
            Log.d(
                TAG,
                "STATE_TRANSITION source=markAlarmRetriggered alarmId=$alarmId cycleId=${existing.cycleId} " +
                    "from=${existing.state} to=${existing.state} scheduledAt=${existing.scheduledAt} dueAt=${existing.dueAt} " +
                    "triggeredAt=${existing.triggeredAt} expiryAt=${existing.expiryAt} action=idempotent_resume"
            )
            return true
        }
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putString("$KEY_STATE$alarmId", WakeCheckCycleState.ALARM_RETRIGGERED.name)
            .putLong("$KEY_SCHEDULED_AT$alarmId", 0L)
            .putLong("$KEY_DUE_AT$alarmId", 0L)
            .putLong("$KEY_TRIGGERED_AT$alarmId", 0L)
            .putLong("$KEY_EXPIRY_AT$alarmId", 0L)
            .putInt("$KEY_RETRIGGER_COUNT$alarmId", existing.retriggerCount + 1)
            .putLong("$KEY_UPDATED_AT$alarmId", now)
            .putBoolean("wakecheck_pending_$alarmId", false)
            .putBoolean("wakecheck_gate_active_$alarmId", false)
            .putBoolean("wakecheck_resume_pending_$alarmId", false)
            .commit()
        logStateTransition("markAlarmRetriggered", alarmId, existing, read(context, alarmId))
        return true
    }

    fun markWakeCheckPassed(context: Context, alarmId: Int, cycleId: Long? = null): Boolean {
        val existing = read(context, alarmId) ?: return false
        if (cycleId != null && cycleId > 0L && existing.cycleId != cycleId) return false
        cancelScheduledWakeCheckAlarm(context, alarmId)
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putString("$KEY_STATE$alarmId", WakeCheckCycleState.WAKEUP_CHECK_PASSED.name)
            .putLong("$KEY_SCHEDULED_AT$alarmId", 0L)
            .putLong("$KEY_DUE_AT$alarmId", 0L)
            .putLong("$KEY_TRIGGERED_AT$alarmId", 0L)
            .putLong("$KEY_EXPIRY_AT$alarmId", 0L)
            .putLong("$KEY_UPDATED_AT$alarmId", now)
            .putBoolean("wakecheck_pending_$alarmId", false)
            .putBoolean("wakecheck_gate_active_$alarmId", false)
            .putBoolean("wakecheck_finalized_$alarmId", true)
            .putBoolean("wakecheck_resume_pending_$alarmId", false)
            .commit()
        logStateTransition("markWakeCheckPassed", alarmId, existing, read(context, alarmId))
        return true
    }

    fun invalidate(context: Context, alarmId: Int) {
        cancelScheduledWakeCheckAlarm(context, alarmId)
        val existing = read(context, alarmId)
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putLong("$KEY_CYCLE_ID$alarmId", 0L)
            .putString("$KEY_STATE$alarmId", WakeCheckCycleState.ALARM_CANCELLED_OR_INVALID.name)
            .putBoolean("$KEY_INITIAL_COMPLETED$alarmId", false)
            .putLong("$KEY_SCHEDULED_AT$alarmId", 0L)
            .putLong("$KEY_DUE_AT$alarmId", 0L)
            .putLong("$KEY_TRIGGERED_AT$alarmId", 0L)
            .putLong("$KEY_EXPIRY_AT$alarmId", 0L)
            .putInt("$KEY_RETRIGGER_COUNT$alarmId", 0)
            .putLong("$KEY_UPDATED_AT$alarmId", now)
            .putBoolean("wakecheck_pending_$alarmId", false)
            .putBoolean("wakecheck_gate_active_$alarmId", false)
            .putBoolean("wakecheck_finalized_$alarmId", false)
            .putBoolean("wakecheck_resume_pending_$alarmId", false)
            .commit()
        logStateTransition("invalidate", alarmId, existing, read(context, alarmId))
    }

    private fun cancelScheduledWakeCheckAlarm(context: Context, alarmId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val requestCode = wakeCheckRequestCode(alarmId)

        runCatching {
            val broadcastIntent = Intent(context, AlarmReceiver::class.java).apply {
                action = "com.vaishnava.alarm.WAKE_UP"
                data = android.net.Uri.parse("alarm-wakecheck://$alarmId")
                putExtra(AlarmReceiver.ALARM_ID, alarmId)
            }
            val broadcastPi = PendingIntent.getBroadcast(
                context,
                requestCode,
                broadcastIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (broadcastPi != null) {
                alarmManager.cancel(broadcastPi)
                broadcastPi.cancel()
            }
        }

        runCatching {
            val wakeShowIntent = Intent(context, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(AlarmReceiver.ALARM_ID, alarmId)
                putExtra("from_wake_check", true)
            }
            val wakeShowPi = PendingIntent.getActivity(
                context,
                requestCode,
                wakeShowIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (wakeShowPi != null) {
                alarmManager.cancel(wakeShowPi)
                wakeShowPi.cancel()
            }
        }

        runCatching {
            val legacyShowIntent = Intent(context, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(AlarmReceiver.ALARM_ID, alarmId)
                putExtra("from_wake_check", true)
            }
            val legacyShowPi = PendingIntent.getActivity(
                context,
                alarmId,
                legacyShowIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (legacyShowPi != null) {
                alarmManager.cancel(legacyShowPi)
                legacyShowPi.cancel()
            }
        }
    }

    private fun prefs(context: Context) = dpsContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun dpsContext(context: Context): Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext() ?: context
        } else {
            context
        }
    }

    private fun logStateTransition(
        source: String,
        alarmId: Int,
        previous: WakeCheckCycle?,
        updated: WakeCheckCycle?
    ) {
        Log.d(
            TAG,
            "STATE_TRANSITION source=$source alarmId=$alarmId cycleId=${updated?.cycleId ?: previous?.cycleId ?: 0L} " +
                "from=${previous?.state ?: "null"} to=${updated?.state ?: "null"} " +
                "scheduledAt=${updated?.scheduledAt ?: previous?.scheduledAt ?: 0L} " +
                "dueAt=${updated?.dueAt ?: previous?.dueAt ?: 0L} " +
                "triggeredAt=${updated?.triggeredAt ?: previous?.triggeredAt ?: 0L} " +
                "expiryAt=${updated?.expiryAt ?: previous?.expiryAt ?: 0L} " +
                "validation=pass"
        )
    }

    private fun logInvariantFailure(
        source: String,
        alarmId: Int,
        cycle: WakeCheckCycle?,
        reason: String
    ) {
        Log.w(
            TAG,
            "INVARIANT_CHECK source=$source alarmId=$alarmId cycleId=${cycle?.cycleId ?: 0L} " +
                "state=${cycle?.state ?: "null"} scheduledAt=${cycle?.scheduledAt ?: 0L} " +
                "dueAt=${cycle?.dueAt ?: 0L} triggeredAt=${cycle?.triggeredAt ?: 0L} " +
                "expiryAt=${cycle?.expiryAt ?: 0L} validation=fail reason=$reason"
        )
    }

    private fun String.toState(): WakeCheckCycleState? {
        return runCatching { WakeCheckCycleState.valueOf(this) }.getOrNull()
    }
}
