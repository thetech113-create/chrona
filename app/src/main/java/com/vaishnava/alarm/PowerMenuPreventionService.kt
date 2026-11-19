package com.vaishnava.alarm

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Aggressive power menu prevention: Auto-dismiss SystemUI overlays during active alarms.
 *
 * Strategy: Whenever alarm is ringing AND SystemUI appears, immediately send BACK.
 * No cooldown—GLOBAL_ACTION_BACK is idempotent and safe to call repeatedly.
 */
class PowerMenuPreventionService : AccessibilityService() {

    companion object {
        private const val TAG = "PowerMenuPrevention"
        private const val PREFS_NAME = "direct_boot_alarm_prefs"
        private const val CURRENT_ALARM_ID_KEY = "current_alarm_id"

        /**
         * Whether the user has switched this service on in Accessibility settings.
         * Nothing here can enable it - only the user can, from system settings.
         */
        fun isAccessibilityEnabled(context: android.content.Context): Boolean {
            return try {
                val component = android.content.ComponentName(
                    context.packageName,
                    PowerMenuPreventionService::class.java.name
                )
                val expectedLong = component.flattenToString()
                val expectedShort = component.flattenToShortString()

                val enabled = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false

                enabled.split(':').any { entry ->
                    val trimmed = entry.trim()
                    trimmed.equals(expectedLong, ignoreCase = true) ||
                        trimmed.equals(expectedShort, ignoreCase = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Accessibility check failed: ${e.message}")
                false
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return

            // Get alarm ID and check if ringing
            val alarmId = try {
                getAlarmId()
            } catch (e: Exception) {
                Log.w(TAG, "Alarm ID check exception: ${e.message}")
                -1
            }
            if (alarmId == -1) return

            // Check if this alarm has power-off prevention enabled
            val preventEnabled = try {
                isPreventPowerOffEnabled(alarmId)
            } catch (e: Exception) {
                Log.w(TAG, "Prevent check exception: ${e.message}")
                false
            }
            if (!preventEnabled) return

            val pkg = event.packageName?.toString() ?: ""

            // If SystemUI appears while alarm is active AND prevention enabled, dismiss it
            if (pkg.contains("systemui")) {
                try {
                    val result = performGlobalAction(GLOBAL_ACTION_BACK)
                    if (result) {
                        Log.d(TAG, "SystemUI dismissed (prevent enabled for alarm $alarmId)")
                    } else {
                        Log.w(TAG, "BACK action not queued")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "BACK failed: ${e.message}")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Event handler crash: ${t.message}", t)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted (accessibility disabled?)")
    }

    private fun getAlarmId(): Int {
        return try {
            val dps = createDeviceProtectedStorageContext() ?: this
            val prefs = dps.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.getInt(CURRENT_ALARM_ID_KEY, -1)
        } catch (e: Exception) {
            -1
        }
    }

    private fun isPreventPowerOffEnabled(alarmId: Int): Boolean {
        if (alarmId == -1) return false

        return try {
            // Query the alarm from database to check preventPowerOff flag
            // This requires accessing the app's database—use AlarmDataService if available
            val prefs = getSharedPreferences("prevent_power_off_prefs", MODE_PRIVATE)
            prefs.getBoolean("alarm_$alarmId", false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check prevent setting: ${e.message}")
            false
        }
    }
}
