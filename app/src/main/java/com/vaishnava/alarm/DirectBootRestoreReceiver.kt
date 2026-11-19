package com.vaishnava.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vaishnava.alarm.data.Alarm
import com.vaishnava.alarm.data.WakeCheckStore
import com.vaishnava.alarm.data.isOneShot
import com.vaishnava.alarm.sequencer.CompletionMarker
import com.vaishnava.alarm.sequencer.DpsMissionMirror
import com.vaishnava.alarm.sequencer.PendingCompletionStore
import com.vaishnava.alarm.shift.ShiftRotationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.FileNotFoundException

/**
 * Minimal, direct-boot-aware receiver whose ONLY job is to re-schedule a near-term alarm
 * after device reboot while still locked. It does NOT start services or activities.
 * Also handles cleanup when app is uninstalled.
 */
class DirectBootRestoreReceiver : BroadcastReceiver() {
    companion object {
        // Call this method from your main activity's onDestroy or from a ContentProvider's onCreate
        const val PREFS_NAME = "AlarmAppPrefs"
        const val KEY_FORCE_RESTART_IN_PROGRESS = "force_restart_in_progress"
        const val KEY_BOOT_RESCHEDULE_IN_PROGRESS = "boot_reschedule_in_progress"
        const val FORCE_RESTART_FLAG_FILE = "force_restart_flag.txt"
        const val NOTIFICATION_CHANNEL_ID = "app_integrity_channel"
        const val NOTIFICATION_ID = 1002

        fun cleanupOnUninstall(context: Context) {
            val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext() ?: context
            } else context
            val isForceRestartInProgress = try {
                dpsContext.openFileInput(FORCE_RESTART_FLAG_FILE).use { it.readBytes().toString(Charsets.UTF_8) == "true" }
            } catch (e: FileNotFoundException) {
                false
            } catch (e: Exception) {
                Log.e("AlarmApp", "CleanupProvider: Failed to read FORCE_RESTART_FLAG_FILE: ${e.message}")
                false
            }

            if (isForceRestartInProgress) {
                Log.d("AlarmApp", "CleanupProvider: Skipping cleanup due to force restart in progress.")
                return
            }
            try {
                val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    context.createDeviceProtectedStorageContext() ?: context
                } else context
                
                // Clear all shared preferences
                val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                prefs.edit().clear().commit()
                
                // Delete the force restart flag file
                try {
                    dps.deleteFile(FORCE_RESTART_FLAG_FILE)
                    Log.d("AlarmApp", "CleanupProvider: FORCE_RESTART_FLAG_FILE deleted.")
                } catch (e: Exception) {
                    Log.e("AlarmApp", "CleanupProvider: Failed to delete FORCE_RESTART_FLAG_FILE: ${e.message}")
                }
                
                 // Cancel all alarms
                 val am = dps.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                 
                // Try to cancel any pending alarm intents
                for (id in 0..100) {
                    try {
                        // Cancel regular alarms
                        val pendingIntent = PendingIntent.getBroadcast(
                            dps,
                            id,
                            Intent(dps, AlarmReceiver::class.java),
                            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                        )
                        if (pendingIntent != null) {
                            am.cancel(pendingIntent)
                            pendingIntent.cancel()
                        }
                        
                        // Cancel activity intents
                        val activityIntent = PendingIntent.getActivity(
                            dps,
                            id,
                            Intent(dps, AlarmActivity::class.java),
                            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                        )
                        if (activityIntent != null) {
                            am.cancel(activityIntent)
                            activityIntent.cancel()
                        }
                    } catch (_: Exception) {}
                }
                
                Log.d("AlarmApp", "Cleaned up all alarms and preferences")
            } catch (e: Exception) {
                Log.e("AlarmApp", "Failed to clean up: ${e.message}")
            }
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleReceiveAsync(context, intent)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleReceiveAsync(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_UNLOCKED) {
            // 1ï¸âƒ£ Mark CPS ready
            ExtremeForensicLogger.Chrona.log(context, ExtremeForensicLogger.Chrona.LogType.DEBUG, "UNLOCK", "User unlocked, CPS ready")

            // Sync DPS completion state into CPS in the background and stop there.
            handleDeviceUnlock(context)
            return
        }
        try {
            var action = intent.action ?: return

            // Get device protected storage context once at the beginning
            val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
            } else null
            val actualDpsContext = dpsContext ?: context

            when (action) {
                Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED -> {
                    // Check app installation validity
                    if (!isAppInstallationValid(actualDpsContext)) {
                        Log.e("AlarmApp", "App installation is invalid. Cannot restore alarms.")
                        handleInvalidInstallation(context)
                        return
                    }

                    // Continue with boot handling - focus on alarm restoration
                    Log.d("AlarmApp", "Boot completed - restoring alarms")
                    try {
                        actualDpsContext.openFileOutput(FORCE_RESTART_FLAG_FILE, Context.MODE_PRIVATE).use {
                            it.write("true".toByteArray())
                        }
                        Log.d("AlarmApp", "DirectBootRestoreReceiver: FORCE_RESTART_FLAG_FILE created.")
                    } catch (e: Exception) {
                        Log.e("AlarmApp", "DirectBootRestoreReceiver: Failed to create FORCE_RESTART_FLAG_FILE: ${e.message}")
                    }
                    
                    // NEW: Reschedule all future alarms in locked boot mode
                    try {
                        val scheduledIds = rescheduleAllAlarmsInLockedMode(actualDpsContext)
                        recoverPersistedWakeCheckCycles(actualDpsContext, "locked_boot")
                        ShiftRotationCoordinator(actualDpsContext).recoverAfterRestart()
                        // Store scheduled IDs to prevent dual ringing in missed alarm check
                        checkAndFireMissedAlarms(actualDpsContext, scheduledIds)
                        
                        // CRITICAL: Handle mission alarm recovery in DPS - with extra safety
                        try {
                            rescheduleMissionAlarmsInLockedMode(actualDpsContext)
                        } catch (missionError: Exception) {
                            Log.e("AlarmApp", "Mission alarm recovery failed during DirectBoot (safe to continue): ${missionError.message}")
                            // Continue without mission recovery - don't crash the boot process
                        }
                        
                    } catch (e: Exception) {
                        Log.e("AlarmApp", "Failed to reschedule alarms in locked mode: ${e.message}")
                        // Continue boot process even if alarm rescheduling fails
                    }
                }
                else -> return
            }

            // Use the single actualDpsContext for all subsequent operations
            val prefs = actualDpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            val wasRinging = prefs.getBoolean("was_ringing", false)
            val ringId = prefs.getInt("ring_alarm_id", -1)
            Log.d("AlarmApp", "DirectBootRestoreReceiver: Retrieved wasRinging=$wasRinging, ringId=$ringId from SharedPreferences.")
            val isForceRestartInProgress = try {
                actualDpsContext.openFileInput(FORCE_RESTART_FLAG_FILE).use {
                    it.readBytes().toString(Charsets.UTF_8) == "true"
                }
            } catch (e: FileNotFoundException) {
                false
            } catch (e: Exception) {
                Log.e("AlarmApp", "DirectBootRestoreReceiver: Failed to read FORCE_RESTART_FLAG_FILE: ${e.message}")
                false
            }
            Log.d("AlarmApp", "DirectBootRestoreReceiver: isForceRestartInProgress=$isForceRestartInProgress")
            if (!wasRinging || ringId == -1) return

            // Schedule near-term re-ring using only AlarmManager
            val am = actualDpsContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            // Check if this is a sequencer alarm - if so, don't launch AlarmActivity directly
            val alarm = try {
                val storage = AlarmStorage(actualDpsContext)
                storage.getAlarm(ringId)
            } catch (_: Exception) {
                null
            }
            
            // CRITICAL FIX: Create unique PendingIntent based on mission content
            val missionSignature = "${ringId}_${alarm?.missionType}_${alarm?.missionPassword}"
            val uniqueRequestCode = missionSignature.hashCode()
            
            val showIntent: PendingIntent = if (alarm?.missionType != "sequencer") {
                PendingIntent.getActivity(
                    actualDpsContext,
                    uniqueRequestCode,
                    Intent(actualDpsContext, AlarmActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra(AlarmReceiver.ALARM_ID, ringId)
                        // CRITICAL FIX: Block "none" missions from being launched via DirectBoot
                        putExtra("mission_type", alarm?.missionType?.let { if (it == "none") "" else it } ?: "")
                        putExtra("mission_password", alarm?.missionPassword ?: "")
                        putExtra("mission_signature", missionSignature)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                // For sequencer alarms, create a broadcast PendingIntent instead
                // The MissionSequencer will handle launching the actual missions
                PendingIntent.getBroadcast(
                    actualDpsContext,
                    uniqueRequestCode,
                    Intent(actualDpsContext, AlarmReceiver::class.java).apply {
                        action = "com.vaishnava.alarm.SEQUENCER_MISSED_ALARM"
                        putExtra(AlarmReceiver.ALARM_ID, ringId)
                        // CRITICAL FIX: Block "none" missions from being launched via DirectBoot
                        putExtra("mission_type", alarm?.missionType?.let { if (it == "none") "" else it } ?: "")
                        putExtra("mission_password", alarm?.missionPassword ?: "")
                        putExtra("mission_signature", missionSignature)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            val fireIntent: PendingIntent = PendingIntent.getBroadcast(
                actualDpsContext,
                ringId,
                Intent(actualDpsContext, AlarmReceiver::class.java).apply {
                    action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
                    data = Uri.parse("alarm://$ringId")
                    putExtra(AlarmReceiver.ALARM_ID, ringId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val now = System.currentTimeMillis()
            val triggerAt = now + 800
            val info = AlarmManager.AlarmClockInfo(triggerAt, showIntent)
            am.setAlarmClock(info, fireIntent)
            // CRITICAL FIX: Remove backup alarm to prevent multiple simultaneous alarms firing

            // CRITICAL FIX: Remove ALL backup alarms to prevent multiple simultaneous alarms firing
            // The primary setAlarmClock trigger is sufficient

            Log.d("AlarmApp", "DirectBootRestoreReceiver: scheduled re-ring for ID=$ringId (AlarmClock + backups)")

            // CRITICAL FIX: Remove immediate broadcast to prevent dual audio
            // The setAlarmClock trigger is sufficient - immediate broadcast causes duplicate AlarmForegroundService

            // Last-resort: try to start the foreground service immediately to ensure sound
            try {
                // Get the ringtone URI and mission data from DPS storage
                val ringtoneUri = try {
                    val prefs = actualDpsContext.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
                    val uriString = prefs.getString("direct_boot_ringtone_$ringId", null)
                    uriString?.let { Uri.parse(it) }
                } catch (e: Exception) {
                    Log.e("AlarmApp", "Failed to get ringtone URI for DPS alarm ID=$ringId: ${e.message}")
                    null
                }

                val directBootPrefs = actualDpsContext.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
                val missionType = alarm?.missionType
                    ?: directBootPrefs.getString("direct_boot_mission_type_$ringId", null)
                val missionPassword = alarm?.missionPassword
                    ?: directBootPrefs.getString("direct_boot_mission_password_$ringId", null)
                
                val isProtected = try {
                    directBootPrefs.getBoolean("direct_boot_is_protected_$ringId", false)
                } catch (e: Exception) {
                    Log.e("AlarmApp", "Failed to get protection status for DPS alarm ID=$ringId: ${e.message}")
                    false
                }
                
                val svc = Intent(actualDpsContext, AlarmForegroundService::class.java).apply {
                    putExtra(AlarmReceiver.ALARM_ID, ringId)
                    putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, ringtoneUri)
                    if (missionType != null && missionType != "sequencer" && missionType != "none") {
                        putExtra("mission_type", missionType)
                        putExtra("mission_password", missionPassword ?: "")
                    }
                    putExtra("is_protected", isProtected)
                }
                
                try {
                    actualDpsContext.startService(svc)
                    Log.d("AlarmApp", "DirectBootRestoreReceiver: started AlarmForegroundService for ID=$ringId with ringtone=$ringtoneUri, mission=$missionType, protected=$isProtected")
                } catch (e: Exception) {
                    Log.e("AlarmApp", "DirectBootRestoreReceiver: Failed to start AlarmForegroundService for ID=$ringId: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e("AlarmApp", "DirectBootRestoreReceiver: Last-resort restore block failed: ${e.message}")
            }

            // CRITICAL FIX: Check if any alarm is currently playing before checking missed alarms
            val currentlyPlayingAlarmId = try {
                val prefs = actualDpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                prefs.getInt("current_service_alarm_id", -1)
            } catch (_: Exception) { -1 }
            
            val isAnyAlarmCurrentlyPlaying = currentlyPlayingAlarmId != -1
            
            if (isAnyAlarmCurrentlyPlaying) {
                Log.d("AlarmApp", " ALARM CURRENTLY PLAYING: Skipping missed alarm check - alarm ID $currentlyPlayingAlarmId is active")
                return
            }
            
            // ADDITIONAL FIX: Check if any alarm was scheduled to fire in the last 2 minutes
            val recentlyScheduledAlarmId = try {
                val prefs = actualDpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                val lastScheduledId = prefs.getInt("last_scheduled_alarm_id", -1)
                val lastScheduledTime = prefs.getLong("last_scheduled_time", 0L)
                val now = System.currentTimeMillis()
                val minutesSinceScheduled = (now - lastScheduledTime) / (1000 * 60)
                
                if (lastScheduledId != -1 && minutesSinceScheduled <= 2) {
                    Log.d("AlarmApp", " RECENTLY SCHEDULED: Alarm ID $lastScheduledId was scheduled $minutesSinceScheduled minutes ago, skipping missed alarm check")
                    return
                }
                -1
            } catch (_: Exception) { -1 }
            
            // CRITICAL: Clear was_ringing flag to prevent dual detection in missed alarm check
            try {
                val prefs = actualDpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                prefs.edit()
                    .remove("was_ringing")
                    .remove("ring_alarm_id") 
                    .remove("ring_started_at")
                    .putBoolean("restored_alarm_${ringId}", true) // Mark this alarm as just restored
                    .commit()
                Log.d("AlarmApp", "DirectBootRestoreReceiver: Cleared was_ringing flags and marked alarm $ringId as restored")
            } catch (e: Exception) {
                Log.e("AlarmApp", "DirectBootRestoreReceiver: Failed to clear was_ringing flags: ${e.message}")
            }

        }   catch (e: Exception) {
            Log.e("AlarmApp", "DirectBootRestoreReceiver failed: ${e.message}")
        }
    }
    
    /**
     * Clears all alarms and shared preferences when app is uninstalled
     */
    private fun clearAllAlarms(context: Context) {
        try {
            // Get both regular and device protected storage contexts
            val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext() ?: context
            } else context
            
            // Clear shared preferences
            val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
            
           // Delete the force restart flag file
           try {
              dps.deleteFile(FORCE_RESTART_FLAG_FILE)
                Log.d("AlarmApp", "DirectBootRestoreReceiver: FORCE_RESTART_FLAG_FILE deleted during uninstall.")
          } catch (e: Exception) {
              Log.e("AlarmApp", "DirectBootRestoreReceiver: Failed to delete FORCE_RESTART_FLAG_FILE during uninstall: ${e.message}")
           }

             // Get alarm manager and cancel any pending alarms
             val am = dps.getSystemService(Context.ALARM_SERVICE) as AlarmManager
             
            // Try to cancel any pending alarm intents
            // Since we don't know which alarms are active, try with a range of IDs
            for (id in 0..100) {
                try {
                    val pendingIntent = PendingIntent.getBroadcast(
                        dps,
                        id,
                        Intent(dps, AlarmReceiver::class.java),
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                    )
                    if (pendingIntent != null) {
                        am.cancel(pendingIntent)
                        pendingIntent.cancel()
                    }
                } catch (_: Exception) {}
            }
            
            Log.d("AlarmApp", "DirectBootRestoreReceiver: cleared all alarms during uninstall")
        } catch (e: Exception) {
            Log.e("AlarmApp", "Failed to clear alarms during uninstall: ${e.message}")
        }
    }
    
    /**
     * Checks if the app installation appears to be valid by trying to access critical resources/files.
     */
     private fun isAppInstallationValid(context: Context): Boolean {
         try {
             Log.d("AlarmApp", "isAppInstallationValid: Context package name: ${context.packageName}, UID: ${context.applicationInfo.uid}")
             // Attempt to access a critical resource (e.g., app name string)
             context.resources.getString(R.string.app_name)

            // Attempt to access a file in device-protected storage
            val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext() ?: run {
                    Log.w("AlarmApp", "Falling back to regular context for DPS access")
                    context
                }
            } else context
            
            // Create dummy file if it doesn't exist to prevent false integrity failures
            try {
                dpsContext.openFileInput("dummy_integrity_check.txt").close()
            } catch (e: FileNotFoundException) {
                Log.d("AlarmApp", "Creating missing dummy integrity check file")
                dpsContext.openFileOutput("dummy_integrity_check.txt", Context.MODE_PRIVATE).use { it.write("valid".toByteArray()) }
            }

            // If both checks pass, the installation is likely valid
            return true
        } catch (e: Exception) {
            Log.e("AlarmApp", "App integrity check failed: ${e.message}", e)
            return false
        }
    }

    /**
     * Notifies the user that the app installation is invalid and prompts for re-installation.
     */
    private fun handleInvalidInstallation(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "App Integrity Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for critical app installation issues."
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Create an intent to open the Play Store page for your app
        val appPackageName = context.packageName
        val playStoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appPackageName")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            playStoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification) // Use an appropriate icon
            .setContentTitle("App Installation Corrupted")
            .setContentText("Your alarm app installation appears to be corrupted. Please re-install the app from the Play Store.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Your alarm app installation appears to be corrupted, possibly due to an unexpected shutdown. Alarms may not function correctly. Please re-install the app from the Play Store to restore full functionality."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
        Log.d("AlarmApp", "Notified user about invalid app installation.")
    }
    
    /**
     * Reschedule all enabled alarms when device is in locked boot mode.
     * This ensures alarms fire even if phone remains locked after reboot.
     * Returns list of scheduled alarm IDs to prevent dual ringing.
     */
    private fun rescheduleAllAlarmsInLockedMode(dpsContext: Context): Set<Int> {
        val directBootPrefs = dpsContext.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
        try {
            directBootPrefs.edit().putBoolean(KEY_BOOT_RESCHEDULE_IN_PROGRESS, true).commit()
            Log.d("AlarmApp", "=== DPS LOCKED BOOT RESCHEDULE START ===")
            Log.d("AlarmApp", "Rescheduling all alarms in locked boot mode")
            
            // Try to get alarms from device protected storage first
            val alarms = try {
                val storage = AlarmStorage(dpsContext)
                val alarmList = storage.getAlarms()
                Log.d("AlarmApp", "Found ${alarmList.size} alarms in DPS storage")
                alarmList.forEach { alarm: Alarm ->
                    Log.d("AlarmApp", "DPS Alarm: ID=${alarm.id}, Enabled=${alarm.isEnabled}, Time=${alarm.hour}:${alarm.minute}")
                }
                alarmList
            } catch (e: Exception) {
                Log.w("AlarmApp", "Failed to read alarms from DPS storage: ${e.message}")
                emptyList()
            }
            
            if (alarms.isEmpty()) {
                Log.d("AlarmApp", "No alarms found in DPS storage during locked boot")
                return emptySet()
            }
            
            val scheduler = AndroidAlarmScheduler(dpsContext)
            var scheduledCount = 0
            val scheduledIds = mutableSetOf<Int>()
            val now = System.currentTimeMillis()
            
            alarms.forEach { alarm: Alarm ->
                if (alarm.isEnabled) {
                    try {
                        // Calculate if alarm should trigger soon (within next 24 hours)
                        val calendar = java.util.Calendar.getInstance().apply {
                            timeInMillis = now
                            set(java.util.Calendar.HOUR_OF_DAY, alarm.hour)
                            set(java.util.Calendar.MINUTE, alarm.minute)
                            set(java.util.Calendar.SECOND, 0)
                            set(java.util.Calendar.MILLISECOND, 0)
                        }
                        
                        // If alarm time is earlier than now, move to next day
                        if (calendar.timeInMillis <= now) {
                            calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
                        }
                        
                        // Only schedule if alarm is within next 24 hours
                        val nextTrigger = calendar.timeInMillis
                        if (nextTrigger - now <= 24 * 60 * 60 * 1000L) {
                            scheduler.schedule(alarm)
                            scheduledCount++
                            scheduledIds.add(alarm.id)
                            val timeUntil = (nextTrigger - now) / 1000 / 60 // minutes
                            Log.d("AlarmApp", "âœ… SCHEDULED DPS Alarm ID ${alarm.id} for ${alarm.hour}:${alarm.minute} (in ${timeUntil} minutes)")
                        } else {
                            Log.d("AlarmApp", "â­ï¸ Skipping alarm ID ${alarm.id} - more than 24 hours away")
                        }
                    } catch (e: Exception) {
                        Log.e("AlarmApp", "âŒ Failed to schedule alarm ID ${alarm.id} in locked mode: ${e.message}")
                    }
                } else {
                    Log.d("AlarmApp", "â¸ï¸ Skipping disabled alarm ID ${alarm.id} in locked boot")
                }
            }
            
            Log.d("AlarmApp", "=== DPS LOCKED BOOT RESCHEDULE COMPLETE: $scheduledCount alarms scheduled ===")
            return scheduledIds
        } catch (e: Exception) {
            Log.e("AlarmApp", "Error in rescheduleAllAlarmsInLockedMode: ${e.message}", e)
            return emptySet()
        } finally {
            directBootPrefs.edit().remove(KEY_BOOT_RESCHEDULE_IN_PROGRESS).commit()
        }
    }
    
    /**
     * CRITICAL: Handle mission alarm recovery in DPS (Direct Boot)
     * CPS-ONLY: No DPS mission alarm recovery needed - CPS handles mission scheduling
     */
    private fun rescheduleMissionAlarmsInLockedMode(dpsContext: Context) {
        try {
            Log.d("AlarmApp", "=== CPS NO MISSION RECOVERY: CPS handles mission scheduling ===")
            
            // CPS-ONLY: No DPS mirror - CPS handles mission scheduling
            val storage = AlarmStorage(dpsContext)
            val alarms = storage.getAlarms()
            val sequencerAlarms = alarms.filter { it.missionType == "sequencer" && it.isEnabled }
            
            Log.d("AlarmApp", "Found ${sequencerAlarms.size} sequencer alarms - CPS will handle scheduling")
            
            for (alarm in sequencerAlarms) {
                try {
                    // CPS-ONLY: No DPS validation - CPS handles mission recovery
                    Log.d("AlarmApp", "CPS_MISSION_HANDLED: alarmId=${alarm.id} - CPS will handle mission scheduling")
                } catch (e: Exception) {
                    Log.e("AlarmApp", "Mission recovery error for alarmId=${alarm.id}: ${e.message}")
                    // Continue with next alarm
                }
            }
            
        } catch (e: Exception) {
            Log.e("AlarmApp", "DPS mission recovery failed: ${e.message}")
            // Don't crash boot process
        }
    }
    
    /**
     * Check for missed alarms that should fire immediately when device boots up.
     * This handles cases where device was powered off during alarm time.
     * Skips alarms that were just scheduled for the near future to prevent dual ringing.
     */
    private fun checkAndFireMissedAlarms(context: Context, scheduledIds: Set<Int> = emptySet()) {
        try {
            Log.d("AlarmApp", "=== MISSED ALARM CHECK START ===")
            val alarmPrefs = context.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            val syncPrefs = context.getSharedPreferences("dps_completion_sync", Context.MODE_PRIVATE)
            val dpsMirror = DpsMissionMirror(context)
            
            // Get alarms from storage
            val alarms = try {
                val storage = AlarmStorage(context)
                storage.getAlarms()
            } catch (e: Exception) {
                Log.e("AlarmApp", "Failed to read alarms for missed alarm check: ${e.message}")
                emptyList()
            }
            
            if (alarms.isEmpty()) {
                Log.d("AlarmApp", "No alarms found for missed alarm check")
                return
            }
            
            val now = System.currentTimeMillis()
            val missedAlarms = mutableListOf<Alarm>()
            
            alarms.forEach { alarm: Alarm ->
                if (alarm.isEnabled) {
                    val wakeCheckCycle = WakeCheckCycleStore.read(context, alarm.id)
                    val wakeCheckContinuityActive =
                        wakeCheckCycle != null &&
                            wakeCheckCycle.cycleId > 0L &&
                            when (wakeCheckCycle.state) {
                                WakeCheckCycleState.WAKEUP_CHECK_SCHEDULED,
                                WakeCheckCycleState.WAKEUP_CHECK_SHOWING,
                                WakeCheckCycleState.WAKEUP_CHECK_MISSED,
                                WakeCheckCycleState.ALARM_RETRIGGERED -> true
                                else -> false
                            }
                    if (wakeCheckContinuityActive) {
                        Log.d(
                            "AlarmApp",
                            "MISSED_ALARM_SKIP_WAKECHECK_CONTINUITY: ID=${alarm.id} " +
                                "cycleId=${wakeCheckCycle?.cycleId ?: 0L} state=${wakeCheckCycle?.state ?: "null"} " +
                                "dueAt=${wakeCheckCycle?.dueAt ?: 0L} expiryAt=${wakeCheckCycle?.expiryAt ?: 0L}"
                        )
                        return@forEach
                    }

                    val snapshot = dpsMirror.readSnapshot(alarm.id)
                    val rawCompletedInDps = snapshot?.isSequenceComplete == true ||
                        alarmPrefs.getBoolean("sequencer_completed_${alarm.id}", false) ||
                        syncPrefs.getBoolean("alarm_completed_in_dps_${alarm.id}", false)
                    if (rawCompletedInDps) {
                        val wakeCheckLoopActive = WakeCheckStore.isLoopActive(context, alarm)
                        Log.d(
                            "AlarmApp",
                            "MISSED ALARM SKIP_DPS_COMPLETION: ID=${alarm.id} " +
                                "wakeCheckLoopActive=$wakeCheckLoopActive " +
                                "cycleId=${wakeCheckCycle?.cycleId ?: 0L} " +
                                "cycleState=${wakeCheckCycle?.state ?: "null"}"
                        )
                        return@forEach
                    }

                    // Calculate when this alarm should have last fired
                    val calendar = java.util.Calendar.getInstance().apply {
                        timeInMillis = now
                        set(java.util.Calendar.HOUR_OF_DAY, alarm.hour)
                        set(java.util.Calendar.MINUTE, alarm.minute)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    
                    // If alarm time is later than now, move to yesterday
                    if (calendar.timeInMillis > now) {
                        calendar.add(java.util.Calendar.DAY_OF_MONTH, -1)
                    }
                    
                    val lastAlarmTime = calendar.timeInMillis
                    val minutesMissed = (now - lastAlarmTime) / (1000 * 60)
                    
                    // Check if alarm was missed in the last 30 minutes
                    if (minutesMissed in 1L..30L) {
                        missedAlarms.add(alarm)
                        Log.d("AlarmApp", "MISSED ALARM: ID=${alarm.id}, Time=${alarm.hour}:${alarm.minute}, Missed by=${minutesMissed} minutes")
                    }
                }
            }
            
            if (missedAlarms.isEmpty()) {
                Log.d("AlarmApp", "No missed alarms found (or skipped to prevent dual ringing)")
                return
            }
            
            // Fire missed alarms immediately
            missedAlarms.forEach { alarm: Alarm ->
                try {
                    Log.d("AlarmApp", " FIRING MISSED ALARM ID=${alarm.id} immediately")
                    
                    if (alarm.missionType == "sequencer") {
                        // For sequencer alarms, use AlarmForegroundService like single alarms
                        // The service will handle rebuilding the mission queue
                        Log.d("AlarmApp", "Using AlarmForegroundService for missed sequencer alarm ID=${alarm.id}")
                        val svc = Intent(context, AlarmForegroundService::class.java).apply {
                            putExtra(AlarmReceiver.ALARM_ID, alarm.id)
                            putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, alarm.ringtoneUri)
                            putExtra("is_missed_alarm", true)
                            putExtra("is_sequencer_missed_alarm", true) // Special flag for sequencer missed alarms
                        }
                        
                        try {
                            // Check if service is already running for this alarm to prevent duplicate starts
                            val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                context.createDeviceProtectedStorageContext()
                            } else {
                                context
                            }
                            val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                            val currentServiceAlarmId = prefs.getInt("current_service_alarm_id", -1)
                            
                            if (currentServiceAlarmId == alarm.id) {
                                Log.d("AlarmApp", "AlarmForegroundService already running for missed sequencer alarm ID=${alarm.id}, skipping service start")
                            } else {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(svc)
                                } else {
                                    context.startService(svc)
                                }
                                Log.d("AlarmApp", "Started AlarmForegroundService unstoppable for missed sequ; will auto-stop after all missions complete ID=${alarm.id}")
                            }
                        } catch (e: Exception) {
                            Log.e("AlarmApp", "Failed to start AlarmForegroundService for missed sequencer alarm ID=${alarm.id}: ${e.message}")
                        }
                    } else {
                        // For non-sequencer alarms, use the regular AlarmForegroundService
                        val svc = Intent(context, AlarmForegroundService::class.java).apply {
                            putExtra(AlarmReceiver.ALARM_ID, alarm.id)
                            putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, alarm.ringtoneUri) // CRITICAL: Pass the correct ringtone URI
                            putExtra("is_missed_alarm", true)
                        }
                        try {
                            // Check if service is already running for this alarm to prevent duplicate starts
                            try {
                                val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    context.createDeviceProtectedStorageContext()
                                } else {
                                    context
                                }
                                val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                                val currentServiceAlarmId = prefs.getInt("current_service_alarm_id", -1)
                                
                                if (currentServiceAlarmId == alarm.id) {
                                    Log.d("AlarmApp", "AlarmForegroundService already running for missed alarm ID=${alarm.id}, skipping service start")
                                } else {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(svc)
                                    } else {
                                        context.startService(svc)
                                    }
                                    Log.d("AlarmApp", "Started AlarmForegroundService for missed alarm ID=${alarm.id} (audio only)")
                                }
                            } catch (e: Exception) {
                                Log.e("AlarmApp", "Failed to check current service alarm ID for missed alarm ID=${alarm.id}: ${e.message}")
                                // Fallback: try starting service anyway
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(svc)
                                } else {
                                    context.startService(svc)
                                }
                                Log.d("AlarmApp", "Fallback: Started AlarmForegroundService for missed alarm ID=${alarm.id}")
                            }
                        } catch (e: Exception) {
                            Log.e("AlarmApp", "Failed to start AlarmForegroundService for missed alarm ID=${alarm.id}: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AlarmApp", "Failed to process missed alarm ID=${alarm.id}: ${e.message}")
                }
            }
            
            Log.d("AlarmApp", "=== MISSED ALARM CHECK COMPLETE: Fired ${missedAlarms.size} missed alarms ===")
        } catch (e: Exception) {
            Log.e("AlarmApp", "Failed to check missed alarms: ${e.message}")
        }
    }

    private fun recoverPersistedWakeCheckCycles(context: Context, source: String) {
        val alarms = try {
            AlarmStorage(context).getAlarms()
        } catch (e: Exception) {
            Log.e("AlarmApp", "STATE_RESTORE source=$source validation=fail reason=alarm_load_failed message=${e.message}")
            emptyList()
        }
        alarms.forEach { alarm ->
            runCatching {
                handleWakeCheckContinuityForAlarm(context, alarm, source)
            }.onFailure { error ->
                Log.e(
                    "AlarmApp",
                    "STATE_RESTORE source=$source alarmId=${alarm.id} validation=fail action=exception message=${error.message}"
                )
            }
        }
    }

    private fun handleWakeCheckContinuityForAlarm(context: Context, alarm: Alarm, source: String): Boolean {
        val cycle = WakeCheckCycleStore.read(context, alarm.id) ?: return false
        val nonTerminal =
            cycle.cycleId > 0L &&
                cycle.state != WakeCheckCycleState.WAKEUP_CHECK_PASSED &&
                cycle.state != WakeCheckCycleState.ALARM_CANCELLED_OR_INVALID
        if (!nonTerminal) {
            return false
        }

        val now = System.currentTimeMillis()
        // A reboot destroys every service. The persisted runtime markers can
        // still describe the pre-reboot process, so they must not suppress an
        // overdue Wake-Up Check during locked-boot recovery. Its persisted
        // cycle state and original dueAt remain the source of truth.
        val runtimeActive = source != "locked_boot" && isWakeCheckRuntimeActive(context, alarm.id)
        fun logDecision(validation: String, action: String, remainingMs: Long = 0L, reason: String = "") {
            Log.d(
                "AlarmApp",
                "STATE_RESTORE alarmId=${alarm.id} cycleId=${cycle.cycleId} state=${cycle.state} " +
                    "source=$source scheduledAt=${cycle.scheduledAt} dueAt=${cycle.dueAt} " +
                    "triggeredAt=${cycle.triggeredAt} expiryAt=${cycle.expiryAt} remainingMs=$remainingMs " +
                    "validation=$validation action=$action runtimeActive=$runtimeActive $reason"
            )
        }

        if (cycle.cycleId <= 0L) {
            logDecision("fail", "ignore_invalid", reason = "reason=missing_cycle_id")
            return true
        }

        return when (cycle.state) {
            WakeCheckCycleState.WAKEUP_CHECK_SCHEDULED -> {
                if (cycle.dueAt <= 0L) {
                    logDecision("fail", "ignore_invalid", reason = "reason=missing_due_at")
                    true
                } else if (cycle.dueAt > now) {
                    rearmWakeCheckFollowUp(context, alarm.id, cycle.cycleId, cycle.dueAt, source)
                    logDecision("pass", "rearm_wait", remainingMs = cycle.dueAt - now)
                    true
                } else {
                    if (runtimeActive) {
                        logDecision("pass", "resume_wakecheck_noop", reason = "reason=runtime_active")
                    } else {
                        startWakeCheckContinuation(context, alarm.id, cycle.cycleId, source)
                        logDecision("pass", "resume_wakecheck")
                    }
                    true
                }
            }
            WakeCheckCycleState.WAKEUP_CHECK_SHOWING -> {
                if (cycle.expiryAt <= 0L) {
                    logDecision("fail", "ignore_invalid", reason = "reason=missing_expiry_at")
                    true
                } else if (cycle.expiryAt > now) {
                    if (runtimeActive) {
                        logDecision("pass", "resume_wakecheck_noop", remainingMs = cycle.expiryAt - now, reason = "reason=runtime_active")
                    } else {
                        startWakeCheckContinuation(context, alarm.id, cycle.cycleId, source)
                        logDecision("pass", "resume_wakecheck", remainingMs = cycle.expiryAt - now)
                    }
                    true
                } else {
                    WakeCheckCycleStore.markWakeCheckMissed(context, alarm.id, cycle.cycleId)
                    if (runtimeActive) {
                        logDecision("pass", "retrigger_alarm_noop", reason = "reason=runtime_active")
                    } else {
                        startWakeCheckRetrigger(context, alarm.id, cycle.cycleId, source)
                        logDecision("pass", "retrigger_alarm")
                    }
                    true
                }
            }
            WakeCheckCycleState.WAKEUP_CHECK_MISSED -> {
                if (runtimeActive) {
                    logDecision("pass", "retrigger_alarm_noop", reason = "reason=runtime_active")
                } else {
                    startWakeCheckRetrigger(context, alarm.id, cycle.cycleId, source)
                    logDecision("pass", "retrigger_alarm")
                }
                true
            }
            WakeCheckCycleState.ALARM_RETRIGGERED -> {
                if (runtimeActive) {
                    logDecision("pass", "resume_retrigger_noop", reason = "reason=runtime_active")
                } else {
                    startWakeCheckRetrigger(context, alarm.id, cycle.cycleId, source)
                    logDecision("pass", "resume_retrigger")
                }
                true
            }
            else -> false
        }
    }

    private fun rearmWakeCheckFollowUp(
        context: Context,
        alarmId: Int,
        cycleId: Long,
        triggerAt: Long,
        source: String
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val requestCode = WakeCheckCycleStore.wakeCheckRequestCode(alarmId)
        val broadcast = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.vaishnava.alarm.WAKE_UP"
            data = Uri.parse("alarm-wakecheck://$alarmId")
            putExtra(AlarmReceiver.ALARM_ID, alarmId)
            putExtra("from_wake_check", true)
            putExtra(Constants.EXTRA_WAKE_CHECK_CYCLE_ID, cycleId)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            broadcast,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val showIntent = PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(AlarmReceiver.ALARM_ID, alarmId)
                putExtra("from_wake_check", true)
                putExtra(Constants.EXTRA_WAKE_CHECK_CYCLE_ID, cycleId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            val info = AlarmManager.AlarmClockInfo(triggerAt, showIntent)
            am.setAlarmClock(info, pi)
        } catch (_: Exception) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
        Log.d(
            "AlarmApp",
            "HANDOVER alarmId=$alarmId cycleId=$cycleId source=$source action=rearm_wait triggerAt=$triggerAt"
        )
    }

    private fun startWakeCheckContinuation(
        context: Context,
        alarmId: Int,
        cycleId: Long,
        source: String
    ) {
        val intent = Intent(context, AlarmForegroundService::class.java).apply {
            putExtra(AlarmReceiver.ALARM_ID, alarmId)
            putExtra("from_wake_check", true)
            putExtra(Constants.EXTRA_WAKE_CHECK_CYCLE_ID, cycleId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        Log.d(
            "AlarmApp",
            "HANDOVER alarmId=$alarmId cycleId=$cycleId source=$source action=resume_wakecheck"
        )
    }

    private fun startWakeCheckRetrigger(
        context: Context,
        alarmId: Int,
        cycleId: Long,
        source: String
    ) {
        val intent = Intent(context, AlarmForegroundService::class.java).apply {
            putExtra(AlarmReceiver.ALARM_ID, alarmId)
            putExtra("from_wake_check", false)
            putExtra(Constants.EXTRA_WAKE_CHECK_RETRIGGER, true)
            putExtra(Constants.EXTRA_WAKE_CHECK_CYCLE_ID, cycleId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        Log.d(
            "AlarmApp",
            "HANDOVER alarmId=$alarmId cycleId=$cycleId source=$source action=retrigger_alarm"
        )
    }

    private fun isWakeCheckRuntimeActive(context: Context, alarmId: Int): Boolean {
        val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext() ?: context
        } else {
            context
        }
        val alarmPrefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
        val bootPrefs = dps.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
        return alarmPrefs.getInt("current_service_alarm_id", -1) == alarmId ||
            alarmPrefs.getInt("ring_alarm_id", -1) == alarmId ||
            bootPrefs.getInt("current_alarm_id", -1) == alarmId
    }
    
    /**
     * Handle device unlock event - transition from DPS to CPS
     */
    private fun handleDeviceUnlock(context: Context) {
        try {
            Log.d("AlarmApp", "DEVICE_UNLOCK_HANDLED: Starting DPS to CPS transition")

            val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext() ?: context
            } else context

            val storage = AlarmStorage(context)
            val scheduler = AndroidAlarmScheduler(context)
            val legacyDpsPrefs = dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            val directBootPrefs = dpsContext.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
            val dpsSyncPrefs = dpsContext.getSharedPreferences("dps_completion_sync", Context.MODE_PRIVATE)
            val dpsMirror = DpsMissionMirror(context)
            var uiNeedsRefresh = false

            CompletionMarker.syncDpsToCps(context)

            // Legacy pending-completion resume is no longer authoritative once the
            // DPS snapshot/completion marker exists. Clear it on unlock so it
            // cannot relaunch a completed alarm through BootReceiver paths.
            PendingCompletionStore.clear(context)
            PendingCompletionStore.clearAlarm(context)

            val allAlarms = storage.getAlarms()
            for (alarm in allAlarms) {
                val legacyKey = "sequencer_completed_${alarm.id}"
                val syncKey = "alarm_completed_in_dps_${alarm.id}"
                val snapshot = dpsMirror.readSnapshot(alarm.id)
                if (handleWakeCheckContinuityForAlarm(context, alarm, "unlock")) {
                    continue
                }
                val rawCompletedInDps = snapshot?.isSequenceComplete == true ||
                    legacyDpsPrefs.getBoolean(legacyKey, false) ||
                    dpsSyncPrefs.getBoolean(syncKey, false)
                val wakeCheckCycle = WakeCheckCycleStore.read(context, alarm.id)
                val wakeCheckPending = legacyDpsPrefs.getBoolean("wakecheck_pending_${alarm.id}", false)
                val wakeCheckFinalized = legacyDpsPrefs.getBoolean("wakecheck_finalized_${alarm.id}", false)
                val wakeCheckLoopActive = WakeCheckStore.isLoopActive(context, alarm)
                val hasNonTerminalWakeCheckCycle =
                    wakeCheckCycle != null &&
                        wakeCheckCycle.cycleId > 0L &&
                        wakeCheckCycle.state != WakeCheckCycleState.WAKEUP_CHECK_PASSED &&
                        wakeCheckCycle.state != WakeCheckCycleState.ALARM_CANCELLED_OR_INVALID
                val preserveWakeCheckState =
                    rawCompletedInDps &&
                        (hasNonTerminalWakeCheckCycle ||
                            wakeCheckLoopActive ||
                            (alarm.wakeCheckEnabled && wakeCheckPending && !wakeCheckFinalized))

                Log.d(
                    "AlarmApp",
                    "DEVICE_UNLOCK_WUC_AUDIT: alarmId=${alarm.id} rawCompleted=$rawCompletedInDps " +
                        "cycleId=${wakeCheckCycle?.cycleId ?: 0L} cycleState=${wakeCheckCycle?.state ?: "null"} " +
                        "scheduledAt=${wakeCheckCycle?.scheduledAt ?: 0L} dueAt=${wakeCheckCycle?.dueAt ?: 0L} " +
                        "pending=$wakeCheckPending finalized=$wakeCheckFinalized loopActive=$wakeCheckLoopActive " +
                        "preserve=$preserveWakeCheckState"
                )

                if (snapshot != null && !snapshot.isSequenceComplete) {
                    ExtremeForensicLogger.Mission.logDpsForensic(
                        component = "DirectBootRestoreReceiver",
                        event = "unlock_recovery_snapshot_active",
                        snapshot = snapshot,
                        expectedMissionId = snapshot.activeMissionId,
                        runtimeMissionId = null,
                        previousState = snapshot.state,
                        source = "handleDeviceUnlock",
                        queueReconstructed = false,
                        nullMissionObserved = snapshot.activeMissionId == null,
                        stalePendingIntent = false,
                        replayingOldData = false
                    )
                }

                if (preserveWakeCheckState) {
                    Log.d(
                        "AlarmApp",
                        "DEVICE_UNLOCK_WUC_GUARD: alarmId=${alarm.id} rawCompleted=$rawCompletedInDps " +
                            "loopActive=$wakeCheckLoopActive pending=$wakeCheckPending finalized=$wakeCheckFinalized"
                    )
                    continue
                }

                if (!rawCompletedInDps) {
                    continue
                }

                val isOneShot = isOneShotAlarm(alarm)
                if (isOneShot) {
                    val disabled = storage.disableAlarm(alarm.id)
                    Log.d(
                        "CPS_SYNC",
                        "Syncing Alarm ${alarm.id}: Turning OFF one-shot toggle in CPS. disabled=$disabled"
                    )
                    try {
                        scheduler.cancel(alarm)
                        Log.d("AlarmApp", "DEVICE_UNLOCK_CANCEL_RESULT: alarmId=${alarm.id} canceled=true")
                    } catch (e: Exception) {
                        Log.e("AlarmApp", "DEVICE_UNLOCK_CANCEL_ERROR: alarmId=${alarm.id} - ${e.message}")
                    }
                    uiNeedsRefresh = true
                } else {
                    Log.d(
                        "AlarmApp",
                        "DEVICE_UNLOCK_REPEAT_GUARD: alarmId=${alarm.id} completed repeating alarm - " +
                            "preserving enabled state and leaving next OS intent intact"
                    )
                }

                clearUnlockCompletionState(
                    alarm = alarm,
                    legacyDpsPrefs = legacyDpsPrefs,
                    directBootPrefs = directBootPrefs,
                    dpsSyncPrefs = dpsSyncPrefs,
                    dpsMirror = dpsMirror,
                    legacyKey = legacyKey,
                    syncKey = syncKey,
                    snapshotWasComplete = snapshot?.isSequenceComplete == true
                )
            }

            if (uiNeedsRefresh) {
                Log.d("CPS_SYNC", "Sync Complete: UI refresh broadcast sent silently.")
                try {
                    val updateIntent = Intent("com.vaishnava.alarm.REFRESH_ALARMS").apply {
                        setPackage(context.packageName)
                    }
                    context.sendBroadcast(updateIntent)
                } catch (e: Exception) {
                    Log.e("AlarmApp", "DEVICE_UNLOCK_UI_REFRESH_ERROR: ${e.message}")
                }
            }
            
            // Clear DPS force restart flag since we're now in CPS mode
            try {
                dpsContext.deleteFile(FORCE_RESTART_FLAG_FILE)
                Log.d("AlarmApp", "DEVICE_UNLOCK_FLAG_CLEARED: DPS force restart flag cleared")
            } catch (e: Exception) {
                Log.e("AlarmApp", "DEVICE_UNLOCK_FLAG_ERROR: Failed to clear DPS flag - ${e.message}")
            }
            
            Log.d("AlarmApp", "=== DEVICE_UNLOCK_COMPLETE: DPS to CPS transition finished ===")
            
        } catch (e: Exception) {
            Log.e("AlarmApp", "DEVICE_UNLOCK_FAILED: DPS to CPS transition error - ${e.message}")
        }
    }

    private fun isOneShotAlarm(alarm: Alarm): Boolean {
        return alarm.isOneShot()
    }

    private fun clearUnlockCompletionState(
        alarm: Alarm,
        legacyDpsPrefs: SharedPreferences,
        directBootPrefs: SharedPreferences,
        dpsSyncPrefs: SharedPreferences,
        dpsMirror: DpsMissionMirror,
        legacyKey: String,
        syncKey: String,
        snapshotWasComplete: Boolean
    ) {
        val legacyCleared = legacyDpsPrefs.edit().remove(legacyKey).commit()
        val syncCleared = dpsSyncPrefs.edit().remove(syncKey).commit()
        val autoLaunchCleared = directBootPrefs.edit()
            .remove("dps_auto_launch_${alarm.id}")
            .commit()

        Log.d(
            "AlarmApp",
            "DPS_COMPLETION_MARKER_COMMIT: source=handleDeviceUnlock alarmId=${alarm.id} " +
                "marker=$legacyKey storage=DPS success=$legacyCleared"
        )
        Log.d(
            "AlarmApp",
            "DPS_COMPLETION_MARKER_COMMIT: source=handleDeviceUnlock alarmId=${alarm.id} " +
                "marker=$syncKey storage=DPS success=$syncCleared"
        )
        Log.d(
            "AlarmApp",
            "DPS_COMPLETION_MARKER_COMMIT: source=handleDeviceUnlock alarmId=${alarm.id} " +
                "marker=dps_auto_launch_${alarm.id} storage=DPS success=$autoLaunchCleared"
        )

        val currentServiceAlarmId = legacyDpsPrefs.getInt("current_service_alarm_id", -1)
        val ringingAlarmId = legacyDpsPrefs.getInt("ring_alarm_id", -1)
        val legacyRuntimeEditor = legacyDpsPrefs.edit()
        if (currentServiceAlarmId == alarm.id) {
            legacyRuntimeEditor.remove("current_service_alarm_id")
        }
        if (ringingAlarmId == alarm.id) {
            legacyRuntimeEditor
                .remove("was_ringing")
                .remove("ring_alarm_id")
                .remove("ring_started_at")
        }
        val legacyRuntimeCleared = legacyRuntimeEditor.commit()

        val currentBootAlarmId = directBootPrefs.getInt("current_alarm_id", -1)
        val directBootRuntimeEditor = directBootPrefs.edit()
        if (currentBootAlarmId == alarm.id) {
            directBootRuntimeEditor
                .remove("current_alarm_id")
                .remove("was_ringing")
        }
        val directBootRuntimeCleared = directBootRuntimeEditor.commit()

        if (snapshotWasComplete) {
            dpsMirror.clearSnapshot(alarm.id)
        }

        Log.d(
            "AlarmApp",
            "DEVICE_UNLOCK_STATE_CLEARED: alarmId=${alarm.id} legacyRuntime=$legacyRuntimeCleared " +
                "directBootRuntime=$directBootRuntimeCleared snapshotCleared=$snapshotWasComplete"
        )
    }
}
