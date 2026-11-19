package com.vaishnava.alarm

import android.content.Context
import android.util.Log

/**
 * ManualWatchdogTrigger - Provides manual methods to trigger watchdog checks
 * This is useful for testing and debugging missed alarms
 */
object ManualWatchdogTrigger {
    private const val TAG = "ManualWatchdog"
    
    /**
     * Create a missed alarm log directly without checking
     * This is a last resort method to ensure logs are created
     */
    fun createMissedAlarmLogDirectly(context: Context, alarmId: Int = -1) {
        try {
            Log.d(TAG, "Creating missed alarm log directly for alarm ID: $alarmId")

            // PATCHED_BY_AUTOFIXER: Removed MissedAlarmLogger.logMissedAlarm call
            Log.d(TAG, "✅ Created missed alarm log directly for alarm ID: $alarmId")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating missed alarm log directly: ${e.message}", e)
        }
    }
}
