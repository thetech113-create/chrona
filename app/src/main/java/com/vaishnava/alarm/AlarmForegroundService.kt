package com.vaishnava.alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.vaishnava.alarm.data.isOneShot
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import com.vaishnava.alarm.data.Alarm
import com.vaishnava.alarm.data.WakeCheckStore
import com.vaishnava.alarm.sequencer.DpsMissionMirror
import com.vaishnava.alarm.sequencer.MissionSequencer
import com.vaishnava.alarm.sequencer.SequencerMissionCodec
import com.vaishnava.alarm.sequencer.SttMissionSupport
import com.vaishnava.alarm.sequencer.FaceMissionSupport
import com.vaishnava.alarm.VolumeBooster
import kotlinx.coroutines.Runnable
import java.io.File
import java.io.FileWriter
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class AlarmForegroundService : Service() {

    companion object {
        private const val TAG: String = "AlarmForegroundService"
        @Volatile
        private var _isActivityForeground: Boolean = false

        val isActivityForeground: Boolean
            get() = _isActivityForeground

        fun setActivityForeground(active: Boolean) {
            _isActivityForeground = active
            Log.d("AlarmForegroundService", "ACTIVITY_FOREGROUND_STATE: $active")
        }

        @Volatile private var forceRestartSequencerRef: WeakReference<MissionSequencer>? = null

        fun getForceRestartSequencer(): MissionSequencer? {
            return forceRestartSequencerRef?.get()
        }
        private const val REPOST_DELAY_MS: Long = 3000L // Reduced to 3 seconds for better persistence
        private const val PREFS_NAME = "alarm_volume_prefs"
        private const val KEY_PREV_VOLUME = "prev_alarm_volume"

        private fun writePersistentLog(message: String) {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val logFile = File(Environment.getExternalStorageDirectory().path + "/Download/alarm_debug.log")
                val writer = FileWriter(logFile, true)
                writer.append("[$timestamp] $message\n")
                writer.close()
            } catch (_: Exception) {
                // Silently fail if we can't write to external storage
            }
        }
    }

    // ========= Dependencies & state =========
    private lateinit var alarmStorage: AlarmStorage
    private lateinit var wakeLock: PowerManager.WakeLock
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var currentAlarmId: Int = -1
    private var currentRepeatDays: IntArray? = null
    private var previousAlarmVolume: Int = -1
    private val launcherScheduled: AtomicBoolean = AtomicBoolean(false)
    private var ringtoneUri: Uri? = null
    private var isWakeCheckLaunch: Boolean = false
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var audioBooster: AudioBooster? = null
    private var volumeBooster: VolumeBooster? = null
    
    // Mission data for Direct Boot restoration
    private var currentMissionType: String? = null
    private var currentMissionPassword: String? = null
    private var currentIsProtected: Boolean = false
    private var duckRestoreRunnable: Runnable? = null
    private var alarmVolumeBeforeDuck: Int? = null
    private var ringtoneFallback: Ringtone? = null
    private var dpsWrittenForThisRing: Boolean = false
    private var playbackStarted: Boolean = false
    private var awaitingResume: Boolean = false
    private var resumeReceiver: BroadcastReceiver? = null
    private var playbackVolume: Float = 1.0f
    private var previousInterruptionFilter: Int? = null
    private var isMissedAlarm: Boolean = false
    private var ttsSpokenForCurrentAlarm: Boolean = false
    private var unlockReceiver: BroadcastReceiver? = null
    private var dismissReceiver: BroadcastReceiver? = null

    private fun cancelDirectBootAlarmTrigger(targetAlarmId: Int) {
        if (targetAlarmId == -1) return

        try {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            val cancelIntent = Intent(this, AlarmReceiver::class.java).apply {
                action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
                data = "alarm://$targetAlarmId".toUri()
                putExtra(AlarmReceiver.ALARM_ID, targetAlarmId)
            }
            val pendingCancel = PendingIntent.getBroadcast(
                this,
                targetAlarmId,
                cancelIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingCancel != null) {
                alarmManager.cancel(pendingCancel)
                pendingCancel.cancel()
                Log.d(TAG, "SINGLE_ALARM_PURGE: Canceled OS AlarmManager intent for $targetAlarmId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "SINGLE_ALARM_PURGE_ERROR: alarmId=$targetAlarmId error=${e.message}")
        }
    }

    private fun writeDpsCompletionSyncMarker(targetAlarmId: Int) {
        if (targetAlarmId == -1) return

        try {
            val dpsContext = createDeviceProtectedStorageContext() ?: this

            val dpsPrefs = dpsContext.getSharedPreferences("dps_completion_sync", MODE_PRIVATE)
            val committed = dpsPrefs.edit()
                .putBoolean("alarm_completed_in_dps_$targetAlarmId", true)
                .commit()

            if (committed) {
                Log.d(
                    TAG,
                    "DPS_COMPLETION_MARKER_COMMIT: source=writeDpsCompletionSyncMarker " +
                        "alarmId=$targetAlarmId marker=alarm_completed_in_dps storage=DPS success=true"
                )
            } else {
                Log.e(
                    TAG,
                    "DPS_COMPLETION_MARKER_COMMIT: source=writeDpsCompletionSyncMarker " +
                        "alarmId=$targetAlarmId marker=alarm_completed_in_dps storage=DPS success=false"
                )
            }
        } catch (e: Exception) {
            Log.e("DPS_SYNC", "DPS Write Error: ${e.message}")
        }
    }

    private fun handleSingleAlarmCompletion(targetAlarmId: Int) {
        if (targetAlarmId == -1) return

        val alarm = runCatching { alarmStorage.getAlarm(targetAlarmId) }.getOrNull()
        val isSequencerAlarm = alarm?.missionType == "sequencer"
        val isRepeating = when {
            alarm != null -> !alarm.isOneShot()
            currentAlarmId == targetAlarmId -> currentRepeatDays?.isNotEmpty() == true
            else -> false
        }

        val dpsContext = createDeviceProtectedStorageContext() ?: this
        val dpsPrefs = dpsContext.getSharedPreferences("alarm_dps", MODE_PRIVATE)

        val isUnlocked = try {
            val userManager = getSystemService(USER_SERVICE) as? UserManager
            userManager?.isUserUnlocked == true
        } catch (_: Exception) {
            false
        }

        logWakeCheckServiceForensic(
            "single_completion_start",
            targetAlarmId,
            "isSequencerAlarm=$isSequencerAlarm isRepeating=$isRepeating isUnlocked=$isUnlocked"
        )

        if (isSequencerAlarm) {
            if (!isUnlocked) {
                runCatching {
                    val committed = dpsPrefs.edit()
                        .putBoolean("sequencer_completed_$targetAlarmId", true)
                        .commit()
                    if (committed) {
                        Log.d(
                            TAG,
                            "DPS_COMPLETION_MARKER_COMMIT: source=handleSingleAlarmCompletion " +
                                "alarmId=$targetAlarmId marker=sequencer_completed storage=DPS unlocked=$isUnlocked success=true"
                        )
                    } else {
                        Log.e(
                            TAG,
                            "DPS_COMPLETION_MARKER_COMMIT: source=handleSingleAlarmCompletion " +
                                "alarmId=$targetAlarmId marker=sequencer_completed storage=DPS unlocked=$isUnlocked success=false"
                        )
                    }
                }
                writeDpsCompletionSyncMarker(targetAlarmId)
            }
            logWakeCheckServiceForensic(
                "single_completion_sequencer_return",
                targetAlarmId,
                "isUnlocked=$isUnlocked"
            )
            return
        }

        if (isRepeating) {
            runCatching {
                dpsPrefs.edit(commit = true) {
                    remove("sequencer_completed_$targetAlarmId")
                }
            }
            Log.d(TAG, "SINGLE_ALARM_COMPLETE: Alarm $targetAlarmId is repeating, keeping toggle ON")
            logWakeCheckServiceForensic("single_completion_repeating_return", targetAlarmId)
            return
        }

        if (alarm?.wakeCheckEnabled == true && !WakeCheckStore.isAcked(this, targetAlarmId, 10 * 60_000L)) {
            runCatching {
                dpsPrefs.edit(commit = true) {
                    remove("sequencer_completed_$targetAlarmId")
                }
            }
            Log.d(
                TAG,
                "SINGLE_ALARM_COMPLETE: keeping one-shot alarm $targetAlarmId enabled until wake-check cycle is finalized"
            )
            logWakeCheckServiceForensic("single_completion_waiting_for_wakecheck", targetAlarmId)
            return
        }

        if (!isUnlocked) {
            runCatching {
                val committed = dpsPrefs.edit()
                    .putBoolean("sequencer_completed_$targetAlarmId", true)
                    .commit()
                if (committed) {
                    Log.d(
                        TAG,
                        "DPS_COMPLETION_MARKER_COMMIT: source=handleSingleAlarmCompletion " +
                            "alarmId=$targetAlarmId marker=sequencer_completed storage=DPS unlocked=$isUnlocked success=true"
                    )
                } else {
                    Log.e(
                        TAG,
                        "DPS_COMPLETION_MARKER_COMMIT: source=handleSingleAlarmCompletion " +
                            "alarmId=$targetAlarmId marker=sequencer_completed storage=DPS unlocked=$isUnlocked success=false"
                    )
                }
            }
            writeDpsCompletionSyncMarker(targetAlarmId)
            cancelDirectBootAlarmTrigger(targetAlarmId)
            logWakeCheckServiceForensic("single_completion_locked_marker_written", targetAlarmId)
            return
        }

        try {
            alarmStorage.disableAlarm(targetAlarmId)
            Log.d(TAG, "SINGLE_ALARM_COMPLETE: Disabled single occurrence alarm $targetAlarmId")

            dpsPrefs.edit(commit = true) {
                remove("sequencer_completed_$targetAlarmId")
            }

            val updateIntent = Intent("com.vaishnava.alarm.REFRESH_ALARMS").apply {
                setPackage(packageName)
            }
            sendBroadcast(updateIntent)
            logWakeCheckServiceForensic("single_completion_disabled_alarm", targetAlarmId)
        } catch (e: Exception) {
            Log.e(TAG, "SINGLE_ALARM_COMPLETE_ERROR: alarmId=$targetAlarmId error=${e.message}")
            logWakeCheckServiceForensic("single_completion_disable_error", targetAlarmId, "message=${e.message}")
        }

        cancelDirectBootAlarmTrigger(targetAlarmId)
        logWakeCheckServiceForensic("single_completion_done", targetAlarmId)
    }

    // Handler/Thread
    private lateinit var bgHandler: Handler
    private lateinit var bgThread: HandlerThread

    // ========= Notification tracking =========
    private var lastNotifiedAlarmId: Int? = null
    private var lastNotificationTime: Long = 0L

    // ========= Receivers / Runnables =========

    // Duck receiver remains (brief duck behaviour). It tries to duck player volume first;
    // when using ringtone fallback it may adjust the stream temporarily and will restore.
    private val duckReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "com.vaishnava.alarm.DUCK_ALARM") return

            val factor: Float = intent.getFloatExtra("factor", 0.2f).coerceIn(0.0f, 1.0f)
            val duration: Long = intent.getLongExtra("duration", 2500L).coerceAtLeast(500L)

            duckRestoreRunnable?.let { bgHandler.removeCallbacks(it) }

            try {
                if (mediaPlayer != null) {
                    mediaPlayer?.setVolume(factor, factor)
                } else if (ringtoneFallback != null) {
                    val am: AudioManager? = getSystemService(AudioManager::class.java)
                    alarmVolumeBeforeDuck = am?.getStreamVolume(AudioManager.STREAM_ALARM)
                    val baseVolume = alarmVolumeBeforeDuck ?: previousAlarmVolume
                    if (baseVolume >= 0) {
                        val duckV: Int = (baseVolume * factor).toInt().coerceAtLeast(1)
                        am?.setStreamVolume(AudioManager.STREAM_ALARM, duckV, 0)
                    }
                }
            } catch (_: Exception) { }

            duckRestoreRunnable = Runnable {
                try {
                    if (mediaPlayer != null) {
                        mediaPlayer?.setVolume(playbackVolume, playbackVolume)
                    } else if (ringtoneFallback != null) {
                        val am: AudioManager? = getSystemService(AudioManager::class.java)
                        val restoreVolume = alarmVolumeBeforeDuck ?: previousAlarmVolume
                        if (restoreVolume >= 0) {
                            am?.setStreamVolume(AudioManager.STREAM_ALARM, restoreVolume, 0)
                        }
                    }
                } catch (_: Exception) { }
                alarmVolumeBeforeDuck = null
            }
            bgHandler.postDelayed(duckRestoreRunnable!!, duration)
        }
    }

    // Repost foreground notification periodically with enhanced persistence
    private val notificationReposter: Runnable = object : Runnable {
        private var consecutiveFailures = 0
        private val maxFailures = 3

        override fun run() {
            try {
                val notification: Notification = buildNotification()
                startForeground(1, notification)
                consecutiveFailures = 0 // Reset on success

                // Log successful repost for debugging
                Log.d(TAG, "Notification reposted successfully for alarmId=$currentAlarmId")

                // Schedule next repost
                bgHandler.postDelayed(this, REPOST_DELAY_MS)
            } catch (e: Exception) {
                consecutiveFailures++
                Log.e(TAG, "Notification repost failed (attempt $consecutiveFailures/$maxFailures): ${e.message}")

                if (consecutiveFailures < maxFailures) {
                    // Retry with exponential backoff
                    val backoffDelay = REPOST_DELAY_MS * (1 shl consecutiveFailures - 1)
                    bgHandler.postDelayed(this, backoffDelay)
                } else {
                    Log.e(TAG, "Max notification repost failures reached, giving up")
                    // Try to restart service as last resort
                    try {
                        val restartIntent = Intent(this@AlarmForegroundService, AlarmForegroundService::class.java)
                        restartIntent.putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
                        startService(restartIntent)
                    } catch (_: Exception) {
                        Log.e(TAG, "Failed to restart service after notification failures")
                    }
                }
            }
        }
    }

    // Launch AlarmActivity
    private val activityLauncher: Runnable = Runnable {
        if (!launcherScheduled.get()) return@Runnable

        // If this alarm was dismissed very recently, do not re-launch the UI.
        // This prevents duplicate AlarmActivity instances for normal alarms
        // when a stray service restart occurs after dismissal.
        try {
            val dps = createDeviceProtectedStorageContext() ?: this@AlarmForegroundService
            val prefs = dps.getSharedPreferences("alarm_dps", MODE_PRIVATE)
            val lastDismiss = prefs.getLong("dismiss_ts_$currentAlarmId", 0L)
            if (lastDismiss > 0L) {
                val now = System.currentTimeMillis()
                if (now - lastDismiss < 60_000L) {
                    Log.d(
                        "WakeCheckDebug",
                        "AlarmForegroundService.activityLauncher: suppressing UI relaunch for alarmId=$currentAlarmId due to recent dismiss (within 60s)"
                    )
                    return@Runnable
                }
            }
        } catch (_: Exception) { }

        try {
            // Preview exit guard: if a suppress flag was written very recently, skip launching UI
            try {
                val dps = createDeviceProtectedStorageContext() ?: this@AlarmForegroundService
                val prefs = dps.getSharedPreferences("alarm_dps", MODE_PRIVATE)
                val ts = prefs.getLong("preview_exit_block_$currentAlarmId", 0L)
                if (ts > 0L && System.currentTimeMillis() - ts < 60_000L) {
                    launcherScheduled.set(false)
                    return@Runnable
                }
            } catch (_: Exception) { }

            // Use the centralized launchAlarmActivity function
            launchAlarmActivity()
        } catch (_: Exception) { }
    }

    // Launch alarm activity (used by both activityLauncher and unlock receiver)
    private fun launchAlarmActivity() {
        startRelaunchWatchdog()
        var finalMissionType: String? = currentMissionType
        var finalMissionPassword: String? = currentMissionPassword
        var finalIsSequencerLaunch = currentMissionType == "sequencer"
        var finalIsProtected = currentIsProtected
        var finalMissionId: String? = null
        var enableSnooze = false

        try {
            // Get current mission data to pass to AlarmActivity
            var localMissionType: String? = currentMissionType
            var localMissionPassword: String? = currentMissionPassword
            var localIsProtected: Boolean = currentIsProtected
            var resolvedAlarm: Alarm? = null

            // CRITICAL FIX: Always get fresh mission data from alarm configuration
            try {
                val alarm = alarmStorage.getAlarm(currentAlarmId)
                resolvedAlarm = alarm
                if (alarm != null) {
                    when (alarm.missionType) {
                        "sequencer" -> {
                            // CRITICAL DEBUG: Log exactly what's in missionPassword
                            val missionNamesRaw = alarm.missionPassword ?: ""
                            Log.d("MISSION_DEBUG_LAUNCH", "Raw missionPassword: '$missionNamesRaw'")

                            val missionIds = SequencerMissionCodec.parseMissionSequence(
                                serialized = missionNamesRaw,
                                alarmId = alarm.id
                            ).map { it.id }

                            Log.d("MISSION_DEBUG_LAUNCH", "Parsed missionIds: $missionIds")

                            // Preserve the sequencer identity and raw mission sequence string.
                            localMissionType = "sequencer"
                            localMissionPassword = if (missionIds.isNotEmpty()) missionNamesRaw else ""
                        }
                        "tap" -> {
                            localMissionType = "tap"
                            localMissionPassword = ""
                        }
                        "password" -> {
                            localMissionType = "password"
                            localMissionPassword = alarm.missionPassword?.takeIf { it.isNotBlank() } ?: MissionSequencer.DEFAULT_GLOBAL_PASSWORD
                        }
                        "none" -> {
                            // CRITICAL FIX: Block "none" missions from being launched
                            localMissionType = ""
                            localMissionPassword = ""
                        }
                        else -> {
                            localMissionType = alarm.missionType
                            localMissionPassword = alarm.missionPassword
                        }
                    }
                    localIsProtected = alarm.isProtected
                }
            } catch (_: Exception) { }

            val rawMissionPassword = localMissionPassword ?: resolvedAlarm?.missionPassword
            val missionIds = rawMissionPassword
                ?.split("+")
                ?.map { it.trim().lowercase() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

            finalIsSequencerLaunch =
                localMissionType == "sequencer" ||
                    resolvedAlarm?.missionType == "sequencer" ||
                    missionIds.size > 1

            finalMissionType = if (finalIsSequencerLaunch) {
                "sequencer"
            } else {
                localMissionType ?: resolvedAlarm?.missionType
            }
            finalMissionPassword = if (finalIsSequencerLaunch) rawMissionPassword else localMissionPassword
            finalIsProtected = localIsProtected

            if (finalIsSequencerLaunch) {
                try {
                    val sequencer = MainActivity.getInstance()?.missionSequencer ?: getForceRestartSequencer()
                    val activeMission = sequencer?.getCurrentMission()
                    if (activeMission != null) {
                        finalMissionId = activeMission.id
                        finalMissionType = activeMission.id
                        finalMissionPassword =
                            if (finalMissionType == "password") MissionSequencer.DEFAULT_GLOBAL_PASSWORD else ""
                        Log.d(TAG, "MISSION_DEBUG_LAUNCH Synced finalMissionType with sequencer: '$finalMissionType'")
                    }
                } catch (_: Exception) { }

                if (finalMissionId == null) {
                    val fallbackMissionId = missionIds.firstOrNull {
                        it == "tap" || it == "password" || it == "math" ||
                            it == SttMissionSupport.MISSION_ID ||
                            it == FaceMissionSupport.MISSION_ID
                    }
                    if (fallbackMissionId != null) {
                        finalMissionId = fallbackMissionId
                        finalMissionType = fallbackMissionId
                        finalMissionPassword =
                            if (fallbackMissionId == "password") MissionSequencer.DEFAULT_GLOBAL_PASSWORD else ""
                        Log.d(
                            TAG,
                            "MISSION_DEBUG_LAUNCH Fallback child mission resolved for sequencer: '$finalMissionType'"
                        )
                    }
                }
            } else {
                finalMissionId = finalMissionType
            }

            Log.d(TAG, "MISSION_DEBUG_LAUNCH Final robust missionType: '$finalMissionType'")
            ExtremeForensicLogger.Mission.log("ULTRA_FORENSIC_LOG: mode=DPS event=INTENT_BUILD preservedType=$finalMissionType rawPwd=$rawMissionPassword")

            // CRITICAL FIX: Get complete alarm data to pass to AlarmActivity
            var hour = -1
            var minute = -1
            var repeatDaily = false
            var wakeCheckEnabled = false
            var wakeCheckMinutes = 0

            try {
                val alarm = alarmStorage.getAlarm(currentAlarmId)
                if (alarm != null) {
                    hour = alarm.hour
                    minute = alarm.minute
                    repeatDaily = alarm.repeatDaily
                    wakeCheckEnabled = alarm.wakeCheckEnabled
                    wakeCheckMinutes = alarm.wakeCheckMinutes
                    enableSnooze = alarm.enableSnooze
                }
            } catch (_: Exception) { }

            // CRITICAL FIX: Use full-screen intent for lock screen display
            val fullScreenIntent = Intent(this@AlarmForegroundService, AlarmActivity::class.java).apply {
                // Use standard flags - AlarmActivity handles lock screen display
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
                putExtra("extra_ringtone_uri", ringtoneUri?.toString())
                putExtra("extra_repeat_days", currentRepeatDays)
                // CRITICAL FIX: Pass complete mission data to prevent screen-off issues
                putExtra("hour", hour)
                putExtra("minute", minute)
                putExtra("repeatDaily", repeatDaily)
                putExtra("wakeCheckEnabled", wakeCheckEnabled)
                putExtra("wakeCheckMinutes", wakeCheckMinutes)
                putExtra("mission_type", finalMissionType)
                putExtra("mission_password", finalMissionPassword)
                putExtra(MissionSequencer.EXTRA_FROM_SEQUENCER, finalIsSequencerLaunch)
                putExtra("is_protected", finalIsProtected)
                putExtra("enable_snooze", enableSnooze)
                finalMissionId?.let { putExtra("mission_id", it) }
                if (isWakeCheckLaunch) putExtra("from_wake_check", true)
            }

            try {
                createFullScreenIntent().send()
                Log.d(TAG, "Successfully triggered fullScreenIntent.send()")
            } catch (e: Exception) {
                Log.w(TAG, "Full screen intent send failed, attempting direct start: ${e.message}")
                try {
                    startActivity(fullScreenIntent)
                } catch (ex: Exception) {
                    Log.e(TAG, "Direct start failed: ${ex.message}")
                }
            }
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(this@AlarmForegroundService, AlarmActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                    putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
                    putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, ringtoneUri?.toString())
                    putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, currentRepeatDays)
                    if (isWakeCheckLaunch) putExtra("from_wake_check", true)

                    // Pass mission data for Direct Boot restoration
                    putExtra("mission_type", finalMissionType)
                    putExtra("mission_password", finalMissionPassword)
                    putExtra(MissionSequencer.EXTRA_FROM_SEQUENCER, finalIsSequencerLaunch)
                    putExtra("is_protected", finalIsProtected)
                    putExtra("enable_snooze", enableSnooze)
                    finalMissionId?.let { putExtra("mission_id", it) }
                }
                startActivity(fallbackIntent)
            } catch (_: Exception) { }
        }
    }

    // Check next-alarm window notification
    private val notificationChecker: Runnable = object : Runnable {
        override fun run() {
            try { showAlarmNotificationIfNeeded() } catch (_: Exception) { }
            bgHandler.postDelayed(this, 2000)
        }
    }

    // ========= Service lifecycle =========

    override fun onCreate() {
        super.onCreate()

        alarmStorage = AlarmStorage(this)

        // Wake lock (PARTIAL while alarm active)
        val powerManager: PowerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AlarmApp::AlarmService"
        )

        bgThread = HandlerThread("AlarmServiceBackground")
        bgThread.start()
        bgHandler = Handler(bgThread.looper)

        vibrator = getSystemService(Vibrator::class.java)

        // Foreground immediately
        val initial: Notification = buildNotification()
        startForeground(1, initial)

        // Register duck receiver
        try {
            val filter = IntentFilter("com.vaishnava.alarm.DUCK_ALARM")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(duckReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(duckReceiver, filter)
            }
        } catch (_: Exception) { }

        // Register user unlock receiver only to clear stale DPS unlock flags.
        unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_UNLOCKED) {
                    Log.d(TAG, "User unlocked detected - clearing any stale DPS unlock trigger state")
                    if (currentAlarmId != -1 && playbackStarted && !awaitingResume) {
                        try {
                            val dps = createDeviceProtectedStorageContext() ?: this@AlarmForegroundService
                            val prefs = dps.getSharedPreferences("direct_boot_alarm_prefs", MODE_PRIVATE)
                            val alarmPrefs = dps.getSharedPreferences("alarm_dps", MODE_PRIVATE)
                            val syncPrefs = dps.getSharedPreferences("dps_completion_sync", MODE_PRIVATE)
                            val wasDpsLaunch = prefs.getBoolean("dps_auto_launch_$currentAlarmId", false)
                            val snapshot = DpsMissionMirror(this@AlarmForegroundService).readSnapshot(currentAlarmId)
                            val isCompletedInDps =
                                alarmPrefs.getBoolean("sequencer_completed_$currentAlarmId", false) ||
                                    syncPrefs.getBoolean("alarm_completed_in_dps_$currentAlarmId", false) ||
                                    snapshot?.isSequenceComplete == true
                            
                            if (isCompletedInDps) {
                                Log.d(
                                    TAG,
                                    "DPS unlock auto-launch suppressed for completed alarmId=$currentAlarmId"
                                )
                                prefs.edit(commit = true) { remove("dps_auto_launch_$currentAlarmId") }
                                stopAlarm()
                            } else if (wasDpsLaunch) {
                                Log.d(
                                    TAG,
                                    "DPS unlock trigger cleared without relaunch for alarmId=$currentAlarmId"
                                )
                                prefs.edit(commit = true) { remove("dps_auto_launch_$currentAlarmId") }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error checking DPS launch state: ${e.message}")
                        }
                    }
                }
            }
        }
        try {
            val unlockFilter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(unlockReceiver, unlockFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(unlockReceiver, unlockFilter)
            }
            Log.d(TAG, "Registered user unlock receiver")
        } catch (_: Exception) {
            Log.e(TAG, "Failed to register unlock receiver")
        }

        // Register notification dismiss receiver to REPOST notification without destroying alarm state
        dismissReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action
                if (action == "com.vaishnava.alarm.NOTIFICATION_DISMISSED") {
                    val dismissedAlarmId = intent.getIntExtra(AlarmReceiver.ALARM_ID, -1).takeIf { it != -1 }
                        ?: intent.getIntExtra("ALARM_ID", -1)
                    if ((dismissedAlarmId == currentAlarmId || currentAlarmId != -1) && currentAlarmId != -1) {
                        Log.d(TAG, "Notification swiped for active alarmId=$currentAlarmId - reposting notification without stopping alarm")
                        try {
                            val notification = buildNotification()
                            startForeground(1, notification)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to repost notification on swipe: ${e.message}")
                        }
                    }
                }
            }
        }
        try {
            val dismissFilter = IntentFilter("com.vaishnava.alarm.NOTIFICATION_DISMISSED")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(dismissReceiver, dismissFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(dismissReceiver, dismissFilter)
            }
        } catch (_: Exception) { }

        resumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Constants.ACTION_RESUME_ALARM_AUDIO) return
                val requestedId = intent.getIntExtra(AlarmReceiver.ALARM_ID, -1)
                if (requestedId != currentAlarmId || !awaitingResume) return
                awaitingResume = false
                resumePlaybackIfNeeded()
            }
        }
        try {
            val resumeFilter = IntentFilter(Constants.ACTION_RESUME_ALARM_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(resumeReceiver, resumeFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(resumeReceiver, resumeFilter)
            }
        } catch (_: Exception) { }
    }

    private fun stopAlarm() {
        try {
            stopRelaunchWatchdog()
            logWakeCheckServiceForensic("stop_alarm_start", currentAlarmId)
            // Prevent any late UI relaunches or notifications
            try {
                launcherScheduled.set(false)
                if (::bgHandler.isInitialized) {
                    bgHandler.removeCallbacks(activityLauncher)
                    bgHandler.removeCallbacks(notificationReposter)
                    bgHandler.removeCallbacks(notificationChecker)
                }
            } catch (_: Exception) { }

            // Stop TTS
            tts?.let { tts ->
                try { tts.stop() } catch (_: Exception) {}
                try { tts.shutdown() } catch (_: Exception) {}
                this@AlarmForegroundService.tts = null
            }

            // Stop periodic TTS
            stopPeriodicTTS()

            // Stop media player
            stopMediaPlayer()

            // Stop vibration
            try {
                vibrator?.cancel()
                vibrator = null
            } catch (_: Exception) {}

            // Clear notifications
            clearNextAlarmNotification()

            // Clear current service alarm ID tracking
            try {
                val dps = createDeviceProtectedStorageContext() ?: this
                val prefs = dps.getSharedPreferences("alarm_dps", MODE_PRIVATE)
                prefs.edit(commit = true) {
                    remove("current_service_alarm_id")
                    remove("was_ringing")
                    remove("ring_alarm_id")
                    remove("ring_started_at")
                }
                    
                // Also clear from direct_boot_alarm_prefs
                val dpsAlarmPrefs = dps.getSharedPreferences("direct_boot_alarm_prefs", MODE_PRIVATE)
                dpsAlarmPrefs.edit(commit = true) {
                    remove("current_alarm_id")
                    remove("dps_auto_launch_$currentAlarmId")
                }
            } catch (_: Exception) { }

            // DPS INVARIANT: Clear current_service_alarm_id only when service truly stops
            // For sequencer alarms, this must persist until entire mission sequence is complete
            restoreInterruptionFilterIfNeeded()

            // Restore previous alarm volume if saved
            try {
                restorePreviousVolumeIfNeeded()
            } catch (_: Exception) {}

            // Release wake lock if held
            if (wakeLock.isHeld) {
                try { wakeLock.release() } catch (_: Exception) {}
            }

            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) { }

            // Stop the service
            stopSelf()
            logWakeCheckServiceForensic("stop_alarm_end", currentAlarmId)
        } catch (e: Exception) {
            logWakeCheckServiceForensic("stop_alarm_exception", currentAlarmId, "message=${e.message}")
            try { stopSelf() } catch (_: Exception) {}
        }
    }

    private fun logWakeCheckServiceForensic(event: String, alarmId: Int, extra: String = "") {
        val safeAlarmId = if (alarmId != -1) alarmId else currentAlarmId
        try {
            val dpsContext = createDeviceProtectedStorageContext() ?: this
            val alarmPrefs = dpsContext.getSharedPreferences("alarm_dps", MODE_PRIVATE)
            val ackTs = if (safeAlarmId != -1) alarmPrefs.getLong("wakecheck_ack_ts_$safeAlarmId", 0L) else 0L
            val pending = if (safeAlarmId != -1) alarmPrefs.getBoolean("wakecheck_pending_$safeAlarmId", false) else false
            val finalized = if (safeAlarmId != -1) alarmPrefs.getBoolean("wakecheck_finalized_$safeAlarmId", false) else false
            val gate = if (safeAlarmId != -1) alarmPrefs.getBoolean("wakecheck_gate_active_$safeAlarmId", false) else false
            val dismissTs = if (safeAlarmId != -1) alarmPrefs.getLong("dismiss_ts_$safeAlarmId", 0L) else 0L
            val cycle = if (safeAlarmId != -1) runCatching { WakeCheckCycleStore.read(this, safeAlarmId) }.getOrNull() else null
            val alarm = if (safeAlarmId != -1) runCatching { alarmStorage.getAlarm(safeAlarmId) }.getOrNull() else null

            Log.d(
                TAG,
                "WAKECHECK_FORENSIC_SERVICE: event=$event alarmId=$safeAlarmId " +
                    "currentAlarmId=$currentAlarmId isWakeCheckLaunch=$isWakeCheckLaunch " +
                    "playbackStarted=$playbackStarted awaitingResume=$awaitingResume launcherScheduled=${launcherScheduled.get()} " +
                    "prefsAckTs=$ackTs prefsPending=$pending prefsFinalized=$finalized prefsGate=$gate prefsDismissTs=$dismissTs " +
                    "cycleId=${cycle?.cycleId ?: 0L} cycleState=${cycle?.state ?: "null"} " +
                    "cycleScheduledAt=${cycle?.scheduledAt ?: 0L} cycleDueAt=${cycle?.dueAt ?: 0L} " +
                    "alarmEnabled=${alarm?.isEnabled} alarmWakeCheck=${alarm?.wakeCheckEnabled} " +
                    "alarmWakeMinutes=${alarm?.wakeCheckMinutes} alarmRepeatDaily=${alarm?.repeatDaily} " +
                    "alarmDays=${alarm?.days} alarmMission=${alarm?.missionType} " +
                    extra
            )
        } catch (e: Exception) {
            Log.e(TAG, "WAKECHECK_FORENSIC_SERVICE_ERROR: event=$event alarmId=$safeAlarmId message=${e.message}")
        }
    }

    private fun isCompletedInDps(alarmId: Int): Boolean {
        if (alarmId == -1) return false

        return try {
            val dpsContext = createDeviceProtectedStorageContext() ?: this
            val alarmPrefs = dpsContext.getSharedPreferences("alarm_dps", MODE_PRIVATE)
            val syncPrefs = dpsContext.getSharedPreferences("dps_completion_sync", MODE_PRIVATE)
            val snapshot = DpsMissionMirror(this).readSnapshot(alarmId)
            val alarm = runCatching { alarmStorage.getAlarm(alarmId) }.getOrNull()
            val rawCompleted =
                alarmPrefs.getBoolean("sequencer_completed_$alarmId", false) ||
                syncPrefs.getBoolean("alarm_completed_in_dps_$alarmId", false) ||
                snapshot?.isSequenceComplete == true
            val wakeCheckLoopActive = WakeCheckStore.isLoopActive(this, alarm)
            if (rawCompleted && wakeCheckLoopActive) {
                logWakeCheckServiceForensic(
                    "is_completed_override_false",
                    alarmId,
                    "rawCompleted=$rawCompleted wakeCheckLoopActive=$wakeCheckLoopActive"
                )
                Log.d(
                    TAG,
                    "WAKECHECK_COMPLETION_OVERRIDE: owner=isCompletedInDps alarmId=$alarmId " +
                        "cycleId=${WakeCheckCycleStore.currentCycleId(this, alarmId)} missionModeActive=${alarm?.missionType == "sequencer"} " +
                        "finalCompletionConfirmed=false awakeConfirmed=${WakeCheckStore.isAcked(this, alarmId, 10 * 60_000L)} " +
                        "loopActive=true interval=${alarm?.wakeCheckMinutes ?: 0} scheduleTs=${WakeCheckCycleStore.read(this, alarmId)?.scheduledAt ?: 0L} " +
                        "allow=true reason=post_alarm_wakecheck_pending"
                )
                false
            } else {
                logWakeCheckServiceForensic(
                    "is_completed_result",
                    alarmId,
                    "rawCompleted=$rawCompleted wakeCheckLoopActive=$wakeCheckLoopActive"
                )
                rawCompleted
            }
        } catch (e: Exception) {
            Log.e(TAG, "DPS_COMPLETION_CHECK_ERROR: alarmId=$alarmId error=${e.message}")
            logWakeCheckServiceForensic("is_completed_exception", alarmId, "message=${e.message}")
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            // CRITICAL DEBUG: Log every time AlarmForegroundService is started
            Log.d("SERVICE_DEBUG_MAIN", "AlarmForegroundService.onStartCommand() called with intent=${intent?.action}")

            val action = intent?.action
            var alarmId = intent?.getIntExtra(AlarmReceiver.ALARM_ID, -1) ?: -1
        val requestedWakeCheckCycleId =
            intent?.getLongExtra(Constants.EXTRA_WAKE_CHECK_CYCLE_ID, -1L) ?: -1L
        val wakeCheckRetriggerRequested =
            intent?.getBooleanExtra(Constants.EXTRA_WAKE_CHECK_RETRIGGER, false) == true
        if (alarmId == -1) {
            try {
                val dpsContext = createDeviceProtectedStorageContext() ?: this
                val servicePrefs = dpsContext.getSharedPreferences("alarm_dps", MODE_PRIVATE)
                val alarmPrefs = dpsContext.getSharedPreferences("direct_boot_alarm_prefs", MODE_PRIVATE)
                val recoveredAlarmId = when {
                    currentAlarmId != -1 -> currentAlarmId
                    servicePrefs.getInt("current_service_alarm_id", -1) != -1 ->
                        servicePrefs.getInt("current_service_alarm_id", -1)
                    else -> alarmPrefs.getInt("current_alarm_id", -1)
                }
                if (recoveredAlarmId != -1) {
                    alarmId = recoveredAlarmId
                    Log.d(TAG, "NULL_INTENT_RECOVERY: restored alarmId=$alarmId from existing DPS state")
                }
            } catch (e: Exception) {
                Log.e(TAG, "NULL_INTENT_RECOVERY_ERROR: failed to recover alarmId", e)
            }
        }

        if (alarmId != -1 && isCompletedInDps(alarmId)) {
            Log.wtf(
                TAG,
                "FORENSIC_LIFECYCLE_AUDIT: rejected service start for completed alarmId=$alarmId " +
                    "action=$action startId=$startId flags=$flags"
            )
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) { }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        
        // CRITICAL: Handle STOP_ALARM_SERVICE action for sequencer completion
        if (action == "com.vaishnava.alarm.STOP_ALARM_SERVICE") {
            Log.d(TAG, "STOP_ALARM_SERVICE received, checking if sequencer is complete and CPS condition is met")
            logWakeCheckServiceForensic("stop_alarm_service_action_received", alarmId)
            
            // CRITICAL FIX: Only stop service if sequencer is actually complete AND CPS condition is met
            val mainActivity = MainActivity.getInstance()
            val sequencer = mainActivity?.missionSequencer ?: getForceRestartSequencer()
            
            if (sequencer != null) {
                // Check both sequencer completion AND DPS->CPS condition
                val isSequencerComplete = sequencer.isSequencerComplete
                val isDeviceLocked = sequencer.isDeviceLocked()
                val queueEmpty = sequencer.getCurrentMission() == null
                
                Log.d(TAG, "STOP_ALARM_SERVICE: sequencerComplete=$isSequencerComplete, deviceLocked=$isDeviceLocked, queueEmpty=$queueEmpty")
                
                if (isSequencerComplete) {
                    Log.d(TAG, "STOP_ALARM_SERVICE: Sequencer is complete. Stopping service unconditionally.")
                    logWakeCheckServiceForensic("stop_alarm_service_sequencer_complete", alarmId)
                    stopAlarm()
                    return START_NOT_STICKY
                } else {
                    Log.d(TAG, "STOP_ALARM_SERVICE: Sequencer not complete, ignoring stop request")
                    logWakeCheckServiceForensic("stop_alarm_service_ignored", alarmId)
                    return START_STICKY
                }
            } else {
                Log.d(TAG, "STOP_ALARM_SERVICE: No sequencer available, stopping service")
                logWakeCheckServiceForensic("stop_alarm_service_single_alarm_path", alarmId)
                handleSingleAlarmCompletion(alarmId)
                stopAlarm()
                return START_NOT_STICKY
            }
        }
        
        // Extract extras and update state
        isWakeCheckLaunch = intent?.getBooleanExtra("from_wake_check", false) == true
        isMissedAlarm = intent?.getBooleanExtra("is_missed_alarm", false) == true
        val isSequencerMissedAlarm = intent?.getBooleanExtra("is_sequencer_missed_alarm", false) == true
        val isForceRestartSequencer = intent?.getBooleanExtra("is_force_restart_sequencer", false) == true
        Log.d(
            "WAKECHECK_BUG",
            "AlarmForegroundService.onStartCommand: alarmId=$alarmId isWakeCheckLaunch=$isWakeCheckLaunch " +
                "isMissedAlarm=$isMissedAlarm cycleIdExtra=${intent?.getLongExtra(Constants.EXTRA_WAKE_CHECK_CYCLE_ID, -1L)} " +
                "wakeCheckEnabledExtra=${intent?.getBooleanExtra("wake_check_enabled", false)} " +
                "wakeCheckMinutesExtra=${intent?.getIntExtra("wake_check_minutes", -1)}"
        )

        // Do not arm any unlock-time re-launch trigger. DPS->CPS handoff must be cleanup-only.
        if (alarmId != -1 && !isWakeCheckLaunch && !isMissedAlarm) {
            try {
                val dps = createDeviceProtectedStorageContext() ?: this
                val prefs = dps.getSharedPreferences("direct_boot_alarm_prefs", MODE_PRIVATE)
                val dpsRingtone = prefs.getString("direct_boot_ringtone_$alarmId", null)
                
                if (dpsRingtone != null) {
                    prefs.edit(commit = true) { remove("dps_auto_launch_$alarmId") }
                    Log.d(TAG, "Cleared DPS auto-launch trigger for alarmId=$alarmId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting DPS launch flag: ${e.message}")
            }
        }
        currentRepeatDays = intent?.getIntArrayExtra(AlarmReceiver.EXTRA_REPEAT_DAYS) ?: intArrayOf()

        // Handle repost notification action
        if (action == Constants.ACTION_REPOST_NOTIFICATION && alarmId == currentAlarmId) {
            Log.d(TAG, "Handling repost notification request for alarmId=$alarmId")
            try {
                val notification = buildNotification()
                startForeground(1, notification)
                Log.d(TAG, "Notification reposted successfully for alarmId=$alarmId")
                return START_STICKY
            } catch (e: Exception) {
                Log.e(TAG, "Failed to repost notification: ${e.message}", e)
                return START_STICKY
            }
        }

        // Handle stop action
        if (action == Constants.ACTION_STOP_FOREGROUND_SERVICE) {
            logWakeCheckServiceForensic("action_stop_foreground_service", alarmId)
            if (intent?.getBooleanExtra("from_snooze", false) == true) {
                logWakeCheckServiceForensic("action_stop_foreground_service_from_snooze", alarmId)
                stopAlarm()
                return START_NOT_STICKY
            }
            handleSingleAlarmCompletion(alarmId)
            stopAlarm()
            return START_NOT_STICKY
        }

        // New start (normal alarm or wake-check follow-up): decide if we should
        // launch AlarmActivity based on the incoming intent. Some restarts
        // (e.g., wake-check timeout) only need audio resumed, not a new UI.
        val skipActivityLaunch = intent?.getBooleanExtra("skip_activity_launch", false) == true
        launcherScheduled.set(false)

        // Hold CPU wakelock during the alarm; normally released in stopAlarm(). The 60-minute
        // timeout is only a battery safety-net if release() never runs (process killed/crashed) —
        // far longer than any real alarm ring, so it never cuts off an active alarm.
        if (!wakeLock.isHeld) {
            try { wakeLock.acquire(60 * 60 * 1000L) } catch (_: Exception) {}
        }

        // Extract extras
        val newAlarmId = alarmId

        // CRITICAL Fix: Prevent the same alarm from starting multiple times
        if (currentAlarmId == newAlarmId && playbackStarted) {
            return START_STICKY
        }

        // If this is a different alarm from what's currently playing, stop the current one first
        if (currentAlarmId != -1 && newAlarmId != -1 && currentAlarmId != newAlarmId) {
            Log.d(TAG, "Alarm ID changed from $currentAlarmId to $newAlarmId, stopping current alarm before starting new one")
            stopMediaPlayer()
            stopVibration()
            // Reset TTS flag for new alarm
            ttsSpokenForCurrentAlarm = false
            // Stop periodic TTS for old alarm
            stopPeriodicTTS()
        }

        currentAlarmId = newAlarmId
        val persistedWakeCheckCycle =
            if (currentAlarmId != -1) {
                runCatching { WakeCheckCycleStore.read(this, currentAlarmId) }.getOrNull()
            } else {
                null
            }

        if (currentAlarmId != -1) {
            Log.d(
                TAG,
                "STATE_RESTORE: alarmId=$currentAlarmId cycleId=${persistedWakeCheckCycle?.cycleId ?: 0L} " +
                    "state=${persistedWakeCheckCycle?.state ?: "null"} dueAt=${persistedWakeCheckCycle?.dueAt ?: 0L} " +
                    "expiryAt=${persistedWakeCheckCycle?.expiryAt ?: 0L} " +
                    "entry=${if (isWakeCheckLaunch) "wakecheck_continuation" else if (wakeCheckRetriggerRequested) "retriggered_normal_alarm" else "normal_initial_ring"}"
            )
        }

        val pendingWakeCheckNormalStart =
            !isWakeCheckLaunch &&
                !wakeCheckRetriggerRequested &&
                persistedWakeCheckCycle != null &&
                persistedWakeCheckCycle.cycleId > 0L &&
                when (persistedWakeCheckCycle.state) {
                    WakeCheckCycleState.WAKEUP_CHECK_SCHEDULED,
                    WakeCheckCycleState.WAKEUP_CHECK_SHOWING -> true
                    else -> false
                }
        if (pendingWakeCheckNormalStart) {
            val now = System.currentTimeMillis()
            val shouldRejectNormalStart =
                when (persistedWakeCheckCycle?.state) {
                    WakeCheckCycleState.WAKEUP_CHECK_SCHEDULED ->
                        persistedWakeCheckCycle.dueAt <= 0L || persistedWakeCheckCycle.dueAt > now
                    WakeCheckCycleState.WAKEUP_CHECK_SHOWING ->
                        persistedWakeCheckCycle.expiryAt <= 0L || persistedWakeCheckCycle.expiryAt > now
                    else -> false
                }
            if (shouldRejectNormalStart) {
                Log.w(
                    TAG,
                    "WAKECHECK_PENDING_NORMAL_START_REJECTED: alarmId=$currentAlarmId " +
                        "cycleId=${persistedWakeCheckCycle?.cycleId ?: 0L} " +
                        "state=${persistedWakeCheckCycle?.state ?: "null"} " +
                        "dueAt=${persistedWakeCheckCycle?.dueAt ?: 0L} " +
                        "expiryAt=${persistedWakeCheckCycle?.expiryAt ?: 0L} " +
                        "isMissedAlarm=$isMissedAlarm action=$action"
                )
                stopAlarm()
                return START_NOT_STICKY
            }
        }

        val missedAlarmContinuityConflict =
            isMissedAlarm &&
                !isWakeCheckLaunch &&
                !wakeCheckRetriggerRequested &&
                persistedWakeCheckCycle != null &&
                persistedWakeCheckCycle.cycleId > 0L &&
                when (persistedWakeCheckCycle.state) {
                    WakeCheckCycleState.WAKEUP_CHECK_SCHEDULED,
                    WakeCheckCycleState.WAKEUP_CHECK_SHOWING,
                    WakeCheckCycleState.WAKEUP_CHECK_MISSED,
                    WakeCheckCycleState.ALARM_RETRIGGERED -> true
                    else -> false
                }
        if (missedAlarmContinuityConflict) {
            Log.w(
                TAG,
                "MISSED_ALARM_REJECTED: alarmId=$currentAlarmId cycleId=${persistedWakeCheckCycle?.cycleId ?: 0L} " +
                    "state=${persistedWakeCheckCycle?.state ?: "null"} dueAt=${persistedWakeCheckCycle?.dueAt ?: 0L} " +
                    "expiryAt=${persistedWakeCheckCycle?.expiryAt ?: 0L}"
            )
            stopAlarm()
            return START_NOT_STICKY
        }

        if (currentAlarmId != -1 && isCompletedInDps(currentAlarmId)) {
            Log.d(TAG, "GHOST_ALARM_SHIELD: Alarm $currentAlarmId already completed in DPS. Killing resurrected service.")
            stopAlarm()
            return START_NOT_STICKY
        }

        var restoredWakeCheckCycle: Boolean
        if (!isWakeCheckLaunch && currentAlarmId != -1) {
            restoredWakeCheckCycle =
                wakeCheckRetriggerRequested &&
                    requestedWakeCheckCycleId > 0L &&
                    WakeCheckCycleStore.markAlarmRetriggered(
                        this,
                        currentAlarmId,
                        requestedWakeCheckCycleId
                    )
            if (!restoredWakeCheckCycle) {
                WakeCheckCycleStore.beginInitialAlarmRing(this, currentAlarmId)
            }
        } else if (isWakeCheckLaunch && currentAlarmId != -1) {
            val effectiveCycleId = when {
                requestedWakeCheckCycleId > 0L -> requestedWakeCheckCycleId
                else -> WakeCheckCycleStore.currentCycleId(this, currentAlarmId)
            }
            if (effectiveCycleId <= 0L ||
                !WakeCheckCycleStore.markWakeCheckShowing(this, currentAlarmId, effectiveCycleId)
            ) {
                Log.w(
                    TAG,
                    "WAKECHECK_FIRE_REJECTED: alarmId=$currentAlarmId cycleId=$effectiveCycleId"
                )
                stopAlarm()
                return START_NOT_STICKY
            }
        }

        // Track current service alarm ID in DPS for missed alarm prevention
        try {
            val dps = createDeviceProtectedStorageContext() ?: this
            val prefs = dps.getSharedPreferences("alarm_dps", MODE_PRIVATE)
            prefs.edit(commit = true) {
                putInt("current_service_alarm_id", currentAlarmId)
            }
                
            // Also save to direct_boot_alarm_prefs for DPS mode detection in AlarmActivity
            val dpsAlarmPrefs = dps.getSharedPreferences("direct_boot_alarm_prefs", MODE_PRIVATE)
            dpsAlarmPrefs.edit(commit = true) {
                putInt("current_alarm_id", currentAlarmId)
            }
        } catch (_: Exception) { }
        
        // DPS INVARIANT: Write sequencer alarm ID to DPS before any multimission logic
        // This ensures current_service_alarm_id persists across entire mission sequence
        isWakeCheckLaunch = intent?.getBooleanExtra("from_wake_check", false) == true
        isMissedAlarm = intent?.getBooleanExtra("is_missed_alarm", false) == true
        this.ringtoneUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(AlarmReceiver.EXTRA_RINGTONE_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(AlarmReceiver.EXTRA_RINGTONE_URI)
        }
        if (this.ringtoneUri == null && currentAlarmId != -1) {
            try {
                val dpsContext = createDeviceProtectedStorageContext() ?: this
                val dpsPrefs = dpsContext.getSharedPreferences("direct_boot_alarm_prefs", MODE_PRIVATE)
                val storedRingtone = dpsPrefs.getString("direct_boot_ringtone_$currentAlarmId", null)
                if (!storedRingtone.isNullOrBlank()) {
                    this.ringtoneUri = storedRingtone.toUri()
                    Log.d(TAG, "NULL_INTENT_RECOVERY: restored ringtone for alarmId=$currentAlarmId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "NULL_INTENT_RECOVERY_ERROR: failed to restore ringtone for alarmId=$currentAlarmId", e)
            }
        }
        
        // Read mission data from intent extras (for Direct Boot restoration)
        val missionType = intent?.getStringExtra("mission_type")
        val missionPassword = intent?.getStringExtra("mission_password")
        val isProtected = intent?.getBooleanExtra("is_protected", false) ?: false
        
        // Store mission data for later use when launching AlarmActivity
        var storedMissionType: String? = null
        var storedMissionPassword: String? = null
        var storedIsProtected = false
        
        // Try to get mission data from alarm storage as fallback
        try {
            val alarm = alarmStorage.getAlarm(currentAlarmId)
            storedMissionType = alarm?.missionType
            storedMissionPassword = alarm?.missionPassword
            storedIsProtected = alarm?.isProtected ?: false
        } catch (_: Exception) { }
        
        // CRITICAL FIX: Parse mission data from alarm configuration for both single and multi-mission
        val alarm = alarmStorage.getAlarm(currentAlarmId)
        if (alarm != null) {
            when (alarm.missionType) {
                "sequencer" -> {
                    // For sequencer alarms, parse the mission sequence
                    val missionNames = alarm.missionPassword ?: ""
                    val missionIds = SequencerMissionCodec.parseMissionSequence(
                        serialized = missionNames,
                        alarmId = alarm.id
                    ).map { it.id }
                    
                    // Preserve the sequencer identity instead of collapsing it to the first child mission.
                    currentMissionType = "sequencer"
                    currentMissionPassword = if (missionIds.isNotEmpty()) alarm.missionPassword else ""
                }
                "tap" -> {
                    currentMissionType = "tap"
                    currentMissionPassword = ""
                }
                "password" -> {
                    currentMissionType = "password"
                    currentMissionPassword = alarm.missionPassword?.takeIf { it.isNotBlank() } ?: MissionSequencer.DEFAULT_GLOBAL_PASSWORD
                }
                "none" -> {
                    // CRITICAL FIX: Block "none" missions from being launched
                    currentMissionType = ""
                    currentMissionPassword = ""
                }
                else -> {
                    currentMissionType = alarm.missionType
                    currentMissionPassword = alarm.missionPassword
                }
            }
            currentIsProtected = alarm.isProtected
        } else {
            // Fallback to intent data first, then DPS mission data for Direct Boot single alarms.
            val dpsContext = createDeviceProtectedStorageContext() ?: this
            val dpsPrefs = dpsContext.getSharedPreferences("direct_boot_alarm_prefs", MODE_PRIVATE)
            val dpsType = dpsPrefs.getString("direct_boot_mission_type_$currentAlarmId", null)
            val dpsPassword = dpsPrefs.getString("direct_boot_mission_password_$currentAlarmId", null)

            currentMissionType = missionType ?: storedMissionType ?: dpsType
            currentMissionPassword = missionPassword ?: storedMissionPassword ?: dpsPassword
            currentIsProtected = isProtected || storedIsProtected
        }
        
        currentRepeatDays = intent?.getIntArrayExtra(AlarmReceiver.EXTRA_REPEAT_DAYS) ?: intArrayOf()

        // Unified log to in-app overlay
        try {
            ExtremeForensicLogger.Mission.log(
                "Service id=$currentAlarmId wakeCheck=$isWakeCheckLaunch skipActivity=$skipActivityLaunch hasRingtone=${this.ringtoneUri != null} mission=$currentMissionType protected=$currentIsProtected"
            )
        } catch (_: Exception) { }

        dpsWrittenForThisRing = false

        // If this is a wake-up-check follow-up start, but the user has already
        // acknowledged "I'm awake" very recently, abort this service start to
        // prevent any late restart races. AlarmReceiver already guards WAKE_UP
        // broadcasts, but this closes the loop in case the service is started
        // via a stale intent.
        if (isWakeCheckLaunch && currentAlarmId >= 0) {
            try {
                val dps = createDeviceProtectedStorageContext() ?: this
                val prefs = dps.getSharedPreferences("alarm_dps", MODE_PRIVATE)
                val ackTs = prefs.getLong("wakecheck_ack_ts_$currentAlarmId", 0L)
                if (ackTs > 0L) {
                    val now = System.currentTimeMillis()
                    if (now - ackTs < 10 * 60_000L) {
                        Log.d(
                            "WakeCheckDebug",
                            "AlarmForegroundService.onStartCommand: ignoring wake-check start for alarmId=$currentAlarmId because 'I'm awake' was acknowledged recently"
                        )
                        stopAlarm()
                        return START_NOT_STICKY
                    }
                }
            } catch (_: Exception) { }
        }

        Log.d("WakeCheckDebug", "AlarmForegroundService.onStartCommand alarmId=$currentAlarmId isWakeCheckLaunch=$isWakeCheckLaunch")

        // Show 1-minute notice when appropriate
        showAlarmNotificationIfNeeded()

        // Periodic silent repost: keeps the notification (and its full-screen tap PendingIntent)
        // pointing at the CURRENT mission so tapping it resumes instead of restarting. It does NOT
        // re-alert because buildNotification() sets setOnlyAlertOnce(true) — so no vibrate/sound pulse.
        bgHandler.post(notificationReposter)
        // notificationChecker disabled: it blocks on alarmStorage.getAlarms() file I/O every 2 seconds,
        // causing main-thread freezes during password entry. Showing next-alarm notifications is not
        // critical during mission playback.

        // Turn screen on
        turnScreenOn()

        // Handle sequencer missed alarms by rebuilding the mission queue
        if (isSequencerMissedAlarm) {
            Log.d(TAG, "Handling sequencer missed alarm: rebuilding mission queue for alarmId=$currentAlarmId")
            try {
                val alarm = alarmStorage.getAlarm(currentAlarmId)
                if (alarm != null && alarm.missionType == "sequencer") {
                    // Parse mission names from missionPassword field (e.g., "Tap+Pwd")
                    val missionNames = alarm.missionPassword ?: ""
                    val specs = SequencerMissionCodec.parseMissionSequence(
                        serialized = missionNames,
                        alarmId = alarm.id
                    )
                    val missionIds = specs.map { it.id }
                    
                    Log.d(TAG, "Parsed mission IDs for sequencer missed alarm: $missionIds")
                    
                    // Get MainActivity and enqueue missions
                    val mainActivity = MainActivity.getInstance()
                    val sequencer = mainActivity?.missionSequencer
                    if (sequencer != null) {
                        sequencer.enqueueAll(specs)
                        Log.d(TAG, "Enqueued ${specs.size} missions for sequencer missed alarm")
                        
                        // Start the sequencer
                        sequencer.startWhenAlarmFires(alarm.ringtoneUri)
                        Log.d(TAG, "Started sequencer for missed alarm: alarmId=$currentAlarmId")
                    } else {
                        Log.w(TAG, "MissionSequencer not available for sequencer missed alarm: alarmId=$currentAlarmId")
                    }
                } else {
                    Log.w(TAG, "Invalid alarm for sequencer missed alarm: alarmId=$currentAlarmId, missionType=${alarm?.missionType}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling sequencer missed alarm: alarmId=$currentAlarmId, error: ${e.message}")
            }
        }
        
        // Detect force restart for sequencer alarms and restore exact mission sequence
        if (!isSequencerMissedAlarm && !isMissedAlarm && !isWakeCheckLaunch) {
            try {
                val alarm = alarmStorage.getAlarm(currentAlarmId)
                val wakeCheckLoopActive = WakeCheckStore.isLoopActive(this@AlarmForegroundService, alarm)
                val isCompletedDps = isCompletedInDps(currentAlarmId)
                if (alarm == null) {
                    Log.w(TAG, "FORCE_RESTART_CHECK: No alarm found for id=$currentAlarmId")
                } else if (alarm.missionType != "sequencer") {
                    // Only care about sequencer alarms
                    Log.d(TAG, "FORCE_RESTART_CHECK: Not a sequencer alarm, skipping")
                } else if (!alarm.isEnabled || isCompletedDps) {
                    Log.d(TAG, "FORCE_RESTART_CHECK: Alarm is disabled or completed, skipping rebuild to prevent infinite loop")
                } else {
                    // Get MissionSequencer instance
                    val mainActivity = MainActivity.getInstance()
                    var sequencer = mainActivity?.missionSequencer

                    if (sequencer == null) {
                        // App was killed, try to create sequencer with DPS context
                        Log.d(TAG, "FORCE_RESTART: MainActivity null, creating MissionSequencer in DPS context for alarmId=$currentAlarmId")
                        val dpsContext = createDeviceProtectedStorageContext() ?: this@AlarmForegroundService
                        
                        // CRITICAL FIX: Add notification to show what data is available during force restart
                        try {
                            val alarmStorage = AlarmStorage(dpsContext)
                            val allAlarms = alarmStorage.getAlarms()
                            val sequencerAlarms = allAlarms.filter { it.missionType == "sequencer" }
                            Log.d("FORCE_RESTART_SERVICE", "AlarmForegroundService: Found ${sequencerAlarms.size} sequencer alarms out of ${allAlarms.size} total")
                            sequencerAlarms.take(3).forEach { alarm ->
                                Log.d("FORCE_RESTART_SAMPLE", "ID=${alarm.id}, Type=${alarm.missionType}, Password=${alarm.missionPassword}")
                            }
                            
                            // Also check DPS data
                            val dpsPrefs = dpsContext.getSharedPreferences("direct_boot_alarm_prefs", MODE_PRIVATE)
                            sequencerAlarms.forEach { alarm ->
                                val dpsType = dpsPrefs.getString("direct_boot_mission_type_${alarm.id}", "not_found")
                                val dpsPassword = dpsPrefs.getString("direct_boot_mission_password_${alarm.id}", "not_found")
                                Log.d("FORCE_RESTART_DPS", "Alarm ${alarm.id}: DPS Type=$dpsType, DPS Password=$dpsPassword")
                            }
                        } catch (e: Exception) {
                            Log.e("FORCE_RESTART_SERVICE", "Failed to read alarm data in force restart", e)
                        }
                        
                        sequencer = MissionSequencer(dpsContext)
                        
                        // Store the sequencer for activity to access safely
                        forceRestartSequencerRef = WeakReference(sequencer)
                        
                        // Wait a moment for async initialization to complete
                        Thread.sleep(100)
                    }

                    // If sequencer already has a current mission, do NOT rebuild – this is not a force restart
                    val freshLaunchMissionIds = SequencerMissionCodec.parseMissionSequence(
                        serialized = alarm.missionPassword,
                        alarmId = currentAlarmId
                    ).map { it.id }
                    val isFreshAlarmLaunch =
                        !isForceRestartSequencer &&
                            intent?.hasExtra(AlarmReceiver.ALARM_ID) == true
                    val existingMission = sequencer.getCurrentMission()
                    val snapshot = DpsMissionMirror(this@AlarmForegroundService).readSnapshot(currentAlarmId)
                    if (existingMission != null) {
                        Log.d(TAG, "FORCE_RESTART_CHECK: Sequencer already has currentMission=${existingMission.id}, skipping restore")
                        ExtremeForensicLogger.Mission.log("ULTRA_FORENSIC_LOG: mode=DPS event=FORCE_RESTART_SKIP pointer=${existingMission.id} reason=already_active")
                    } else if (snapshot == null && isFreshAlarmLaunch && freshLaunchMissionIds.isNotEmpty()) {
                        val initializedSnapshot =
                            sequencer.initializeFreshSequence(
                                alarmId = currentAlarmId,
                                orderedMissionIds = freshLaunchMissionIds,
                                ringtoneUri = alarm.ringtoneUri
                            )
                        ExtremeForensicLogger.Mission.logDpsForensic(
                            component = "AlarmForegroundService",
                            event = "fresh_fire_snapshot_initialized",
                            snapshot = initializedSnapshot,
                            expectedMissionId = initializedSnapshot?.activeMissionId,
                            runtimeMissionId = initializedSnapshot?.activeMissionId,
                            previousState = initializedSnapshot?.state,
                            source = "AlarmForegroundService",
                            queueReconstructed = false,
                            nullMissionObserved = initializedSnapshot?.activeMissionId == null && initializedSnapshot?.isSequenceComplete == false,
                            stalePendingIntent = false,
                            replayingOldData = false
                        )
                    } else if (snapshot == null) {
                        ExtremeForensicLogger.Mission.logError("DPS_RESTORE_ABORT: snapshot_missing alarmId=$currentAlarmId")
                        ExtremeForensicLogger.Mission.logDpsForensic(
                            component = "AlarmForegroundService",
                            event = "force_restart_snapshot_missing",
                            snapshot = null,
                            expectedMissionId = null,
                            runtimeMissionId = null,
                            previousState = null,
                            source = "AlarmForegroundService",
                            queueReconstructed = false,
                            nullMissionObserved = false,
                            stalePendingIntent = false,
                            replayingOldData = true
                        )
                        stopAlarm()
                        return START_NOT_STICKY
                    } else if (snapshot.isSequenceComplete && wakeCheckLoopActive) {
                        ExtremeForensicLogger.Mission.log(
                            "WAKECHECK_REARM_REBUILD: owner=AlarmForegroundService alarmId=$currentAlarmId " +
                                "cycleId=${WakeCheckCycleStore.currentCycleId(this@AlarmForegroundService, currentAlarmId)} " +
                                "missionModeActive=true currentMissionPointer=${snapshot.activeMissionId ?: "<none>"} " +
                                "finalCompletionConfirmed=false awakeConfirmed=${WakeCheckStore.isAcked(this@AlarmForegroundService, currentAlarmId, 10 * 60_000L)} " +
                                "loopActive=true interval=${alarm.wakeCheckMinutes} " +
                                "scheduleTs=${WakeCheckCycleStore.read(this@AlarmForegroundService, currentAlarmId)?.scheduledAt ?: 0L} " +
                                "allow=true reason=snapshot_completed_but_wakecheck_pending"
                        )
                        val initializedSnapshot =
                            sequencer.initializeFreshSequence(
                                alarmId = currentAlarmId,
                                orderedMissionIds = freshLaunchMissionIds,
                                ringtoneUri = alarm.ringtoneUri
                            )
                        ExtremeForensicLogger.Mission.logDpsForensic(
                            component = "AlarmForegroundService",
                            event = "wakecheck_rearm_snapshot_initialized",
                            snapshot = initializedSnapshot,
                            expectedMissionId = initializedSnapshot?.activeMissionId,
                            runtimeMissionId = initializedSnapshot?.activeMissionId,
                            previousState = snapshot.state,
                            source = "AlarmForegroundService",
                            queueReconstructed = false,
                            nullMissionObserved = initializedSnapshot?.activeMissionId == null && initializedSnapshot?.isSequenceComplete == false,
                            stalePendingIntent = false,
                            replayingOldData = false
                        )
                    } else if (snapshot.isSequenceComplete) {
                        ExtremeForensicLogger.Mission.log("DPS_RESTORE_ABORT: snapshot_completed alarmId=$currentAlarmId")
                        ExtremeForensicLogger.Mission.logDpsForensic(
                            component = "AlarmForegroundService",
                            event = "force_restart_snapshot_completed",
                            snapshot = snapshot,
                            expectedMissionId = snapshot.activeMissionId,
                            runtimeMissionId = null,
                            previousState = snapshot.state,
                            source = "AlarmForegroundService",
                            queueReconstructed = false,
                            nullMissionObserved = false,
                            stalePendingIntent = false,
                            replayingOldData = true
                        )
                        stopAlarm()
                        return START_NOT_STICKY
                    } else {
                        ExtremeForensicLogger.Mission.logDpsForensic(
                            component = "AlarmForegroundService",
                            event = "force_restart_snapshot_rehydrate",
                            snapshot = snapshot,
                            expectedMissionId = snapshot.activeMissionId,
                            runtimeMissionId = null,
                            previousState = snapshot.state,
                            source = "AlarmForegroundService",
                            queueReconstructed = false,
                            nullMissionObserved = snapshot.activeMissionId == null,
                            stalePendingIntent = false,
                            replayingOldData = false
                        )
                        sequencer.rehydrateFromSnapshot(snapshot, alarm.ringtoneUri)
                        Thread.sleep(500)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring mission sequence after force restart for alarmId=$currentAlarmId", e)
            }
        }

        // Speak current time via service-level TTS only for initial alarm launches, not wake-check follow-ups
        if (!isWakeCheckLaunch) {
            try {
                // Check if silence_no_sound is selected - if so, don't use TTS
                val isSilenceRingtone = try {
                    val ringtoneUri = this.ringtoneUri?.toString()
                    Log.d(TAG, "SERVICE_TTS_CHECK: alarmId=$currentAlarmId ringtoneUri=$ringtoneUri")

                    val containsSilence = if (ringtoneUri?.startsWith("android.resource://") == true) {
                        // Extract resource ID and check if it's silence_no_sound
                        val uriParts = ringtoneUri.split("/")
                        val resourceId = uriParts.lastOrNull()?.toIntOrNull()
                        if (resourceId != null) {
                            // Check if this resource ID matches silence_no_sound
                            val silenceResourceId = R.raw.silence_no_sound
                            resourceId == silenceResourceId
                        } else {
                            // Fallback to string check for resource names in URI
                            ringtoneUri.contains("silence_no_sound")
                        }
                    } else {
                        ringtoneUri?.contains("silence_no_sound") == true
                    }

                    Log.d(TAG, "SERVICE_TTS_CHECK: containsSilence=$containsSilence")

                    // Write to persistent log file
                    writePersistentLog("SERVICE_TTS_CHECK: alarmId=$currentAlarmId ringtoneUri=$ringtoneUri containsSilence=$containsSilence")

                    // Add to app's sequencer log system
                    MainActivity.getInstance()?.addSequencerLog("SERVICE_TTS_CHECK: alarmId=$currentAlarmId ringtoneUri=$ringtoneUri containsSilence=$containsSilence")

                    containsSilence
                } catch (_: Exception) {
                    Log.d(TAG, "SERVICE_TTS_CHECK: Exception checking ringtone")
                    writePersistentLog("SERVICE_TTS_CHECK: Exception checking ringtone for alarmId=$currentAlarmId")
                    MainActivity.getInstance()?.addSequencerLog("SERVICE_TTS_CHECK: Exception checking ringtone for alarmId=$currentAlarmId")
                    false
                }

                if (!isSilenceRingtone) {
                    // Start periodic TTS announcements
                    startPeriodicTTS()
                } else {
                    Log.d(TAG, "Silence ringtone detected - TTS disabled in service")
                }
            } catch (_: Exception) { }
        }

        playbackStarted = false
        awaitingResume = false

        // Only true wake-check launches (from_wake_check=true) should suppress playback.
        // Initial normal alarms must always start playback.
        val resumePending = isWakeCheckLaunch
        ensureDndAllowsAlarm()

        if (resumePending) {
            Log.d("WakeCheckDebug", "AlarmForegroundService: wake-check gate active; suppressing playback for alarmId=$currentAlarmId (isWakeCheckLaunch=$isWakeCheckLaunch)")
            awaitingResume = true
            writePendingResumeFlag(currentAlarmId, true)
            stopMediaPlayer()
            stopVibration()

            // Ensure the wake-check UI is brought to the front when in wake-check mode
            try {
                val intent = Intent(this@AlarmForegroundService, AlarmActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                    putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
                    putExtra("from_wake_check", true)
                }
                startActivity(intent)
                Log.d("WakeCheckDebug", "AlarmForegroundService: started wake-check activity immediately for alarmId=$currentAlarmId")
            } catch (_: Exception) { }
        } else {
            // Prepare audio & play immediately, using Direct Boot-safe stored ringtone if needed
            val effectiveUri = getEffectiveRingtoneUri()

            // Persist the effective ringtone URI so UI can show the correct title even after fallbacks
            try {
                val dps = createDeviceProtectedStorageContext() ?: this
                val prefs = dps.getSharedPreferences("alarm_dps", MODE_PRIVATE)
                prefs.edit(commit = true) {
                    putString("effective_ringtone_uri_$currentAlarmId", effectiveUri.toString())
                }
            } catch (_: Exception) { }
            Log.d("WakeCheckDebug", "AlarmForegroundService: starting normal playback for alarmId=$currentAlarmId isWakeCheckLaunch=$isWakeCheckLaunch")
            startPlayback(effectiveUri)

            // Playback watchdog: if for any reason playback didn't start within 2s, force a retry
            try {
                bgHandler.postDelayed({
                    try {
                        if (!playbackStarted && !awaitingResume) {
                            Log.w(TAG, "Playback watchdog retry for alarmId=$currentAlarmId")
                            val retryUri = this.ringtoneUri ?: run {
                                val silenceResourceId = R.raw.silence_no_sound
                                if (silenceResourceId != 0) {
                                    "android.resource://$packageName/$silenceResourceId".toUri()
                                } else {
                                    "android.resource://$packageName/raw/silence_no_sound".toUri()
                                }
                            }
                            startPlayback(retryUri)
                        }
                    } catch (_: Exception) { }
                }, 2000L)
            } catch (_: Exception) { }
        }

        // Launch activity immediately and also once after playback has started, unless the caller
        // explicitly requested to skip launching (e.g., wake-check timeout
        // restart from AlarmActivity which already has UI on screen).
        if (!skipActivityLaunch) {
            // Launch immediately for all alarms, including sequencer alarms, while the
            // foreground service still has background-activity-launch privilege.
            launchAlarmActivity()
            Log.d("WakeCheckDebug", "AlarmForegroundService: started immediate activity for alarmId=$currentAlarmId isWakeCheckLaunch=$isWakeCheckLaunch")

            // Also schedule delayed launch as backup
            if (launcherScheduled.compareAndSet(false, true)) {
                bgHandler.postDelayed(activityLauncher, 50) // Reduced delay to 50ms for faster response
            }
        }

            // Keep service alive
            START_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand crash: ${e.message}", e)
            START_STICKY
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(duckReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(resumeReceiver) } catch (_: Exception) {}
        try { unlockReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { dismissReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        unlockReceiver = null
        dismissReceiver = null

        stopAlarm()

        // Restore audio mode and any saved volume
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            restorePreviousVolumeIfNeeded()
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}

        restoreInterruptionFilterIfNeeded()

        // Clear any remaining notifications
        clearNextAlarmNotification()

        try {
            if (audioFocusRequest != null) {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    am.abandonAudioFocusRequest(audioFocusRequest!!)
                } else {
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus(null)
                }
                audioFocusRequest = null
            }
        } catch (_: Exception) {}

        try { bgHandler.removeCallbacks(notificationReposter) } catch (_: Exception) {}
        try { bgHandler.removeCallbacks(activityLauncher) } catch (_: Exception) {}
        try { bgHandler.removeCallbacks(notificationChecker) } catch (_: Exception) {}

        try { bgThread.quit() } catch (_: Exception) {}

        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ========= Playback =========

    private fun getCachedRingtoneUri(context: Context, originalUri: Uri, alarmId: Int): Uri {
        if (!originalUri.toString().startsWith("content://")) {
            Log.d(TAG, "Not a content URI, using original: $originalUri")
            return originalUri
        }

        return try {
            val deviceContext = context.createDeviceProtectedStorageContext()

            val cacheFile = File(deviceContext.filesDir, "ringtone_$alarmId.mp3")

            if (cacheFile.exists() && cacheFile.length() > 0) {
                val cachedUri = Uri.fromFile(cacheFile)
                Log.d(TAG, "Using cached ringtone: ${cacheFile.absolutePath}")
                cachedUri
            } else {
                Log.w(TAG, "No cached ringtone found, falling back to silence")
                val silenceResourceId = R.raw.silence_no_sound
                if (silenceResourceId != 0) {
                    "android.resource://${context.packageName}/$silenceResourceId".toUri()
                } else {
                    "android.resource://${context.packageName}/raw/silence_no_sound".toUri()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing cached ringtone, using silence fallback", e)
            val silenceResourceId = R.raw.silence_no_sound
            if (silenceResourceId != 0) {
                "android.resource://${context.packageName}/$silenceResourceId".toUri()
            } else {
                "android.resource://${context.packageName}/raw/silence_no_sound".toUri()
            }
        }
    }

    private fun getEffectiveRingtoneUri(): Uri {
        return try {
            // Try to use the provided ringtone URI first
            this.ringtoneUri?.let { return it }

            // Fallback to silence_no_sound if no ringtone is set
            val silenceResourceId = R.raw.silence_no_sound
            if (silenceResourceId != 0) {
                "android.resource://$packageName/$silenceResourceId".toUri()
            } else {
                "android.resource://$packageName/raw/silence_no_sound".toUri()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting effective ringtone, using silence fallback", e)
            // Final fallback to silence_no_sound
            val silenceResourceId = R.raw.silence_no_sound
            if (silenceResourceId != 0) {
                "android.resource://$packageName/$silenceResourceId".toUri()
            } else {
                "android.resource://$packageName/raw/silence_no_sound".toUri()
            }
        }
    }

    private fun startPlayback(ringtoneUri: Uri) {
        Log.d(TAG, "Starting playback with URI: $ringtoneUri")

        // DUAL_AUDIO_AUDIT: identity-level signal to distinguish "this service instance's
        // startPlayback() got called twice" (mediaPlayerAlreadyActive=true, but the reset
        // below already stops it before creating a new one - not audible dual audio) from
        // "a second, separate AlarmForegroundService instance/process is also playing"
        // (different servicePid/serviceInstance from a concurrent log line - genuine dual
        // audio). Logged into the unified event buffer so it shows up in the exported
        // forensic report automatically.
        run {
            val mediaPlayerAlreadyActive = try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false }
            val msg = "startPlayback: alarmId=$currentAlarmId servicePid=${android.os.Process.myPid()} " +
                "serviceInstance=${System.identityHashCode(this)} mediaPlayerAlreadyActive=$mediaPlayerAlreadyActive uri=$ringtoneUri"
            Log.d("DUAL_AUDIO_AUDIT", msg)
            try { com.vaishnava.alarm.ExtremeForensicLogger.logEvent("DUAL_AUDIO_AUDIT", msg) } catch (_: Exception) { }
        }

        // Check if this is silence_no_sound - if so, skip playback entirely
        val isSilenceNoSound = ringtoneUri.toString().contains("silence_no_sound")
        if (isSilenceNoSound) {
            Log.d(TAG, "Silence ringtone detected - skipping audio playback")
            playbackStarted = true
            // DPS write: mark as ringing even though silent
            if (!dpsWrittenForThisRing) {
                try {
                    val dps = createDeviceProtectedStorageContext()
                    val prefs = dps.getSharedPreferences("alarm_dps", MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("was_ringing", true)
                        .putInt("ring_alarm_id", currentAlarmId)
                        .putLong("ring_started_at", System.currentTimeMillis())
                        .commit()
                } catch (_: Exception) { }
                dpsWrittenForThisRing = true
            }
            return
        }

        // Reset any existing player
        try { mediaPlayer?.stop() } catch (_: Throwable) {}
        try { mediaPlayer?.release() } catch (_: Throwable) {}
        mediaPlayer = null

        // Get the appropriate ringtone URI (cached if available)
        // CRITICAL FIX: Always use direct URI to prevent cache fallback to device default
        // This is the same fix that resolved DPS missed alarms - apply to ALL alarms
        val useCache = false // NEVER use cache - always use direct URI like DPS missed alarms

        val finalRingtoneUri = if (useCache) {
            Log.d(TAG, "Using cache for normal alarm - cache lookup")
            val cachedUri = getCachedRingtoneUri(this@AlarmForegroundService, ringtoneUri, currentAlarmId)
            if (cachedUri.toString().contains("silence_no_sound")) {
                Log.w(TAG, "Cache returned silence, using provided ringtone URI directly")
                ringtoneUri
            } else {
                cachedUri
            }
        } else {
            // ALWAYS use direct URI - same as DPS missed alarms fix
            Log.d(TAG, "Using direct URI (DPS-style fix) for alarm: $ringtoneUri")
            ringtoneUri
        }

        Log.d(TAG, "Final ringtone URI for playback: $finalRingtoneUri")

        val audioManager: AudioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // Force ringtone mode (unchanged)
        try { audioManager.mode = AudioManager.MODE_RINGTONE } catch (_: Exception) {}

        previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        savePreviousVolume(previousAlarmVolume)

        val maxAlarmVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        playbackVolume = 1.0f
        if (maxAlarmVolume > 0) {
            // 50% floor with retries as per memory requirement
            val targetAlarm = (maxAlarmVolume * 0.5f).toInt().coerceAtLeast(1)

            try {
                fun getCurrentAlarm() = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                var currentAlarm = getCurrentAlarm()

                if (currentAlarm < targetAlarm) {
                    Log.i(TAG, "ALARM stream below 50% ($currentAlarm < $targetAlarm). Raising...")

                    val maxRetries = 3
                    var attempt = 0
                    while (attempt < maxRetries) {
                        try {
                            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetAlarm, 0)
                        } catch (e: Exception) {
                            Log.e(TAG, "setStreamVolume attempt $attempt failed", e)
                        }

                        try { Thread.sleep(120) } catch (_: InterruptedException) {}

                        currentAlarm = getCurrentAlarm()
                        if (currentAlarm >= targetAlarm) {
                            Log.i(TAG, "ALARM raised to $currentAlarm on attempt ${attempt + 1}")
                            break
                        } else {
                            Log.w(TAG, "ALARM still below 50% after attempt ${attempt + 1}: $currentAlarm < $targetAlarm")
                        }
                        attempt++
                    }

                    if (currentAlarm < targetAlarm) {
                        Log.e(TAG, "Failed to raise ALARM to 50% after $maxRetries attempts (final=$currentAlarm)")
                    }
                } else {
                    Log.i(TAG, "ALARM already ≥ 50% ($currentAlarm) — unchanged")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error while enforcing ALARM 50% floor", e)
            }

            // MUSIC stream: same 50% floor logic - keeping media at full gain as per memory requirement
            try {
                val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (maxMusic > 0) {
                    // Keep media at full gain as per memory requirement - no floor enforcement
                    val currentMusic = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    Log.i(TAG, "MUSIC stream at full gain ($currentMusic/$maxMusic) — unchanged")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking MUSIC stream volume", e)
            }
        }

        // Request focus
        val audioAttributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = android.media.AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            ).setAudioAttributes(audioAttributes).build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }

        // Try MediaPlayer first; if it fails, use Ringtone fallback
        try {
            val attrs: AudioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            mp.setDataSource(this, finalRingtoneUri)
            mp.isLooping = true

            // Start volume boosting from 40% minimum using VolumeBooster
            volumeBooster = VolumeBooster(this)
            volumeBooster?.setMinimumVolume()
            mp.setVolume(0.4f, 0.4f) // Start at 40% minimum

            mp.setOnCompletionListener { p ->
                try {
                    if (p.isPlaying) p.stop()
                    p.seekTo(0)
                    p.start()
                    // Don't reset volume - keep current boosted level
                } catch (_: Exception) {
                    val recreated = restartMediaPlayer(p, finalRingtoneUri, attrs)
                    if (recreated != null) mediaPlayer = recreated
                }
            }
            mp.prepare()
            mp.start()

            // Gradually boost volume from 40% to max AFTER audio starts playing
            val alarm = try { alarmStorage.getAlarm(currentAlarmId) } catch (_: Exception) { null }
            val enableGradual = alarm?.enableGradualVolume ?: true
            val enableBoost = alarm?.enableAudioBoost ?: true
            Log.d(TAG, "Audio settings: gradualVolume=$enableGradual, audioBoost=$enableBoost")
            volumeBooster?.boostVolumeGradually(mp, enableGradual, enableBoost)

            ensurePlaybackSilencedIfAwaiting()
            // DPS write: audio actually started playing
            if (!dpsWrittenForThisRing) {
                try {
                    val dps = createDeviceProtectedStorageContext()
                    val prefs = dps.getSharedPreferences("alarm_dps", MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("was_ringing", true)
                        .putInt("ring_alarm_id", currentAlarmId)
                        .putLong("ring_started_at", System.currentTimeMillis())
                        .commit()
                } catch (_: Exception) { }
                dpsWrittenForThisRing = true
            }
            mediaPlayer = mp

            // ======== CRITICAL: start periodic TTS only after playback started ========
            try {
                playbackStarted = true // ensure flag set
                Log.d(TAG, "MediaPlayer started — triggering startPeriodicTTS() for alarmId=$currentAlarmId")
                // Start TTS on background handler to match rest of service scheduling (startPeriodicTTS defers if not ready)
                bgHandler.post { startPeriodicTTS() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to post startPeriodicTTS after media start: ${e.message}")
            }

        } catch (_: Exception) {
            // Ringtone fallback - try the provided ringtone first, not device default
            try {
                var fallbackUri = finalRingtoneUri
                // CRITICAL FIX: Don't fall back to device default alarm - use the provided ringtone
                // The cache mechanism may have failed, but we still want the selected ringtone

                val ring: Ringtone = try {
                    RingtoneManager.getRingtone(this, fallbackUri)
                } catch (_: Exception) {
                    // If the provided URI fails, try the original ringtoneUri directly
                    Log.w(TAG, "Fallback URI failed, trying original ringtoneUri: $ringtoneUri")
                    RingtoneManager.getRingtone(this, ringtoneUri)
                }

                ring.audioAttributes = audioAttributes
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ring.isLooping = true
                }
                ring.play()
                ensurePlaybackSilencedIfAwaiting()
                if (!dpsWrittenForThisRing) {
                    try {
                        val dps = createDeviceProtectedStorageContext()
                        val prefs = dps.getSharedPreferences("alarm_dps", MODE_PRIVATE)
                        prefs.edit()
                            .putBoolean("was_ringing", true)
                            .putInt("ring_alarm_id", currentAlarmId)
                            .putLong("ring_started_at", System.currentTimeMillis())
                            .commit()
                    } catch (_: Exception) { }
                    dpsWrittenForThisRing = true
                }
                ringtoneFallback = ring

                // ======== CRITICAL: start periodic TTS only after ringtone started ========
                try {
                    playbackStarted = true
                    Log.d(TAG, "Ringtone fallback started — triggering startPeriodicTTS() for alarmId=$currentAlarmId")
                    bgHandler.post { startPeriodicTTS() }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to post startPeriodicTTS after ringtone start: ${e.message}")
                }
            } catch (_: Exception) { }
        }
    }

    private fun ensurePlaybackSilencedIfAwaiting() {
        if (!awaitingResume) {
            playbackStarted = true
            return
        }
        Log.d("WakeCheckDebug", "AlarmForegroundService.ensurePlaybackSilencedIfAwaiting alarmId=$currentAlarmId awaitingResume=$awaitingResume")
        try { mediaPlayer?.setVolume(0f, 0f) } catch (_: Exception) { }
        try { ringtoneFallback?.stop() } catch (_: Exception) { }
        try { mediaPlayer?.pause() } catch (_: Exception) { }
    }

    private fun resumePlaybackIfNeeded() {
        Log.d("WakeCheckDebug", "AlarmForegroundService.resumePlaybackIfNeeded alarmId=$currentAlarmId")

        // If no player exists yet (typical for wake-check launches where we suppressed playback),
        // start playback now using the same effective URI as initial normal alarms.
        if (mediaPlayer == null && ringtoneFallback == null) {
            try {
                val effectiveUri: Uri = this.ringtoneUri ?: run {
                    val dps = createDeviceProtectedStorageContext() ?: this
                    val prefs = dps.getSharedPreferences("direct_boot_alarm_prefs", MODE_PRIVATE)
                    val stored = prefs.getString("direct_boot_ringtone_$currentAlarmId", null)
                    stored?.toUri() ?: run {
                        val silenceResourceId = R.raw.silence_no_sound
                        if (silenceResourceId != 0) {
                            "android.resource://$packageName/$silenceResourceId".toUri()
                        } else {
                            "android.resource://$packageName/raw/silence_no_sound".toUri()
                        }
                    }
                }
                Log.d("WakeCheckDebug", "AlarmForegroundService.resumePlaybackIfNeeded starting fresh playback for alarmId=$currentAlarmId")
                startPlayback(effectiveUri)
            } catch (_: Exception) { }
        }

        playbackStarted = true
        try { mediaPlayer?.setVolume(playbackVolume, playbackVolume) } catch (_: Exception) { }
        try { mediaPlayer?.start() } catch (_: Exception) { }
        try {
            if (ringtoneFallback != null && !(ringtoneFallback?.isPlaying ?: false)) {
                ringtoneFallback?.play()
            }
        } catch (_: Exception) { }
        // Check if the selected ringtone is silence_no_sound before vibrating
        val isSilenceNoSound = (ringtoneUri?.toString() ?: "").contains("silence_no_sound")
        if (!isSilenceNoSound) {
            if (vibrator == null) {
                vibrator = getSystemService(Vibrator::class.java)
            }
            try { 
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(longArrayOf(0, 500, 500), 0)
                }
            } catch (_: Exception) { }
        } else {
            Log.d(TAG, "Silence ringtone detected - skipping vibration")
        }

        writePendingResumeFlag(currentAlarmId, false)
    }

    private fun stopMediaPlayer() {
        try { mediaPlayer?.stop() } catch (_: Throwable) {}
        try { mediaPlayer?.release() } catch (_: Throwable) {}
        mediaPlayer = null
        try { ringtoneFallback?.stop() } catch (_: Throwable) {}
        ringtoneFallback = null
        try { volumeBooster?.stopBoost() } catch (_: Throwable) {}
        volumeBooster = null
    }

    private fun stopVibration() {
        try { vibrator?.cancel() } catch (_: Exception) {}
    }

    fun syncAudioBoostToCurrentVolume() {
        try {
            volumeBooster?.syncBoostToCurrentVolume()
            Log.d(TAG, "Audio boost synced to current device volume")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing boost: ${e.message}")
        }
    }

    private fun writePendingResumeFlag(alarmId: Int, value: Boolean) {
        runCatching {
            val dps = createDeviceProtectedStorageContext() ?: this
            val prefs = dps.getSharedPreferences("alarm_dps", MODE_PRIVATE)
            prefs.edit().putBoolean("wakecheck_resume_pending_$alarmId", value).commit()
        }
    }

    private fun restartMediaPlayer(
        originalPlayer: MediaPlayer,
        ringtoneUri: Uri,
        attrs: AudioAttributes
    ): MediaPlayer? {
        return try {
            try { originalPlayer.stop() } catch (_: Exception) {}
            try { originalPlayer.release() } catch (_: Exception) {}

            val newPlayer = MediaPlayer()
            newPlayer.setAudioAttributes(attrs)
            newPlayer.setDataSource(this, ringtoneUri)
            newPlayer.prepare()
            newPlayer.isLooping = true
            newPlayer.setVolume(playbackVolume, playbackVolume)
            newPlayer.start()
            newPlayer
        } catch (_: Exception) {
            null
        }
    }

    // ---------- FOR FORENSIC TTS SUBSYSTEM (REPLACE existing TTS fields & functions) ----------

    private var tts: android.speech.tts.TextToSpeech? = null
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) } // MUST use main/UI thread for TTS
    private var isPeriodicTTSRunning = false
    private var ttsPeriodicRunnable: Runnable? = null
    private var ttsReady = false
    private var ttsInitializing = false

    private val ttsPauseMs: Long = 15_000L // 15 seconds between announcements

    private fun ttsForensicLog(msg: String) {
        Log.d(TAG, "TTS-FOR: $msg")
        try { writePersistentLog("TTS-FOR: $msg") } catch (_: Exception) {}
    }

    private fun formatCanonicalTtsNumber(value: Int): String {
        val ones = arrayOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen"
        )
        val tens = arrayOf("", "", "twenty", "thirty", "forty", "fifty")
        return when {
            value < 0 -> value.toString()
            value < 20 -> ones[value]
            value < 60 -> {
                val tensPart = tens[value / 10]
                val onesPart = value % 10
                if (onesPart == 0) tensPart else "$tensPart ${ones[onesPart]}"
            }
            else -> value.toString()
        }
    }

    private fun buildCanonicalTimeAnnouncement(): String {
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR)
        val hour = if (h == 0) 12 else h
        val m = cal.get(Calendar.MINUTE)
        val hourStr = formatCanonicalTtsNumber(hour)
        val minuteStr = if (m == 0) "" else " ${formatCanonicalTtsNumber(m)}"
        val ampm = if (cal.get(Calendar.AM_PM) == Calendar.AM) "A M" else "P M"
        return "The time is $hourStr$minuteStr $ampm. Time to wake up."
    }

    private fun buildAlarmTtsParams(): Bundle = Bundle().apply {
        putString(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM.toString())
        putString(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_VOLUME, "1.0")
    }

    private fun selectCanonicalTtsVoice(engine: android.speech.tts.TextToSpeech): android.speech.tts.Voice? {
        return runCatching {
            engine.voices
                ?.asSequence()
                ?.filter { voice ->
                    voice.locale?.language.equals(Locale.ENGLISH.language, ignoreCase = true) &&
                        !voice.isNetworkConnectionRequired
                }
                ?.sortedWith(
                    compareByDescending<android.speech.tts.Voice> {
                        voice -> voice.locale?.country.equals(Locale.US.country, ignoreCase = true)
                    }.thenByDescending { voice ->
                        voice.name.contains("en-us", ignoreCase = true) ||
                            voice.name.contains("en_us", ignoreCase = true)
                    }.thenBy { voice ->
                        voice.latency
                    }.thenByDescending { voice ->
                        voice.quality
                    }.thenBy { voice ->
                        voice.name
                    }
                )
                ?.firstOrNull()
        }.getOrNull()
    }

    private fun configureCanonicalTts(engine: android.speech.tts.TextToSpeech) {
        try {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            ttsForensicLog("TTS configured for alarm audio attributes")
        } catch (e: Exception) {
            ttsForensicLog("Failed to apply alarm audio attributes: ${e.message}")
        }

        try { engine.setSpeechRate(1.0f) } catch (_: Exception) {}
        try { engine.setPitch(1.0f) } catch (_: Exception) {}

        val canonicalVoice = selectCanonicalTtsVoice(engine)
        if (canonicalVoice != null) {
            try {
                engine.voice = canonicalVoice
                ttsForensicLog(
                    "TTS canonical voice selected: name=${canonicalVoice.name} locale=${canonicalVoice.locale}"
                )
            } catch (e: Exception) {
                ttsForensicLog("Failed to set canonical voice: ${e.message}")
            }
        } else {
            ttsForensicLog("TTS canonical voice unavailable; using engine default voice")
        }
    }

    private fun resetTTSInstance(reason: String) {
        ttsForensicLog("Resetting TTS instance: $reason")
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ttsReady = false
        ttsInitializing = false
    }

    private fun ensureTTSInitialized(onReady: (() -> Unit)? = null) {
        mainHandler.post {
            try {
                if (ttsReady && tts != null) {
                    onReady?.invoke()
                    return@post
                }

                if (ttsInitializing) {
                    ttsForensicLog("TTS init already in progress")
                    return@post
                }

                if (tts != null) {
                    resetTTSInstance("stale_unready_instance")
                }

                ttsInitializing = true
                ttsForensicLog("Initializing TTS instance")
                tts = android.speech.tts.TextToSpeech(this@AlarmForegroundService) { status ->
                    mainHandler.post {
                        ttsInitializing = false
                        if (status == android.speech.tts.TextToSpeech.SUCCESS && tts != null) {
                            try {
                                tts?.setLanguage(Locale.US)
                            } catch (_: Exception) {}
                            try {
                                tts?.let(::configureCanonicalTts)
                            } catch (e: Exception) {
                                ttsForensicLog("Failed to configure canonical TTS: ${e.message}")
                            }
                            ttsReady = true
                            ttsForensicLog("TTS init SUCCESS")
                            onReady?.invoke()
                        } else {
                            ExtremeForensicLogger.Chrona.fatal(this@AlarmForegroundService, -1, "TTS init failed with status $status")
                            resetTTSInstance("init_failed_status_$status")
                        }
                    }
                }
            } catch (e: Exception) {
                ttsInitializing = false
                ExtremeForensicLogger.Chrona.fatal(this@AlarmForegroundService, -1, "ensureTTSInitialized exception: ${e.message}")
                resetTTSInstance("ensure_exception_${e.message}")
            }
        }
    }

    private fun startPeriodicTTS() {
        // DEFENSIVE GUARD: defer start until playback is ready
        if (!playbackStarted || awaitingResume || currentAlarmId == -1) {
            Log.d(TAG, "startPeriodicTTS deferred (playbackStarted=$playbackStarted, awaitingResume=$awaitingResume)")
            try { bgHandler.postDelayed({ startPeriodicTTS() }, 500L) } catch (_: Exception) {}
            return
        }

        // Prevent multipl  e concurrent loops
        if (isPeriodicTTSRunning) return
        isPeriodicTTSRunning = true
        ttsForensicLog("startPeriodicTTS: STARTED for alarmId=$currentAlarmId")

        ttsPeriodicRunnable = object : Runnable {
            override fun run() {
                if (!isPeriodicTTSRunning || !playbackStarted || awaitingResume) {
                    isPeriodicTTSRunning = false
                    return
                }

                ensureTTSInitialized {
                    mainHandler.post {
                        try {
                            if (ttsReady && tts != null) {
                                if (tts?.isSpeaking != true) {
                                    val message = buildCanonicalTimeAnnouncement()
                                    val utteranceId = "TTS_${System.currentTimeMillis()}"

                                    ttsForensicLog("Speaking canonically: '$message'")
                                    val speakResult = tts?.speak(
                                        message,
                                        android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                                        buildAlarmTtsParams(),
                                        utteranceId
                                    ) ?: android.speech.tts.TextToSpeech.ERROR
                                    if (speakResult == android.speech.tts.TextToSpeech.ERROR) {
                                        ttsForensicLog("speak() returned ERROR; forcing TTS rebind")
                                        resetTTSInstance("speak_returned_error")
                                    }
                                } else {
                                    ttsForensicLog("Skipping announcement because TTS is already speaking")
                                }
                            } else {
                                ttsForensicLog("Skipping announcement because TTS is not ready")
                            }
                        } catch (e: Exception) {
                            ttsForensicLog("Speak exception: ${e.message}")
                            resetTTSInstance("speak_exception_${e.message}")
                        }

                        if (isPeriodicTTSRunning) {
                            ttsPeriodicRunnable?.let { bgHandler.postDelayed(it, ttsPauseMs) }
                        }
                    }
                }
            }
        }

        bgHandler.post(ttsPeriodicRunnable!!)
    }

    private fun stopPeriodicTTS() {
        mainHandler.post {
            ExtremeForensicLogger.Chrona.log(this, "TTS", "Stopping periodic TTS")
            isPeriodicTTSRunning = false
            ttsPeriodicRunnable?.let { try { bgHandler.removeCallbacks(it) } catch (_: Exception) {} }
            try { tts?.stop() } catch (_: Exception) {}
        }
    }

    // ========= Notifications =========

    private fun buildNotification(): Notification {
        createNotificationChannel()
        val fullScreen: PendingIntent = createFullScreenIntent()

        val inWakeCheckGate = isWakeCheckLaunch && awaitingResume
        ExtremeForensicLogger.Chrona.log(this, "WakeCheckDebug", "AlarmForegroundService.buildNotification alarmId=$currentAlarmId inWakeCheckGate=$inWakeCheckGate")

        // Create delete intent for notification dismiss handling (reposts notification without stopping alarm)
        val deleteIntent = Intent(this, NotificationRepostReceiver::class.java).apply {
            action = Constants.ACTION_NOTIFICATION_DISMISSED
            putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
            putExtra("ALARM_ID", currentAlarmId)
        }
        val deletePendingIntent = PendingIntent.getBroadcast(
            this, currentAlarmId + 3000, deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, "alarm_channel_v2")
            .setSmallIcon(  R.drawable.ic_notification)
            .setColor("#1976D2".toColorInt())
            .setOngoing(true)
            // Alarm audio/vibration come from the service; this notification is reposted every
            // REPOST_DELAY_MS for persistence, so alert only once to avoid a re-vibrate on each repost.
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreen, true)
            .setDeleteIntent(deletePendingIntent) // Handle notification dismiss

        if (inWakeCheckGate) {
            builder
                .setContentTitle("Wake-up check active")
                .setContentText("Confirm that you're awake in the app")
                .setCategory(NotificationCompat.CATEGORY_SYSTEM)
        } else {
            builder
                .setContentTitle("Alarm Active")
                .setContentText("Your alarm is ringing - Tap to stop")
                .setDefaults(NotificationCompat.DEFAULT_ALL) // Enable sound, vibration, lights
        }

        return builder.build()
    }

    // CRITICAL FIX: Cache snapshot to avoid blocking main thread
    @Volatile private var cachedSnapshot: com.vaishnava.alarm.sequencer.DpsMissionMirror.Snapshot? = null

    private fun createFullScreenIntent(): PendingIntent {
        var localMissionType: String? = currentMissionType
        var localMissionPassword: String? = currentMissionPassword
        var localIsProtected: Boolean = currentIsProtected
        var localMissionId: String? = null
        var isSequencer = currentMissionType == "sequencer"
        var localEnableSnooze = false

        try {
            val alarm = alarmStorage.getAlarm(currentAlarmId)
            if (alarm != null) {
                // CRITICAL FIX: Use cached snapshot - never block main thread for DPS reads
                // The snapshot is updated asynchronously on background handler
                val snapshot = cachedSnapshot

                // If cache is empty, load asynchronously (don't wait synchronously)
                if (cachedSnapshot == null) {
                    bgHandler.post {
                        try {
                            Log.d(TAG, "NOTIFICATION_SNAPSHOT_CACHE: Loading snapshot asynchronously for alarmId=$currentAlarmId")
                            cachedSnapshot = DpsMissionMirror(this@AlarmForegroundService).readSnapshot(currentAlarmId)
                        } catch (e: Exception) {
                            Log.w(TAG, "NOTIFICATION_SNAPSHOT_CACHE_ERROR: Failed to load snapshot: ${e.message}")
                        }
                    }
                }

                val activeMission = snapshot?.activeMissionId
                
                if (!activeMission.isNullOrBlank()) {
                    localMissionType = activeMission
                    localMissionId = activeMission
                    localMissionPassword = if (activeMission == "password") MissionSequencer.DEFAULT_GLOBAL_PASSWORD else ""
                    isSequencer = true
                } else if (alarm.missionType == "sequencer") {
                    val missionNamesRaw = alarm.missionPassword ?: ""
                    val firstId = SequencerMissionCodec.parseMissionSequence(missionNamesRaw, alarm.id).firstOrNull()?.id ?: "tap"
                    localMissionType = firstId
                    localMissionId = firstId
                    localMissionPassword = if (firstId == "password") MissionSequencer.DEFAULT_GLOBAL_PASSWORD else ""
                    isSequencer = true
                } else if (alarm.missionType == "none" || alarm.missionType.isNullOrBlank()) {
                    // CRITICAL FIX: Ensure no-mission alarms are explicitly cleared
                    localMissionType = ""
                    localMissionPassword = ""
                    isSequencer = false
                } else {
                    localMissionType = alarm.missionType
                    localMissionPassword = alarm.missionPassword
                    isSequencer = false
                }
                localIsProtected = alarm.isProtected
                localEnableSnooze = alarm.enableSnooze
            }
        } catch (_: Exception) {}

        val intent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            putExtra(AlarmReceiver.ALARM_ID, currentAlarmId)
            putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, ringtoneUri?.toString())
            putExtra("mission_type", localMissionType)
            putExtra("mission_password", localMissionPassword)
            putExtra("mission_id", localMissionId)
            putExtra(MissionSequencer.EXTRA_FROM_SEQUENCER, isSequencer)
            putExtra("is_protected", localIsProtected)
            putExtra("enable_snooze", localEnableSnooze)
            // This intent is the one actually launched for every alarm ring, including
            // wake-check follow-ups (launchAlarmActivity's non-resume branch calls this
            // directly). It never carried from_wake_check, so AlarmActivity always saw
            // isWakeCheckLaunchState=false on a follow-up fire, which skips the wake-up
            // gate screen entirely - if no mission is configured nothing else renders
            // either, producing a blank screen on every wake-check follow-up.
            putExtra("from_wake_check", isWakeCheckLaunch)
        }
        Log.d(
            "WAKECHECK_BUG",
            "createFullScreenIntent: alarmId=$currentAlarmId isWakeCheckLaunch=$isWakeCheckLaunch " +
                "missionType=$localMissionType isSequencer=$isSequencer"
        )

        return PendingIntent.getActivity(
            this,
            currentAlarmId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            // CRITICAL Fix: Delete existing channel to remove sound permanently
            // Android doesn't allow updating notification sound after channel creation
            try {
                notificationManager.deleteNotificationChannel("alarm_channel")
            } catch (_: Exception) { }

            val channel = NotificationChannel(
                "alarm_channel_v2", // Use new ID to ensure no sound
                "Alarm Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for active alarms with full-screen alerts"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
                // CRITICAL Fix: Remove notification sound to prevent dual audio
                // The service already plays the correct ringtone, notification shouldn't play sound
                setSound(null, null) // Disable notification sound completely
            }

            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, content: String) {
        val nm: NotificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "next_alarm_high",
                "Next Alarm Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows information about your next alarm"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
                // Set proper sound for next alarm notifications
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(soundUri, audioAttributes)
                // Allow bypassing DND for important notifications
                setBypassDnd(true)
                // Show on lock screen
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }

        // Create a person for messaging style
        val me = androidx.core.app.Person.Builder()
            .setName("Alarm Assistant")
            .setImportant(true)
            .build()

        val messaging = NotificationCompat.MessagingStyle(me)
            .addMessage(content, System.currentTimeMillis(), me)
            .setConversationTitle("Alarm Reminder")

        // Build the notification with enhanced visibility
        val n: Notification = NotificationCompat.Builder(this, "next_alarm_high")
            .setSmallIcon(R.drawable.ic_notification)
            .setColor("#FF5722".toColorInt()) // Use orange color for urgency
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(messaging)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL) // Enable sound, vibration, lights
            .setOnlyAlertOnce(false) // Allow multiple alerts for different time windows
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(false) // Allow dismissal
            .setTimeoutAfter(30000) // Auto-dismiss after 30 seconds
            .build()

        nm.notify(2, n)
    }

    private fun clearNextAlarmNotification() {
        val nm: NotificationManager = getSystemService(NotificationManager::class.java)
        nm.cancel(2)
        lastNotifiedAlarmId = null
    }

    // ========= Next-alarm helpers =========

    private fun showAlarmNotificationIfNeeded() {
        try {
            val nextAlarm: Alarm? = getNextScheduledAlarm()
            if (nextAlarm != null) {
                val timeUntil: Double = getTimeUntilNextAlarmInMinutes(nextAlarm)
                val nowMs: Long = System.currentTimeMillis()

                // Enhanced timing windows for better user experience
                when {
                    timeUntil in 0.5..1.0 && lastNotifiedAlarmId != nextAlarm.id -> {
                        showNotification(
                            "Alarm Soon",
                            "Your alarm will ring in 1 minute"
                        )
                        lastNotifiedAlarmId = nextAlarm.id
                        lastNotificationTime = nowMs
                    }
                    timeUntil in 0.25..0.5 && lastNotifiedAlarmId == nextAlarm.id -> {
                        showNotification(
                            "Alarm Very Soon",
                            "Your alarm will ring in 30 seconds!"
                        )
                        lastNotificationTime = nowMs
                    }
                    timeUntil in 0.0..0.25 && (nowMs - lastNotificationTime > 15000) -> {
                        showNotification(
                            "Alarm Imminent",
                            "Your alarm will ring in 10 seconds!"
                        )
                        lastNotificationTime = nowMs
                    }
                    timeUntil > 2.0 || timeUntil < 0.0 -> {
                        clearNextAlarmNotification()
                        lastNotifiedAlarmId = null
                    }
                }
            } else {
                // No alarms scheduled, clear any existing notifications
                clearNextAlarmNotification()
                lastNotifiedAlarmId = null
            }
        } catch (_: Exception) { }
    }

    private fun getNextScheduledAlarm(): Alarm? {
        val alarms: List<Alarm> = alarmStorage.getAlarms().filter { it.isEnabled }
        if (alarms.isEmpty()) return null

        var next: Alarm? = null
        var nextTime: Long = Long.MAX_VALUE
        for (a in alarms) {
            val t: Long = calculateNextAlarmTime(a)
            if (t < nextTime) {
                nextTime = t
                next = a
            }
        }
        return next
    }

    private fun calculateNextAlarmTime(alarm: Alarm): Long {
        val now: Calendar = Calendar.getInstance()
        if (alarm.days.isNullOrEmpty() && !alarm.repeatDaily && alarm.alarmTime > now.timeInMillis) {
            return alarm.alarmTime
        }
        val alarmCal: Calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val days: List<Int>? = alarm.days
        if (!days.isNullOrEmpty()) {
            val currentDay: Int = now.get(Calendar.DAY_OF_WEEK)
            val currentHour: Int = now.get(Calendar.HOUR_OF_DAY)
            val currentMinute: Int = now.get(Calendar.MINUTE)

            if (days.contains(currentDay)) {
                if (alarm.hour > currentHour || (alarm.hour == currentHour && alarm.minute > currentMinute)) {
                    return alarmCal.timeInMillis
                }
            }

            for (i in 1..7) {
                val checkDay: Int = (currentDay + i - 1) % 7 + 1
                if (days.contains(checkDay)) {
                    alarmCal.add(Calendar.DAY_OF_YEAR, i)
                    return alarmCal.timeInMillis
                }
            }
        } else {
            if (alarmCal.timeInMillis <= now.timeInMillis) {
                alarmCal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return alarmCal.timeInMillis
        }

        return alarmCal.timeInMillis
    }

    private fun getTimeUntilNextAlarmInMinutes(alarm: Alarm): Double {
        val nextTime: Long = calculateNextAlarmTime(alarm)
        val now: Long = System.currentTimeMillis()
        val diff: Long = nextTime - now
        return diff.toDouble() / (1000.0 * 60.0)
    }

    // ========= Screen wake =========

    private fun turnScreenOn() {
        try {
            val pm: PowerManager = getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            val wl: PowerManager.WakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "AlarmApp::SvcScreenOn"
            )
            wl.acquire(3000)
            wl.release()
        } catch (_: Exception) { }
    }

    // ========= Volume save/restore helpers =========

    private fun savePreviousVolume(vol: Int) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().putInt(KEY_PREV_VOLUME, vol).commit()
        } catch (_: Exception) { }
    }

    private fun restorePreviousVolumeIfNeeded() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            if (prefs.contains(KEY_PREV_VOLUME)) {
                val prev = prefs.getInt(KEY_PREV_VOLUME, -1)
                if (prev >= 0) {
                    val am = getSystemService(AUDIO_SERVICE) as? AudioManager
                    val max = am?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: -1
                    val toSet = when {
                        max in 0..<prev -> max
                        else -> prev
                    }
                    try { am?.setStreamVolume(AudioManager.STREAM_ALARM, toSet, 0) } catch (_: Exception) {}
                }
                prefs.edit().remove(KEY_PREV_VOLUME).commit()
            } else {
                // fallback: if previousAlarmVolume variable was set earlier, restore that
                if (previousAlarmVolume >= 0) {
                    try {
                        val am = getSystemService(AUDIO_SERVICE) as? AudioManager
                        am?.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) { }
    }

    private fun ensureDndAllowsAlarm() {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (!nm.isNotificationPolicyAccessGranted) {
                Log.w(TAG, "Notification policy access not granted; cannot override DND")
                return
            }

            if (previousInterruptionFilter == null) {
                previousInterruptionFilter = nm.currentInterruptionFilter
            }

            val current = nm.currentInterruptionFilter
            if (current != NotificationManager.INTERRUPTION_FILTER_ALARMS &&
                current != NotificationManager.INTERRUPTION_FILTER_ALL) {
                try {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
                    Log.d(TAG, "Set interruption filter to ALARMS for active alarm")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set interruption filter", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureDndAllowsAlarm crash", e)
        }
    }

    private fun restoreInterruptionFilterIfNeeded() {
        val previous = previousInterruptionFilter ?: return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm == null || !nm.isNotificationPolicyAccessGranted) {
                previousInterruptionFilter = null
                return
            }
            try {
                nm.setInterruptionFilter(previous)
                Log.d(TAG, "Restored interruption filter to $previous")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore interruption filter", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "restoreInterruptionFilterIfNeeded crash", e)
        } finally {
            previousInterruptionFilter = null
        }
    }

    private val relaunchWatchdogHandler = Handler(Looper.getMainLooper())
    private val relaunchWatchdogRunnable = object : Runnable {
        override fun run() {
            if (currentAlarmId != -1 && !isActivityForeground && !isMissedAlarm) {
                // FLICKER FIX: pressing power mid-alarm turns the display off, and re-sending the
                // full-screen intent every 1.2s RECREATES the activity surface each time -> that
                // surface rebuild is the on/off flashing the user sees. Split the two cases:
                val pm = getSystemService(PowerManager::class.java)
                val screenOn = try { pm?.isInteractive == true } catch (_: Exception) { true }
                if (!screenOn) {
                    // Display is off (user pressed power / OS slept it). Wake ONLY the display -
                    // turnScreenOn() uses a wakelock and never recreates the activity surface, so a
                    // still-alive AlarmActivity (shown over the keyguard) reappears with no flicker.
                    // If it was actually killed, the screen is now on and the next tick's screen-on
                    // branch below re-launches it. Behavior is unchanged (screen is still forced on).
                    Log.d(TAG, "OPPO_COLOROS_RELAUNCH_GUARD: screen off - waking display only (no surface recreate)")
                    turnScreenOn()
                } else {
                    // Screen is on but our activity isn't in front: it was covered or killed, so
                    // bring it back via the full-screen intent (the real OEM-survival path).
                    Log.d(TAG, "OPPO_COLOROS_RELAUNCH_GUARD: AlarmActivity is not in foreground! Re-launching UI via PendingIntent...")
                    try {
                        createFullScreenIntent().send()
                    } catch (_: Exception) {
                        launchAlarmActivity()
                    }
                }
            }
            if (currentAlarmId != -1 && !isMissedAlarm) {
                relaunchWatchdogHandler.postDelayed(this, 1200L)
            }
        }
    }

    private fun startRelaunchWatchdog() {
        relaunchWatchdogHandler.removeCallbacks(relaunchWatchdogRunnable)
        relaunchWatchdogHandler.postDelayed(relaunchWatchdogRunnable, 1200L)
    }

    private fun stopRelaunchWatchdog() {
        relaunchWatchdogHandler.removeCallbacks(relaunchWatchdogRunnable)
        setActivityForeground(false)
    }
}
