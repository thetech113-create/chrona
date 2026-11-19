package com.vaishnava.alarm.sequencer

import com.vaishnava.alarm.ExtremeForensicLogger
import com.vaishnava.alarm.ExtremeForensicLogger.Chrona.LogType

import android.content.Context
import android.content.SharedPreferences

data class PendingCompletion(
    val alarmId: Int,
    val completedIndex: Int
)

/**
 * DPS-Safe Pending Mission Store
 * Persists mission completion state during DPS phase for CPS restoration
 * Works with CompletionMarker for robust mission continuation
 */
object PendingCompletionStore {
    private const val PREFS_NAME = "pending_completion_store"
    private const val KEY_PENDING_MISSION_ID = "pending_mission_id"
    private const val KEY_TIMESTAMP = "pending_timestamp"
    private const val KEY_PENDING_ALARM_ID = "pending_alarm_id"
    private const val KEY_COMPLETED_INDEX = "completed_index"
    
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Save a mission completion (missionId) for later replay when CPS becomes available.
     * Kept for backward compatibility with older callers.
     */
    fun save(context: Context, missionId: String) {
        try {
            val prefs = getPrefs(context)
            prefs.edit()
                .putString(KEY_PENDING_MISSION_ID, missionId)
                .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                .commit()
        
            ExtremeForensicLogger.Mission.log("PENDING_COMPLETION_SAVED: missionId=$missionId")
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("PENDING_COMPLETION_SAVE_ERROR: ${e.message}")
        }
    }

    /**
     * Mark a pending completion using alarmId (strict DPS handoff).
     * DPS should call this and do nothing else.
     */
    fun markPending(context: Context, alarmId: Int, completedIndex: Int) {
        getPrefs(context).edit().apply {
            putInt(KEY_PENDING_ALARM_ID, alarmId)
            putInt(KEY_COMPLETED_INDEX, completedIndex)
            putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            commit()
        }
        ExtremeForensicLogger.Chrona.log(context, "PendingCompletionStore", "PENDING_MARK: Marked pending completion for alarmId=$alarmId, completedIndex=$completedIndex")
    }
    
    /**
     * Get the pending mission completion, if any.
     */
    fun get(context: Context): String? {
        try {
            val prefs = getPrefs(context)
            val missionId = prefs.getString(KEY_PENDING_MISSION_ID, null)
            val timestamp = prefs.getLong(KEY_TIMESTAMP, 0)
            
            if (missionId != null && timestamp > 0) {
                ExtremeForensicLogger.Mission.log("PENDING_COMPLETION_RETRIEVED: missionId=$missionId timestamp=$timestamp")
                return missionId
            }
            
            return null
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("PENDING_COMPLETION_RETRIEVE_ERROR: ${e.message}")
            return null
        }
    }

    /**
     * Get pending alarmId, if any.
     */
    fun getAlarmId(context: Context): Int? {
        return try {
            val prefs = getPrefs(context)
            val alarmId = prefs.getInt(KEY_PENDING_ALARM_ID, -1)
            val timestamp = prefs.getLong(KEY_TIMESTAMP, 0)
            if (alarmId != -1 && timestamp > 0) {
                ExtremeForensicLogger.Mission.log("PENDING_ALARM_RETRIEVED: alarmId=$alarmId timestamp=$timestamp")
                alarmId
            } else null
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("PENDING_ALARM_RETRIEVE_ERROR: ${e.message}")
            null
        }
    }
    
    fun clear(context: Context) {
        getPrefs(context).edit().apply {
            remove(KEY_PENDING_ALARM_ID)
            remove(KEY_COMPLETED_INDEX)
            remove(KEY_TIMESTAMP)
            commit()
        }
        ExtremeForensicLogger.Chrona.log(context, "PendingCompletionStore", "Cleared pending completion")
    }

    /**
     * Clear pending alarmId only.
     */
    fun clearAlarm(context: Context) {
        try {
            val prefs = getPrefs(context)
            prefs.edit()
                .remove(KEY_PENDING_ALARM_ID)
                .remove(KEY_TIMESTAMP)
                .commit()
            ExtremeForensicLogger.Mission.log("PENDING_ALARM_CLEARED")
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("PENDING_ALARM_CLEAR_ERROR: ${e.message}")
        }
    }
    
    /**
     * Check if there's a pending completion waiting.
     */
    fun hasPending(context: Context): Boolean {
        return get(context) != null
    }

    /**
     * Check if a pending alarmId exists.
     */
    fun hasPendingAlarm(context: Context): Boolean {
        return getAlarmId(context) != null
    }

    /**
     * Restore pending completion state from DPS.
     * This is called by DirectBootRestoreReceiver on ACTION_USER_UNLOCKED.
     */
    fun restore(context: Context): PendingCompletion? {
        return try {
            val prefs = getPrefs(context)
            val alarmId = prefs.getInt(KEY_PENDING_ALARM_ID, -1)
            val completedIndex = prefs.getInt(KEY_COMPLETED_INDEX, -1)

            if (alarmId != -1 && completedIndex != -1) {
                ExtremeForensicLogger.Chrona.log(context, "PendingCompletionStore", "Restored pending completion for alarmId=$alarmId, completedIndex=$completedIndex")
                PendingCompletion(alarmId, completedIndex)
            } else {
                null
            }
        } catch (e: Exception) {
            ExtremeForensicLogger.Chrona.log(context, LogType.ERROR, "PendingCompletionStore", "Failed to restore pending completion: ${e.message}")
            null
        }
    }

    /**
     * Load pending completion state for a specific alarmId.
     * This is called by MissionSequencer.forceResumeFromCps.
     */
    fun load(context: Context, alarmId: Int): PendingCompletion? {
        return try {
            val prefs = getPrefs(context)
            val storedAlarmId = prefs.getInt(KEY_PENDING_ALARM_ID, -1)
            val completedIndex = prefs.getInt(KEY_COMPLETED_INDEX, -1)

            if (storedAlarmId == alarmId && completedIndex != -1) {
                ExtremeForensicLogger.Chrona.log(context, "PendingCompletionStore", "Loaded pending completion for alarmId=$alarmId, completedIndex=$completedIndex")
                PendingCompletion(alarmId, completedIndex)
            } else {
                null
            }
        } catch (e: Exception) {
            ExtremeForensicLogger.Chrona.log(context, LogType.ERROR, "PendingCompletionStore", "Failed to load pending completion for alarmId=$alarmId: ${e.message}")
            null
        }
    }
}
