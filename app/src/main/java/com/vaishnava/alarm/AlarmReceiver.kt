package com.vaishnava.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.vaishnava.alarm.data.Alarm
import com.vaishnava.alarm.Constants
import java.util.concurrent.Executors
import com.vaishnava.alarm.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "AlarmReceiver"
        const val ALARM_ID = "alarm_id"
        const val EXTRA_RINGTONE_URI = "extra_ringtone_uri"
        const val EXTRA_REPEAT_DAYS = "extra_repeat_days"
        
        // Pending request tracking
        private val pendingRequests = mutableSetOf<String>()
        
        data class PendingRequest(
            val requestId: String,
            val requesterPackage: String,
            val title: String,
            val description: String,
            val triggerTimeMs: Long,
            val timestamp: Long = System.currentTimeMillis()
        )
        
        private val requestDetails = mutableMapOf<String, PendingRequest>()
        
        fun addPendingRequest(request: PendingRequest) {
            pendingRequests.add(request.requestId)
            requestDetails[request.requestId] = request
        }
        
        fun removePendingRequest(requestId: String) {
            pendingRequests.remove(requestId)
            requestDetails.remove(requestId)
        }
        
        fun getPendingRequests(): List<PendingRequest> {
            return requestDetails.values.toList()
        }
        
        fun hasPendingRequests(): Boolean {
            return pendingRequests.isNotEmpty()
        }
        
        /**
         * CRITICAL FIX: Get DPS-first AlarmStorage for Direct Boot compatibility
         */
        private fun getDpsAlarmStorage(context: Context): AlarmStorage {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val deviceContext = context.createDeviceProtectedStorageContext()
                    AlarmStorage(deviceContext)
                } else {
                    AlarmStorage(context)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create DPS AlarmStorage, using regular storage", e)
                AlarmStorage(context)
            }
        }
        
        private fun writeExternalLog(context: Context, message: String) {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val logFile = java.io.File(context.filesDir, "alarm_debug.log")
                val writer = java.io.FileWriter(logFile.absolutePath, true)
                writer.append("[$timestamp] [RECEIVER] $message\n")
                writer.close()
            } catch (e: Exception) {
                // Silently fail if we can't write to storage
            }
        }
    }

    private val bg = Executors.newSingleThreadExecutor()

    override fun onReceive(context: Context, intent: Intent?) {
        val TAG = "AlarmReceiver"

        // CRITICAL: Log ALL incoming broadcasts for debugging
        Log.d(TAG, "═════════════════════════════════════════════════════════")
        Log.d(TAG, "[SNOOZE_RCV_1] BROADCAST RECEIVED")
        Log.d(TAG, "[SNOOZE_RCV_1] Action: ${intent?.action}")
        Log.d(TAG, "[SNOOZE_RCV_1] Package: ${intent?.`package`}")
        Log.d(TAG, "[SNOOZE_RCV_1] alarmId: ${intent?.getIntExtra("alarm_id", -1)}")
        Log.d(TAG, "[SNOOZE_RCV_1] from_snooze: ${intent?.getBooleanExtra("from_snooze", false)}")
        Log.d(TAG, "[SNOOZE_RCV_1] enable_snooze_extra: ${intent?.getBooleanExtra("enable_snooze", false)}")

        // ALARM_TIMING_AUDIT: compare the alarm's intended hour:minute against the actual
        // moment this broadcast was received, to distinguish an OS/Doze delivery delay
        // (this gap is large) from an app-side delay (this gap is ~0 but AlarmActivity/
        // AlarmForegroundService take a while to actually start ringing after this point).
        run {
            val hourExtra = intent?.getIntExtra("hour", -1) ?: -1
            val minuteExtra = intent?.getIntExtra("minute", -1) ?: -1
            if (hourExtra in 0..23 && minuteExtra in 0..59) {
                val now = java.util.Calendar.getInstance()
                val intended = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, hourExtra)
                    set(java.util.Calendar.MINUTE, minuteExtra)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                    // The broadcast can arrive up to ~12h after intended time only if genuinely
                    // stale; assume same-day unless "now" is well before "intended", in which
                    // case intended was yesterday (e.g. receiver processing crossed midnight).
                    if (timeInMillis - now.timeInMillis > 12 * 3600_000L) add(java.util.Calendar.DAY_OF_YEAR, -1)
                }
                val delayMs = now.timeInMillis - intended.timeInMillis
                Log.d(TAG, "ALARM_TIMING_AUDIT: intendedTime=$hourExtra:$minuteExtra receivedAt=${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(now.time)} delayMs=$delayMs delaySec=${delayMs / 1000}")
            }
        }

        val action = intent?.action ?: return
        val wakeCheckData = intent.data?.toString()
        val wakeCheckFollowUp =
            intent.getBooleanExtra("from_wake_check", false) ||
                wakeCheckData?.startsWith("alarm-wakecheck://") == true
        
        // Handle IPC requests from external apps FIRST
        when (action) {
            "com.vaishnava.alarm.CREATE_ALARM_REQUEST" -> {
                Log.d(TAG, "🎯 CREATE_ALARM_REQUEST MATCHED - Processing...")
                handleCreateAlarmRequest(context, intent)
                return
            }
            "com.vaishnava.alarm.CANCEL_ALARM_REQUEST" -> {
                Log.d(TAG, "🎯 CANCEL_ALARM_REQUEST MATCHED - Processing...")
                handleCancelAlarmRequest(context, intent)
                return
            }
            "com.vaishnava.alarm.QUERY_ALARM_STATUS" -> {
                Log.d(TAG, "🎯 QUERY_ALARM_STATUS MATCHED - Processing...")
                handleQueryAlarmStatus(context, intent)
                return
            }
            "com.vaishnava.alarm.SHORTS_TEST_ALARM" -> {
                Log.w(TAG, "LEGACY_SHORTS_TEST_ALARM: This action is deprecated. Use CREATE_ALARM_REQUEST instead.")
                handleCreateAlarmRequest(context, intent) // Handle legacy for compatibility
                return
            }
            else -> {
                Log.d(TAG, "❌ NO MATCH FOR ACTION: $action")
            }
        }

        // Continue with existing alarm logic...
        if (intent == null) {
            Log.w(TAG, "onReceive: null intent")
            return
        }

        // Forensic logging of inbound intent
        try {
            val extrasKeys = intent.extras?.keySet()?.joinToString()
            Log.d(
                TAG,
                "onReceive: action=$action data=${intent.data} extrasKeys=$extrasKeys requestCode_hint=${intent.getIntExtra(ALARM_ID, -1)}"
            )
        } catch (_: Exception) { }

        // Get alarmId early for use in action handlers
        val alarmId = try {
            intent.getIntExtra(ALARM_ID, -1)
        } catch (e: Exception) {
            -1
        }

        // Continue with existing alarm processing logic...
        val storageAlarm: Alarm? = try {
            val storage = getDpsAlarmStorage(context)
            val dpsResult = storage.getAlarm(alarmId)
            if (dpsResult != null) {
                dpsResult
            } else {
                // DPS mirror missing/stale for this alarm - fall back to CPS storage instead
                // of treating this as "no alarm". A wake-check follow-up broadcast carries no
                // hour/minute extras, so fallbackAlarm below can never build a substitute -
                // a DPS miss used to fall straight through to alarm=null, which invalidates
                // the wake-check cycle and drops the whole event silently before the service
                // or activity ever run.
                Log.d(TAG, "WAKECHECK_BUG: DPS lookup miss for alarmId=$alarmId action=$action wakeCheckFollowUp=$wakeCheckFollowUp - trying CPS storage")
                AlarmStorage(context).getAlarm(alarmId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AlarmStorage lookup failed: ${e.message}")
            null
        }

        // fallback: build from intent extras
        val fallbackAlarm: Alarm? = try {
            val hour = intent.getIntExtra("hour", -1)
            val minute = intent.getIntExtra("minute", -1)
            val ringtone: Uri? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RINGTONE_URI, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RINGTONE_URI)
                }
            } catch (_: Exception) { null }
            val repeatDaysArray = intent.getIntArrayExtra(EXTRA_REPEAT_DAYS)
            val daysList = repeatDaysArray?.toList()
            val repeatDaily = intent.getBooleanExtra("repeatDaily", false)
            if (alarmId >= 0 && hour >= 0 && minute >= 0) {
                Alarm(
                    id = alarmId,
                    hour = hour,
                    minute = minute,
                    days = daysList,
                    isEnabled = true,
                    ringtoneUri = ringtone,
                    repeatDaily = repeatDaily,
                    missionType = intent.getStringExtra("mission_type") ?: "",
                    missionPassword = intent.getStringExtra("mission_password"),
                    isProtected = intent.getBooleanExtra("is_protected", false),
                    wakeCheckEnabled = intent.getBooleanExtra("wake_check_enabled", false),
                    wakeCheckMinutes = intent.getIntExtra("wake_check_minutes", 5),
                    enableSnooze = intent.getBooleanExtra("enable_snooze", false)
                )
            } else null
        } catch (e: Exception) {
            null
        }

        val alarm = storageAlarm ?: fallbackAlarm
        Log.d(
            TAG,
            "WAKECHECK_BUG: alarm_resolution alarmId=$alarmId action=$action wakeCheckFollowUp=$wakeCheckFollowUp " +
                "storageAlarmFound=${storageAlarm != null} fallbackAlarmFound=${fallbackAlarm != null} " +
                "finalAlarmNull=${alarm == null}"
        )
        if (alarm == null) {
            if (wakeCheckFollowUp && alarmId >= 0) {
                Log.w(TAG, "WAKECHECK_BUG: DROPPING wake-check follow-up and INVALIDATING cycle - no alarm data found in DPS or CPS for alarmId=$alarmId")
                WakeCheckCycleStore.invalidate(context, alarmId)
            }
            Log.w(TAG, "[SNOOZE_RCV_2] No alarm data available for id=$alarmId")
            return
        }

        Log.d(TAG, "[SNOOZE_RCV_2] Alarm resolved")
        Log.d(TAG, "[SNOOZE_RCV_2] alarmId=${alarm.id}")
        Log.d(TAG, "[SNOOZE_RCV_2] enableSnooze=${alarm.enableSnooze}")
        Log.d(TAG, "[SNOOZE_RCV_2] isEnabled=${alarm.isEnabled}")
        Log.d(TAG, "[SNOOZE_RCV_2] usedStorage=${storageAlarm != null}")
        Log.d(TAG, "[SNOOZE_RCV_2] ALARM_ID_TRACKING: intentAlarmId=$alarmId vs resolvedAlarmId=${alarm.id} (MATCH=${alarmId == alarm.id})")

        val wakeCheckCycleId = if (wakeCheckFollowUp) {
            when (val requestedCycleId = intent.getLongExtra(Constants.EXTRA_WAKE_CHECK_CYCLE_ID, -1L)) {
                in 1L..Long.MAX_VALUE -> requestedCycleId
                else -> WakeCheckCycleStore.currentCycleId(context, alarm.id)
            }
        } else {
            -1L
        }

        if (wakeCheckFollowUp) {
            val persistedCycle = WakeCheckCycleStore.read(context, alarm.id)
            val stillValid =
                alarm.isEnabled &&
                    alarm.wakeCheckEnabled &&
                    alarm.wakeCheckMinutes > 0 &&
                    wakeCheckCycleId > 0L &&
                    WakeCheckCycleStore.canFireScheduledWakeCheck(context, alarm.id, wakeCheckCycleId)
            Log.d(
                TAG,
                "WAKECHECK_BUG: stillValid_check alarmId=${alarm.id} cycleId=$wakeCheckCycleId " +
                    "alarmEnabled=${alarm.isEnabled} alarmWakeCheckEnabled=${alarm.wakeCheckEnabled} " +
                    "alarmWakeCheckMinutes=${alarm.wakeCheckMinutes} cycleIdPositive=${wakeCheckCycleId > 0L} " +
                    "canFireScheduled=${WakeCheckCycleStore.canFireScheduledWakeCheck(context, alarm.id, wakeCheckCycleId)} " +
                    "persistedCycleState=${persistedCycle?.state} stillValid=$stillValid"
            )
            if (!stillValid) {
                Log.d(
                    TAG,
                    "WAKECHECK_BROADCAST_IGNORED: alarmId=${alarm.id} cycleId=$wakeCheckCycleId enabled=${alarm.isEnabled} " +
                        "wakeCheck=${alarm.wakeCheckEnabled} dueAt=${persistedCycle?.dueAt ?: 0L} " +
                        "triggeredAt=${persistedCycle?.triggeredAt ?: 0L} expiryAt=${persistedCycle?.expiryAt ?: 0L}"
                )
                return
            }
            Log.d(
                TAG,
                "WAKEUP_CHECK: alarmId=${alarm.id} cycleId=$wakeCheckCycleId action=accept_follow_up " +
                    "dueAt=${persistedCycle?.dueAt ?: 0L} triggeredAt=${persistedCycle?.triggeredAt ?: 0L} " +
                    "expiryAt=${persistedCycle?.expiryAt ?: 0L}"
            )
        }

        // Continue with existing alarm processing...
        try {
            Log.d(
                TAG,
                "onReceive: RESOLVED_ALARM id=${alarm.id} time=${alarm.hour}:${alarm.minute} days=${alarm.days} repeatDaily=${alarm.repeatDaily} ringtone=${alarm.ringtoneUri} wakeCheck=${alarm.wakeCheckEnabled}"
            )
        } catch (_: Exception) { }

        // 1) Start foreground service to manage alarm lifecycle
        try {
            val skipAudio = intent.getBooleanExtra("skip_audio", false)
            if (skipAudio) {
                Log.d(TAG, "Skipping AlarmForegroundService start for alarm ${alarm.id} (audio already handled) - returning early")
            } else {
                // CRITICAL FIX 2: Extract WUC Metadata before it vanishes
                val isWakeCheck = intent.getBooleanExtra("IS_WAKE_CHECK", false)
                val wucCycleId = intent.getLongExtra("WAKE_CHECK_CYCLE_ID", -1L)

                Log.d("WUC_AUDIT", "FORENSIC_RECEIVER: Intercepted alarmId=${alarm.id} isWakeCheck=$isWakeCheck cycle=$wucCycleId")

                Log.d(TAG, "Starting AlarmForegroundService for alarm ${alarm.id} (missionType: ${alarm.missionType})")
                val svc = Intent(context, AlarmForegroundService::class.java).apply {
                    putExtras(intent) // Blanket copy all existing extras
                    
                    // Explicitly enforce the WUC identity to prevent "blank" drops
                    putExtra(ALARM_ID, alarm.id)
                    putExtra("IS_WAKE_CHECK", isWakeCheck)
                    putExtra("WAKE_CHECK_CYCLE_ID", wucCycleId)
                    putExtra(EXTRA_RINGTONE_URI, alarm.ringtoneUri)
                    putExtra(EXTRA_REPEAT_DAYS, alarm.days?.toIntArray())
                    putExtra("mission_type", alarm.missionType)
                    putExtra("mission_password", alarm.missionPassword)
                    putExtra("wake_check_enabled", alarm.wakeCheckEnabled)
                    putExtra("wake_check_minutes", alarm.wakeCheckMinutes)
                    putExtra("enable_snooze", alarm.enableSnooze)
                    putExtra("snooze_duration_minutes", alarm.wakeCheckMinutes)
                    if (wakeCheckFollowUp) {
                        putExtra("from_wake_check", true)
                        putExtra(Constants.EXTRA_WAKE_CHECK_CYCLE_ID, wakeCheckCycleId)
                    }
                    if (intent.getBooleanExtra("is_wake_up_follow_up", false)) {
                        putExtra("is_wake_up_follow_up", true)
                    }
                }
                context.startForegroundService(svc)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AlarmForegroundService: ${e.message}", e)
        }
    }

    // ==================== VALIDATION HELPERS ====================
    
    private fun validateExternalAlarmRequest(context: Context, intent: Intent): Boolean {
        // CRITICAL FIX: Allow internal ringtone resolution (Alarm app is source of truth)
        // Removed requirement for ringtone_uri - Alarm app resolves internally
        
        // mission_type is optional - if not provided, defaults to simple alarm
        val missionType = intent.getStringExtra("mission_type")
        if (missionType != null && missionType == "sequencer") {
            // Only require mission_password for sequencer missions
            val missionPassword = intent.getStringExtra("mission_password")
            if (missionPassword == null) {
                Log.w(TAG, "External request missing required mission_password for sequencer")
                return false
            }
        }
        
        // CRITICAL FIX: Protection is optional for external requests
        // Removed requirement for is_protected = true
        
        return true
    }
    
    private fun getValidatedRingtoneUri(context: Context, intent: Intent): android.net.Uri? {
        val ringtoneUriString = intent.getStringExtra("ringtone_uri")
        return if (ringtoneUriString != null) {
            try {
                Uri.parse(ringtoneUriString)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid ringtone URI: $ringtoneUriString")
                null
            }
        } else {
            // Default to system default ringtone
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        }
    }
    
    private fun getValidatedMissionType(intent: Intent): String {
        // CRITICAL FIX: Respect Shorts app's explicit selection, don't override with hardcoded default
        return if (intent.hasExtra("mission_type")) {
            intent.getStringExtra("mission_type") ?: ""
        } else {
            "" // Default only when not provided
        }
    }
    
    private fun getValidatedMissionPassword(intent: Intent, missionType: String): String? {
        return if (missionType == "sequencer") {
            intent.getStringExtra("mission_password") ?: "password,tap"
        } else {
            null
        }
    }
    
    private fun getValidatedProtectionSetting(intent: Intent): Boolean {
        // CRITICAL FIX: Respect Shorts app's explicit selection, don't override with hardcoded default
        return if (intent.hasExtra("is_protected")) {
            intent.getBooleanExtra("is_protected", false)
        } else {
            false // Default only when not provided
        }
    }
    
    private fun getValidatedWakeCheckSetting(intent: Intent): Boolean {
        // CRITICAL FIX: Respect Shorts app's explicit selection, don't override with hardcoded default
        return if (intent.hasExtra("wake_check_enabled")) {
            intent.getBooleanExtra("wake_check_enabled", false)
        } else {
            false // Default only when not provided
        }
    }

    // ==================== IPC REQUEST HANDLERS ====================
    
    private fun handleCreateAlarmRequest(context: Context, intent: Intent) {
        try {
            Log.d(TAG, "=== CREATE_ALARM_REQUEST RECEIVED ===")
            
            val requestId = intent.getStringExtra("request_id")
            val requesterPackage = intent.getStringExtra("requester_package")
            val triggerTimeMs = intent.getLongExtra("trigger_time_ms", 0L)
            val title = intent.getStringExtra("title") ?: "External App Alarm"
            val description = intent.getStringExtra("description") ?: ""
            
            Log.d(TAG, "Request: requestId=$requestId, requester=$requesterPackage, triggerTime=$triggerTimeMs")
            
            // Validate required parameters
            if (requestId == null || requesterPackage == null || triggerTimeMs <= 0) {
                sendErrorResponse(context, requestId, requesterPackage, "INVALID_REQUEST", "Missing required parameters")
                return
            }
            
            // Validate requester package
            if (!isValidRequester(requesterPackage)) {
                sendErrorResponse(context, requestId, requesterPackage, "UNAUTHORIZED", "Package not authorized")
                return
            }
            
            // Check for duplicate requests
            if (isDuplicateRequest(requestId)) {
                Log.d(TAG, "Duplicate request ignored: $requestId")
                return
            }
            
            // CRITICAL FIX: Enforce same validation as UI path
            if (!validateExternalAlarmRequest(context, intent)) {
                sendErrorResponse(context, requestId, requesterPackage, "VALIDATION_FAILED", "External request missing required alarm configuration")
                return
            }
            
            // ADD PENDING REQUEST TO TRACKER
            val pendingRequest = PendingRequest(
                requestId = requestId,
                requesterPackage = requesterPackage,
                title = title,
                description = description,
                triggerTimeMs = triggerTimeMs
            )
            addPendingRequest(pendingRequest)
            
            // NOTIFY UI ABOUT NEW PENDING REQUEST
            val pendingIntent = Intent("com.vaishnava.alarm.PENDING_REQUESTS_CHANGED").apply {
                putExtra("action", "REQUEST_ADDED")
                putExtra("request_id", requestId)
                putExtra("requester_package", requesterPackage)
                putExtra("title", title)
            }
            context.sendBroadcast(pendingIntent)
            
            // Create alarm with generated ID
            val alarmId = generateUniqueAlarmId()
            
            // Initialize calendar for timestamp derivation
            val calendar = java.util.Calendar.getInstance()
            calendar.timeInMillis = triggerTimeMs
            
            // CRITICAL FIX: Use explicit hour/minute from Shorts app if provided, fallback to timestamp derivation
            val hour = if (intent.hasExtra("hour")) {
                intent.getIntExtra("hour", -1).also { 
                    Log.d(TAG, "Using explicit hour from Shorts app: $it")
                }
            } else {
                calendar.get(java.util.Calendar.HOUR_OF_DAY).also { 
                    Log.d(TAG, "Derived hour from timestamp: $it")
                }
            }
            
            val minute = if (intent.hasExtra("minute")) {
                intent.getIntExtra("minute", -1).also { 
                    Log.d(TAG, "Using explicit minute from Shorts app: $it")
                }
            } else {
                calendar.get(java.util.Calendar.MINUTE).also { 
                    Log.d(TAG, "Derived minute from timestamp: $it")
                }
            }
            
            Log.d(TAG, "Final hour=$hour, minute=$minute")
            
            // CRITICAL VALIDATION: Ensure hour/minute are valid
            if (hour == -1 || minute == -1) {
                sendErrorResponse(context, requestId, requesterPackage, "INVALID_TIME", "Invalid hour or minute: hour=$hour, minute=$minute")
                return
            }
            
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                sendErrorResponse(context, requestId, requesterPackage, "INVALID_TIME", "Hour/minute out of range: hour=$hour, minute=$minute")
                return
            }
            
            // Get validated alarm configuration
            val ringtoneUri = if (intent.hasExtra("ringtone_uri")) {
                getValidatedRingtoneUri(context, intent).also { 
                    Log.d(TAG, "Using ringtone from Shorts app: $it")
                }
            } else {
                // Use default ringtone when Shorts doesn't provide one
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM).also { 
                    Log.d(TAG, "Using default ringtone (Shorts app didn't provide ringtone_uri)")
                }
            }
            
            // CRITICAL FIX: Handle days from Shorts app
            val days = if (intent.hasExtra("extra_repeat_days")) {
                intent.getIntArrayExtra("extra_repeat_days")?.toList()?.also { 
                    Log.d(TAG, "Using days from Shorts app: $it")
                }
            } else {
                null.also { Log.d(TAG, "No days provided (single alarm)") }
            }
            val missionType = getValidatedMissionType(intent)
            val missionPassword = getValidatedMissionPassword(intent, missionType)
            val isProtected = getValidatedProtectionSetting(intent)
            val wakeCheckEnabled = getValidatedWakeCheckSetting(intent)
            
            // CRITICAL LOGGING: Verify Shorts app selections are respected
            Log.d(TAG, "=== SHORTS APP SELECTIONS RESPECTED ===")
            Log.d(TAG, "Ringtone: $ringtoneUri (from Shorts: ${intent.getStringExtra("ringtone_uri")})")
            Log.d(TAG, "Days: $days (from Shorts: ${intent.getIntArrayExtra("extra_repeat_days")?.toList()})")
            Log.d(TAG, "Mission Type: $missionType (from Shorts: ${intent.getStringExtra("mission_type")})")
            Log.d(TAG, "Mission Password: $missionPassword (from Shorts: ${intent.getStringExtra("mission_password")})")
            Log.d(TAG, "Is Protected: $isProtected (from Shorts: ${intent.getBooleanExtra("is_protected", false)})")
            Log.d(TAG, "Wake Check Enabled: $wakeCheckEnabled (from Shorts: ${intent.getBooleanExtra("wake_check_enabled", false)})")
            val wakeCheckMinutes = if (intent.hasExtra("wake_check_minutes")) {
                intent.getIntExtra("wake_check_minutes", 5).also { 
                    Log.d(TAG, "Using explicit wake_check_minutes from Shorts app: $it")
                }
            } else {
                5 // Default value
            }
            
            val alarm = Alarm(
                id = alarmId,
                hour = hour, // Valid hour from Shorts app (explicit) or timestamp derivation
                minute = minute, // Valid minute from Shorts app (explicit) or timestamp derivation
                days = days, // CRITICAL FIX: Use days from Shorts app
                isEnabled = true,
                ringtoneUri = ringtoneUri, // Ringtone from Shorts app or default
                repeatDaily = false,
                alarmTime = triggerTimeMs, // Set the actual trigger time
                missionType = missionType, // Validated mission type
                missionPassword = missionPassword, // Validated mission config
                isProtected = isProtected, // Validated protection
                wakeCheckEnabled = wakeCheckEnabled, // Validated wake check
                wakeCheckMinutes = wakeCheckMinutes // Use wake check minutes from Shorts app
            )
            
            // CRITICAL FIX: Check all permissions before creating alarm from external request
            if (!MainActivity.hasAllRequiredPermissions(context)) {
                Log.w(TAG, "❌ Cannot create alarm from SHORTS app - missing required permissions")
                
                // Show Toast message to user
                try {
                    val toastIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("show_permission_message", true)
                        putExtra("permission_message", "Enable all permissions to create alarms")
                    }
                    context.startActivity(toastIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to show permission message", e)
                }
                
                sendErrorResponse(context, requestId, requesterPackage, "PERMISSION_DENIED", "Enable all permissions to create alarms")
                return
            }
            
            // Store alarm
            val storage = getDpsAlarmStorage(context)
            storage.addAlarm(alarm)
            
            // Schedule with AlarmManager ONLY AFTER successful storage
            val triggerIntent = Intent(context, AlarmReceiver::class.java).apply {
                action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
                putExtra(ALARM_ID, alarmId)
                data = Uri.parse("alarm://$alarmId")
            }

            val pendingIntentAlarm = PendingIntent.getBroadcast(
                context, alarmId,
                triggerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTimeMs,
                pendingIntentAlarm
            )
            
            // REMOVE PENDING REQUEST AFTER SUCCESSFUL CREATION
            removePendingRequest(requestId)
            
            // NOTIFY UI ABOUT REQUEST COMPLETION
            val completeIntent = Intent("com.vaishnava.alarm.PENDING_REQUESTS_CHANGED").apply {
                putExtra("action", "REQUEST_COMPLETED")
                putExtra("request_id", requestId)
                putExtra("alarm_id", alarmId.toString())
                putExtra("status", "SUCCESS")
            }
            context.sendBroadcast(completeIntent)
            
            // CRITICAL: Send success response ONLY AFTER AlarmManager scheduling succeeds
            sendSuccessResponse(
                context, requestId, requesterPackage, alarmId,
                System.currentTimeMillis(), triggerTimeMs
            )
            
            // Send reverse notification for state change
            sendStateNotification(context, requesterPackage, "ALARM_CREATED", alarmId.toString())
            
            Log.d(TAG, "✅ Alarm created and acknowledged: alarmId=$alarmId, requestId=$requestId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling CREATE_ALARM_REQUEST: ${e.message}", e)
            
            // REMOVE PENDING REQUEST ON FAILURE
            intent.getStringExtra("request_id")?.let { requestId ->
                removePendingRequest(requestId)
                
                // NOTIFY UI ABOUT REQUEST FAILURE
                val failIntent = Intent("com.vaishnava.alarm.PENDING_REQUESTS_CHANGED").apply {
                    putExtra("action", "REQUEST_COMPLETED")
                    putExtra("request_id", requestId)
                    putExtra("status", "FAILED")
                    putExtra("error", e.message)
                }
                context.sendBroadcast(failIntent)
            }
            
            // CRITICAL: Send error response - NO alarm created without acknowledgement
            sendErrorResponse(
                context,
                intent.getStringExtra("request_id"),
                intent.getStringExtra("requester_package"),
                "CREATION_FAILED",
                "Alarm creation failed: ${e.message}"
            )
        }
    }
    
    private fun handleCancelAlarmRequest(context: Context, intent: Intent) {
        try {
            Log.d(TAG, "=== CANCEL_ALARM_REQUEST RECEIVED ===")
            
            val requestId = intent.getStringExtra("request_id")
            val requesterPackage = intent.getStringExtra("requester_package")
            val alarmId = intent.getStringExtra("alarm_id")
            
            if (requestId == null || requesterPackage == null || alarmId == null) {
                sendErrorResponse(context, requestId, requesterPackage, "INVALID_REQUEST", "Missing required parameters")
                return
            }
            
            // Validate requester and alarm ownership
            if (!isValidRequester(requesterPackage)) {
                sendErrorResponse(context, requestId, requesterPackage, "UNAUTHORIZED", "Package not authorized")
                return
            }
            
            val storage = getDpsAlarmStorage(context)
            val alarm = storage.getAlarm(alarmId.toIntOrNull() ?: -1)
            
            if (alarm == null) {
                sendErrorResponse(context, requestId, requesterPackage, "ALARM_NOT_FOUND", "Alarm does not exist")
                return
            }
            
            // CRITICAL: Cancel from AlarmManager FIRST
            val scheduler = AndroidAlarmScheduler(context)
            scheduler.cancel(alarm)
            
            // Then remove from storage
            storage.deleteAlarm(alarm.id)
            
            // Send success response ONLY AFTER both operations succeed
            sendCancelResponse(context, requestId, requesterPackage, alarmId)
            
            // Notify state change
            sendStateNotification(context, requesterPackage, "ALARM_CANCELLED", alarmId)
            
            Log.d(TAG, "✅ Alarm cancelled and acknowledged: alarmId=$alarmId, requestId=$requestId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling CANCEL_ALARM_REQUEST: ${e.message}", e)
            
            // CRITICAL: Send error response - NO cancellation without acknowledgement
            sendErrorResponse(
                context,
                intent.getStringExtra("request_id"),
                intent.getStringExtra("requester_package"),
                "CANCELLATION_FAILED",
                "Alarm cancellation failed: ${e.message}"
            )
        }
    }
    
    private fun handleQueryAlarmStatus(context: Context, intent: Intent) {
        try {
            Log.d(TAG, "=== QUERY_ALARM_STATUS RECEIVED ===")
            
            val requestId = intent.getStringExtra("request_id")
            val requesterPackage = intent.getStringExtra("requester_package")
            val alarmId = intent.getStringExtra("alarm_id")
            
            if (requestId == null || requesterPackage == null) {
                sendErrorResponse(context, requestId, requesterPackage, "INVALID_REQUEST", "Missing required parameters")
                return
            }
            
            if (!isValidRequester(requesterPackage)) {
                sendErrorResponse(context, requestId, requesterPackage, "UNAUTHORIZED", "Package not authorized")
                return
            }
            
            val storage = getDpsAlarmStorage(context)
            
            if (alarmId != null) {
                // Query specific alarm
                val alarm = storage.getAlarm(alarmId.toIntOrNull() ?: -1)
                if (alarm != null) {
                    sendAlarmStatusResponse(context, requestId, requesterPackage, alarm)
                } else {
                    sendErrorResponse(context, requestId, requesterPackage, "ALARM_NOT_FOUND", "Alarm does not exist")
                }
            } else {
                // Query all alarms for this requester
                val allAlarms = storage.getAlarms()
                sendAlarmListResponse(context, requestId, requesterPackage, allAlarms)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling QUERY_ALARM_STATUS: ${e.message}", e)
            sendErrorResponse(
                context,
                intent.getStringExtra("request_id"),
                intent.getStringExtra("requester_package"),
                "INTERNAL_ERROR",
                e.message ?: "Unknown error"
            )
        }
    }
    
    // ==================== IPC RESPONSE METHODS ====================
    
    private fun sendSuccessResponse(
        context: Context,
        requestId: String,
        requesterPackage: String,
        alarmId: Int,
        creationTimestamp: Long,
        triggerTime: Long
    ) {
        try {
            val responseIntent = Intent().apply {
                setPackage(requesterPackage)
                action = "${requesterPackage}.ALARM_RESPONSE"
                putExtra("request_id", requestId)
                putExtra("status", "SUCCESS")
                putExtra("alarm_id", alarmId.toString())
                putExtra("creation_timestamp_ms", creationTimestamp)
                putExtra("effective_trigger_time_ms", triggerTime)
                putExtra("alarm_state", "CREATED")
            }
            context.sendBroadcast(responseIntent)
            Log.d(TAG, "Success response sent to $requesterPackage for alarm $alarmId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send success response: ${e.message}", e)
        }
    }
    
    private fun sendErrorResponse(
        context: Context,
        requestId: String?,
        requesterPackage: String?,
        errorCode: String,
        errorMessage: String
    ) {
        try {
            if (requestId == null || requesterPackage == null) return
            
            val responseIntent = Intent().apply {
                setPackage(requesterPackage)
                action = "${requesterPackage}.ALARM_RESPONSE"
                putExtra("request_id", requestId)
                putExtra("status", "FAILED")
                putExtra("error_code", errorCode)
                putExtra("error_message", errorMessage)
            }
            context.sendBroadcast(responseIntent)
            Log.d(TAG, "Error response sent to $requesterPackage: $errorCode - $errorMessage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send error response: ${e.message}", e)
        }
    }
    
    private fun sendCancelResponse(
        context: Context,
        requestId: String,
        requesterPackage: String,
        alarmId: String
    ) {
        try {
            val responseIntent = Intent().apply {
                setPackage(requesterPackage)
                action = "${requesterPackage}.ALARM_RESPONSE"
                putExtra("request_id", requestId)
                putExtra("status", "SUCCESS")
                putExtra("alarm_id", alarmId)
                putExtra("alarm_state", "CANCELLED")
            }
            context.sendBroadcast(responseIntent)
            Log.d(TAG, "Cancel response sent to $requesterPackage for alarm $alarmId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send cancel response: ${e.message}", e)
        }
    }
    
    private fun sendAlarmStatusResponse(
        context: Context,
        requestId: String,
        requesterPackage: String,
        alarm: Alarm
    ) {
        try {
            val responseIntent = Intent().apply {
                setPackage(requesterPackage)
                action = "${requesterPackage}.ALARM_RESPONSE"
                putExtra("request_id", requestId)
                putExtra("status", "SUCCESS")
                putExtra("alarm_id", alarm.id.toString())
                putExtra("alarm_state", if (alarm.isEnabled) "ACTIVE" else "DISABLED")
                putExtra("alarm_data", alarm.toString())
            }
            context.sendBroadcast(responseIntent)
            Log.d(TAG, "Status response sent to $requesterPackage for alarm ${alarm.id}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send status response: ${e.message}", e)
        }
    }
    
    private fun sendAlarmListResponse(
        context: Context,
        requestId: String,
        requesterPackage: String,
        alarms: List<Alarm>
    ) {
        try {
            val responseIntent = Intent().apply {
                setPackage(requesterPackage)
                action = "${requesterPackage}.ALARM_RESPONSE"
                putExtra("request_id", requestId)
                putExtra("status", "SUCCESS")
                putExtra("alarm_count", alarms.size)
                putExtra("alarm_ids", alarms.map { it.id }.toIntArray())
            }
            context.sendBroadcast(responseIntent)
            Log.d(TAG, "List response sent to $requesterPackage: ${alarms.size} alarms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send list response: ${e.message}", e)
        }
    }
    
    // ==================== REVERSE NOTIFICATIONS ====================
    
    private fun sendStateNotification(
        context: Context,
        requesterPackage: String,
        stateChange: String,
        alarmId: String
    ) {
        try {
            val notificationIntent = Intent().apply {
                setPackage(requesterPackage)
                action = "${requesterPackage}.ALARM_NOTIFICATION"
                putExtra("notification_type", "STATE_CHANGE")
                putExtra("alarm_id", alarmId)
                putExtra("new_state", stateChange)
                putExtra("timestamp_ms", System.currentTimeMillis())
            }
            context.sendBroadcast(notificationIntent)
            Log.d(TAG, "State notification sent to $requesterPackage: $stateChange for alarm $alarmId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send state notification: ${e.message}", e)
        }
    }
    
    // ==================== UTILITY METHODS ====================
    
    private fun isValidRequester(requesterPackage: String): Boolean {
        // Only allow Shorts app for now
        return requesterPackage == "com.example.shorts"
    }
    
    private fun isDuplicateRequest(requestId: String): Boolean {
        // Simple in-memory duplicate check
        val processedRequests = getProcessedRequests()
        return if (processedRequests.contains(requestId)) {
            true
        } else {
            processedRequests.add(requestId)
            false
        }
    }
    
    private fun generateUniqueAlarmId(): Int {
        return (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
    }
    
    // Processed requests tracking
    private val processedRequestsSet = mutableSetOf<String>()
    
    private fun getProcessedRequests(): MutableSet<String> = processedRequestsSet
}
