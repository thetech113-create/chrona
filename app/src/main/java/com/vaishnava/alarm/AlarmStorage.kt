  package com.vaishnava.alarm

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.vaishnava.alarm.data.Alarm // Added this import
import com.vaishnava.alarm.data.isOneShot
import com.vaishnava.alarm.data.WakeCheckStore
import com.vaishnava.alarm.sequencer.DpsMissionMirror
import com.vaishnava.alarm.sequencer.MissionQueueStore
import com.vaishnava.alarm.sequencer.PendingCompletionStore
import com.vaishnava.alarm.shift.ShiftRotationCoordinator
import com.vaishnava.alarm.shift.ShiftRotationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Calendar

class AlarmStorage(context: Context) {

    private val sharedPreferences:  SharedPreferences
    private val appContext: Context
    private val storageContext: Context
    private val dpsContext: Context?
    private val gson: Gson
    private val directBootMirrorPrefixes = listOf(
        "direct_boot_ringtone_",
        "direct_boot_is_protected_",
        "direct_boot_wake_check_enabled_",
        "direct_boot_wake_check_minutes_",
        "direct_boot_mission_type_",
        "direct_boot_mission_password_",
        "direct_boot_enable_gradual_volume_",
        "direct_boot_enable_audio_boost_"
    )

    init {
        appContext = context.applicationContext ?: context
        dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (appContext.isDeviceProtectedStorage) appContext else appContext.createDeviceProtectedStorageContext()
        } else {
            null
        }

        storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            when {
                context.isDeviceProtectedStorage -> {
                    Log.d("AlarmStorage", "USING_EXISTING_DPS: Context is already device-protected")
                    context
                }
                isUserUnlocked(appContext) -> {
                    Log.d("AlarmStorage", "USING_CPS_UNLOCKED: Using credential-protected storage")
                    appContext
                }
                else -> {
                    Log.d("AlarmStorage", "LOCKED_FALLBACK_TO_DPS: User locked, using device-protected storage")
                    dpsContext ?: appContext
                }
            }
        } else {
            Log.d("AlarmStorage", "PRE_N_VERSION: Using regular context")
            appContext
        }

        sharedPreferences = storageContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        gson = Gson().newBuilder()
            .registerTypeAdapter(Uri::class.java, UriAdapter())
            .create()

        synchronized(LOCK) {
            if (!hasRunInitMigrations) {
                maybeMigrateDpsAlarmsToCps()
                hasRunInitMigrations = true
            }
        }

        Log.d(
            "AlarmStorage",
            "FINAL_CONTEXT: isDeviceProtected=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) storageContext.isDeviceProtectedStorage else "N/A"}"
        )
    }

    private fun isUserUnlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        return try {
            val userManager = context.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
            userManager?.isUserUnlocked == true
        } catch (_: Exception) {
            false
        }
    }

    private fun maybeMigrateDpsAlarmsToCps() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (storageContext.isDeviceProtectedStorage) return
        val dps = dpsContext ?: return

        try {
            val cpsJson = sharedPreferences.getString(KEY_ALARMS, null)
            val dpsJson = dps.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_ALARMS, null)
            if (cpsJson.isNullOrBlank() && !dpsJson.isNullOrBlank()) {
                val migrated = sharedPreferences.edit().putString(KEY_ALARMS, dpsJson).commit()
                Log.d("AlarmStorage", "MIGRATE_DPS_TO_CPS: success=$migrated")
                return
            }
            if (cpsJson.isNullOrBlank() || dpsJson.isNullOrBlank()) return

            // During locked boot, the shift coordinator can generate the next
            // dated alarms in DPS. Merge those records at unlock so the normal
            // CPS reconciliation can cancel/rebuild them instead of leaving
            // hidden duplicate PendingIntents behind.
            val cpsAlarms: List<Alarm> = gson.fromJson(cpsJson, object : TypeToken<List<Alarm>>() {}.type) ?: emptyList()
            val dpsAlarms: List<Alarm> = gson.fromJson(dpsJson, object : TypeToken<List<Alarm>>() {}.type) ?: emptyList()
            val dpsShiftAlarms = dpsAlarms.filter { it.isShift }
            
            // Reconcile shifts: since DPS is post-lock, its shift alarms are authoritative.
            val merged = cpsAlarms.filterNot { it.isShift } + dpsShiftAlarms
            
            val hasChanges = merged.size != cpsAlarms.size || merged.any { mergedAlarm ->
                cpsAlarms.none { it.id == mergedAlarm.id && it.lastModifiedTime == mergedAlarm.lastModifiedTime }
            }
            
            if (hasChanges) {
                val persisted = sharedPreferences.edit()
                    .putString(KEY_ALARMS, gson.toJson(merged))
                    .commit()
                Log.d("AlarmStorage", "MERGE_DPS_SHIFT_ALARMS_TO_CPS: success=$persisted count=${dpsShiftAlarms.size}")
            }
        } catch (e: Exception) {
            Log.w("AlarmStorage", "MIGRATE_DPS_TO_CPS_ERROR: ${e.message}")
        }
    }

    private fun reconcileCompletedDpsAlarmsIntoCps(currentAlarms: List<Alarm>): List<Alarm>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        if (storageContext.isDeviceProtectedStorage) return null
        if (!isUserUnlocked(appContext)) return null
        if (currentAlarms.isEmpty()) return null

        val dps = dpsContext ?: return null
        try {
            val alarmPrefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            val syncPrefs = dps.getSharedPreferences("dps_completion_sync", Context.MODE_PRIVATE)
            val dpsMirror = DpsMissionMirror(dps)

            var changed = false
            val alarmPrefsEditor = alarmPrefs.edit()
            val syncPrefsEditor = syncPrefs.edit()

            val reconciled = currentAlarms.map { alarm ->
                val isOneShot = alarm.isOneShot()
                val rawCompletedInDps =
                    alarmPrefs.getBoolean("sequencer_completed_${alarm.id}", false) ||
                        syncPrefs.getBoolean("alarm_completed_in_dps_${alarm.id}", false) ||
                        dpsMirror.readSnapshot(alarm.id)?.isSequenceComplete == true
                val completedInDps = rawCompletedInDps && !WakeCheckStore.isLoopActive(appContext, alarm)

                if (isOneShot && alarm.isEnabled && completedInDps) {
                    changed = true
                    Log.d(
                        "AlarmStorage",
                        "RECONCILE_DPS_COMPLETION_TO_CPS: alarmId=${alarm.id} disabling stale enabled one-shot alarm"
                    )
                    alarmPrefsEditor.remove("sequencer_completed_${alarm.id}")
                    syncPrefsEditor.remove("alarm_completed_in_dps_${alarm.id}")
                    alarm.copy(isEnabled = false)
                } else {
                    alarm
                }
            }

            if (changed) {
                alarmPrefsEditor.apply()
                syncPrefsEditor.apply()
                val json = gson.toJson(reconciled)
                val persisted = sharedPreferences.edit().putString(KEY_ALARMS, json).commit()
                Log.d("AlarmStorage", "RECONCILE_DPS_COMPLETION_TO_CPS_RESULT: success=$persisted")
                try {
                    appContext.sendBroadcast(Intent("com.vaishnava.alarm.REFRESH_ALARMS"))
                } catch (_: Exception) {}
                return reconciled
            }
        } catch (e: Exception) {
            Log.w("AlarmStorage", "RECONCILE_DPS_COMPLETION_TO_CPS_ERROR: ${e.message}")
        }
        return null
    }

    fun saveAlarms(alarms: List<Alarm>, synchronous: Boolean = false) {
        val json = gson.toJson(alarms)
        val editor = sharedPreferences.edit().putString(KEY_ALARMS, json)
        val persisted = if (synchronous) editor.commit() else { editor.apply(); true }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !storageContext.isDeviceProtectedStorage) {
            try {
                val dpsEditor = dpsContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    ?.edit()
                    ?.putString(KEY_ALARMS, json)
                val dpsPersisted = if (synchronous) {
                    dpsEditor?.commit() ?: false
                } else {
                    dpsEditor?.apply()
                    true
                }
                Log.d("AlarmStorage", "MIRROR_CPS_TO_DPS: sync=$synchronous success=$dpsPersisted")
            } catch (e: Exception) {
                Log.w("AlarmStorage", "MIRROR_CPS_TO_DPS_ERROR: ${e.message}")
            }
        }
        
        Log.d("AlarmStorage", "Saved ${alarms.size} alarms to storage (sync=$synchronous success=$persisted)")

        persistDirectBootAlarmMirror(alarms, synchronous)
    }

    private fun persistDirectBootAlarmMirror(alarms: List<Alarm>, synchronous: Boolean): Boolean {
        return try {
            val prefsContext = dpsContext ?: storageContext
            val prefs = prefsContext.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
            val activeAlarmIds = alarms.mapTo(mutableSetOf()) { it.id }
            val editor = prefs.edit()

            prefs.all.keys.forEach { key ->
                val prefix = directBootMirrorPrefixes.firstOrNull(key::startsWith) ?: return@forEach
                val alarmId = key.removePrefix(prefix).toIntOrNull() ?: return@forEach
                if (alarmId !in activeAlarmIds) {
                    editor.remove(key)
                }
            }

            alarms.forEach { alarm ->
                editor.putString("direct_boot_ringtone_${alarm.id}", alarm.ringtoneUri?.toString())
                editor.putBoolean("direct_boot_is_protected_${alarm.id}", alarm.isProtected)
                editor.putBoolean("direct_boot_wake_check_enabled_${alarm.id}", alarm.wakeCheckEnabled)
                editor.putInt("direct_boot_wake_check_minutes_${alarm.id}", alarm.wakeCheckMinutes)
                editor.putString("direct_boot_mission_type_${alarm.id}", alarm.missionType)
                editor.putString("direct_boot_mission_password_${alarm.id}", alarm.missionPassword)
                editor.putBoolean("direct_boot_enable_gradual_volume_${alarm.id}", alarm.enableGradualVolume)
                editor.putBoolean("direct_boot_enable_audio_boost_${alarm.id}", alarm.enableAudioBoost)
            }

            val committed = if (synchronous) editor.commit() else { editor.apply(); true }
            Log.d(
                "AlarmStorage",
                "DIRECT_BOOT_MIRROR_SYNC: sync=$synchronous success=$committed alarmCount=${alarms.size}"
            )
            committed
        } catch (e: Exception) {
            Log.w("AlarmStorage", "DIRECT_BOOT_MIRROR_SYNC_ERROR: ${e.message}")
            false
        }
    }

    fun getAlarms(): List<Alarm> {
        val json = sharedPreferences.getString(KEY_ALARMS, null)
        var alarms: List<Alarm> = if (json != null) {
            gson.fromJson(json, object : TypeToken<List<Alarm>>() {}.type)
        } else {
            emptyList()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !storageContext.isDeviceProtectedStorage && isUserUnlocked(appContext)) {
            val reconciled = reconcileCompletedDpsAlarmsIntoCps(alarms)
            if (reconciled != null) {
                alarms = reconciled
            }
        }

        Log.d("AlarmStorage", "Retrieved ${alarms.size} alarms from storage")

        return alarms
    }

    fun addAlarm(alarm: Alarm) {
        synchronized(LOCK) {
            val currentAlarms = getAlarms().toMutableList()
            // All alarms are handled consistently
            val finalAlarm: Alarm = alarm
            
            Log.d("AlarmStorage", "addAlarm called with ID: ${alarm.id}")
            Log.d("AlarmStorage", "Current alarms count: ${currentAlarms.size}")
            
            // Check if an alarm with the same ID already exists
            val existingIndex = currentAlarms.indexOfFirst { it.id == finalAlarm.id }
            val modifiedAlarm = finalAlarm.copy(lastModifiedTime = System.currentTimeMillis())

            if (existingIndex != -1) {
                // If an alarm with the same ID exists, update it instead of adding a duplicate
                Log.d("AlarmStorage", "Updating existing alarm with ID: ${modifiedAlarm.id}")
                currentAlarms[existingIndex] = modifiedAlarm
            } else {
                // If no alarm with the same ID exists, add the new alarm
                Log.d("AlarmStorage", "Adding new alarm with ID: ${modifiedAlarm.id}")
                currentAlarms.add(modifiedAlarm)
            }
            saveAlarms(currentAlarms, synchronous = true)
            triggerAutoBackup()
            
            Log.d("AlarmStorage", "Added/Updated alarm ID: ${alarm.id}, Time: ${alarm.hour}:${alarm.minute}, Enabled: ${alarm.isEnabled}, Ringtone: ${alarm.ringtoneUri}, Mission: ${alarm.missionType}, Protected: ${alarm.isProtected}")
        }
    }

    fun updateAlarm(updatedAlarm: Alarm) {
        val currentAlarms = getAlarms().toMutableList()
        val index = currentAlarms.indexOfFirst { it.id == updatedAlarm.id }
        if (index != -1) {
            val existingAlarm = currentAlarms[index]
            val finalAlarm = updatedAlarm.copy(lastModifiedTime = System.currentTimeMillis())
            
            currentAlarms[index] = finalAlarm
            saveAlarms(currentAlarms, synchronous = true)
            val requiresRuntimeReset = shouldInvalidateWakeCheck(existingAlarm, finalAlarm)
            if (requiresRuntimeReset) {
                WakeCheckCycleStore.invalidate(appContext, finalAlarm.id)
                if (finalAlarm.isEnabled) {
                    clearRuntimeExecutionState(finalAlarm.id)
                }
            }
            if (!finalAlarm.isEnabled) {
                cascadeDeleteToDps(finalAlarm.id)
            }
            triggerAutoBackup()
        }
    }

    fun isAlarmSnoozing(alarmId: Int): Boolean {
        val alarm = getAlarm(alarmId) ?: return false
        val dps = dpsContext ?: storageContext
        val snoozeStartedAt = dps
            .getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            .getLong("snooze_ts_$alarmId", 0L)
        if (snoozeStartedAt <= 0L) return false

        val durationMs = alarm.wakeCheckMinutes.coerceAtLeast(5) * 60_000L
        val elapsedMs = System.currentTimeMillis() - snoozeStartedAt
        return elapsedMs in 0..(durationMs + 60_000L)
    }

    fun deleteAlarm(alarmId: Int): Boolean {
        if (isAlarmSnoozing(alarmId)) {
            Log.w("AlarmStorage", "DELETE_BLOCKED_SNOOZE_ACTIVE: alarmId=$alarmId")
            return false
        }

        val currentAlarms = getAlarms().toMutableList()
        val removed = currentAlarms.removeIf { it.id == alarmId }
        if (!removed) return false

        saveAlarms(currentAlarms, synchronous = true)
        WakeCheckCycleStore.invalidate(appContext, alarmId)
        cascadeDeleteToDps(alarmId)
        triggerAutoBackup()

        // Check if this was a shift alarm and trigger rotation reconciliation for the next shift
        try {
            val shiftStore = ShiftRotationStore(appContext)
            val config = shiftStore.read()
            if (config != null && config.enabled && alarmId in config.runtime.generatedAlarmIds) {
                Log.d("AlarmStorage", "SHIFT_ALARM_COMPLETED: alarmId=$alarmId was a shift alarm. Reconciling schedule for next shift.")
                ShiftRotationCoordinator(appContext, shiftStore).reconcileSchedule(System.currentTimeMillis(), forceRegenerateCurrentWeek = true)
                // Send broadcast to refresh UI alarms list
                appContext.sendBroadcast(android.content.Intent("com.vaishnava.alarm.REFRESH_ALARMS"))
            }
        } catch (e: Exception) {
            Log.w("AlarmStorage", "SHIFT_ALARM_RECONCILE_ERROR: ${e.message}")
        }
        return true
    }

    fun getAlarm(alarmId: Int): Alarm? {
        return getAlarms().firstOrNull { it.id == alarmId }
    }

    fun disableAlarm(alarmId: Int): Boolean {
        val currentAlarms = getAlarms().toMutableList()
        val index = currentAlarms.indexOfFirst { it.id == alarmId }
        if (index != -1) {
            val existing = currentAlarms[index]

            if (!existing.selectedDates.isNullOrEmpty()) {
                val now = System.currentTimeMillis()
                val remainingDates = existing.selectedDates.filter { dateMillis ->
                    val cal = Calendar.getInstance().apply {
                        timeInMillis = dateMillis
                        set(Calendar.HOUR_OF_DAY, existing.hour)
                        set(Calendar.MINUTE, existing.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    cal.timeInMillis > now
                }

                if (remainingDates.isNotEmpty()) {
                    val updated = existing.copy(selectedDates = remainingDates, isEnabled = true)
                    currentAlarms[index] = updated
                    saveAlarms(currentAlarms, synchronous = true)
                    clearRuntimeExecutionState(alarmId) // CRITICAL: Reset mission/completion markers so the next date triggers correctly
                    AndroidAlarmScheduler(appContext).schedule(updated)
                    triggerAutoBackup()
                    Log.d("AlarmStorage", "MULTI_DATE_ALARM_ADVANCED: alarmId=$alarmId, remainingDates=${remainingDates.size}")
                    return true
                }
            }

            currentAlarms[index] = existing.copy(isEnabled = false)
            saveAlarms(currentAlarms, synchronous = true)
            WakeCheckCycleStore.invalidate(appContext, alarmId)
            cascadeDeleteToDps(alarmId)
            triggerAutoBackup()
            return true
        }
        return false
    }

    private fun clearRuntimeExecutionState(alarmId: Int) {
        val dps = dpsContext ?: return

        try {
            val alarmPrefsEditor = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE).edit()
            listOf(
                "sequencer_completed_$alarmId",
                "sequence_active_$alarmId",
                "sequence_remaining_$alarmId",
                "wakecheck_cycle_id_$alarmId",
                "wakecheck_cycle_state_$alarmId",
                "wakecheck_initial_completed_$alarmId",
                "wakecheck_scheduled_at_$alarmId",
                "wakecheck_due_at_$alarmId",
                "wakecheck_retrigger_count_$alarmId",
                "wakecheck_updated_at_$alarmId",
                "wakecheck_pending_$alarmId",
                "wakecheck_gate_active_$alarmId",
                "wakecheck_finalized_$alarmId",
                "wakecheck_resume_pending_$alarmId",
                "wakecheck_ack_ts_$alarmId"
            ).forEach(alarmPrefsEditor::remove)
            val alarmPrefsCommitted = alarmPrefsEditor.commit()

            val dpsCompletionCommitted = dps.getSharedPreferences("dps_completion_sync", Context.MODE_PRIVATE)
                .edit()
                .remove("alarm_completed_in_dps_$alarmId")
                .commit()

            val pointerCommitted = dps.getSharedPreferences("mission_sequencer_pointer", Context.MODE_PRIVATE)
                .edit()
                .remove("active_mission_$alarmId")
                .remove("queue_ids_$alarmId")
                .remove("pointer_timestamp_$alarmId")
                .commit()

            val directBootPrefs = dps.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
            val currentAlarmId = directBootPrefs.getInt("current_alarm_id", -1)
            val directBootEditor = directBootPrefs.edit()
                .remove("wakecheck_resume_pending_$alarmId")
            if (currentAlarmId == alarmId) {
                directBootEditor
                    .remove("current_alarm_id")
                    .remove("was_ringing")
            }
            val directBootCommitted = directBootEditor.commit()

            if (PendingCompletionStore.getAlarmId(appContext) == alarmId) {
                PendingCompletionStore.clear(appContext)
                PendingCompletionStore.clearAlarm(appContext)
            }
            if (dps !== appContext && PendingCompletionStore.getAlarmId(dps) == alarmId) {
                PendingCompletionStore.clear(dps)
                PendingCompletionStore.clearAlarm(dps)
            }

            DpsMissionMirror(dps).clearSnapshot(alarmId)

            if (currentAlarmId == alarmId) {
                val queueStore = MissionQueueStore(dps)
                queueStore.clearQueue()
                queueStore.clearCurrentMission()
            }

            clearLegacyAlarmCaches(dps, alarmId)

            Log.d(
                "AlarmStorage",
                "CLEAR_RUNTIME_STATE_DPS: alarmId=$alarmId alarmPrefs=$alarmPrefsCommitted " +
                    "completionSync=$dpsCompletionCommitted pointer=$pointerCommitted directBoot=$directBootCommitted"
            )
        } catch (e: Exception) {
            Log.w("AlarmStorage", "CLEAR_RUNTIME_STATE_DPS_ERROR: alarmId=$alarmId ${e.message}")
        }
    }

    private fun clearLegacyAlarmCaches(dps: Context, alarmId: Int) {
        runCatching {
            dps.getSharedPreferences("alarm_data_cache", Context.MODE_PRIVATE)
                .edit()
                .remove("mission_type_$alarmId")
                .remove("mission_password_$alarmId")
                .remove("cache_time_$alarmId")
                .commit()
        }

        runCatching {
            dps.getSharedPreferences("direct_boot_alarms", Context.MODE_PRIVATE)
                .edit()
                .remove("alarm_$alarmId")
                .remove("alarm_ringtone_$alarmId")
                .remove("alarm_ringtone_is_content_$alarmId")
                .remove("alarm_ringtone_cached_$alarmId")
                .remove("alarm_mission_type_$alarmId")
                .remove("alarm_mission_password_$alarmId")
                .remove("alarm_is_protected_$alarmId")
                .remove("alarm_wake_check_enabled_$alarmId")
                .remove("alarm_wake_check_minutes_$alarmId")
                .remove("alarms")
                .commit()
        }
    }

    private fun cascadeDeleteToDps(alarmId: Int) {
        clearRuntimeExecutionState(alarmId)

        val dps = dpsContext ?: return
        try {
            val directBootPrefs = dps.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
            val currentAlarmId = directBootPrefs.getInt("current_alarm_id", -1)
            val editor = directBootPrefs.edit()
                .remove("direct_boot_ringtone_$alarmId")
                .remove("direct_boot_is_protected_$alarmId")
                .remove("direct_boot_wake_check_enabled_$alarmId")
                .remove("direct_boot_wake_check_minutes_$alarmId")
                .remove("direct_boot_mission_type_$alarmId")
                .remove("direct_boot_mission_password_$alarmId")
                .remove("wakecheck_resume_pending_$alarmId")

            if (currentAlarmId == alarmId) {
                editor
                    .remove("current_alarm_id")
                    .remove("was_ringing")
            }

            val committed = editor.commit()
            Log.d("AlarmStorage", "CASCADE_DELETE_TO_DPS: alarmId=$alarmId committed=$committed")
        } catch (e: Exception) {
            Log.w("AlarmStorage", "CASCADE_DELETE_TO_DPS_ERROR: alarmId=$alarmId ${e.message}")
        }
    }

    private fun shouldInvalidateWakeCheck(previous: Alarm, updated: Alarm): Boolean {
        return previous.hour != updated.hour ||
            previous.minute != updated.minute ||
            previous.isEnabled != updated.isEnabled ||
            previous.ringtoneUri != updated.ringtoneUri ||
            previous.days != updated.days ||
            previous.repeatDaily != updated.repeatDaily ||
            previous.isHidden != updated.isHidden ||
            previous.isProtected != updated.isProtected ||
            previous.missionType != updated.missionType ||
            previous.missionPassword != updated.missionPassword ||
            previous.wakeCheckEnabled != updated.wakeCheckEnabled ||
            previous.wakeCheckMinutes != updated.wakeCheckMinutes
    }

    fun triggerAutoBackup() {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        autoBackupScope.launch {
            try {
                // Add delay to ensure local changes are fully persisted before cloud backup
                delay(500)
                val result = CloudAlarmStorage(appContext).backupAlarmsToFirebase()
                if (result.success) {
                    Log.d("AlarmStorage", "AUTO_BACKUP_SUCCESS: uid=${currentUser.uid} count=${result.count}")
                } else {
                    Log.w("AlarmStorage", "AUTO_BACKUP_SKIPPED_OR_FAILED: ${result.message}")
                }
            } catch (e: Exception) {
                Log.e("AlarmStorage", "AUTO_BACKUP_ERROR: ${e.message}", e)
            }
        }
    }

    fun getNextAlarmId(): Int {
        synchronized(LOCK) {
            val currentAlarms = getAlarms()
            Log.d("AlarmStorage", "Current alarms when generating ID: ${currentAlarms.size}")
            for (alarm in currentAlarms) {
                Log.d("AlarmStorage", "Existing alarm ID: ${alarm.id}")
            }
            val nextId = if (currentAlarms.isEmpty()) {
                1
            } else {
                val maxId = currentAlarms.maxOf { it.id }
                Log.d("AlarmStorage", "Max existing ID: $maxId")
                maxId + 1
            }
            Log.d("AlarmStorage", "getNextAlarmId() returning: $nextId")
            return nextId
        }
    }

    companion object {
        val LOCK = Any()
        private const val PREF_NAME = "alarm_prefs"
        private const val KEY_ALARMS = "alarms"
        private val autoBackupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var hasRunInitMigrations = false
    }
}

class UriAdapter : TypeAdapter<Uri>() {
    override fun write(out: JsonWriter, value: Uri?) {
        out.value(value?.toString())
    }

    override fun read(input: JsonReader): Uri? {
        val uriString = input.nextString()
        return if (uriString.isNullOrEmpty()) null else Uri.parse(uriString)
    }
}
