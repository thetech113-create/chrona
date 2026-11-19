@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)


package com.vaishnava.alarm

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.vaishnava.alarm.data.Alarm
import com.vaishnava.alarm.data.WakeCheckStore
import com.vaishnava.alarm.data.isOneShot
import com.vaishnava.alarm.sequencer.DpsMissionMirror
import com.vaishnava.alarm.sequencer.FaceMissionSupport
import com.vaishnava.alarm.sequencer.MathDifficulty
import com.vaishnava.alarm.sequencer.MathMissionConfig
import com.vaishnava.alarm.sequencer.MathMissionSupport
import com.vaishnava.alarm.sequencer.MissionQueueStore
import com.vaishnava.alarm.sequencer.MissionSequencer
import com.vaishnava.alarm.sequencer.SequencerMissionCodec
import com.vaishnava.alarm.sequencer.SttMissionSupport
import com.vaishnava.alarm.util.PermissionUtils
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

private data class WakeCheckParticle(
    val xFraction: Float,
    val yFraction: Float,
    val radiusFraction: Float,
    val alpha: Float
)

@Suppress("KotlinConstantConditions", "RemoveSingleExpressionStringTemplate")
class AlarmActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AlarmActivity"
        const val DEFAULT_GLOBAL_PASSWORD = "IfYouWantYouCanSleep"

        
        private fun writePersistentLog(message: String) {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val context = MainActivity.getInstance()
                val logFile = File(context?.filesDir, "alarm_debug.log")
                val writer = FileWriter(logFile, true)
                writer.append("[$timestamp] [ALARM_ACTIVITY] $message\n")
                writer.close()
            } catch (e: Exception) {
                // Silently fail if we can't write to storage
            }
        }

        // Add external logging for mission events
        fun logMissionEvent(message: String) {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val context = MainActivity.getInstance()
                val logFile = File(context?.filesDir, "alarm_debug.log")
                val writer = FileWriter(logFile, true)
                writer.append("[$timestamp] [MISSION] $message\n")
                writer.close()
            } catch (e: Exception) {
                // Silently fail if we can't write to storage
            }
        }
    }

    // -------------------- Sequencer Context --------------------
    private var isSequencerMission: Boolean = false
    private var sequencerContext: String = ""
    private var isSequencerComplete: Boolean = false
    private var isMissedAlarm: Boolean = false
    
    // -------------------- DPS/CPS Authority Detection --------------------
    
    private fun Intent?.extractAlarmId(defaultValue: Int = -1): Int {
        val extras = this?.extras ?: return defaultValue
        val rawAlarmId =
            when {
                extras.containsKey(AlarmReceiver.ALARM_ID) -> extras.get(AlarmReceiver.ALARM_ID)
                AlarmReceiver.ALARM_ID != "alarm_id" && extras.containsKey("alarm_id") -> extras.get("alarm_id")
                else -> null
            }

        return when (rawAlarmId) {
            is Int -> rawAlarmId
            is Number -> rawAlarmId.toInt()
            is String -> rawAlarmId.toIntOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    /**
     * Check if alarm is in DPS mode using device-protected storage detection
     */
    private fun isDpsMode(): Boolean {
        val alarmId = intent.extractAlarmId(-1)
        if (alarmId == -1) return false
        
        return try {
            // Use device-protected storage context for DPS detection
            val dpsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                createDeviceProtectedStorageContext() ?: this
            } else this
            
            val dpsPrefs = dpsContext.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
            val dpsAlarmId = dpsPrefs.getInt("current_alarm_id", -1)
            val dpsMissionType = dpsPrefs.getString("direct_boot_mission_type_$alarmId", null)
            
            val isDps = dpsAlarmId != -1 && dpsAlarmId == alarmId
            Log.d(TAG, "DPS_DETECTION: alarmId=$alarmId dpsAlarmId=$dpsAlarmId dpsMissionType=$dpsMissionType isDps=$isDps")
            isDps
        } catch (e: Exception) {
            Log.e(TAG, "AUTHORITY_DETECTION_ERROR: alarmId=$alarmId - ${e.message}")
            false // Default to CPS mode on error
        }
    }

    private fun bindDpsMissionFromSnapshot(alarmId: Int) {
        val snapshot = DpsMissionMirror(this).readSnapshot(alarmId) ?: return
        if (!snapshot.isSequenceComplete && snapshot.activeMissionId != null) {
            currentMissionId = snapshot.activeMissionId
            missionTapEnabled = snapshot.activeMissionId == "tap"
            missionMathEnabled = snapshot.activeMissionId == MathMissionSupport.MISSION_ID
            missionSttEnabled = snapshot.activeMissionId == SttMissionSupport.MISSION_ID
            missionFaceEnabled = snapshot.activeMissionId == FaceMissionSupport.MISSION_ID
            if (missionSttEnabled) {
                sttTargetPhrase = SttMissionSupport.getTargetPhrase(this, alarmId)
            }
            requiredPassword = if (snapshot.activeMissionId == "password") {
                DEFAULT_GLOBAL_PASSWORD
            } else {
                null
            }
            Log.d(
                TAG,
                "DPS_SNAPSHOT_BIND: alarmId=$alarmId activeMissionId=${snapshot.activeMissionId} " +
                    "version=${snapshot.version} state=${snapshot.state}"
            )
        }
    }
    
    /**
     * Get current mission from the appropriate source based on execution context
     */
    private fun getCurrentMissionFromAuthority(): com.vaishnava.alarm.sequencer.MissionSpec? {
        val isDpsMode = isDpsMode()
        
        return if (isDpsMode) {
            try {
                val alarmId = intent.extractAlarmId(-1)
                val snapshot = if (alarmId != -1) DpsMissionMirror(this).readSnapshot(alarmId) else null
                val activeMissionId = snapshot?.activeMissionId
                
                if (snapshot?.isSequenceComplete == true) {
                    Log.d(TAG, "DPS_AUTHORITY: sequence is complete for alarmId=$alarmId")
                    return null
                }

                val dpsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    createDeviceProtectedStorageContext() ?: this
                } else this

                // 1. Try to load from persisted store if active ID is known
                if (activeMissionId != null) {
                    val persistedMission = MissionQueueStore(dpsContext)
                        .loadCurrentMission()
                        ?.first
                        ?.takeIf { it.id == activeMissionId }
                    if (persistedMission != null) {
                        Log.d(TAG, "DPS_AUTHORITY: current mission store matched active mission alarmId=$alarmId missionId=$activeMissionId")
                        return persistedMission
                    }
                }

                // 2. Reconstruct from direct-boot config, defaulting to the first mission if pointer is missing
                val configuredMission = dpsContext
                    .getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
                    .getString("direct_boot_mission_password_$alarmId", null)
                    ?.let { serializedMissionData ->
                        val sequence = SequencerMissionCodec.parseMissionSequence(serializedMissionData, alarmId)
                        if (activeMissionId != null) {
                            sequence.firstOrNull { it.id == activeMissionId }
                        } else {
                            sequence.firstOrNull()
                        }
                    }

                if (configuredMission != null) {
                    val targetId = activeMissionId ?: configuredMission.id
                    Log.d(TAG, "DPS_AUTHORITY: reconstructed mission from config alarmId=$alarmId missionId=$targetId")
                    return configuredMission.copy(
                        params = configuredMission.params + mapOf(
                            "mission_type" to targetId,
                            "alarm_id" to alarmId.toString()
                        )
                    )
                }

                // 3. Fallback to raw active ID if nothing else works
                if (activeMissionId != null) {
                    Log.d(TAG, "DPS_AUTHORITY: snapshot mission authority active alarmId=$alarmId missionId=$activeMissionId version=${snapshot?.version}")
                    return com.vaishnava.alarm.sequencer.MissionSpec(
                        id = activeMissionId,
                        params = mapOf("mission_type" to activeMissionId, "alarm_id" to alarmId.toString()),
                        timeoutMs = if (activeMissionId == "tap") 105_000L else 30_000L,
                        retryCount = 3,
                        sticky = true,
                        retryDelayMs = 1_000L
                    )
                }
                
                Log.d(TAG, "DPS_AUTHORITY: no active snapshot mission authority for alarmId=$alarmId")
                null
            } catch (e: Exception) {
                Log.e(TAG, "DPS_AUTHORITY_ERROR: ${e.message}")
                null
            }
        } else {
            // CPS MODE: Read from an existing sequencer instance only.
            // Never construct a new MissionSequencer here because its init path
            // touches storage and receiver registration during alarm launch.
            try {
                val sequencer =
                    MainActivity.getSafeMissionSequencer()
                        ?: AlarmForegroundService.getForceRestartSequencer()
                val currentMission = sequencer?.getCurrentMission()
                Log.d(
                    TAG,
                    "CPS_AUTHORITY: existingSequencer=${sequencer != null} currentMission=${currentMission?.id}"
                )
                currentMission
            } catch (e: Exception) {
                Log.e(TAG, "CPS_AUTHORITY_ERROR: ${e.message}")
                null
            }
        }
    }

    private fun persistCurrentMissionSnapshot(mission: com.vaishnava.alarm.sequencer.MissionSpec) {
        val sequencer =
            MainActivity.getSafeMissionSequencer()
                ?: AlarmForegroundService.getForceRestartSequencer()

        if (sequencer != null) {
            sequencer.updateCurrentMission(mission)
        } else {
            val storageContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                createDeviceProtectedStorageContext() ?: this
            } else this
            MissionQueueStore(storageContext).saveCurrentMission(mission)
        }

        currentMissionId = mission.id
        missionMathEnabled = mission.id == MathMissionSupport.MISSION_ID
        missionSttEnabled = mission.id == SttMissionSupport.MISSION_ID
        missionFaceEnabled = mission.id == FaceMissionSupport.MISSION_ID
        if (missionSttEnabled) {
            sttTargetPhrase = SttMissionSupport.getTargetPhrase(this, alarmId)
        }
        intent?.putExtra("mission_type", mission.id)
        mission.params.forEach { (key, value) ->
            intent?.putExtra(key, value)
        }
    }

    private fun resolveSafeMissionType(
        rawMissionType: String?,
        rawMissionPassword: String?,
        fallbackMissionId: String? = currentMissionId
    ): String {
        // FAST FAIL: If intent explicitly passes "none" or an empty string, respect it immediately.
        val explicitType = rawMissionType?.trim()?.lowercase(Locale.US)
        if (explicitType == "none" || explicitType == "") {
            return ""
        }
        fun normalize(candidate: String?): String? {
            return when (candidate?.trim()?.lowercase(Locale.US)) {
                "tap" -> "tap"
                "pwd", "password", "pswd" -> "password"
                "math" -> "math"
                "none", "sequencer", "", null -> null
                else -> if (candidate.startsWith("sequencer_mission")) null else candidate.trim().lowercase(Locale.US)
            }
        }

        // Recover the mission string from DPS memory if the OS stripped it from the intent
        val dpsPassword = if (isDpsMode() && rawMissionPassword.isNullOrBlank()) {
            try {
                val dpsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    createDeviceProtectedStorageContext() ?: this
                } else this
                dpsContext.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
                    .getString("direct_boot_mission_password_$alarmId", null)
            } catch (e: Exception) { null }
        } else null

        val activePassword = rawMissionPassword?.takeIf { it.isNotBlank() } ?: dpsPassword
        val normalizedPassword = activePassword?.trim()?.lowercase(Locale.US).orEmpty()
        
        val firstParsedSequence = SequencerMissionCodec.firstMissionId(activePassword)
            ?.let { normalize(it) }

        val resolvedMission = normalize(rawMissionType)
            ?: normalize(fallbackMissionId)
            ?: normalize(getCurrentMissionFromAuthority()?.id)
            ?: firstParsedSequence
            ?: when {
                normalizedPassword.contains("math") -> "math"
                normalizedPassword.contains("tap") -> "tap"
                normalizedPassword.contains("pwd") || normalizedPassword.contains("password") -> "password"
                missionMathEnabled -> "math"
                missionTapEnabled -> "tap"
                requiredPassword != null -> "password"
                isSequencerMission -> "password"
                else -> ""
            }

        Log.d(
            TAG,
            "MISSION_RESOLUTION_FORENSIC: raw=$rawMissionPassword dps=$dpsPassword chronological_first=$firstParsedSequence selected=$resolvedMission"
        )

        return resolvedMission
    }

    private fun explicitPasswordOrNull(rawMissionPassword: String?): String? {
        val candidate = rawMissionPassword?.trim()
        return candidate?.takeIf {
            it.isNotEmpty() && !SequencerMissionCodec.isSequencerConfig(it)
        }
    }

    // -------------------- TTS --------------------
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsMessage: String = ""
    private val ttsHandler = Handler(Looper.getMainLooper())
    // DISABLED: ttsRunnable removed - legacy TTS repeater causing continuous repetition
    private var lastSpokenTime: String = ""

    private val writeSettingsState = mutableStateOf(false)

    // Bring-to-front management for background/swipe scenarios
    private val bringToFrontHandler = Handler(Looper.getMainLooper())
    private var bringToFrontPending = false
    private var lastBringToFrontAttemptAt = 0L
    private val bringToFrontRunnable = Runnable {
        bringToFrontPending = false
        try {
            if (activityDismissed || isDismissed) return@Runnable
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                Log.d(TAG, "BRING_TO_FRONT_SKIP: activity already visible")
                return@Runnable
            }
            bringToFront()
        } catch (_: Exception) { }
    }

    private fun scheduleBringToFront(delayMs: Long) {
        if (activityDismissed || isDismissed || isRequestingPermission) return
        bringToFrontHandler.removeCallbacks(bringToFrontRunnable)
        bringToFrontPending = true
        bringToFrontHandler.postDelayed(bringToFrontRunnable, delayMs)
    }

    private fun startTtsRepeater() {
        // DISABLED: Legacy TTS repeater causing continuous repetition
        // This independent timer conflicted with the forensic TTS system in AlarmForegroundService
        // TTS is now handled exclusively by the service with proper completion guards
        Log.d(TAG, "Legacy TTS repeater disabled - using service-based forensic TTS system")
    }

    private fun exitPreview() {
        if (!isPreview) return
        try {
            activityDismissed = true
            isDismissed = true
            runCatching {
                val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this
                val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                prefs.edit().putLong("preview_exit_block_$alarmId", System.currentTimeMillis()).commit()
            }
            // DISABLED: ttsRunnable cleanup - legacy TTS repeater removed
            try { tts?.stop(); tts?.shutdown(); tts = null } catch (_: Exception) {}
            try { ttsHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
            // CRITICAL FIX: Don't stop AlarmForegroundService for sequencer alarms
            // MissionSequencer will stop the service when all missions are complete
            if (!isSequencerMission) {
                try {
                    val svc = Intent(this@AlarmActivity, AlarmForegroundService::class.java).apply {
                        action = Constants.ACTION_STOP_FOREGROUND_SERVICE
                        putExtra(AlarmReceiver.ALARM_ID, alarmId)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
                } catch (_: Exception) {}
            } else {
                Log.d(TAG, "Skipping AlarmForegroundService stop for sequencer alarm - MissionSequencer will handle it")
            }
            try {
                val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                // Primary fire PI
                runCatching {
                    val piFire = android.app.PendingIntent.getBroadcast(
                        this, alarmId,
                        Intent(this, AlarmReceiver::class.java).apply {
                            action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"; data = android.net.Uri.parse("alarm://$alarmId"); putExtra(AlarmReceiver.ALARM_ID, alarmId)
                        },
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    ); am.cancel(piFire); piFire.cancel()
                }
                // Wake-check follow-up PI
                runCatching {
                    val wake = android.app.PendingIntent.getBroadcast(
                        this, alarmId xor 0x9C0A,
                        Intent(this, AlarmReceiver::class.java).apply {
                            action = "com.vaishnava.alarm.WAKE_UP"; data = android.net.Uri.parse("alarm-wakecheck://$alarmId"); putExtra(AlarmReceiver.ALARM_ID, alarmId)
                        },
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    ); am.cancel(wake); wake.cancel()
                }
                // WAKE_UP backup variants used in restore paths
                runCatching {
                    val wake1 = android.app.PendingIntent.getBroadcast(
                        this, alarmId xor 0x4A11,
                        Intent(this, AlarmReceiver::class.java).apply {
                            action = "com.vaishnava.alarm.WAKE_UP"; data = android.net.Uri.parse("alarm-wakeup://$alarmId"); putExtra(AlarmReceiver.ALARM_ID, alarmId)
                        },
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    ); am.cancel(wake1); wake1.cancel()
                }
                runCatching {
                    val wake2 = android.app.PendingIntent.getBroadcast(
                        this, alarmId xor 0x6B7F,
                        Intent(this, AlarmReceiver::class.java).apply {
                            action = "com.vaishnava.alarm.WAKE_UP"; data = android.net.Uri.parse("alarm-wakeup2://$alarmId"); putExtra(AlarmReceiver.ALARM_ID, alarmId)
                        },
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    ); am.cancel(wake2); wake2.cancel()
                }
                // AlarmClock showIntent (getActivity) used by scheduler
                runCatching {
                    val showPi = android.app.PendingIntent.getActivity(
                        this, alarmId,
                        Intent(this, AlarmActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            putExtra(AlarmReceiver.ALARM_ID, alarmId)
                        },
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    ); am.cancel(showPi); showPi.cancel()
                }
            } catch (_: Exception) {}
        } catch (_: Exception) {}
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) finishAndRemoveTask() else finish()
            overridePendingTransition(0, 0)
        } catch (_: Exception) {}
    }

    private fun requestWriteSettingsPermission() {
        if (Settings.System.canWrite(this)) {
            writeSettingsState.value = true
            return
        }
        runCatching {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun sendDuckForTts() {
        try {
            val duck = Intent("com.vaishnava.alarm.DUCK_ALARM").apply { putExtra("duration", 5000) }
            sendBroadcast(duck)
        } catch (_: Exception) {}
    }

    // CRITICAL FIX: Handle new intents for both single missions and multi-mission sequences
    // Because we use FLAG_ACTIVITY_SINGLE_TOP + REORDER_TO_FRONT, the same AlarmActivity 
    // is reused for subsequent missions, but the intent needs to be updated.
    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        try {
            val previousAlarmId = alarmId
            val previousMissionId = currentMissionId
            val previousSequencer = isSequencerMission
            val previousStarted = classMissionStarted
            setIntent(newIntent)
            val resolvedAlarmId = newIntent.extractAlarmId(alarmId)
            alarmId = if (resolvedAlarmId != -1) resolvedAlarmId else alarmId

            val incomingMissionId = newIntent.getStringExtra("mission_id")
            val incomingMissionType = resolveSafeMissionType(
                newIntent.getStringExtra("mission_type"),
                newIntent.getStringExtra("mission_password"),
                incomingMissionId ?: currentMissionId
            )
            val incomingMissionPassword = newIntent.getStringExtra("mission_password") ?: ""
            val fromSequencer = newIntent.getBooleanExtra(MissionSequencer.EXTRA_FROM_SEQUENCER, isSequencerMission)
            val sequencerComplete = newIntent.getBooleanExtra("sequencer_complete", false)

            Log.d(TAG, "onNewIntent_FIXED: alarmId=$alarmId missionId=$incomingMissionId missionType=$incomingMissionType fromSequencer=$fromSequencer")

            if (sequencerComplete) {
                missionTapEnabled = false
                missionMathEnabled = false
                missionSttEnabled = false
                missionFaceEnabled = false
                requiredPassword = null
                currentMissionId = null
                isSequencerMission = false
                classMissionStarted = false
                activityDismissed = true
                isDismissed = true
                isSequencerComplete = true
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) finishAndRemoveTask() else finish()
                } catch (_: Exception) {}
                return
            }

            val incomingResolvedMissionId = incomingMissionId?.takeIf { !it.startsWith("sequencer_mission") } ?: incomingMissionType

            // PRESERVE PROGRESS: a relaunch of the SAME alarm + SAME mission that is already in
            // progress (notification tap, or the relaunch watchdog re-opening the screen) must keep
            // the current state instead of resetting the mission back to the beginning. A genuinely
            // new sub-mission has a different missionId and still resets below.
            val sameMissionInProgress = previousStarted &&
                alarmId == previousAlarmId &&
                previousMissionId == incomingResolvedMissionId &&
                previousSequencer == fromSequencer

            if (sameMissionInProgress) {
                // Same alarm + same mission already running. Reassigning currentMissionId /
                // isSequencerMission below is what re-triggers the reset LaunchedEffects (which
                // zero tapCount and set missionStarted=false), so on a notification tap or a
                // watchdog relaunch we must touch NOTHING and just leave the live screen alone.
                Log.d(TAG, "onNewIntent_FIXED: same mission in progress - preserving state (missionId=$incomingResolvedMissionId)")
                return
            }

            currentMissionId = incomingResolvedMissionId
            isSequencerMission = fromSequencer
            missionTapEnabled = (incomingMissionType == "tap")
            missionMathEnabled = (incomingMissionType == MathMissionSupport.MISSION_ID)
            missionSttEnabled = (incomingMissionType == SttMissionSupport.MISSION_ID)
            missionFaceEnabled = (incomingMissionType == FaceMissionSupport.MISSION_ID)
            sttHandler.removeCallbacksAndMessages(null)
            releaseSttRecognizer()
            sttListening = false
            sttAutoStarted = false
            sttMatchedWords = 0
            if (missionSttEnabled) {
                sttTargetPhrase = SttMissionSupport.getTargetPhrase(this, alarmId)
                sttRecognizedText = ""
                sttStatusMessage = null
            }
            requiredPassword = when {
                incomingMissionType == "password" -> explicitPasswordOrNull(incomingMissionPassword) ?: DEFAULT_GLOBAL_PASSWORD
                else -> null
            }

            // Force UI reset to process new sub-mission correctly
            sequencerMissionUpdate = true
            classMissionStarted = false

            val incomingIsWakeCheck = newIntent.getBooleanExtra("from_wake_check", false)
            isWakeCheckLaunchState.value = incomingIsWakeCheck
            if (incomingIsWakeCheck) {
                // The wake-check follow-up is a new session arriving on top of an already-
                // dismissed initial ring (dismissAlarm() sets this true and it is never reset
                // elsewhere, since the same Activity instance is reused via onNewIntent). Left
                // set, it makes the gate's visibility (gateActive && !activityDismissed) false
                // even though gateActive is now correctly true, blanking the follow-up screen.
                activityDismissed = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "onNewIntent error: ${e.message}")
        }
    }

    // -------------------- Mission / timers --------------------
    private val REQUEST_CODE_OVERLAY_PERMISSION = 101
    private var requiredPassword by mutableStateOf<String?>(null)
    private val DEFAULT_GLOBAL_PASSWORD = "IfYouWantYouCanSleep"

    
    private enum class TimerState { StartupBlocked, InitialEntry, Blocked, Idle }

    private val STARTUP_BLOCKED_TIME_SECONDS = 90     // 90s (after Start Mission)
    private val INITIAL_PASSWORD_ENTRY_TIME_SECONDS = 30  // 30s entry window
    private val BLOCKED_TIME_SECONDS = 120            // 120s lockout

    private var timerState = TimerState.Idle
    private var alarmId = -1
    private var currentMissionId: String? = null
    private var sequencerMissionUpdate by mutableStateOf(false)
    @Volatile private var isDismissed: Boolean = false
    @Volatile private var activityDismissed: Boolean = false
    @Volatile private var wakeCheckAcknowledged: Boolean = false
    @Volatile private var wakeCheckGuardActive: Boolean = false
    @Volatile private var samsungNoRestartGuard: Boolean = false
    private val isWakeCheckLaunchState = mutableStateOf(false)
    private var missionTapEnabled by mutableStateOf(false)
    private var missionMathEnabled by mutableStateOf(false)
    private var alarmEnableSnooze by mutableStateOf(false)
    private val REQ_STT_RECORD_AUDIO = 7731
    private var missionSttEnabled by mutableStateOf(false)
    private var missionFaceEnabled by mutableStateOf(false)
    private var faceMissionStatus by mutableStateOf<String?>(null)
    private var sttTargetPhrase: String? = null
    private var sttRecognizedText by mutableStateOf("")
    private var sttListening by mutableStateOf(false)
    private var sttStatusMessage by mutableStateOf<String?>(null)
    private var sttMatchedWords by mutableStateOf(0)
    private var sttAutoStarted by mutableStateOf(false)
    private var sttRecognizer: SpeechRecognizer? = null
    private val sttHandler = Handler(Looper.getMainLooper())
    private var isRequestingPermission = false
    private var isPreview: Boolean = false
    private var previewMission: String? = null
    private var isTestMode: Boolean = false
    private var isFromWakeCheck: Boolean = false
    
    // CRITICAL FIX: Class-level mission state for persistence
    private var classMissionStarted by mutableStateOf(false)

    // Timer state tracking for continuous countdown
    private var timerPhaseStartTime = System.currentTimeMillis()

    private fun isSamsungProblemModel(): Boolean {
        val manu = Build.MANUFACTURER ?: ""
        val model = Build.MODEL ?: ""
        return manu.equals("samsung", ignoreCase = true) && model.uppercase().startsWith("SM-E156")
    }

    // -------------------- Screen receiver --------------------
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "SCREEN_OFF: Screen turned off - mission state preserved")
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "USER_PRESENT: Device unlocked - checking mission state")
                    // CRITICAL FIX: Do NOT reset mission state on unlock
                    // Mission state is preserved and restored in onResume()
                    if (classMissionStarted || missionTapEnabled || missionMathEnabled || missionSttEnabled || missionFaceEnabled || requiredPassword != null) {
                        Log.d(TAG, "USER_PRESENT: Active mission detected - preserving state")
                    } else {
                        Log.d(TAG, "USER_PRESENT: No active mission - keeping current state")
                    }
                }
            }
        }
    }

    // -------------------- Finish alarm receiver --------------------
    private val finishAlarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "FINISH_ALARM_BROADCAST_RECEIVED: ${intent?.action}")
            
            val sequencerComplete = intent?.getBooleanExtra("sequencer_complete", false) ?: false
            val finishDirectly = intent?.getBooleanExtra("finish_directly", false) ?: false
            
            Log.d(TAG, "FINISH_ALARM_PARAMS: sequencerComplete=$sequencerComplete finishDirectly=$finishDirectly")
            
            // Force-finish must win even for active sequencer missions, otherwise the
            // last DPS mission screen remains stuck after sequencer completion.
            if (finishDirectly || (sequencerComplete && !isSequencerMission)) {
                Log.d(TAG, "FINISH_ALARM_SEQUENCER_COMPLETE: Finishing activity due to sequencer completion")
                runCatching {
                    val alarm = AlarmStorage(applicationContext).getAlarm(alarmId)
                    val scheduled = scheduleWakeCheckAfterCompletion(alarmId, alarm, "finishAlarmReceiver")
                    val cycle = WakeCheckCycleStore.read(applicationContext, alarmId)
                    val awakeConfirmed = WakeCheckStore.isAcked(applicationContext, alarmId, 10 * 60_000L)
                    Log.d(
                        TAG,
                        "WAKECHECK_MULTI_MISSION_FINAL_UI: owner=finishAlarmReceiver alarmId=$alarmId cycleId=${cycle?.cycleId ?: 0L} " +
                            "missionModeActive=$isSequencerMission currentMissionPointer=${currentMissionId ?: "<none>"} " +
                            "finalCompletionConfirmed=true awakeConfirmed=$awakeConfirmed " +
                            "loopActive=${alarm?.wakeCheckEnabled == true && !awakeConfirmed} interval=${alarm?.wakeCheckMinutes ?: 0} " +
                            "scheduleTs=${cycle?.scheduledAt ?: 0L} allow=$scheduled reason=${if (scheduled) "wakecheck_scheduled" else "wakecheck_not_scheduled"}"
                    )
                }
                
                // Set dismiss flags IMMEDIATELY
                activityDismissed = true
                isDismissed = true
                isSequencerComplete = true
                isSequencerMission = false
                
                // CRITICAL: Finish activity IMMEDIATELY without any delays or conditions
                try {
                    Log.d(TAG, "FINISH_ALARM: Force finishing activity immediately")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        finishAndRemoveTask()
                    } else {
                        finish()
                    }
                    overridePendingTransition(0, 0)
                    return // Exit immediately
                } catch (e: Exception) {
                    Log.e(TAG, "FINISH_ALARM_ERROR: ${e.message}")
                    try {
                        finish()
                        overridePendingTransition(0, 0)
                    } catch (_: Exception) {}
                    return // Exit immediately
                }
            }

            // For sequencer missions, ignore sequencer_complete broadcasts to allow multi-mission sequences
            if (sequencerComplete && isSequencerMission) {
                Log.d(TAG, "FINISH_ALARM_SEQUENCER_IGNORED: Ignoring sequencer_complete broadcast for active sequencer mission")
            }
        }
    }

    private fun acknowledgeWakeCheck(alarmId: Int) {
        val safeAlarmId =
            if (alarmId != -1) {
                alarmId
            } else {
                intent.extractAlarmId(-1)
            }

        try {
            logWakeCheckForensic("ack_start", safeAlarmId)
            val alreadyAcked =
                if (safeAlarmId != -1) {
                    WakeCheckStore.isAcked(this, safeAlarmId, 10 * 60_000L)
                } else {
                    false
                }

            activityDismissed = true
            isDismissed = true
            wakeCheckAcknowledged = true
            wakeCheckGuardActive = false

            val now = System.currentTimeMillis()

            if (safeAlarmId != -1) {
                runCatching {
                    WakeCheckCycleStore.markWakeCheckPassed(this, safeAlarmId)
                    WakeCheckStore.markAck(this, safeAlarmId, now)
                }.onFailure {
                    Log.e(TAG, "acknowledgeWakeCheck: failed to persist wake-check pass for alarmId=$safeAlarmId", it)
                }

                runCatching {
                    val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        createDeviceProtectedStorageContext() ?: this
                    } else {
                        this
                    }
                    val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putLong("dismiss_ts_$safeAlarmId", now)
                        .putLong("wakecheck_ack_ts_$safeAlarmId", now)
                        .putBoolean("wakecheck_pending_$safeAlarmId", false)
                        .putBoolean("wakecheck_finalized_$safeAlarmId", true)
                        .putBoolean("wakecheck_gate_active_$safeAlarmId", false)
                        .putBoolean("wakecheck_resume_pending_$safeAlarmId", false)
                        
                        // CRITICAL FIX 3: Unlock the terminal state so tomorrow's repeating alarm works!
                        .remove("sequencer_completed_$safeAlarmId") 
                        
                        .commit()
                    if (isSamsungProblemModel()) {
                        samsungNoRestartGuard = true
                        prefs.edit()
                            .putLong("samsung_no_restart_guard_ts_$safeAlarmId", now)
                            .commit()
                    }
                }.onFailure {
                    Log.e(TAG, "acknowledgeWakeCheck: DPS finalize failed for alarmId=$safeAlarmId", it)
                }
                logWakeCheckForensic(
                    "ack_persisted",
                    safeAlarmId,
                    "alreadyAcked=$alreadyAcked now=$now"
                )

                Log.d(TAG, "acknowledgeWakeCheck: cancel follow-up for $safeAlarmId (alreadyAcked=$alreadyAcked)")
                runCatching { cancelWakeCheckFollowUp(this, safeAlarmId) }
                    .onFailure { Log.e(TAG, "acknowledgeWakeCheck: cancel follow-up failed for alarmId=$safeAlarmId", it) }
                logWakeCheckForensic("ack_followup_cancel_requested", safeAlarmId)
            }

            try { ttsHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
            try {
                tts?.apply {
                    stop()
                    shutdown()
                }
            } catch (_: Exception) {}

            if (safeAlarmId != -1) {
                runCatching {
                    var alarm: com.vaishnava.alarm.data.Alarm? = null
                    var storage: AlarmStorage? = null
                    try {
                        storage = AlarmStorage(applicationContext)
                        alarm = storage.getAlarm(safeAlarmId)
                    } catch (e: Exception) {
                        Log.w(TAG, "acknowledgeWakeCheck: CPS/DPS storage bypass for alarmId=$safeAlarmId: ${e.message}")
                    }

                    if (alarm != null && storage != null) {
                        val isOneShot = alarm.isOneShot()
                        if (isOneShot) {
                            try {
                                storage.disableAlarm(safeAlarmId)
                            } catch (_: Exception) {
                                storage.updateAlarm(alarm.copy(isEnabled = false))
                            }
                        }
                    }
                }.onFailure {
                    Log.e(TAG, "acknowledgeWakeCheck: one-shot disable failed for alarmId=$safeAlarmId", it)
                }
                logWakeCheckForensic("ack_alarm_disable_attempted", safeAlarmId)

                // Wake Check is already post-completion, so the foreground alarm service
                // must be stopped regardless of whether the originating alarm used sequencer flow.
                runCatching {
                    val serviceIntent = Intent(this@AlarmActivity, AlarmForegroundService::class.java).apply {
                        action = Constants.ACTION_STOP_FOREGROUND_SERVICE
                        putExtra(AlarmReceiver.ALARM_ID, safeAlarmId)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                }.onFailure {
                    Log.e(TAG, "acknowledgeWakeCheck: stop service failed for alarmId=$safeAlarmId", it)
                }
                logWakeCheckForensic("ack_stop_service_requested", safeAlarmId)
            }

            runCatching {
                Log.d(TAG, "acknowledgeWakeCheck: navigating to MainActivity for alarmId=$safeAlarmId")
                val homeIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("from_alarm_dismiss", true)
                }
                startActivity(homeIntent)
            }.onFailure {
                Log.e(TAG, "acknowledgeWakeCheck: home navigation failed for alarmId=$safeAlarmId", it)
            }
            logWakeCheckForensic("ack_navigation_requested", safeAlarmId)
        } catch (e: Exception) {
            Log.e(TAG, "acknowledgeWakeCheck: unexpected failure for alarmId=$safeAlarmId", e)
            logWakeCheckForensic("ack_exception", safeAlarmId, "message=${e.message}")
        } finally {
            logWakeCheckForensic("ack_finally_before_finish", safeAlarmId)
            Log.d(TAG, "acknowledgeWakeCheck: forcing close for alarmId=$safeAlarmId")
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    finishAndRemoveTask()
                } else {
                    finish()
                }
                overridePendingTransition(0, 0)
            }.onFailure {
                runCatching { finish() }
            }
        }
    }

    private fun logWakeCheckForensic(event: String, alarmId: Int, extra: String = "") {
        val safeAlarmId =
            if (alarmId != -1) {
                alarmId
            } else {
                intent.extractAlarmId(-1)
            }

        try {
            val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                createDeviceProtectedStorageContext() ?: this
            } else {
                this
            }
            val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            val ackTs = if (safeAlarmId != -1) prefs.getLong("wakecheck_ack_ts_$safeAlarmId", 0L) else 0L
            val pending = if (safeAlarmId != -1) prefs.getBoolean("wakecheck_pending_$safeAlarmId", false) else false
            val finalized = if (safeAlarmId != -1) prefs.getBoolean("wakecheck_finalized_$safeAlarmId", false) else false
            val gate = if (safeAlarmId != -1) prefs.getBoolean("wakecheck_gate_active_$safeAlarmId", false) else false
            val resumePending = if (safeAlarmId != -1) prefs.getBoolean("wakecheck_resume_pending_$safeAlarmId", false) else false
            val dismissTs = if (safeAlarmId != -1) prefs.getLong("dismiss_ts_$safeAlarmId", 0L) else 0L
            val cycle = if (safeAlarmId != -1) runCatching { WakeCheckCycleStore.read(this, safeAlarmId) }.getOrNull() else null
            val alarm = if (safeAlarmId != -1) runCatching { AlarmStorage(applicationContext).getAlarm(safeAlarmId) }.getOrNull() else null

            Log.d(
                TAG,
                "WAKECHECK_FORENSIC_UI: event=$event alarmId=$safeAlarmId " +
                    "activityDismissed=$activityDismissed isDismissed=$isDismissed " +
                    "wakeCheckAcknowledged=$wakeCheckAcknowledged wakeCheckGuardActive=$wakeCheckGuardActive " +
                    "launchState=${isWakeCheckLaunchState.value} " +
                    "prefsAckTs=$ackTs prefsPending=$pending prefsFinalized=$finalized prefsGate=$gate " +
                    "prefsResumePending=$resumePending prefsDismissTs=$dismissTs " +
                    "cycleId=${cycle?.cycleId ?: 0L} cycleState=${cycle?.state ?: "null"} " +
                    "cycleScheduledAt=${cycle?.scheduledAt ?: 0L} cycleDueAt=${cycle?.dueAt ?: 0L} " +
                    "cycleTriggeredAt=${cycle?.triggeredAt ?: 0L} cycleExpiryAt=${cycle?.expiryAt ?: 0L} " +
                    "alarmEnabled=${alarm?.isEnabled} alarmWakeCheck=${alarm?.wakeCheckEnabled} " +
                    "alarmWakeMinutes=${alarm?.wakeCheckMinutes} alarmRepeatDaily=${alarm?.repeatDaily} " +
                    "alarmDays=${alarm?.days} alarmMission=${alarm?.missionType} " +
                    extra
            )
        } catch (e: Exception) {
            Log.e(TAG, "WAKECHECK_FORENSIC_UI_ERROR: event=$event alarmId=$safeAlarmId message=${e.message}")
        }
    }

    private fun bringToFront() {
        try {
            if (activityDismissed || isRequestingPermission) return
            val now =   android.os.SystemClock.elapsedRealtime()
            if (now - lastBringToFrontAttemptAt < 1500L) {
                Log.d(TAG, "BRING_TO_FRONT_THROTTLED: skipping rapid self-relaunch")
                return
            }
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && hasWindowFocus()) {
                Log.d(TAG, "BRING_TO_FRONT_SKIP: activity already visible")
                return
            }
            lastBringToFrontAttemptAt = now
            bringToFrontHandler.removeCallbacks(bringToFrontRunnable)
            bringToFrontPending = false

            // First try to move the current task to front
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val activityManager = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager
                    @Suppress("DEPRECATION")
                    val tasks = activityManager?.getRunningTasks(1)
                    @Suppress("DEPRECATION")
                    if (!tasks.isNullOrEmpty() && tasks[0].id == taskId) {
                        activityManager?.moveTaskToFront(taskId, android.app.ActivityManager.MOVE_TASK_WITH_HOME)
                    }
                }
            } catch (_: Exception) { }

            // Then also try the intent approach as backup with enhanced flags
            val resolvedMissionType = resolveSafeMissionType(
                this@AlarmActivity.intent?.getStringExtra("mission_type"),
                this@AlarmActivity.intent?.getStringExtra("mission_password"),
                currentMissionId
            )
            val relaunchIntent = Intent(this@AlarmActivity, AlarmActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
                putExtra(AlarmReceiver.ALARM_ID, alarmId)
                if (isPreview) {
                    putExtra("is_preview", true)
                }
                if (isTestMode) {
                    putExtra("is_test_mode", true)
                }
                if (isFromWakeCheck) {
                    putExtra("from_wake_check", true)
                }
                if (missionSttEnabled) {
                    putExtra("mission_type", SttMissionSupport.MISSION_ID)
                } else if (missionTapEnabled) {
                    putExtra("mission_type", "tap")
                } else if (requiredPassword != null) {
                    putExtra("mission_type", "password")
                    putExtra("mission_password", requiredPassword)
                } else if (isSequencerMission && resolvedMissionType.isNotEmpty()) {
                    putExtra("mission_type", resolvedMissionType)
                    if (resolvedMissionType == "password") {
                        putExtra("mission_password", DEFAULT_GLOBAL_PASSWORD)
                    }
                }
                currentMissionId?.let { putExtra("mission_id", it) }
                putExtra(MissionSequencer.EXTRA_FROM_SEQUENCER, isSequencerMission)
                putExtra("sequencer_context", sequencerContext)
            }
            startActivity(relaunchIntent)

            // Also ensure window flags are set properly
            try {
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                            WindowManager.LayoutParams.FLAG_FULLSCREEN
                )
            } catch (_: Exception) { }
        } catch (_: Exception) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

            // GUARD: this activity must only ever run for a real, stored alarm that our
            // own scheduler launched. Reject anything else before any lockscreen/wake/
            // ringing setup begins — this is what actually stops any future stray
            // intent (manifest, test tooling, adb, a bad PendingIntent) from ringing.
            val incomingAlarmId = intent.extractAlarmId(-1)
            val isKnownTestEntry = intent.getBooleanExtra("is_test_alarm", false) &&
                intent.getStringExtra("test_source") != null
            if (!isKnownTestEntry && incomingAlarmId == -1) {
                Log.w(TAG, "REJECTED_UNVERIFIED_LAUNCH: no alarm_id, action=${intent.action}")
                finish()
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "onCreate guard failed: ${e.message}", e)
            finish()
            return
        }

        writeSettingsState.value = Settings.System.canWrite(this)

        // Show over lockscreen / wake - MUST be called before any UI setup
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // Extra flags for ensuring visibility
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Register receiver
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
        } catch (_: Exception) {}

        // Register finish alarm receiver for sequencer completion
        try {
            val finishFilter = IntentFilter().apply {
                addAction("com.vaishnava.alarm.FINISH_ALARM")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(finishAlarmReceiver, finishFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(finishAlarmReceiver, finishFilter)
            }
        } catch (_: Exception) {}

        alarmId = intent.extractAlarmId(-1)
        currentMissionId = intent.getStringExtra("mission_id")
        isWakeCheckLaunchState.value = intent.getBooleanExtra("from_wake_check", false)
        isSequencerMission = intent.getBooleanExtra(com.vaishnava.alarm.sequencer.MissionSequencer.EXTRA_FROM_SEQUENCER, false)
        sequencerContext = intent.getStringExtra("sequencer_context") ?: ""
        isMissedAlarm = intent.getBooleanExtra("is_missed_alarm", false)
        if (alarmId != -1 && isDpsMode()) {
            bindDpsMissionFromSnapshot(alarmId)
        }
        
        // ✅ Check if this is a test alarm from SHORTS app
        val isTestAlarm = intent.getBooleanExtra("is_test_alarm", false)
        val testSource = intent.getStringExtra("test_source")
        
        // Always reset sequencer complete flag for every new alarm launch
        isSequencerComplete = false
        Log.d(TAG, "SEQUENCER_RESET: Reset isSequencerComplete for new alarm launch")
        
        Log.d(TAG, "onCreate: alarmId=$alarmId missionId=$currentMissionId isSequencerMission=$isSequencerMission sequencerContext=$sequencerContext fromWakeCheck=${isWakeCheckLaunchState.value}")
        
        // ✅ Log test alarm detection
        if (isTestAlarm) {
            Log.d(TAG, "✅ TEST ALARM DETECTED: source=$testSource alarmId=$alarmId")
        }
        isPreview = intent.getBooleanExtra("preview", false)
        isTestMode = intent.getBooleanExtra("is_test_mode", false)
        isFromWakeCheck = intent.getBooleanExtra("from_wake_check", false)
        Log.d(TAG, "onCreateFlags: isPreview=$isPreview isTestMode=$isTestMode isFromWakeCheck=$isFromWakeCheck")

        // Debug: Show all intent extras including mission type
        Log.d(TAG, "=== INTENT DEBUG ===")
        val missionTypeFromIntent = intent.getStringExtra("mission_type")
        Log.d(TAG, "alarmId=$alarmId missionId=$currentMissionId missionType=$missionTypeFromIntent from_wake_check=${isWakeCheckLaunchState.value} isSequencerMission=$isSequencerMission sequencerContext=$sequencerContext")
        Log.d(TAG, "Intent extras: ${intent.extras?.keySet()?.joinToString(", ")}")
        intent.extras?.keySet()?.forEach { key ->
            Log.d(TAG, "  $key = ${intent.extras?.get(key)}")
        }
        Log.d(TAG, "=== END INTENT DEBUG ===")

        // CRITICAL FIX: Read enable_snooze from intent extra for snooze fires
        val enableSnoozeFromIntent = intent.getBooleanExtra("enable_snooze", false)
        Log.d(TAG, "[SNOOZE_INTENT_READ] enable_snooze from intent extras: $enableSnoozeFromIntent")
        Log.d(TAG, "[SNOOZE_INTENT_READ] alarmId=$alarmId from_snooze=${intent.getBooleanExtra("from_snooze", false)}")

        Log.d("WakeCheckDebug", "AlarmActivity.onCreate alarmId=$alarmId from_wake_check=${isWakeCheckLaunchState.value}")
        if (isWakeCheckLaunchState.value) {
            // Wake-up check follow-up launch; no user-facing toast needed.
        }

        // Load config & TTS (only for initial alarm launches, not wake-check follow-ups)
        val isWakeCheckAlarm: Boolean = try {
            // SIMPLIFIED: Use Intent extras first, storage only if absolutely needed
            var alarm: com.vaishnava.alarm.data.Alarm? = null
            
            // Only try to get alarm from storage if Intent extras are missing
            val hasIntentExtras = intent?.getStringExtra("mission_type") != null
            if (!hasIntentExtras) {
                try {
                    // CRITICAL: DPS MUST NEVER access AlarmStorage - CPS only
                com.vaishnava.alarm.ExtremeForensicLogger.Chrona.cpsViolation(
                    alarmId,
                    "ALARM_STORAGE_ACCESS",
                    "AlarmActivity.getCurrentMissionFromAuthority",
                    applicationContext,
                    "CPS_ALARM_STORAGE"
                )
                val storage = AlarmStorage(applicationContext)
                    alarm = storage.getAlarm(alarmId)
                    Log.d(TAG, "STORAGE_FALLBACK: Got alarm from storage since Intent extras missing")
                } catch (e: Exception) {
                    Log.w(TAG, "STORAGE_FALLBACK: Failed to get alarm from storage", e)
                }
            } else {
                Log.d(TAG, "STORAGE_SKIP: Using Intent extras, no storage access needed")
            }

            // CRITICAL FIX: When snooze fires, override stored alarm's enableSnooze with intent value
            val isSnoozeActivation = intent.getBooleanExtra("from_snooze", false)
            if (isSnoozeActivation && enableSnoozeFromIntent && alarm != null) {
                Log.d(TAG, "[SNOOZE_STATE_OVERRIDE] Snooze activation detected - overriding stored enableSnooze")
                Log.d(TAG, "[SNOOZE_STATE_OVERRIDE] OLD alarm.enableSnooze=${alarm.enableSnooze} NEW enableSnoozeFromIntent=$enableSnoozeFromIntent")
                alarm = alarm.copy(enableSnooze = enableSnoozeFromIntent)
                Log.d(TAG, "[SNOOZE_STATE_OVERRIDE] Alarm updated: enableSnooze=${alarm.enableSnooze}")
            }

            alarmEnableSnooze = if (alarm != null) {
                alarm.enableSnooze
            } else {
                enableSnoozeFromIntent
            }
            Log.d(TAG, "[SNOOZE_CONFIG_DEBUG] alarm=$alarm, enableSnoozeFromIntent=$enableSnoozeFromIntent, alarmEnableSnooze=$alarmEnableSnooze")

            // CRITICAL FIX: Detect sequencer missions by checking alarm missionType, not just intent extra
            // This ensures sequencer detection works during force restart when EXTRA_FROM_SEQUENCER is not set
            if (alarm?.missionType == "sequencer" && !isSequencerMission) {
                isSequencerMission = true
                Log.d(TAG, "SEQUENCER_DETECTION: Detected sequencer mission from alarm missionType during force restart")
            }
            
            Log.d(TAG, "CONFIG_LOAD_START: alarmId=$alarmId isSequencerMission=$isSequencerMission sequencerContext=$sequencerContext hasAlarm=${alarm != null}")

            // CRITICAL FIX: Use context-aware authority checking for DPS vs CPS mode
            val isDpsMode = isDpsMode()
            Log.d(TAG, "AUTHORITY_MODE: alarmId=$alarmId isDpsMode=$isDpsMode")
            if (isDpsMode && !isSequencerMission) {
                try {
                    val snapshot = DpsMissionMirror(this).readSnapshot(alarmId)
                    if (snapshot != null && !snapshot.isSequenceComplete && !snapshot.activeMissionId.isNullOrBlank()) {
                        isSequencerMission = true
                        currentMissionId = snapshot.activeMissionId
                        Log.d(
                            TAG,
                            "SEQUENCER_RECOVERY: recovered sequencer mission from DPS snapshot " +
                                "alarmId=$alarmId missionId=${snapshot.activeMissionId} version=${snapshot.version}"
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SEQUENCER_RECOVERY_ERROR: alarmId=$alarmId error=${e.message}")
                }
            }
            
            val intentMissionType = intent?.getStringExtra("mission_type") ?: ""
            val intentMissionPassword = intent?.getStringExtra("mission_password") ?: ""
            
            Log.d(TAG, "INTENT_ONLY: alarmId=$alarmId missionType=$intentMissionType missionPassword=$intentMissionPassword")
            
            // Get mission type from appropriate authority based on DPS/CPS mode
            val persistedMissionType = try {
                if (isDpsMode) {
                    // DPS MODE: Read mission type directly from device-protected storage
                    val dpsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        createDeviceProtectedStorageContext() ?: this
                    } else this
                    
                    val dpsPrefs = dpsContext.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
                    val snapshotMissionType = DpsMissionMirror(this).readSnapshot(alarmId)?.activeMissionId
                    val dpsMissionType = dpsPrefs.getString("direct_boot_mission_type_$alarmId", null)
                    
                    if (!snapshotMissionType.isNullOrBlank()) {
                        currentMissionId = snapshotMissionType
                        Log.d(TAG, "DPS_AUTHORITY: Got mission type from snapshot: $snapshotMissionType")
                        snapshotMissionType
                    } else if (dpsMissionType != null) {
                        Log.d(TAG, "DPS_AUTHORITY: Got mission type from DPS storage: $dpsMissionType")
                        dpsMissionType
                    } else {
                        Log.w(TAG, "DPS_AUTHORITY: No mission type found in DPS storage, falling back to Intent")
                        intentMissionType
                    }
                } else {
                    // CPS MODE: Get mission from CPS MissionSequencer
                    val currentMission = getCurrentMissionFromAuthority()
                    if (currentMission != null) {
                        Log.d(TAG, "CPS_AUTHORITY: Got mission from CPS sequencer: ${currentMission.id}")
                        currentMission.id
                    } else {
                        val resolvedIntentMission = resolveSafeMissionType(
                            intentMissionType,
                            intentMissionPassword,
                            currentMissionId
                        )
                        Log.w(
                            TAG,
                            "CPS_AUTHORITY: No mission found in CPS sequencer, falling back to resolved intent mission: $resolvedIntentMission"
                        )
                        resolvedIntentMission
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "AUTHORITY_ERROR: ${e.message}")
                resolveSafeMissionType(intentMissionType, intentMissionPassword, currentMissionId)
            }
            
            missionTapEnabled = (persistedMissionType == "tap")
            missionMathEnabled = (persistedMissionType == MathMissionSupport.MISSION_ID)
            missionSttEnabled = (persistedMissionType == SttMissionSupport.MISSION_ID)
            missionFaceEnabled = (persistedMissionType == FaceMissionSupport.MISSION_ID)
            if (missionSttEnabled) {
                sttTargetPhrase = SttMissionSupport.getTargetPhrase(this, alarmId)
            }
            requiredPassword = if (persistedMissionType == "password") {
                explicitPasswordOrNull(intentMissionPassword) ?: run {
                // Get password from appropriate authority based on DPS/CPS mode
                    if (isDpsMode) {
                        try {
                            val dpsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                                createDeviceProtectedStorageContext() ?: this
                            } else this

                            val dpsPrefs = dpsContext.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
                            val dpsPassword = explicitPasswordOrNull(
                                dpsPrefs.getString("direct_boot_mission_password_$alarmId", null)
                            )

                            if (dpsPassword != null) {
                                Log.d(TAG, "DPS_PASSWORD_AUTHORITY: Got password from DPS storage")
                                dpsPassword
                            } else {
                                Log.d(TAG, "DPS_PASSWORD_AUTHORITY: No explicit password found in DPS storage, using default")
                                DEFAULT_GLOBAL_PASSWORD
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "DPS_PASSWORD_ERROR: ${e.message}")
                            DEFAULT_GLOBAL_PASSWORD
                        }
                    } else {
                        DEFAULT_GLOBAL_PASSWORD
                    }
                }
            } else {
                null
            }

            Log.d(TAG, "MISSION_CONFIG: alarmId=$alarmId isSequencer=$isSequencerMission missionType=$persistedMissionType tapEnabled=$missionTapEnabled hasPassword=${requiredPassword != null} requiredPassword=$requiredPassword alarmMissionType=${alarm?.missionType} alarmMissionPassword=${alarm?.missionPassword}")

            // Set currentMissionId for sequencer missions to prevent fallback issues
            if (isSequencerMission) {
                currentMissionId = intent?.getStringExtra("mission_id")
                if (isDpsMode) {
                    bindDpsMissionFromSnapshot(alarmId)
                }
                if (currentMissionId.isNullOrBlank() && persistedMissionType.isNotBlank() && persistedMissionType != "sequencer") {
                    currentMissionId = persistedMissionType
                }
                currentMissionId = currentMissionId
                    ?: "sequencer_mission_${alarmId}"
            }

            if (isPreview) {
                clearTapCount(alarmId)
                val pm = intent.getStringExtra("preview_mission")
                previewMission = pm
                missionTapEnabled = (pm == "tap")
                missionMathEnabled = (pm == MathMissionSupport.MISSION_ID)
                missionSttEnabled = (pm == SttMissionSupport.MISSION_ID)
                missionFaceEnabled = (pm == FaceMissionSupport.MISSION_ID)
                if (missionSttEnabled) {
                    sttTargetPhrase = intent.getStringExtra("preview_stt_phrase")?.ifBlank { null }
                        ?: SttMissionSupport.DEFAULT_TARGET_PHRASE
                    sttRecognizedText = ""
                    sttListening = false
                }
                val pwd = intent.getStringExtra("preview_password")
                requiredPassword = if (pm == "password") (pwd?.ifBlank { null } ?: DEFAULT_GLOBAL_PASSWORD) else null
                isWakeCheckLaunchState.value = false
            }

            if (!isWakeCheckLaunchState.value) Handler(Looper.getMainLooper()).postDelayed({
                val nowStr: String = try {
                    val fmt = java.text.SimpleDateFormat("H:mm", java.util.Locale.US)
                    val time = fmt.format(java.util.Date())
                    val (hour, minute) = time.split(":").map { it.toInt() }
                    val period = if (hour < 12) "AM" else "PM"
                    val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
                    val minuteStr = if (minute < 10) "zero $minute" else minute.toString()
                    "$displayHour $minuteStr $period"
                } catch (_: Exception) { 
                    // Always try to get time, even if exception occurs
                    try {
                        val cal = java.util.Calendar.getInstance()
                        val hour = cal.get(java.util.Calendar.HOUR)
                        val minute = cal.get(java.util.Calendar.MINUTE)
                        val ampm = if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
                        val displayHour = if (hour == 0) 12 else hour
                        val minuteStr = if (minute < 10) "zero $minute" else minute.toString()
                        "$displayHour $minuteStr $ampm"
                    } catch (_: Exception) { 
                        // Final fallback - use system time directly
                        java.text.SimpleDateFormat("h:mm a").format(java.util.Date())
                    }
                }
                ttsMessage = "The time is $nowStr. Time to wake up."

                // Check if silence_no_sound is selected - if so, don't use TTS
                val isSilenceRingtone = try {
                    // CRITICAL FIX: Use safe storage approach for Direct Boot compatibility
                    val alarm = try {
                        val safeStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            AlarmStorage(createDeviceProtectedStorageContext())
                        } else {
                            AlarmStorage(applicationContext)
                        }
                        safeStorage.getAlarms().find { it.id == alarmId }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get alarm from device-protected storage for TTS check, trying smart storage", e)
                        val regularStorage = AlarmStorage(applicationContext)
                        regularStorage.getAlarms().find { it.id == alarmId }
                    }
                    
                    val ringtoneUri = alarm?.ringtoneUri?.toString()
                    Log.d(TAG, "TTS_CHECK: alarmId=$alarmId ringtoneUri=$ringtoneUri")

                    val containsSilence = if (ringtoneUri?.startsWith("android.resource://") == true) {
                        // Extract resource ID and check if it's silence_no_sound
                        val uriParts = ringtoneUri.split("/")
                        val resourceId = uriParts.lastOrNull()?.toIntOrNull()
                        if (resourceId != null) {
                            // Check if this resource ID matches silence_no_sound
                            val silenceResourceId = resources.getIdentifier("silence_no_sound", "raw", packageName)
                            resourceId == silenceResourceId
                        } else {
                            // Fallback to string check for resource names in URI
                            ringtoneUri.contains("silence_no_sound")
                        }
                    } else {
                        ringtoneUri?.contains("silence_no_sound") == true
                    }

                    Log.d(TAG, "TTS_CHECK: containsSilence=$containsSilence")

                    // Write to persistent log file
                    writePersistentLog("TTS_CHECK: alarmId=$alarmId ringtoneUri=$ringtoneUri containsSilence=$containsSilence")

                    // Add to app's sequencer log system
                    MainActivity.getInstance()?.addSequencerLog("TTS_CHECK: alarmId=$alarmId ringtoneUri=$ringtoneUri containsSilence=$containsSilence")

                    containsSilence
                } catch (_: Exception) {
                    Log.d(TAG, "TTS_CHECK: Exception checking ringtone")
                    writePersistentLog("TTS_CHECK: Exception checking ringtone for alarmId=$alarmId")
                    MainActivity.getInstance()?.addSequencerLog("TTS_CHECK: Exception checking ringtone for alarmId=$alarmId")
                    false
                }

                if (!isSilenceRingtone) {
                    tts = android.speech.tts.TextToSpeech(this) { status ->
                        if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                            tts?.language = Locale.getDefault()
                            try {
                                val attrs = AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                                tts?.setAudioAttributes(attrs)
                            } catch (_: Exception) {}
                            tts?.speak(ttsMessage, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "WAKE_MSG")
                            lastSpokenTime = nowStr
                            // DISABLED: Legacy TTS repeater removed - service handles periodic TTS
                            Log.d(TAG, "Initial TTS spoken - periodic TTS handled by service")
                        }
                    }
                } else {
                    Log.d(TAG, "Silence ringtone detected - TTS disabled")
                }
            }, 900L)
            Log.d(TAG, "CONFIG_LOAD_DONE: alarmId=$alarmId missionTapEnabled=$missionTapEnabled requiredPasswordSet=${requiredPassword != null} isSequencerMission=$isSequencerMission sequencerContext=$sequencerContext")
            true
        } catch (_: Exception) {
            requiredPassword = null
            Log.w(TAG, "CONFIG_LOAD_ERROR: alarmId=$alarmId; falling back to no mission/password")
            false
        }

        Handler(Looper.getMainLooper()).postDelayed({ checkOverlayPermission() }, 5000)

        try {
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
            window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        } catch (_: Exception) { }

        setContent {
            val recomposeTime = System.currentTimeMillis()
            Log.d("Recompose", "🔄 COMPOSABLE RECOMPOSITION: timestamp=$recomposeTime")

            val view = androidx.compose.ui.platform.LocalView.current

            ResponsiveTextScale {
            MaterialTheme(
                colorScheme = androidx.compose.material3.darkColorScheme(
                    primary = Color(0xFFFFAB91),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E)
                )
            ) {
                // Handle system bar colors based on theme
                if (!view.isInEditMode) {
                    androidx.compose.runtime.SideEffect {
                        val window = (view.context as android.app.Activity).window
                        window.statusBarColor = android.graphics.Color.TRANSPARENT
                        window.navigationBarColor = android.graphics.Color.TRANSPARENT
                        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                            isAppearanceLightStatusBars = false
                            isAppearanceLightNavigationBars = false
                        }
                        Log.d(TAG, "BACKGROUND_ENFORCEMENT: Scaffold containerColor heavily enforced to Color.Black")
                    }
                }
                
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Black,
                    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)
                ) { innerPadding ->
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(Color.Black)
                    ) {

                        Log.d(
                            "WAKECHECK_BUG",
                            "AlarmUI_CALL: alarmId=$alarmId isWakeCheckAlarm(configLoadOk)=$isWakeCheckAlarm " +
                                "isWakeCheckLaunchState=${isWakeCheckLaunchState.value} " +
                                "computedIsWakeCheck=${isWakeCheckAlarm && isWakeCheckLaunchState.value} " +
                                "fromWakeCheckIntentExtra=${intent.getBooleanExtra("from_wake_check", false)}"
                        )
                        AlarmUI(
                            context = LocalContext.current,
                            alarmId = alarmId,
                            isWakeCheck = isWakeCheckAlarm && isWakeCheckLaunchState.value,
                            writeSettingsGranted = writeSettingsState.value,
                            isTestAlarm = isTestAlarm,
                            testSource = testSource,
                            onOpenWriteSettings = { requestWriteSettingsPermission() }
                        )
                    }
                }
            }
            }
        }

        turnScreenOn()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Theme changes are handled automatically by Compose with isSystemInDarkTheme()
        Log.d("AlarmActivity", "Configuration changed - theme transition applied smoothly")
    }

    override fun onStart() {
        super.onStart()
        AlarmForegroundService.setActivityForeground(true)
        bringToFrontHandler.removeCallbacks(bringToFrontRunnable)
        bringToFrontPending = false
    }

    override fun onResume() {
        super.onResume()
        if (isRequestingPermission) {
            val micGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (micGranted) {
                isRequestingPermission = false
                if (missionSttEnabled && !sttListening) {
                    startSttRecognition(alarmId)
                }
            }
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        AlarmForegroundService.setActivityForeground(true)
        bringToFrontHandler.removeCallbacks(bringToFrontRunnable)
        bringToFrontPending = false
        
        // CRITICAL FIX: Only restore mission state for non-sequencer missions
        // For sequencer missions, use current intent data instead of saved state
        if (!isSequencerMission) {
            restoreMissionState()
        }
        
        // CRITICAL FIX: Only finish if sequencer is complete AND activity is already dismissed
        if (isSequencerComplete && !isSequencerMission && !classMissionStarted && (activityDismissed || isDismissed)) {
            Log.d(TAG, "ON_RESUME_SEQUENCER_COMPLETE: Finishing activity immediately (no active mission)")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    finishAndRemoveTask()
                } else {
                    finish()
                }
                overridePendingTransition(0, 0)
                return
            } catch (e: Exception) {
                try { finish() } catch (_: Exception) {}
                return
            }
        }
        
        // CRITICAL FIX: Only reset mission if no valid mission is currently active
        // This prevents unlock events from clearing active missions
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isKeyguardLocked = keyguardManager?.isKeyguardLocked ?: false
        
        if (!isKeyguardLocked) {
            // Device is unlocked - only reset if no active mission
            if (!classMissionStarted && !missionTapEnabled && !missionMathEnabled && requiredPassword == null) {
                Log.d(TAG, "ON_RESUME_UNLOCKED: No active mission - keeping current state")
            } else {
                Log.d(TAG, "ON_RESUME_UNLOCKED: Active mission detected - preserving state (started=$classMissionStarted, tap=$missionTapEnabled, math=$missionMathEnabled, password=${requiredPassword != null})")
            }
        }
        
        writeSettingsState.value = Settings.System.canWrite(this)
    }

    private fun turnScreenOn() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "AlarmApp::TurnScreenOn"
            )
            wakeLock.acquire(5000)
            wakeLock.release()
        } catch (e: Exception) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    setShowWhenLocked(true)
                    setTurnScreenOn(true)
                } else {
                    window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        var isProtected = false
        var enableGradualVolume = true
        try {
            val alarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    AlarmStorage(createDeviceProtectedStorageContext()).getAlarms().find { it.id == alarmId }
                } catch (e: Exception) {
                    AlarmStorage(applicationContext).getAlarms().find { it.id == alarmId }
                }
            } else {
                AlarmStorage(applicationContext).getAlarms().find { it.id == alarmId }
            }
            isProtected = alarm?.isProtected == true
            enableGradualVolume = alarm?.enableGradualVolume != false
        } catch (e: Exception) {
            Log.w(TAG, "CPS/DPS storage bypass in onKeyDown for alarmId=$alarmId: ${e.message}")
        }

        if (isProtected && (
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        )) {
            Log.w(TAG, "Volume button press blocked for protected alarm $alarmId")
            return true
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && !enableGradualVolume) {
            return true
        }

        return if (
            keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_POWER
        ) {
            true
        } else super.onKeyDown(keyCode, event)
    }

    // ---------- UI COMPOSABLE ----------
    @Composable
    private fun BoxScope.AlarmUI(
        context: Context,
        modifier: Modifier = Modifier,
        alarmId: Int,
        isWakeCheck: Boolean,
        writeSettingsGranted: Boolean,
        isTestAlarm: Boolean = false,
        testSource: String? = null,
        onOpenWriteSettings: () -> Unit
    ) {
        // Only skip rendering if activity is explicitly dismissed
        if (isSequencerComplete && !isSequencerMission && (activityDismissed || isDismissed)) {
            Log.d(TAG, "AlarmUI: Skipping render for completed sequencer (non-sequencer mission)")
            return
        }
        
        // State
        var missionStarted by remember { mutableStateOf(false) }
        var timerState by remember { mutableStateOf(TimerState.Idle) }
        var remainingTime by remember { mutableStateOf(0) }
        var passwordInput by remember { mutableStateOf("") }
        var showPasswordError by remember { mutableStateOf(false) }
        var showPasswordVisible by remember { mutableStateOf(false) }
        // Keyed on isWakeCheck (matching gateActive and the other wake-check-local state
        // below) so a wake-check follow-up starts fresh. Unkeyed, this survives the entire
        // composition - including every onNewIntent - so it stayed true forever after the
        // first ring's Dismiss button set it, making the early-return below permanently
        // render a blank Box for any later wake-check follow-up on the same Activity instance.
        var isDismissed by remember(isWakeCheck) { mutableStateOf(false) }
        var tapCount by remember(alarmId) { mutableStateOf(if (isPreview) 0 else getSavedTapCount(alarmId)) }
        var tapSeconds by remember { mutableStateOf(105) }
        var mathMissionSpec by remember { mutableStateOf<com.vaishnava.alarm.sequencer.MissionSpec?>(null) }
        var mathAnswerInput by remember { mutableStateOf("") }
        var mathStatusMessage by remember { mutableStateOf<String?>(null) }
        var mathRemainingSeconds by remember { mutableStateOf(0) }

        LaunchedEffect(this@AlarmActivity.classMissionStarted) {
            if (missionStarted != this@AlarmActivity.classMissionStarted) {
                missionStarted = this@AlarmActivity.classMissionStarted
            }
        }
        
        // CRITICAL FIX: Update class-level state when Composable state changes
        LaunchedEffect(missionStarted) {
            this@AlarmActivity.classMissionStarted = missionStarted
        }

        // Reset state for new sequencer missions
        LaunchedEffect(isSequencerMission, currentMissionId) {
            if (isSequencerMission && !isPreview) {
                missionStarted = false
                timerState = TimerState.Idle
                passwordInput = ""
                showPasswordError = false
                tapCount = getSavedTapCount(alarmId)
                tapSeconds = 105
                mathMissionSpec = null
                mathAnswerInput = ""
                mathStatusMessage = null
                mathRemainingSeconds = 0
                
                // CRITICAL FIX: Set initial mission configuration for sequencer missions
                val missionType = this@AlarmActivity.resolveSafeMissionType(
                    intent?.getStringExtra("mission_type"),
                    intent?.getStringExtra("mission_password"),
                    currentMissionId
                )
                missionTapEnabled = (missionType == "tap")
                missionMathEnabled = (missionType == MathMissionSupport.MISSION_ID)
                missionSttEnabled = (missionType == SttMissionSupport.MISSION_ID)
                missionFaceEnabled = (missionType == FaceMissionSupport.MISSION_ID)
                if (missionSttEnabled) {
                    sttTargetPhrase = SttMissionSupport.getTargetPhrase(this@AlarmActivity, alarmId)
                }
                this@AlarmActivity.requiredPassword = when {
                    missionType == "password" -> DEFAULT_GLOBAL_PASSWORD
                    else -> null // CRITICAL: Clear password for non-password missions
                }
                Log.d(TAG, "INITIAL_SEQUENCER_CONFIG: missionType=$missionType missionTapEnabled=$missionTapEnabled requiredPassword=${this@AlarmActivity.requiredPassword != null}")
            }
        }

        // Handle sequencer mission updates from onNewIntent
        LaunchedEffect(sequencerMissionUpdate, currentMissionId) {
            if (isSequencerMission && !isPreview) {
                val missionType = resolveSafeMissionType(
                    intent?.getStringExtra("mission_type"),
                    intent?.getStringExtra("mission_password"),
                    currentMissionId
                )
                missionTapEnabled = (missionType == "tap")
                missionMathEnabled = (missionType == MathMissionSupport.MISSION_ID)
                missionSttEnabled = (missionType == SttMissionSupport.MISSION_ID)
                missionFaceEnabled = (missionType == FaceMissionSupport.MISSION_ID)
                if (missionSttEnabled) {
                    sttTargetPhrase = SttMissionSupport.getTargetPhrase(this@AlarmActivity, alarmId)
                }
                requiredPassword = when {
                    missionType == "password" -> DEFAULT_GLOBAL_PASSWORD
                    else -> null
                }
                classMissionStarted = false // Ensures Start Mission / direct interaction triggers cleanly
                sequencerMissionUpdate = false
            }
        }

        // NOTE: Do not auto-start missions; they must begin only when user taps Start Mission (including previews).

        // Ensure mission state is properly initialized for normal alarms
        LaunchedEffect(Unit) {
            if (!isPreview && !missionStarted) {
                // Small delay to ensure all states are loaded
                delay(100)
                // Force UI recomposition if mission is detected but not started
                if (missionTapEnabled || requiredPassword != null) {
                    // This ensures the "Start Mission" button appears
                    Log.d("AlarmActivity", "Mission detected but not started - ensuring UI state")
                }
            }
        }

        LaunchedEffect(missionStarted, missionMathEnabled, currentMissionId, isWakeCheckLaunchState.value, isDismissed) {
            if (!missionStarted || !missionMathEnabled || isWakeCheckLaunchState.value || isDismissed) {
                if (!missionStarted || !missionMathEnabled) {
                    mathMissionSpec = null
                    mathRemainingSeconds = 0
                }
                return@LaunchedEffect
            }

            if (this@AlarmActivity.isPreview) {
                if (mathMissionSpec == null) {
                    val diffName = intent.getStringExtra("preview_math_difficulty") ?: "EASY"
                    val diff = MathDifficulty.fromWireValue(diffName)
                    val limitSec = intent.getStringExtra("preview_math_time_limit")?.toIntOrNull() ?: 30
                    val reqAns = intent.getStringExtra("preview_math_required_answers")?.toIntOrNull() ?: 2
                    val config = MathMissionConfig(
                        difficulty = diff,
                        questionTimeLimitSec = limitSec,
                        requiredCorrectAnswers = reqAns
                    )
                    val spec = MathMissionSupport.createMissionSpec(config = config)
                    mathMissionSpec = MathMissionSupport.ensureActiveQuestion(spec, SystemClock.elapsedRealtime())
                }
                val runtimeState = mathMissionSpec?.let { MathMissionSupport.readRuntimeState(it) }
                val config = mathMissionSpec?.let { MathMissionSupport.readConfig(it) }
                if (runtimeState != null && config != null && runtimeState.solvedCount >= config.requiredCorrectAnswers) {
                    this@AlarmActivity.onMissionCompleted(alarmId)
                }
                return@LaunchedEffect
            }

            val authorityMission = this@AlarmActivity.getCurrentMissionFromAuthority()
            if (authorityMission == null || !MathMissionSupport.isMathMission(authorityMission)) {
                mathMissionSpec = null
                return@LaunchedEffect
            }

            val restoredMission = MathMissionSupport.ensureActiveQuestion(
                mission = authorityMission,
                nowElapsedMs = android.os.SystemClock.elapsedRealtime()
            )
            if (restoredMission != authorityMission) {
                this@AlarmActivity.persistCurrentMissionSnapshot(restoredMission)
            }

            mathMissionSpec = restoredMission
            val runtimeState = MathMissionSupport.readRuntimeState(restoredMission)
            val config = MathMissionSupport.readConfig(restoredMission)
            if (runtimeState != null && config != null && runtimeState.solvedCount >= config.requiredCorrectAnswers) {
                this@AlarmActivity.onMissionCompleted(alarmId)
            }
        }

        LaunchedEffect(
            missionStarted,
            missionMathEnabled,
            mathMissionSpec?.params?.get(MathMissionSupport.KEY_QUESTION_ID),
            mathMissionSpec?.params?.get(MathMissionSupport.KEY_QUESTION_DEADLINE_ELAPSED_MS),
            isWakeCheckLaunchState.value,
            isDismissed
        ) {
            if (!missionStarted || !missionMathEnabled || isWakeCheckLaunchState.value || isDismissed) {
                return@LaunchedEffect
            }

            while (true) {
                val activeMission = mathMissionSpec ?: break
                val runtimeState = MathMissionSupport.readRuntimeState(activeMission) ?: break
                val nowElapsedMs = android.os.SystemClock.elapsedRealtime()
                if (runtimeState.questionDeadlineElapsedMs <= nowElapsedMs) {
                    val rotatedMission = MathMissionSupport.rotateQuestion(activeMission, nowElapsedMs)
                    this@AlarmActivity.persistCurrentMissionSnapshot(rotatedMission)
                    mathMissionSpec = rotatedMission
                    mathAnswerInput = ""
                    mathStatusMessage = "Time expired. New question generated."
                    break
                }

                mathRemainingSeconds = (((runtimeState.questionDeadlineElapsedMs - nowElapsedMs) + 999L) / 1000L)
                    .toInt()
                    .coerceAtLeast(0)
                delay(250)
            }
        }

        if (this@AlarmActivity.activityDismissed || isDismissed) {
            Box(modifier = Modifier.fillMaxSize()) {}
            return
        }

        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current

      
        // Get current alarm data for protected status check
        val currentAlarm = remember(alarmId, isPreview) {
            Log.d("MemoryLog", "📦 remember(currentAlarm) EXECUTING - this should only happen once per alarmId change")
            val startMs = System.currentTimeMillis()
            try {
                if (this@AlarmActivity.isPreview) {
                    null
                } else {
                    // Try DPS first (where alarm was saved), then CPS fallback
                Log.d("MemoryLog", "🔍 Attempting DPS AlarmStorage.getAlarms()")
                val dpsStart = System.currentTimeMillis()
                val foundAlarm = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        AlarmStorage(createDeviceProtectedStorageContext()).getAlarms().find { it.id == alarmId }
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ALARM_LOAD_DEBUG: DPS access failed, trying CPS: ${e.message}")
                    Log.d("MemoryLog", "⚠️ DPS getAlarms() took ${System.currentTimeMillis() - dpsStart}ms")
                    null
                } ?: run {
                    // Fallback to CPS if DPS fails
                    Log.d("MemoryLog", "🔍 Attempting CPS AlarmStorage.getAlarms()")
                    val cpsStart = System.currentTimeMillis()
                    com.vaishnava.alarm.ExtremeForensicLogger.Chrona.cpsViolation(
                        alarmId,
                        "ALARM_STORAGE_ACCESS",
                        "AlarmActivity.getCurrentMissionFromAuthority",
                        applicationContext,
                        "CPS_ALARM_STORAGE"
                    )
                    AlarmStorage(applicationContext).getAlarms().find { it.id == alarmId }
                        .also { Log.d("MemoryLog", "✅ CPS getAlarms() took ${System.currentTimeMillis() - cpsStart}ms") }
                }
                Log.d(TAG, "ALARM_LOAD_DEBUG: alarmId=$alarmId foundAlarm=$foundAlarm ringtoneUri=${foundAlarm?.ringtoneUri}")
                foundAlarm
                }
            } catch (_: Exception) { null }
                .also { Log.d("MemoryLog", "✅ remember(currentAlarm) completed in ${System.currentTimeMillis() - startMs}ms") }
        }

        // Title time + subtitle
        val ringtoneTitle = remember(alarmId, isPreview) {
            try {
                if (this@AlarmActivity.isPreview) {
                    "Preview"
                } else {
                    // CRITICAL FIX: Use same approach as MainActivity but with fallback for sequencer
                    val alarmRingtoneUri = currentAlarm?.ringtoneUri
                    
                    if (alarmRingtoneUri != null) {
                        // Use same approach as MainActivity
                        RingtoneHelper.getRingtoneTitle(this@AlarmActivity, alarmRingtoneUri)
                    } else {
                        // Fallback for sequencer missions - get ringtone from latest alarm
                        if (isSequencerMission) {
                            try {
                                val alarmStorage = AlarmStorage(applicationContext)
                                val alarmsWithRingtone = alarmStorage.getAlarms().filter { it.ringtoneUri != null }
                                val latestAlarmWithRingtone = alarmsWithRingtone.maxByOrNull { it.createdTime }
                                latestAlarmWithRingtone?.ringtoneUri?.let { uri ->
                                    RingtoneHelper.getRingtoneTitle(this@AlarmActivity, uri)
                                } ?: "None selected"
                            } catch (e: Exception) {
                                "None selected"
                            }
                        } else {
                            "None selected"
                        }
                    }
                }
            } catch (e: Exception) { 
                if (this@AlarmActivity.isPreview) "Preview" else "Unknown Ringtone" 
            }
        }

        val uiNowStr = remember(alarmId) {
            try {
                val fmt = java.text.SimpleDateFormat("H:mm", java.util.Locale.US)
                val time = fmt.format(java.util.Date())
                val (hour, minute) = time.split(":").map { it.toInt() }
                val period = if (hour < 12) "AM" else "PM"
                val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
                "$displayHour:${minute.toString().padStart(2, '0')} $period"
            } catch (_: Exception) { "--:--" }
        }

        // Wake-up Check prompt only on follow-up launches
        val wakeCheckConfig = remember(alarmId) {
            try {
                // Try DPS first, then CPS - fall back whenever DPS doesn't have this alarm
                // mirrored, not only on a hard read failure. A missing/stale DPS mirror was
                // silently treated as "wake-check disabled" (defaulting to null), which made
                // gateActive false on the follow-up launch even though the check was
                // legitimately scheduled - blanking the wake-up-check screen entirely since
                // the normal mission UI is also gated off during a wake-check launch.
                var alarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        AlarmStorage(createDeviceProtectedStorageContext()).getAlarms().find { it.id == alarmId }
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
                val foundInDps = alarm != null
                if (alarm == null) {
                    com.vaishnava.alarm.ExtremeForensicLogger.Chrona.cpsViolation(
                        alarmId,
                        "ALARM_STORAGE_ACCESS",
                        "AlarmActivity.getCurrentMissionFromAuthority",
                        applicationContext,
                        "CPS_ALARM_STORAGE"
                    )
                    alarm = AlarmStorage(applicationContext).getAlarms().find { it.id == alarmId }
                }
                val result = alarm?.let { it.wakeCheckEnabled to it.wakeCheckMinutes }
                    ?: (false to 5)
                Log.d(
                    "WAKECHECK_BUG",
                    "wakeCheckConfig: alarmId=$alarmId foundInDps=$foundInDps " +
                        "foundInCps=${alarm != null && !foundInDps} alarmNullAfterBothLookups=${alarm == null} " +
                        "resultWakeCheckEnabled=${result.first} resultWakeCheckMinutes=${result.second}"
                )
                result
            } catch (e: Exception) {
                Log.d("WAKECHECK_BUG", "wakeCheckConfig: EXCEPTION alarmId=$alarmId error=${e.message}")
                false to 5
            }
        }
        val restoredWakeCheckCycle = remember(
            alarmId,
            isWakeCheck,
            isWakeCheckLaunchState.value
        ) {
            val cycle = if (isWakeCheck && alarmId != -1) {
                runCatching { WakeCheckCycleStore.read(this@AlarmActivity, alarmId) }.getOrNull()
            } else {
                null
            }
            Log.d(
                "WAKECHECK_BUG",
                "restoredWakeCheckCycle: alarmId=$alarmId isWakeCheck=$isWakeCheck " +
                    "isWakeCheckLaunchState=${isWakeCheckLaunchState.value} cycleState=${cycle?.state} " +
                    "cycleId=${cycle?.cycleId} expiryAt=${cycle?.expiryAt}"
            )
            cycle
        }
        val restoredWakeCheckSeconds = remember(
            alarmId,
            isWakeCheck,
            restoredWakeCheckCycle?.cycleId,
            restoredWakeCheckCycle?.state,
            restoredWakeCheckCycle?.expiryAt
        ) {
            if (
                isWakeCheck &&
                restoredWakeCheckCycle?.state == WakeCheckCycleState.WAKEUP_CHECK_SHOWING
            ) {
                (((WakeCheckCycleStore.remainingWakeCheckMs(this@AlarmActivity, alarmId) + 999L) / 1000L)
                    .coerceIn(0L, 20L)).toInt()
            } else {
                20
            }
        }
        var gateActive by remember(isWakeCheck) { mutableStateOf(isWakeCheck && wakeCheckConfig.first) }
        var gateSeconds by remember(
            isWakeCheck,
            alarmId,
            restoredWakeCheckCycle?.cycleId,
            restoredWakeCheckCycle?.state,
            restoredWakeCheckCycle?.expiryAt
        ) { mutableStateOf(restoredWakeCheckSeconds) }

        LaunchedEffect(
            isWakeCheck,
            gateActive,
            gateSeconds,
            missionTapEnabled,
            missionMathEnabled,
            missionSttEnabled,
            missionFaceEnabled,
            isSequencerMission,
            requiredPassword,
            isDismissed,
            activityDismissed
        ) {
            Log.d(
                "WAKECHECK_BUG",
                "RENDER_DECISION: alarmId=$alarmId isWakeCheck=$isWakeCheck " +
                    "gateActive=$gateActive gateSeconds=$gateSeconds " +
                    "wakeCheckConfigEnabled=${wakeCheckConfig.first} wakeCheckConfigMinutes=${wakeCheckConfig.second} " +
                    "restoredCycleState=${restoredWakeCheckCycle?.state} " +
                    "missionTapEnabled=$missionTapEnabled missionMathEnabled=$missionMathEnabled " +
                    "missionSttEnabled=$missionSttEnabled missionFaceEnabled=$missionFaceEnabled " +
                    "isSequencerMission=$isSequencerMission requiredPassword=${requiredPassword != null} " +
                    "isDismissed=$isDismissed activityDismissed=$activityDismissed " +
                    "willShowWakeGate=${isWakeCheck && gateActive} " +
                    "willShowAnyMissionUi=${!isWakeCheck && (missionTapEnabled || missionMathEnabled || missionSttEnabled || missionFaceEnabled || isSequencerMission || requiredPassword != null)}"
            )
        }

        val wakeCheckUrgency = ((20 - gateSeconds).coerceIn(0, 20)) / 20f
        val wakeCheckAccent = lerp(Color(0xFFE45592), Color(0xFFFF7A3D), wakeCheckUrgency)
        val wakeCheckParticles = remember(alarmId) {
            val seededRandom = Random((alarmId * 53) + 19)
            List(96) {
                WakeCheckParticle(
                    xFraction = seededRandom.nextFloat(),
                    yFraction = seededRandom.nextFloat(),
                    radiusFraction = 0.0015f + (seededRandom.nextFloat() * 0.003f),
                    alpha = 0.08f + (seededRandom.nextFloat() * 0.18f)
                )
            }
        }
        val wakeCheckHoldDurationMs = 1_000
        val wakeCheckHoldInteractionSource = remember(isWakeCheck) { MutableInteractionSource() }
        val wakeCheckButtonPressed by wakeCheckHoldInteractionSource.collectIsPressedAsState()
        val wakeCheckHoldProgress = remember(isWakeCheck) { Animatable(0f) }
        var wakeCheckCompletionInProgress by remember(isWakeCheck) { mutableStateOf(false) }
        fun logWakeCheckUiForensic(event: String, extra: String = "") {
            this@AlarmActivity.logWakeCheckForensic(
                event,
                alarmId,
                "uiGateActive=$gateActive uiGateSeconds=$gateSeconds " +
                    "uiCompletionInProgress=$wakeCheckCompletionInProgress " +
                    "uiButtonPressed=$wakeCheckButtonPressed " +
                    "uiHoldProgress=${String.format(Locale.US, "%.2f", wakeCheckHoldProgress.value)} " +
                    extra
            )
        }

        fun finalizeWakeCheckFromUi() {
            if (wakeCheckCompletionInProgress || wakeCheckAcknowledged || activityDismissed || gateSeconds <= 0) {
                logWakeCheckUiForensic("ui_finalize_blocked")
                return
            }
            Log.d(TAG, "WakeCheckComplete: UI finalization start for alarmId=$alarmId")
            logWakeCheckUiForensic("ui_finalize_start")
            wakeCheckCompletionInProgress = true
            // Match the old single-tap "I'm awake" flow exactly:
            // close the wake-check gate first, then run the original ack path.
            wakeCheckGuardActive = false
            gateActive = false
            logWakeCheckUiForensic("ui_finalize_gate_cleared")
            acknowledgeWakeCheck(alarmId)
        }

        fun restartAlarmAfterWakeCheckTimeout(wakeCheckCycleId: Long, reason: String) {
            logWakeCheckUiForensic(
                "state_restore_timeout_retrigger",
                "reason=$reason cycleId=$wakeCheckCycleId"
            )
            gateActive = false
            wakeCheckGuardActive = false
            if (wakeCheckCycleId > 0L) {
                WakeCheckCycleStore.markWakeCheckMissed(
                    this@AlarmActivity,
                    alarmId,
                    wakeCheckCycleId
                )
            }

            try {
                Log.d("WakeCheckDebug", "AlarmActivity: Force finishing activity due to timer timeout for alarmId=$alarmId")
                isDismissed = true
                this@AlarmActivity.isDismissed = true
                activityDismissed = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    finishAndRemoveTask()
                } else {
                    finish()
                }
                overridePendingTransition(0, 0)
            } catch (_: Exception) { }

            try {
                Log.d("WakeCheckDebug", "AlarmActivity: Restarting AlarmForegroundService as NORMAL alarm for alarmId=$alarmId")
                val serviceIntent = Intent(this@AlarmActivity, AlarmForegroundService::class.java).apply {
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                    putExtra("from_wake_check", false)
                    if (wakeCheckCycleId > 0L) {
                        putExtra(Constants.EXTRA_WAKE_CHECK_RETRIGGER, true)
                        putExtra(Constants.EXTRA_WAKE_CHECK_CYCLE_ID, wakeCheckCycleId)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (_: Exception) { }

            runCatching {
                this@AlarmActivity.isWakeCheckLaunchState.value = false
            }
            runCatching {
                val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this@AlarmActivity
                val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("wakecheck_gate_active_$alarmId", false).commit()
            }
        }

        // When wake-up-check gate is visible, duck alarm audio so only the UI is prominent
        LaunchedEffect(gateActive) {
            if (gateActive) {
                // Mark that the wake-check gate is active so onNewIntent can
                // ignore stray normal alarm intents while the "I'm awake" UI
                // is on screen.
                wakeCheckGuardActive = true
                Log.d("WakeCheckDebug", "AlarmActivity.WakeCheck gateActive=true isWakeCheck=$isWakeCheck enabled=${wakeCheckConfig.first} alarmId=$alarmId")
                logWakeCheckUiForensic("ui_gate_activated")
                // Persist that the wake-check gate is currently active for this alarm
                runCatching {
                    val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this@AlarmActivity
                    val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("wakecheck_gate_active_$alarmId", true).commit()
                }
                runCatching {
                    val duck = Intent("com.vaishnava.alarm.DUCK_ALARM").apply {
                        putExtra("factor", 0f)
                        putExtra("duration", 20_000L)
                    }
                    this@AlarmActivity.sendBroadcast(duck)
                }
                // Ensure no alarm audio/vibration continues while wake-up-check UI is shown
                // Playback suppression is already handled by AlarmForegroundService when launched
                // as a wake-check follow-up; avoid stopping the service here to prevent restart races.
            } else {
                // When gate becomes inactive, stop the ducking effect
                logWakeCheckUiForensic("ui_gate_deactivated")
                runCatching {
                    val duck = Intent("com.vaishnava.alarm.DUCK_ALARM").apply {
                        putExtra("factor", 1f)
                        putExtra("duration", 0L)
                    }
                    this@AlarmActivity.sendBroadcast(duck)
                }
            }
        }
        LaunchedEffect(
            wakeCheckButtonPressed,
            gateActive,
            activityDismissed,
            wakeCheckAcknowledged,
            wakeCheckCompletionInProgress
        ) {
            if (!gateActive || activityDismissed || wakeCheckAcknowledged || wakeCheckCompletionInProgress || gateSeconds <= 0) {
                wakeCheckHoldProgress.snapTo(0f)
                return@LaunchedEffect
            }

            if (!wakeCheckButtonPressed) {
                if (wakeCheckHoldProgress.value > 0f) {
                    Log.d(TAG, "WakeCheckHold: released early for alarmId=$alarmId")
                    logWakeCheckUiForensic("ui_hold_released_early")
                }
                wakeCheckHoldProgress.snapTo(0f)
                return@LaunchedEffect
            }

            // Do not key this effect on gateSeconds. The countdown changes every second,
            // and restarting the coroutine on each tick prevents a continuous hold from completing.
            wakeCheckHoldProgress.snapTo(0f)
            Log.d(TAG, "WakeCheckHold: press start for alarmId=$alarmId")
            logWakeCheckUiForensic("ui_hold_press_start")
            wakeCheckHoldProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = wakeCheckHoldDurationMs, easing = LinearEasing)
            )

            if (wakeCheckButtonPressed &&
                gateActive &&
                !activityDismissed &&
                !wakeCheckAcknowledged &&
                !wakeCheckCompletionInProgress &&
                gateSeconds > 0
            ) {
                Log.d(TAG, "WakeCheckHold: threshold reached for alarmId=$alarmId")
                logWakeCheckUiForensic("ui_hold_threshold_reached")
                finalizeWakeCheckFromUi()
            }
        }
        if (gateActive) {
            LaunchedEffect(gateActive) {
                if (gateActive) {
                    val cycle = runCatching { WakeCheckCycleStore.read(this@AlarmActivity, alarmId) }.getOrNull()
                    val now = System.currentTimeMillis()
                    val restoredRemainingMs =
                        if (cycle?.state == WakeCheckCycleState.WAKEUP_CHECK_SHOWING) {
                            WakeCheckCycleStore.remainingWakeCheckMs(this@AlarmActivity, alarmId, now)
                        } else {
                            0L
                        }
                    logWakeCheckUiForensic(
                        "state_restore",
                        "source=AlarmActivity cycleState=${cycle?.state ?: "null"} " +
                            "triggeredAt=${cycle?.triggeredAt ?: 0L} expiryAt=${cycle?.expiryAt ?: 0L} " +
                            "remainingMs=$restoredRemainingMs"
                    )
                    if (
                        cycle?.state == WakeCheckCycleState.WAKEUP_CHECK_SHOWING &&
                        cycle.expiryAt in 1L..now
                    ) {
                        restartAlarmAfterWakeCheckTimeout(cycle.cycleId, "expired_on_restore")
                        return@LaunchedEffect
                    }
                    if (
                        cycle?.state == WakeCheckCycleState.WAKEUP_CHECK_SHOWING &&
                        cycle.expiryAt <= 0L
                    ) {
                        logWakeCheckUiForensic(
                            "state_restore_invariant_fail",
                            "reason=missing_expiry cycleState=${cycle.state}"
                        )
                        gateActive = false
                        wakeCheckGuardActive = false
                        return@LaunchedEffect
                    }
                    val expiryAt =
                        if (cycle?.state == WakeCheckCycleState.WAKEUP_CHECK_SHOWING && cycle.expiryAt > now) {
                            cycle.expiryAt
                        } else {
                            now + 20_000L
                        }
                    gateSeconds =
                        (((expiryAt - now).coerceAtLeast(0L) + 999L) / 1000L)
                            .coerceIn(0L, 20L)
                            .toInt()
                    logWakeCheckUiForensic("ui_timer_started")
                    while (
                        gateSeconds > 0 &&
                        gateActive &&
                        !activityDismissed &&
                        !wakeCheckAcknowledged &&
                        !wakeCheckCompletionInProgress
                    ) {
                        val remainingMs = (expiryAt - System.currentTimeMillis()).coerceAtLeast(0L)
                        gateSeconds =
                            ((remainingMs + 999L) / 1000L)
                                .coerceIn(0L, 20L)
                                .toInt()
                        if (remainingMs <= 0L) {
                            break
                        }
                        logWakeCheckUiForensic("ui_timer_tick")
                        delay(minOf(1000L, remainingMs))
                    }
                    
                    // CRITICAL: Only restart if NOT acknowledged and NOT dismissed
                    if (!wakeCheckAcknowledged && !activityDismissed && !wakeCheckCompletionInProgress) {
                        logWakeCheckUiForensic("ui_timer_expired_retriggering")
                        val wakeCheckCycleId = WakeCheckCycleStore.currentCycleId(this@AlarmActivity, alarmId)
                        restartAlarmAfterWakeCheckTimeout(wakeCheckCycleId, "ui_timer_expired")
                    } else {
                        Log.d("WakeCheckDebug", "AlarmActivity: Timer completed but alarm was acknowledged or dismissed, not restarting for alarmId=$alarmId")
                        logWakeCheckUiForensic("ui_timer_completed_without_retrigger")
                    }
                    
                    // CRITICAL: Exit the LaunchedEffect immediately
                    return@LaunchedEffect
                }
            }
            AnimatedVisibility(
                visible = gateActive && !activityDismissed,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                val wakeCheckMotion = rememberInfiniteTransition(label = "wakeCheckMotion")
                val orbDrift by wakeCheckMotion.animateFloat(
                    initialValue = -4f,
                    targetValue = 4f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 3600, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "wakeCheckOrbDrift"
                )
                val orbGlow by wakeCheckMotion.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 0.72f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 2800, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "wakeCheckOrbGlow"
                )
                val wakeCheckActionEnabled =
                    gateSeconds > 0 &&
                        !activityDismissed &&
                        !wakeCheckCompletionInProgress &&
                        !wakeCheckAcknowledged
                val wakeCheckProgress = gateSeconds.coerceIn(0, 20) / 20f
                val wakeCheckHoldFraction = wakeCheckHoldProgress.value.coerceIn(0f, 1f)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(10f)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF08030C),
                                    Color(0xFF18040E),
                                    Color(0xFF28050F),
                                    Color(0xFF12020B)
                                )
                            )
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFA01A6A).copy(alpha = 0.20f + (wakeCheckUrgency * 0.08f)),
                                    Color.Transparent
                                ),
                                center = Offset(size.width * 0.28f, size.height * 0.24f),
                                radius = size.minDimension * 0.48f
                            ),
                            radius = size.minDimension * 0.48f,
                            center = Offset(size.width * 0.28f, size.height * 0.24f)
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFFF5E2B).copy(alpha = 0.24f + (wakeCheckUrgency * 0.12f)),
                                    Color.Transparent
                                ),
                                center = Offset(size.width * 0.70f, size.height * 0.64f),
                                radius = size.minDimension * 0.58f
                            ),
                            radius = size.minDimension * 0.58f,
                            center = Offset(size.width * 0.70f, size.height * 0.64f)
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    wakeCheckAccent.copy(alpha = 0.18f + (wakeCheckUrgency * 0.18f)),
                                    Color.Transparent
                                ),
                                center = Offset(size.width * 0.50f, size.height * 0.54f),
                                radius = size.minDimension * 0.26f
                            ),
                            radius = size.minDimension * 0.26f,
                            center = Offset(size.width * 0.50f, size.height * 0.54f)
                        )
                        wakeCheckParticles.forEach { particle ->
                            drawCircle(
                                color = lerp(
                                    Color(0xFFC58BFF).copy(alpha = particle.alpha * 0.45f),
                                    Color(0xFFFFAA72).copy(alpha = particle.alpha),
                                    wakeCheckUrgency
                                ),
                                radius = size.minDimension * particle.radiusFraction,
                                center = Offset(
                                    x = size.width * particle.xFraction,
                                    y = size.height * particle.yFraction
                                )
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 28.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Color(0xFF2B1331).copy(alpha = 0.82f))
                                    .padding(horizontal = 24.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = "Wake Check",
                                    color = Color.White.copy(alpha = 0.92f),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            Text(
                                text = "Are you awake?",
                                color = Color.White.copy(alpha = 0.98f),
                                fontSize = 34.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Press and hold for 1 second",
                                color = Color(0xFFD7C4CC).copy(alpha = 0.82f),
                                fontSize = 18.sp,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "${gateSeconds}s remaining",
                                color = Color(0xFFFFD5C2).copy(alpha = 0.86f),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center
                            )
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(28.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(286.dp)
                                        .clip(CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val startAngle = 132f
                                        val totalSweep = 286f
                                        val strokeWidth = 11.dp.toPx()
                                        drawArc(
                                            color = Color.White.copy(alpha = 0.08f + (wakeCheckUrgency * 0.05f)),
                                            startAngle = startAngle,
                                            sweepAngle = totalSweep,
                                            useCenter = false,
                                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                        )
                                        drawArc(
                                            brush = Brush.sweepGradient(
                                                colors = listOf(
                                                    Color(0xFFAC2D7D),
                                                    wakeCheckAccent,
                                                    Color(0xFFFFA24B),
                                                    Color(0xFFAC2D7D)
                                                )
                                            ),
                                            startAngle = startAngle,
                                            sweepAngle = totalSweep * wakeCheckProgress,
                                            useCenter = false,
                                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                        )
                                        if (wakeCheckHoldFraction > 0f) {
                                            drawArc(
                                                color = Color.White.copy(alpha = 0.92f),
                                                startAngle = startAngle + 18f,
                                                sweepAngle = (totalSweep - 36f) * wakeCheckHoldFraction,
                                                useCenter = false,
                                                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                                            )
                                        }
                                        drawCircle(
                                            brush = Brush.radialGradient(
                                                colors = listOf(
                                                    Color(0xFFFF742D).copy(alpha = 0.16f + (wakeCheckUrgency * 0.14f)),
                                                    Color.Transparent
                                                ),
                                                center = center,
                                                radius = size.minDimension * 0.34f
                                            ),
                                            radius = size.minDimension * 0.34f,
                                            center = center
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(164.dp)
                                            .offset(y = orbDrift.dp)
                                            .background(
                                                brush = Brush.radialGradient(
                                                    colors = listOf(
                                                        Color(0xFFFF7A3D).copy(alpha = orbGlow * (0.36f + (wakeCheckUrgency * 0.18f))),
                                                        Color.Transparent
                                                    )
                                                ),
                                                shape = CircleShape
                                            )
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(132.dp)
                                            .offset(y = orbDrift.dp)
                                            .background(
                                                brush = Brush.radialGradient(
                                                    colors = listOf(
                                                        Color.White.copy(alpha = 0.95f),
                                                        Color(0xFFFFCB7A).copy(alpha = 0.92f),
                                                        Color(0xFFFF7A3D).copy(alpha = 0.86f),
                                                        Color(0xFF61120E)
                                                    )
                                                ),
                                                shape = CircleShape
                                            )
                                            .border(
                                                width = 1.2.dp,
                                                color = Color(0xFFFFC68C).copy(alpha = 0.70f),
                                                shape = CircleShape
                                            )
                                    )
                                }
                            }

                            Text(
                                text = if (wakeCheckButtonPressed) {
                                    "Keep holding..."
                                } else {
                                    "Hold until the inner ring completes"
                                },
                                color = Color.White.copy(alpha = 0.84f),
                                fontSize = 18.sp,
                                textAlign = TextAlign.Center
                            )

                            Button(
                                onClick = {},
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(76.dp),
                                enabled = wakeCheckActionEnabled,
                                interactionSource = wakeCheckHoldInteractionSource,
                                shape = RoundedCornerShape(40.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = lerp(Color(0xFFB4312E), Color(0xFFCE492E), wakeCheckUrgency),
                                    disabledContainerColor = Color(0xFF5A3030),
                                    contentColor = Color.White,
                                    disabledContentColor = Color.White.copy(alpha = 0.7f)
                                )
                            ) {
                                Text(
                                    text = if (wakeCheckButtonPressed) "KEEP HOLDING..." else "HOLD TO END",
                                    color = Color.White,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (!writeSettingsGranted) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .clip(RoundedCornerShape(999.dp))
                                    .clickable { onOpenWriteSettings() }
                                    .alpha(0.48f)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Image(
                                    painter = painterResource(id = android.R.drawable.ic_menu_manage),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    colorFilter = ColorFilter.tint(Color(0xFFE1D2DA))
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(
                                    text = "Modify system settings",
                                    color = Color(0xFFE1D2DA),
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.height(40.dp))
                        }
                    }
                }
            }
            return
        }

        AnimatedVisibility(
            visible = !writeSettingsGranted && !gateActive,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "System volume control requires enabling 'Modify system settings'.",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = onOpenWriteSettings,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .fillMaxWidth(0.85f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("Open Settings", fontWeight = FontWeight.Bold)
                }
            }
        }

        // ---- TIMER ENGINE ----
        // Start Mission -> 90s StartupBlocked -> 30s InitialEntry -> 120s Blocked -> loop
        // CRITICAL FIX: Timer moved to background handler to prevent recomposition loop
        // Each state transition is instant - timer ticks happen on separate thread
        LaunchedEffect(missionStarted, timerState, missionMathEnabled, requiredPassword) {
            if (!missionStarted || activityDismissed || missionMathEnabled || requiredPassword == null) {
                if (!missionStarted || missionMathEnabled || requiredPassword == null) {
                    timerState = TimerState.Idle
                    remainingTime = 0
                }
                return@LaunchedEffect
            }

            when (timerState) {
                TimerState.Idle, TimerState.StartupBlocked -> {
                    // Instant state change - timer countdown is handled by the timer loop
                    timerState = TimerState.StartupBlocked
                    remainingTime = STARTUP_BLOCKED_TIME_SECONDS
                }
                TimerState.InitialEntry -> {
                    // Instant state change - no blocking loop
                    remainingTime = INITIAL_PASSWORD_ENTRY_TIME_SECONDS
                    // Focus and show immediately - AnimatedVisibility handles timing
                    runCatching { focusRequester.requestFocus() }
                    keyboardController?.show()

                    // Schedule background timer tick
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (timerState == TimerState.InitialEntry && remainingTime <= 0) {
                            keyboardController?.hide()
                            timerState = TimerState.Blocked
                        }
                    }, (INITIAL_PASSWORD_ENTRY_TIME_SECONDS * 1000).toLong())
                }
                TimerState.Blocked -> {
                    // Instant state change - no blocking loop
                    remainingTime = BLOCKED_TIME_SECONDS
                    keyboardController?.hide()

                    // Schedule background timer tick
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (timerState == TimerState.Blocked && remainingTime <= 0) {
                            timerState = TimerState.InitialEntry
                        }
                    }, (BLOCKED_TIME_SECONDS * 1000).toLong())
                }
            }
        }

        // Separate handler for updating remainingTime without triggering recompositions
        // Timer loop runs continuously during mission without restarting on state changes
        LaunchedEffect(missionStarted) {
            if (!missionStarted) return@LaunchedEffect

            timerPhaseStartTime = System.currentTimeMillis()

            while (missionStarted && !isDismissed && !activityDismissed && timerState != TimerState.Idle) {
                val currentTime = System.currentTimeMillis()
                val elapsedSincePhaseStart = (currentTime - timerPhaseStartTime) / 1000

                val max = when (timerState) {
                    TimerState.StartupBlocked -> STARTUP_BLOCKED_TIME_SECONDS
                    TimerState.InitialEntry -> INITIAL_PASSWORD_ENTRY_TIME_SECONDS
                    TimerState.Blocked -> BLOCKED_TIME_SECONDS
                    else -> 0
                }
                remainingTime = (max - elapsedSincePhaseStart).toInt().coerceAtLeast(0)

                // Transition when countdown reaches 0
                if (remainingTime <= 0) {
                    timerState = when (timerState) {
                        TimerState.StartupBlocked -> TimerState.InitialEntry
                        TimerState.InitialEntry -> TimerState.Blocked
                        TimerState.Blocked -> TimerState.InitialEntry
                        else -> TimerState.Idle
                    }
                    timerPhaseStartTime = currentTime
                }

                delay(100) // Update display 10x per second instead of every second
            }
        }

        // Auto safety dismiss after 10 minutes
        LaunchedEffect(Unit) {
            delay(10 * 60 * 1000)
            if (!isDismissed && !activityDismissed) this@AlarmActivity.dismissAlarm(alarmId)
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isPreview) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    val noRipple = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Text(
                        text = "exit preview",
                        color = Color(0xFF90A4AE),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .clickable(indication = null, interactionSource = noRipple) { exitPreview() }
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            
            // ✅ Test Alarm Display
            if (isTestAlarm) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF4CAF50).copy(alpha = 0.2f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, Color(0xFF4CAF50)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "🧪 TEST ALARM",
                            color = Color(0xFF4CAF50),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Source: ${testSource ?: "Unknown"}",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Alarm ID: $alarmId",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            // Clock title + subtitle only when not in the wake-check gate
            if (!gateActive) {
                Text(
                    text = uiNowStr,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = ringtoneTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
            }

            // ===== TAP CHALLENGE (only after Start Mission; only on initial alarm) =====
            if (!isWakeCheck && missionTapEnabled && missionStarted && !gateActive && !isDismissed) {
                LaunchedEffect(missionStarted, currentMissionId) {
                    val requiredTapCount = if (isPreview) 5 else 108
                    val tapWindowSeconds = if (isPreview) 30 else 105

                    // CRITICAL SEQUENCER FIX: For sequencer missions, ignore timer state and use the tap timer only.
                    if (isSequencerMission) {
                        timerState = TimerState.Idle
                    }

                    while (!isDismissed && missionTapEnabled && missionStarted && !gateActive && !activityDismissed) {
                        tapSeconds = tapWindowSeconds

                        while (
                            tapSeconds > 0 &&
                            tapCount < requiredTapCount &&
                            !isDismissed &&
                            missionTapEnabled &&
                            missionStarted &&
                            !gateActive &&
                            !activityDismissed
                        ) {
                            delay(1000)
                            tapSeconds -= 1
                        }

                        if (
                            isDismissed ||
                            !missionTapEnabled ||
                            !missionStarted ||
                            gateActive ||
                            activityDismissed ||
                            tapCount >= requiredTapCount
                        ) {
                            break
                        }

                        Log.d(
                            TAG,
                            "TAP_CHALLENGE_RESTART: alarmId=$alarmId missionId=$currentMissionId tapCount=$tapCount"
                        )
                        tapCount = 0
                        clearTapCount(alarmId)
                    }
                }

                // Moving round button implementation
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Tap Challenge",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Tap ${if (isPreview) 5 else 108} times in ${tapSeconds}s",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Stationary centered round button
                    val density = LocalDensity.current
                    var containerW by remember { mutableStateOf(0) }
                    var containerH by remember { mutableStateOf(0) }
                    var btnX by remember { mutableStateOf(0.dp) }
                    var btnY by remember { mutableStateOf(0.dp) }
                    val safePad = 16.dp

                    // Compute current button size based on tapCount
                    val currentBtnSize = when {
                        tapCount >= 96 -> 40.dp
                        tapCount >= 72 -> 48.dp
                        tapCount >= 36 -> 56.dp
                        else -> 64.dp
                    }

                    // Clamp and optionally randomize initial placement within bounds
                    LaunchedEffect(containerW, containerH, currentBtnSize) {
                        if (containerW > 0 && containerH > 0 && !activityDismissed) {
                            val padPx = with(density) { safePad.toPx() }
                            val usableW = (containerW - 2 * padPx).coerceAtLeast(0f)
                            val usableH = (containerH - 2 * padPx).coerceAtLeast(0f)
                            val btnPx = with(density) { currentBtnSize.toPx() }
                            val maxX = (usableW - btnPx).toInt().coerceAtLeast(0)
                            val maxY = (usableH - btnPx).toInt().coerceAtLeast(0)

                            if (tapCount == 0) {
                                val rx = if (maxX > 0) Random.nextInt(0, maxX + 1) else 0
                                val ry = if (maxY > 0) Random.nextInt(0, maxY + 1) else 0
                                btnX = with(density) { rx.toDp() }
                                btnY = with(density) { ry.toDp() }
                            } else {
                                // Clamp existing position if size/container changed
                                val xPx = with(density) { btnX.toPx() }.coerceIn(0f, maxX.toFloat())
                                val yPx = with(density) { btnY.toPx() }.coerceIn(0f, maxY.toFloat())
                                btnX = with(density) { xPx.toDp() }
                                btnY = with(density) { yPx.toDp() }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .onSizeChanged { sz ->
                                containerW = sz.width
                                containerH = sz.height
                            }
                    ) {
                        // Per-tap letter label from mantra text (letters only; skip spaces/newlines)
                        val mantraText = """
                            Hare Krishna Hare Krishna
                            Krishna Krishna Hare Hare
                            Hare Rama Hare Rama
                            Rama Rama Hare Hare
                            Om Shri Raghavendraya Namah
                        """.trimIndent()
                        val mantraLetters = remember { mantraText.filter { it.isLetter() } }
                        val labelChar = mantraLetters.getOrNull(tapCount)?.toString() ?: "✓"

                        Button(
                            onClick = {
                                if (!isDismissed && tapSeconds > 0) {
                                    tapCount += 1
                                    saveTapCount(alarmId, tapCount)

                                    // Move to a new random position within bounds on every click
                                    val padPx = with(density) { safePad.toPx() }
                                    val btnPx = with(density) { currentBtnSize.toPx() }
                                    val usableW = (containerW - 2 * padPx).coerceAtLeast(0f)
                                    val usableH = (containerH - 2 * padPx).coerceAtLeast(0f)
                                    val maxX = (usableW - btnPx).toInt().coerceAtLeast(0)
                                    val maxY = (usableH - btnPx).toInt().coerceAtLeast(0)
                                    val rx = if (maxX > 0) Random.nextInt(0, maxX + 1) else 0
                                    val ry = if (maxY > 0) Random.nextInt(0, maxY + 1) else 0
                                    btnX = with(density) { rx.toDp() }
                                    btnY = with(density) { ry.toDp() }

                                    if (tapCount >= (if (isPreview) 5 else 108)) {
                                        clearTapCount(alarmId)
                                        // Mission success: handle completion appropriately
                                        if (isSequencerMission) {
                                            // For sequencer missions, don't dismiss - advance to next mission
                                            this@AlarmActivity.onTapMissionSuccess(alarmId)
                                        } else {
                                            // For normal missions, dismiss as before
                                            isDismissed = true
                                            this@AlarmActivity.onTapMissionSuccess(alarmId)
                                        }
                                    }
                                }
                            },
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier
                                .offset(x = btnX + safePad, y = btnY + safePad)
                                .size(currentBtnSize)
                        ) {
                            Text(labelChar, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Progress text kept always visible below the moving area
                    Text(
                        text = "$tapCount of ${if (isPreview) 5 else 108}",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }

            if (!isWakeCheck && missionFaceEnabled && missionStarted && !gateActive && !isDismissed) {
                // The mission's own enrolled face - NOT the alarm's requiresFaceAuth face.
                // Preview runs on a throwaway alarm id with nothing committed to it, so it
                // falls back to the face staged in the add-alarm dialog.
                val enrolledFace = remember(alarmId, isPreview) {
                    FaceMissionSupport.getMissionEmbedding(this@AlarmActivity, alarmId)
                        ?: if (isPreview) {
                            FaceMissionSupport.getPendingEmbedding(this@AlarmActivity)
                        } else null
                }

                if (enrolledFace != null) {
                    // FaceAuthDialog already owns the camera lifecycle and releases it in
                    // onDispose, so the mission inherits that fix rather than binding again.
                    // currentAlarm is null in preview (no real alarm 9100), and `?.copy()`
                    // would make the whole argument null - which made FaceAuthDialog show
                    // its own "Face Enrollment Required" even though a face IS enrolled.
                    val alarmForFaceMission = currentAlarm?.copy(enrolledFaceEmbedding = enrolledFace)
                        ?: com.vaishnava.alarm.data.Alarm(
                            id = alarmId,
                            hour = 0,
                            minute = 0,
                            isEnabled = false,
                            ringtoneUri = null,
                            enrolledFaceEmbedding = enrolledFace
                        )

                    com.vaishnava.alarm.faceauth.FaceAuthDialog(
                        action = com.vaishnava.alarm.faceauth.MatchAction.CREATE_OR_EDIT,
                        alarmId = alarmId,
                        alarm = alarmForFaceMission,
                        onDismiss = {
                            // Cancelling verification must never dismiss the alarm.
                            faceMissionStatus = "Verification cancelled. Try again."
                        },
                        onVerificationSuccess = {
                            faceMissionStatus = null
                            this@AlarmActivity.onMissionCompleted(alarmId)
                        },
                        onRequestEnrollment = {
                            faceMissionStatus = "No face enrolled for this alarm."
                        },
                        // An old (pre-1.0.6) mission face just matched: keep the new aligned one.
                        onTemplateUpgraded = { upgraded ->
                            if (!isPreview) {
                                FaceMissionSupport.saveMissionEmbedding(this@AlarmActivity, alarmId, upgraded)
                            }
                        }
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "FACE VERIFY",
                            color = Color(0xFFFFAB91),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "No face is enrolled for this alarm, so it cannot be " +
                                "verified. Enrol a face in the alarm settings.",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                faceMissionStatus?.let { message ->
                    Text(
                        text = message,
                        color = Color.Red,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                }
            }

            if (!isWakeCheck && missionSttEnabled && missionStarted && !gateActive && !isDismissed) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val sttAccent = Color(0xFFFFAB91)
                    val sttSuccess = Color(0xFF4CAF50)
                    val sttError = Color(0xFFE57373)

                    // Hands-free: arm the mic as soon as the challenge appears.
                    LaunchedEffect(missionSttEnabled, missionStarted) {
                        if (missionSttEnabled && missionStarted && !sttAutoStarted) {
                            sttAutoStarted = true
                            this@AlarmActivity.startSttRecognition(alarmId)
                        }
                    }

                    Text(
                        text = "SPEECH CHALLENGE",
                        color = sttAccent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Target phrase is the hero element, mirroring how Math frames its equation.
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = sttAccent.copy(alpha = 0.10f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, sttAccent.copy(alpha = 0.45f)
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "SAY THIS PHRASE",
                                color = sttAccent.copy(alpha = 0.85f),
                                style = MaterialTheme.typography.labelSmall,
                                letterSpacing = 1.5.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(10.dp))

                            val phraseWords = SttMissionSupport.displayWords(sttTargetPhrase)
                            Text(
                                text = buildAnnotatedString {
                                    phraseWords.forEachIndexed { index, word ->
                                        val spoken = index < sttMatchedWords
                                        withStyle(
                                            SpanStyle(
                                                color = if (spoken) sttSuccess else Color.White,
                                                fontWeight = if (spoken) FontWeight.Bold else FontWeight.SemiBold
                                            )
                                        ) {
                                            append(word)
                                        }
                                        if (index != phraseWords.lastIndex) append("  ")
                                    }
                                },
                                style = MaterialTheme.typography.displaySmall,
                                textAlign = TextAlign.Center
                            )

                            if (phraseWords.size > 1) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = "$sttMatchedWords of ${phraseWords.size} words",
                                    color = if (sttMatchedWords == phraseWords.size) sttSuccess
                                    else Color.White.copy(alpha = 0.55f),
                                    style = MaterialTheme.typography.labelMedium,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier.size(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Scoped inside the branch so no animation frames are requested while idle.
                        if (sttListening) {
                            val sttPulse by rememberInfiniteTransition(label = "sttPulse")
                                .animateFloat(
                                    initialValue = 0f,
                                    targetValue = 1f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1100, easing = LinearEasing),
                                        repeatMode = RepeatMode.Restart
                                    ),
                                    label = "sttPulseDriver"
                                )
                            Box(
                                modifier = Modifier
                                    .size(108.dp)
                                    .scale(1f + sttPulse * 0.38f)
                                    .clip(CircleShape)
                                    .background(sttAccent.copy(alpha = 0.30f * (1f - sttPulse)))
                            )
                        }

                        val micBase = Modifier
                            .size(108.dp)
                            .clip(CircleShape)
                            .background(
                                if (sttListening) sttAccent else Color.White.copy(alpha = 0.08f)
                            )
                        // A 0.dp border is avoided entirely rather than conditionally sized.
                        val micModifier = (
                            if (sttListening) micBase
                            else micBase.border(1.dp, sttAccent.copy(alpha = 0.55f), CircleShape)
                            ).clickable(enabled = !sttListening) {
                            this@AlarmActivity.startSttRecognition(alarmId)
                        }

                        Box(
                            modifier = micModifier,
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = if (sttListening) "Listening" else "Tap to speak",
                                tint = if (sttListening) Color.Black else sttAccent,
                                modifier = Modifier.size(46.dp)
                            )
                        }
                    }

                    Text(
                        text = if (sttListening) "Listening…" else "Tap the microphone to speak again",
                        color = Color.White.copy(alpha = if (sttListening) 1f else 0.72f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (sttListening) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (sttRecognizedText.isNotBlank()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = Color.White.copy(alpha = 0.06f)
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "HEARD",
                                    color = Color.White.copy(alpha = 0.45f),
                                    style = MaterialTheme.typography.labelSmall,
                                    letterSpacing = 1.5.sp
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = sttRecognizedText,
                                    color = Color.White.copy(alpha = 0.85f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    sttStatusMessage?.let { message ->
                        val isSuccess = message.startsWith("Correct")
                        val statusColor = if (isSuccess) sttSuccess else sttError
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = statusColor.copy(alpha = 0.16f)
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, statusColor.copy(alpha = 0.55f)
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = message,
                                color = statusColor,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            }

            if (!isWakeCheck && missionMathEnabled && missionStarted && !gateActive && !isDismissed) {
                val activeMathMission = mathMissionSpec
                val mathConfig = activeMathMission?.let { MathMissionSupport.readConfig(it) }
                val mathRuntime = activeMathMission?.let { MathMissionSupport.readRuntimeState(it) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Math Challenge",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (mathConfig != null && mathRuntime != null) {
                        Text(
                            text = "Difficulty: ${mathConfig.difficulty.displayName}",
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Solved ${mathRuntime.solvedCount} of ${mathConfig.requiredCorrectAnswers}",
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Time left: ${mathRemainingSeconds}s",
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = mathRuntime.currentQuestion.expressionText,
                            color = Color.White,
                            style = MaterialTheme.typography.displaySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = mathAnswerInput,
                            onValueChange = { value ->
                                mathAnswerInput = value.filter { it.isDigit() || it == '-' }.take(8)
                                mathStatusMessage = null
                            },
                            label = { Text("Answer") },
                            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                val currentMission = mathMissionSpec ?: return@Button
                                val result = MathMissionSupport.submitAnswer(
                                    mission = currentMission,
                                    answerText = mathAnswerInput,
                                    nowElapsedMs = android.os.SystemClock.elapsedRealtime()
                                )

                                if (result.updatedMission != currentMission) {
                                    this@AlarmActivity.persistCurrentMissionSnapshot(result.updatedMission)
                                    mathMissionSpec = result.updatedMission
                                }

                                mathAnswerInput = ""
                                mathStatusMessage = when {
                                    result.missionCompleted -> "Correct."
                                    result.wasCorrect -> "Correct. Next question generated."
                                    result.questionChanged -> "Time expired. New question generated."
                                    else -> "Incorrect answer."
                                }

                                if (result.missionCompleted) {
                                    this@AlarmActivity.onMissionCompleted(alarmId)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(0.8f)
                        ) {
                            Text("Submit")
                        }

                        mathStatusMessage?.let { message ->
                            Text(
                                text = message,
                                color = if (message.startsWith("Incorrect")) Color.Red else Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // ===== BEFORE MISSION STARTS (unified for any mission except NONE) =====
            run {
                // For protected alarms,// CRITICAL FIX: Use intent-based mission detection for screen-off reliability
                val hasMission = if (isPreview) {
                    true
                } else if (currentAlarm?.isProtected == true) {
                    val missionType = intent?.getStringExtra("mission_type")?.let { if (it == "none" || it.isEmpty()) "" else it }
                        ?: currentMissionId?.let { if (it == "none" || it.isEmpty()) "" else it }
                        ?: currentAlarm?.missionType?.let { if (it == "none" || it.isEmpty()) "" else it } ?: ""
                    missionType == "tap" ||
                        missionType == "password" ||
                        missionType == MathMissionSupport.MISSION_ID ||
                        missionType == SttMissionSupport.MISSION_ID ||
                        missionType == FaceMissionSupport.MISSION_ID ||
                        (isSequencerMission && (missionTapEnabled || missionMathEnabled || missionSttEnabled || missionFaceEnabled || requiredPassword != null))
                } else {
                    val missionType = intent?.getStringExtra("mission_type") ?: currentMissionId ?: ""
                    if (missionType == "none" || missionType.isEmpty()) {
                        false
                    } else {
                        (missionTapEnabled || missionMathEnabled || missionSttEnabled || missionFaceEnabled || requiredPassword != null)
                    }
                }
                if (!isWakeCheck && hasMission) {
                    AnimatedVisibility(
                        visible = !missionStarted && !gateActive && !isDismissed,
                        enter = fadeIn() + slideInVertically(),
                        exit = fadeOut() + slideOutVertically()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Button(
                                onClick = {
                                    missionStarted = true
                                    mathStatusMessage = null
                                    mathAnswerInput = ""
                                    if (requiredPassword != null) {
                                        if (isPreview) {
                                            timerState = TimerState.InitialEntry
                                            remainingTime = 30
                                        } else {
                                            timerState = TimerState.StartupBlocked
                                            remainingTime = STARTUP_BLOCKED_TIME_SECONDS
                                        }
                                        passwordInput = ""
                                        showPasswordError = false
                                    } else {
                                        timerState = TimerState.Idle
                                        remainingTime = 0
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(0.8f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFAB91))
                            ) {
                                Text(
                                    text = "Start Mission",
                                    maxLines = 1,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            // ===== DURING MISSION: STATUS STRIP (only on initial alarm) =====
            if (!isWakeCheck && !missionTapEnabled && !missionMathEnabled && !missionSttEnabled && !missionFaceEnabled && missionStarted && !gateActive && !isDismissed) {
                Spacer(Modifier.height(8.dp))
                val statusText = when (timerState) {
                    TimerState.StartupBlocked -> "Startup blocked. Wait $remainingTime sec."
                    TimerState.InitialEntry   -> "Enter password. Remaining time: $remainingTime sec."
                    TimerState.Blocked        -> "Input blocked. Try again in $remainingTime sec."
                    else                      -> ""
                }
                if (statusText.isNotEmpty()) {
                    Text(
                        text = statusText,
                        color = if (timerState == TimerState.Blocked || timerState == TimerState.StartupBlocked) Color.Red else Color.White,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ===== PASSWORD UI: ONLY DURING InitialEntry AFTER 90s (only on initial alarm) =====
            val passwordUiVisible =
                !isWakeCheck &&
                    !missionTapEnabled &&
                    !missionMathEnabled &&
                    missionStarted &&
                    timerState == TimerState.InitialEntry &&
                    requiredPassword != null &&
                    !gateActive &&
                    !isDismissed

            // ROBUST FOCUS: the field below lives inside AnimatedVisibility, so on a slow / cold
            // -started device it may not be laid out yet when the timer coroutine's fixed-delay
            // focusRequester.requestFocus() fires (see TimerState.InitialEntry). When that happens
            // the IME opens (keyboardController.show() still runs) but has NO input target, so
            // typing does nothing. Re-acquire focus + IME the moment the password field genuinely
            // becomes visible, retrying briefly to outlast the enter animation. Idempotent (single
            // field) and scoped to passwordUiVisible, so it never steals focus mid-typing.
            LaunchedEffect(passwordUiVisible) {
                if (passwordUiVisible) {
                    repeat(6) {
                        delay(100)
                        runCatching { focusRequester.requestFocus() }
                        keyboardController?.show()
                    }
                }
            }

            AnimatedVisibility(
                visible = passwordUiVisible,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Log.d("PasswordUI", "🎨 Password UI rendering: visible=$passwordUiVisible, input='$passwordInput', timerState=$timerState")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Enter password to dismiss",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { newValue ->
                            // DIAGNOSTIC: Log every keystroke to identify freeze point
                            Log.d("PasswordInput", "📝 Keystroke: '${newValue.lastOrNull()}' length=${newValue.length}")
                            val startTime = System.nanoTime()

                            passwordInput = newValue
                            showPasswordError = false

                            val duration = (System.nanoTime() - startTime) / 1_000_000
                            Log.d("PasswordInput", "✅ Keystroke processed in ${duration}ms")

                            if (duration > 100) {
                                Log.w("PasswordInput", "⚠️ SLOW KEYSTROKE: ${duration}ms - potential freeze source")
                            }
                        },
                        placeholder = { Text(requiredPassword ?: DEFAULT_GLOBAL_PASSWORD, color = Color.White.copy(alpha = 0.6f)) },
                        visualTransformation = if (showPasswordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            autoCorrect = false,
                            keyboardType = KeyboardType.Password,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        enabled = (timerState == TimerState.InitialEntry),
                        singleLine = true,
                        shape = RoundedCornerShape(0.dp),
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = Color.White,
                            unfocusedIndicatorColor = Color.White.copy(alpha = 0.6f),
                            cursorColor = Color.White,
                            disabledTextColor = Color.White.copy(alpha = 0.5f)
                        ),
                        trailingIcon = {
                            val label = if (showPasswordVisible) "Hide" else "Show"
                            androidx.compose.material3.TextButton(onClick = { showPasswordVisible = !showPasswordVisible }) {
                                Text(label, color = Color.White)
                            }
                        }
                    )

                    if (showPasswordError) {
                        val errorMessage = if (requiredPassword.isNullOrBlank()) {
                            "No password configured - please set a password in alarm settings"
                        } else {
                            "Incorrect password"
                        }
                        Text(
                            text = errorMessage,
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Red.copy(alpha = 0.2f))
                                .padding(8.dp),
                            textAlign = TextAlign.Center
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .background(if (timerState == TimerState.InitialEntry) Color(0xFFFFAB91) else Color(0x66FFAB91))
                            .clickable(timerState == TimerState.InitialEntry) {
                                val actualPassword = requiredPassword
                                if (actualPassword.isNullOrBlank()) {
                                    // No password configured - show error and don't allow completion
                                    showPasswordError = true
                                    passwordInput = ""
                                    focusRequester.requestFocus()
                                } else if (passwordInput == actualPassword) {
                                    // CRITICAL FIX: For sequencer missions, call onMissionCompleted to advance to next mission
                                    if (isSequencerMission) {
                                        onMissionCompleted(alarmId)
                                    } else {
                                        // For normal password missions, just dismiss the alarm directly
                                        dismissAlarm(alarmId)
                                    }
                                } else {
                                    showPasswordError = true
                                    passwordInput = ""
                                    focusRequester.requestFocus()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Confirm",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Show dismiss only when there is no mission attached to this alarm.
            val dismissButtonVisible = when {
                // Never show dismiss button during wake-up check.
                isWakeCheckLaunchState.value -> false
                // Never show dismiss button in sequencer mode.
                isSequencerMission || isSequencerComplete -> false
                // Normal alarm with no tap challenge and no password mission.
                !missionTapEnabled && !missionMathEnabled && !missionSttEnabled && !missionFaceEnabled && requiredPassword == null -> true
                // Mission alarms must be completed before dismissing.
                else -> false
            }

            AnimatedVisibility(
                visible = dismissButtonVisible,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Snooze button - only show if snooze is enabled
                    if (alarmEnableSnooze) {
                        Button(
                            onClick = {
                                snoozeAlarm(alarmId)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2196F3),
                                contentColor = Color.Black
                            )
                        ) {
                            Text(
                                text = "Snooze",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                    }

                    // Dismiss button
                    Button(
                        onClick = {
                            isDismissed = true
                            // For all normal alarms, just dismiss directly without mission completion logic
                            dismissAlarm(alarmId)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50),
                            contentColor = Color.Black
                        )
                    ) {
                        Text(
                            text = "Dismiss",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                }
            }
        }
    }

    // -------------------- Dismiss / cleanup --------------------
    // Helper function to determine actual password from mission data
    private fun getActualPassword(alarm: Alarm?, missionType: String?): String? {
        // If mission type is tap, no password needed
        if (missionType == "tap") {
            return null
        }
        
        // CRITICAL FIX: For Direct Boot compatibility, try to get fresh mission data if alarm is null
        val missionPassword = if (alarm?.missionPassword == null && missionType != null) {
            try {
                // Use default password for sequencer missions
                if (missionType.contains("+")) {
                    DEFAULT_GLOBAL_PASSWORD
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get fresh mission data for password retrieval", e)
                null
            }
        } else {
            alarm?.missionPassword
        }
        
        // CRITICAL FIX: If mission password contains "+", it's ANY sequence (tap+password, password+tap, etc.)
        // Never use the sequence string as the actual password
        if (missionPassword?.contains("+") == true) {
            return DEFAULT_GLOBAL_PASSWORD // Use default password for any sequencer missions
        }
        
        // For non-sequencer missions, use the mission password if it's a password mission
        return if (missionType == "password" && alarm?.missionType != "sequencer") {
            missionPassword?.takeIf { it.isNotBlank() } ?: DEFAULT_GLOBAL_PASSWORD
        } else null
    }

    private fun scheduleWakeCheckAfterCompletion(
        alarmId: Int,
        alarm: com.vaishnava.alarm.data.Alarm?,
        source: String
    ): Boolean {
        val existingCycle = WakeCheckCycleStore.read(this, alarmId)
        val awakeConfirmed = WakeCheckStore.isAcked(this, alarmId, 10 * 60_000L)
        if (alarm?.wakeCheckEnabled != true || wakeCheckAcknowledged) {
            logWakeCheckForensic(
                "schedule_after_completion_blocked",
                alarmId,
                "source=$source awakeConfirmed=$awakeConfirmed"
            )
            Log.d(
                TAG,
                "WAKECHECK_COMPLETION_GATE: owner=$source alarmId=$alarmId cycleId=${existingCycle?.cycleId ?: 0L} " +
                    "missionModeActive=$isSequencerMission currentMissionPointer=${currentMissionId ?: "<none>"} " +
                    "finalCompletionConfirmed=true awakeConfirmed=$awakeConfirmed " +
                    "loopActive=${alarm?.wakeCheckEnabled == true && !awakeConfirmed} interval=${alarm?.wakeCheckMinutes ?: 0} " +
                    "scheduleTs=${existingCycle?.scheduledAt ?: 0L} allow=false reason=${if (alarm?.wakeCheckEnabled != true) "wakecheck_disabled" else "already_acknowledged"}"
            )
            return false
        }

        val scheduledCycle = WakeCheckCycleStore.scheduleAfterCompletion(
            this,
            alarmId,
            alarm.wakeCheckMinutes
        ) ?: run {
            logWakeCheckForensic(
                "schedule_after_completion_failed",
                alarmId,
                "source=$source awakeConfirmed=$awakeConfirmed"
            )
            Log.w(
                TAG,
                "WAKECHECK_COMPLETION_GATE: owner=$source alarmId=$alarmId cycleId=${existingCycle?.cycleId ?: 0L} " +
                    "missionModeActive=$isSequencerMission currentMissionPointer=${currentMissionId ?: "<none>"} " +
                    "finalCompletionConfirmed=true awakeConfirmed=$awakeConfirmed " +
                    "loopActive=${alarm.wakeCheckEnabled && !awakeConfirmed} interval=${alarm.wakeCheckMinutes} " +
                    "scheduleTs=${existingCycle?.scheduledAt ?: 0L} allow=false reason=schedule_after_completion_blocked state=${existingCycle?.state}"
            )
            Log.w(TAG, "WAKECHECK_SCHEDULE_BLOCKED: source=$source alarmId=$alarmId")
            return false
        }

        logWakeCheckForensic(
            "schedule_after_completion_success",
            alarmId,
            "source=$source cycleId=${scheduledCycle.cycleId} awakeConfirmed=$awakeConfirmed"
        )
        Log.d(
            TAG,
            "WAKECHECK_SCHEDULED: source=$source alarmId=$alarmId cycleId=${scheduledCycle.cycleId} interval=${alarm.wakeCheckMinutes}m"
        )
        Log.d(
            TAG,
            "WAKECHECK_COMPLETION_GATE: owner=$source alarmId=$alarmId cycleId=${scheduledCycle.cycleId} " +
                "missionModeActive=$isSequencerMission currentMissionPointer=${currentMissionId ?: "<none>"} " +
                "finalCompletionConfirmed=true awakeConfirmed=$awakeConfirmed " +
                "loopActive=${alarm.wakeCheckEnabled && !awakeConfirmed} interval=${alarm.wakeCheckMinutes} " +
                "scheduleTs=${scheduledCycle.scheduledAt} allow=true reason=scheduled dueAt=${scheduledCycle.dueAt}"
        )
        scheduleWakeCheckFollowUp(this, alarmId, alarm.wakeCheckMinutes, scheduledCycle.cycleId, scheduledCycle.dueAt)
        return true
    }

    private fun getSavedTapCount(alarmId: Int): Int {
        if (alarmId == -1) return 0
        return runCatching {
            val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this
            val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            prefs.getInt("tap_count_$alarmId", 0)
        }.getOrDefault(0)
    }

    private fun saveTapCount(alarmId: Int, tapCount: Int) {
        if (alarmId == -1) return
        runCatching {
            val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this
            val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            prefs.edit().putInt("tap_count_$alarmId", tapCount).apply()
        }
    }

    private fun clearTapCount(alarmId: Int) {
        if (alarmId == -1) return
        runCatching {
            val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this
            val prefs = dps.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            prefs.edit().remove("tap_count_$alarmId").apply()
        }
    }

    private fun onTapMissionSuccess(alarmId: Int) {
        clearTapCount(alarmId)
        try {
            Log.d(TAG, "TAP_MISSION_SUCCESS: alarmId=$alarmId isSequencer=$isSequencerMission")

            // For sequencer missions, call onMissionCompleted to advance to next mission
            if (isSequencerMission) {
                onMissionCompleted(alarmId)
                return
            }

            // For normal missions, dismiss as before
            isDismissed = true
            activityDismissed = true
            missionTapEnabled = false
            missionMathEnabled = false
            missionSttEnabled = false
            missionFaceEnabled = false
            try { this@AlarmActivity.runOnUiThread { } } catch (_: Exception) {}
            // DISABLED: ttsRunnable cleanup - legacy TTS repeater removed
            try { ttsHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
            try {
                tts?.apply {
                    stop()
                    shutdown()
                }
            } catch (_: Exception) {}

            // Stop the foreground service (alarm audio/vibration)
            try {
                val serviceIntent = Intent(this@AlarmActivity, AlarmForegroundService::class.java).apply {
                    action = Constants.ACTION_STOP_FOREGROUND_SERVICE
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (_: Exception) {}

            // For preview mode, just finish normally
            if (isPreview) {
                finish()
                overridePendingTransition(0, 0)
                return
            }

            // For real alarms, we need to handle the alarm state properly
            // Check if this is a protected alarm
            var alarm: com.vaishnava.alarm.data.Alarm? = null
            try {
                alarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        AlarmStorage(createDeviceProtectedStorageContext()).getAlarms().find { it.id == alarmId }
                    } catch (e: Exception) {
                        AlarmStorage(applicationContext).getAlarms().find { it.id == alarmId }
                    }
                } else {
                    AlarmStorage(applicationContext).getAlarms().find { it.id == alarmId }
                }
            } catch (e: Exception) {
                Log.w(TAG, "CPS/DPS storage bypass in onTapMissionSuccess for alarmId=$alarmId: ${e.message}")
            }

            if (alarm?.isProtected == true) {
                Log.w(TAG, "Protected alarm $alarmId completed tap mission - mission completed, allowing dismissal")
                // Mission completed successfully, allow the activity to finish
                // The alarm remains active but the mission screen can close
            }

            // If wake-up check is enabled for this alarm, schedule the follow-up now (same as dismiss flow)
            runCatching {
                scheduleWakeCheckAfterCompletion(alarmId, alarm, "onTapMissionSuccess")
            }

            // For non-protected real alarms, finish the activity
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            } else {
                finish()
            }
            overridePendingTransition(0, 0)
        } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_STT_RECORD_AUDIO) return

        isRequestingPermission = false
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            // The auto-start attempt bailed out waiting on this, so arm the mic now.
            if (missionSttEnabled && !sttListening) startSttRecognition(alarmId)
        } else {
            // A denial must never count as success - the mission stays incomplete.
            sttStatusMessage = "Microphone permission required."
        }
    }

    /** Readable names for SpeechRecognizer error codes, for the forensic report. */
    private fun sttErrorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        else -> "UNKNOWN"
    }

    /** Re-arms the mic after a miss so the challenge stays hands-free. */
    private fun scheduleSttRelisten(alarmId: Int) {
        if (!missionSttEnabled || isDismissed || activityDismissed) return
        sttHandler.removeCallbacksAndMessages(null)
        sttHandler.postDelayed({
            if (missionSttEnabled && !sttListening && !isDismissed && !activityDismissed) {
                startSttRecognition(alarmId)
            }
        }, 1200L)
    }

    private fun releaseSttRecognizer() {
        try {
            sttRecognizer?.apply {
                try { cancel() } catch (_: Exception) {}
                destroy()
            }
        } catch (e: Exception) {
            Log.w(TAG, "STT recognizer release failed: ${e.message}")
        } finally {
            sttRecognizer = null
        }
    }

    private fun startSttRecognition(alarmId: Int) {
        try {
            if (!ExtremeForensicLogger.isInitialized()) {
                ExtremeForensicLogger.initialize(this)
            }
            ExtremeForensicLogger.logEvent(
                "STT",
                "startSttRecognition alarmId=$alarmId target='${sttTargetPhrase}' preview=$isPreview"
            )

            val recognizerAvailable = SttMissionSupport.isSpeechRecognizerAvailable(this)
            ExtremeForensicLogger.logEvent("STT", "isRecognitionAvailable=$recognizerAvailable")

            if (!recognizerAvailable) {
                // Returning silently here made the mic look broken on devices where the
                // recognizer is missing or hidden. Say so instead - and never pass.
                Log.w(TAG, "Speech recognizer not available on this device")
                ExtremeForensicLogger.logError("STT: no recognition service visible to this app")
                sttListening = false
                sttStatusMessage = "Speech recognition unavailable on this device."
                ExtremeForensicLogger.saveReport()
                return
            }

            val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            ExtremeForensicLogger.logEvent("STT", "RECORD_AUDIO granted=$micGranted")

            if (!micGranted) {
                Log.w(TAG, "RECORD_AUDIO not granted - requesting")
                isRequestingPermission = true
                bringToFrontHandler.removeCallbacks(bringToFrontRunnable)
                bringToFrontPending = false
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.RECORD_AUDIO),
                    REQ_STT_RECORD_AUDIO
                )
                return
            }

            // A recognizer that outlives its activity keeps holding the mic, so the next
            // startListening() fails with ERROR_RECOGNIZER_BUSY. Always release first.
            releaseSttRecognizer()

            sttRecognizedText = ""
            sttStatusMessage = null
            sttMatchedWords = 0
            sttListening = true
                val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            sttRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "STT: Ready for speech")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "STT: Beginning of speech")
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    Log.d(TAG, "STT: End of speech")
                }

                override fun onError(error: Int) {
                    sttListening = false
                    Log.w(TAG, "STT error: $error")
                    ExtremeForensicLogger.logEvent(
                        "STT",
                        "onError code=$error (${sttErrorName(error)})"
                    )
                    ExtremeForensicLogger.saveReport()
                    // None of these count as success - the mission stays incomplete.
                    sttStatusMessage = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Try again."
                        SpeechRecognizer.ERROR_AUDIO -> "Microphone problem. Try again."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Still busy. Try again in a moment."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network problem. Try again."
                        else -> "Recognition failed. Try again."
                    }
                    releaseSttRecognizer()

                    // Stay hands-free: relisten after the normal "didn't hear you" outcomes.
                    // Permission, busy and unavailable are NOT retried - that would spin.
                    val retryable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    if (retryable) scheduleSttRelisten(alarmId)
                }

                override fun onResults(results: Bundle?) {
                    sttListening = false
                    try {
                        results?.let {
                            @Suppress("UNCHECKED_CAST")
                            val recognizedList = it.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: emptyList()
                            ExtremeForensicLogger.logEvent(
                                "STT",
                                "onResults size=${recognizedList.size} values=$recognizedList"
                            )
                            if (recognizedList.isNotEmpty()) {
                                // Top hypothesis only. Lower-ranked alternatives are the
                                // recognizer guessing ahead, not what was actually spoken.
                                val best = recognizedList[0]
                                sttRecognizedText = best
                                Log.d(TAG, "STT recognized: $recognizedList")

                                sttMatchedWords =
                                    SttMissionSupport.matchedWordCount(best, sttTargetPhrase)

                                val matched = SttMissionSupport.matches(best, sttTargetPhrase)
                                ExtremeForensicLogger.logEvent(
                                    "STT",
                                    "match heard='$best' target='$sttTargetPhrase' " +
                                        "matched=$matched words=$sttMatchedWords"
                                )
                                ExtremeForensicLogger.saveReport()
                                if (matched) {
                                    Log.d(TAG, "STT: Match - completing mission")
                                    sttMatchedWords =
                                        SttMissionSupport.displayWords(sttTargetPhrase).size
                                    sttStatusMessage = "Correct."
                                    // Brief beat so the final word lights up green before the
                                    // screen closes, instead of quitting mid-highlight.
                                    sttHandler.removeCallbacksAndMessages(null)
                                    sttHandler.postDelayed({
                                        if (!isDismissed && !activityDismissed) {
                                            onMissionCompleted(alarmId)
                                        }
                                    }, 550L)
                                } else {
                                    Log.d(TAG, "STT: No match. Expected: '$sttTargetPhrase'")
                                    sttStatusMessage = "Incorrect. Try again."
                                    scheduleSttRelisten(alarmId)
                                }
                            } else {
                                sttStatusMessage = "Didn't catch that. Try again."
                                scheduleSttRelisten(alarmId)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "STT results error: ${e.message}")
                    } finally {
                        releaseSttRecognizer()
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    // Drives the per-word indicator only. A partial result never completes
                    // the mission - only the final result in onResults is validated.
                    try {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?: return
                        val count = SttMissionSupport.matchedWordCount(partial, sttTargetPhrase)
                        // Partials get revised; never walk the indicator backwards mid-attempt.
                        if (count > sttMatchedWords) sttMatchedWords = count
                    } catch (_: Exception) {}
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = SttMissionSupport.createSpeechRecognizerIntent()
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "STT recognition error: ${e.message}", e)
            sttListening = false
            releaseSttRecognizer()
        }
    }

    private fun onMissionCompleted(alarmId: Int) {
        try {
            Log.d(TAG, "MISSION_COMPLETED: alarmId=$alarmId isSequencer=$isSequencerMission currentMissionId=$currentMissionId")

            // For normal alarms (non-sequencer), just dismiss directly without mission completion logic
            if (!isSequencerMission) {
                dismissAlarm(alarmId, bypassProtectedMissionGuard = true)
                return
            }

            // For sequencer completions, prefer the concrete active mission type over placeholder UI IDs
            // like "sequencer_mission_1". MissionSequencer expects mission IDs such as "tap"/"password".
            val resolvedUiMissionId = resolveSafeMissionType(
                intent?.getStringExtra("mission_type"),
                intent?.getStringExtra("mission_password"),
                currentMissionId
            )
            val missionId = intent?.getStringExtra("mission_id")
                ?.takeUnless { it.startsWith("sequencer_mission") }
                ?: resolvedUiMissionId.takeUnless { it.isBlank() || it == "sequencer" }
                ?: currentMissionId?.takeUnless { it.startsWith("sequencer_mission") }
                ?: intent?.getStringExtra("mission_type")?.takeUnless { it == "none" || it == "sequencer" }
                ?: "sequencer_mission_${alarmId}"

            Log.d(
                TAG,
                "MISSION_COMPLETION_BROADCAST: alarmId=$alarmId missionId=$missionId " +
                    "intentMissionId=${intent?.getStringExtra("mission_id")} resolvedUiMissionId=$resolvedUiMissionId"
            )

            try {
                val broadcast = Intent(MissionSequencer.ACTION_MISSION_COMPLETED).apply {
                    putExtra(MissionSequencer.EXTRA_MISSION_ID, missionId)
                    putExtra(MissionSequencer.EXTRA_MISSION_SUCCESS, true)
                    putExtra(MissionSequencer.EXTRA_FROM_SEQUENCER, true)
                    setPackage(packageName)
                }
                sendBroadcast(broadcast)
                Log.d(TAG, "MISSION_COMPLETION_BROADCAST_SENT: alarmId=$alarmId missionId=$missionId")
            } catch (e: Exception) {
                Log.e(TAG, "MISSION_COMPLETION_BROADCAST_ERROR: ${e.message}", e)
            }

            Log.d(TAG, "MISSION_COMPLETED_SEQUENCER: Keeping activity open for next mission in sequence")

            if (isPreview) {
                finish()
                overridePendingTransition(0, 0)
            }
            return
        } catch (_: Exception) {}
    }

    private fun dismissAlarm(alarmId: Int, bypassProtectedMissionGuard: Boolean = false) {
        try {
            // Check if this is a protected alarm
            var alarm: com.vaishnava.alarm.data.Alarm? = null
            var alarmStorage: AlarmStorage? = null
            try {
                alarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        alarmStorage = AlarmStorage(createDeviceProtectedStorageContext())
                        alarmStorage!!.getAlarms().find { it.id == alarmId }
                    } catch (e: Exception) {
                        alarmStorage = AlarmStorage(applicationContext)
                        alarmStorage.getAlarms().find { it.id == alarmId }
                    }
                } else {
                    alarmStorage = AlarmStorage(applicationContext)
                    alarmStorage.getAlarms().find { it.id == alarmId }
                }
            } catch (e: Exception) {
                Log.w(TAG, "CPS/DPS storage bypass in dismissAlarm for alarmId=$alarmId: ${e.message}")
            }

            // Only block dismissal for protected alarms with missions (password/tap)
            // Convert "none" to empty string for comparison
            val missionType = alarm?.missionType?.let { if (it == "none") "" else it } ?: ""
            if (!bypassProtectedMissionGuard && alarm?.isProtected == true && missionType.isNotEmpty()) {
                Log.w(TAG, "Attempted to dismiss protected alarm $alarmId with mission $missionType - blocking dismissal")
                // Show a message that this alarm cannot be dismissed
                try {
                    android.widget.Toast.makeText(
                        this,
                        "Protected alarm — cannot dismiss.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (_: Exception) {}
                return // Don't allow dismissal
            }
            if (bypassProtectedMissionGuard && alarm?.isProtected == true && missionType.isNotEmpty()) {
                Log.d(
                    TAG,
                    "MISSION_COMPLETION_DISMISS_ALLOWED: alarmId=$alarmId missionType=$missionType"
                )
            }

            activityDismissed = true

            try {
                val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    createDeviceProtectedStorageContext()
                } else {
                    this
                }
                val prefs = dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                val editor = prefs.edit()
                    // Clear any lingering wake-check gate/pending flags so future daily fires are not suppressed
                    .putBoolean("wakecheck_gate_active_$alarmId", false)
                    .putBoolean("wakecheck_pending_$alarmId", false)
                    .putBoolean("wakecheck_finalized_$alarmId", false)
                    .putLong("dismiss_ts_$alarmId", System.currentTimeMillis())
                    // Clear snooze marker so delete isn't blocked after the alarm is fully dismissed
                    .remove("snooze_ts_$alarmId")
                val markerIncluded = isSequencerMission
                if (markerIncluded) {
                    editor.putBoolean("sequencer_completed_$alarmId", true)
                }
                val committed = editor.commit()
                Log.d(
                    TAG,
                    "DPS_ACTIVITY_DISMISS_COMMIT: alarmId=$alarmId isSequencerMission=$isSequencerMission " +
                        "markerIncluded=$markerIncluded success=$committed"
                )
            } catch (e: Exception) {
                Log.e(TAG, "DPS_ACTIVITY_DISMISS_COMMIT_ERROR: alarmId=$alarmId error=${e.message}")
            }

            // Only cancel wake-check loop if user acknowledged via "I'm awake"
            if (wakeCheckAcknowledged) {
                runCatching {
                    cancelWakeCheckFollowUp(this, alarmId)
                }
            }

            // Stop TTS
            // DISABLED: ttsRunnable cleanup - legacy TTS repeater removed
            try { ttsHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
            try {
                tts?.apply {
                    stop()
                    shutdown()
                }
            } catch (e: Exception) {
                // Error stopping TTS
            }

            // CRITICAL FIX: Don't stop AlarmForegroundService for sequencer alarms
            // MissionSequencer will stop the service when all missions are complete
            if (!isSequencerMission) {
                // Stop the foreground service
                val serviceIntent = Intent(this@AlarmActivity, AlarmForegroundService::class.java).apply {
                    action = Constants.ACTION_STOP_FOREGROUND_SERVICE
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                Log.d(TAG, "Skipping AlarmForegroundService stop for sequencer alarm - MissionSequencer will handle it")
            }

            // Handle the alarm state - use existing alarm variable from above
            // If wake-check is enabled and user did NOT press "I'm awake", schedule (first or next) follow-up from now.
            runCatching {
                scheduleWakeCheckAfterCompletion(alarmId, alarm, "dismissAlarm")
            }

            // Cancel all possible alarm intents to prevent any re-trigger
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

            try {
                // 1) Cancel the actual scheduled fire PendingIntent (DIRECT_BOOT_ALARM with requestCode=alarmId)
                val fireIntent = Intent(this, AlarmReceiver::class.java).apply {
                    action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
                    data = android.net.Uri.parse("alarm://$alarmId")
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                }
                PendingIntent.getBroadcast(
                    this,
                    alarmId,
                    fireIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ).let { pi ->
                    alarmManager.cancel(pi)
                    pi.cancel()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling DIRECT_BOOT_ALARM PI: ${e.message}")
            }

            try {
                // 2) Cancel WAKE_UP backup broadcasts (used in restore/backup paths)
                val wakeIntent1 = Intent(this, AlarmReceiver::class.java).apply {
                    action = "com.vaishnava.alarm.WAKE_UP"
                    data = android.net.Uri.parse("alarm-wakeup://$alarmId")
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                }
                val wakeIntent2 = Intent(this, AlarmReceiver::class.java).apply {
                    action = "com.vaishnava.alarm.WAKE_UP"
                    data = android.net.Uri.parse("alarm-wakeup2://$alarmId")
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                }
                val codes = listOf(alarmId xor 0x4A11, alarmId xor 0x6B7F)
                val intents = listOf(wakeIntent1, wakeIntent2)
                codes.zip(intents).forEach { (code, intent) ->
                    val pi = PendingIntent.getBroadcast(
                        this,
                        code,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    try { alarmManager.cancel(pi); pi.cancel() } catch (_: Exception) {}
                }
                // Also cancel wake-up check follow-up (our loop) only if acknowledged
                if (wakeCheckAcknowledged) {
                    runCatching {
                        val wakeCheckIntent = Intent(this, AlarmReceiver::class.java).apply {
                            action = "com.vaishnava.alarm.WAKE_UP"
                            data = android.net.Uri.parse("alarm-wakecheck://$alarmId")
                            putExtra(AlarmReceiver.ALARM_ID, alarmId)
                        }
                        val pi = PendingIntent.getBroadcast(
                            this,
                            alarmId xor 0x9C0A,
                            wakeCheckIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        alarmManager.cancel(pi); pi.cancel()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling WAKE_UP backup PIs: ${e.message}")
            }

            try {
                // 3) Cancel the AlarmClock show intent (getActivity with requestCode=alarmId)
                val showIntent = Intent(this, AlarmActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                }
                val showPi = PendingIntent.getActivity(
                    this,
                    alarmId,
                    showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(showPi)
                showPi.cancel()
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling AlarmClock show PI: ${e.message}")
            }

            // Clear any existing notifications
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(alarmId)
            notificationManager.cancel(Constants.NOTIFICATION_ID_UPCOMING_ALARM)

            // Update alarm state
            if (alarm != null) {
                val isOneShot = alarm.days.isNullOrEmpty() && !alarm.repeatDaily
                if (isOneShot) {
                    // For one-shot alarms with wake-up check enabled, keep the alarm
                    // logically pending until the user explicitly acknowledges via
                    // "I'm awake". Only disable immediately when either wake-check
                    // is not enabled, or it has already been acknowledged.
                    val shouldDisableNow = !alarm.wakeCheckEnabled || wakeCheckAcknowledged
                    
                    // CPS-ONLY: Disable alarm when wake check is complete
                    val shouldDisable = shouldDisableNow
                    
                    if (shouldDisable) {
                        val updatedAlarm = alarm.copy(isEnabled = false)
                        try {
                            alarmStorage?.updateAlarm(updatedAlarm)
                            Log.d(TAG, "dismissAlarm: alarm disabled - CPS mode")
                            logWakeCheckForensic(
                                "dismiss_alarm_disabled",
                                alarmId,
                                "shouldDisableNow=$shouldDisableNow"
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "dismissAlarm: delayed CPS update until unlock for alarmId=$alarmId")
                            logWakeCheckForensic(
                                "dismiss_alarm_disable_failed",
                                alarmId,
                                "message=${e.message} shouldDisableNow=$shouldDisableNow"
                            )
                        }
                    }
                } else {
                     // Repeating alarm: keep enabled and reschedule next occurrence
                    try {
                        val alarmScheduler = AndroidAlarmScheduler(applicationContext)
                        alarmScheduler.cancel(alarm)
                        alarmScheduler.schedule(alarm)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to reschedule repeating alarm $alarmId: ${e.message}", e)
                    }
                }
            }

            // Clear device-protected storage flags
            try {
                val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    createDeviceProtectedStorageContext()
                } else {
                    this
                }
                val prefs = dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                prefs.edit()
                    .remove("was_ringing")
                    .remove("ring_alarm_id")
                    .commit()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing alarm_dps preferences: ${e.message}", e)
            }

            // Bring user back to the app home page after dismiss
            val homeIntent = Intent(this@AlarmActivity, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("dismiss_alarm", true)
            }
            startActivity(homeIntent)
        } catch (e: Exception) {
            // PATCHED_BY_AUTOFIXER: Removed UnifiedLogger call
        }
    }

    private fun snoozeAlarm(alarmId: Int) {
        try {
            Log.d(TAG, "═════════════════════════════════════════════════════════")
            Log.d(TAG, "[SNOOZE_DEBUG_1] SNOOZE BUTTON PRESSED")
            Log.d(TAG, "[SNOOZE_DEBUG_1] alarmId=$alarmId")
            Log.d(TAG, "[SNOOZE_DEBUG_1] timestamp=${System.currentTimeMillis()}")

            // Get the alarm to get snooze duration
            var alarm: com.vaishnava.alarm.data.Alarm? = null
            var alarmStorage: AlarmStorage? = null
            try {
                alarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        alarmStorage = AlarmStorage(createDeviceProtectedStorageContext())
                        alarmStorage!!.getAlarms().find { it.id == alarmId }
                    } catch (e: Exception) {
                        alarmStorage = AlarmStorage(applicationContext)
                        alarmStorage.getAlarms().find { it.id == alarmId }
                    }
                } else {
                    alarmStorage = AlarmStorage(applicationContext)
                    alarmStorage.getAlarms().find { it.id == alarmId }
                }
                Log.d(TAG, "[SNOOZE_DEBUG_2] Alarm loaded from storage")
                Log.d(TAG, "[SNOOZE_DEBUG_2] enableSnooze=${alarm?.enableSnooze}")
                Log.d(TAG, "[SNOOZE_DEBUG_2] wakeCheckMinutes=${alarm?.wakeCheckMinutes}")
                Log.d(TAG, "[SNOOZE_DEBUG_2] isEnabled=${alarm?.isEnabled}")
            } catch (e: Exception) {
                Log.w(TAG, "[SNOOZE_DEBUG_2] CPS/DPS storage bypass in snoozeAlarm for alarmId=$alarmId: ${e.message}")
            }

            // Get snooze duration from alarm's wakeCheckMinutes field (default 5 minutes)
            // If alarm data retrieval failed, fallback to intent extras
            val snoozeDurationMinutes = if (alarm != null && alarm.enableSnooze && alarm.wakeCheckMinutes > 0) {
                alarm.wakeCheckMinutes
            } else if (alarm != null) {
                alarm.wakeCheckMinutes.coerceAtLeast(5)
            } else {
                // Fallback to intent extras if alarm data retrieval failed
                intent.getIntExtra("wake_check_minutes", intent.getIntExtra("snooze_duration_minutes", 5))
            }

            Log.d(TAG, "snoozeAlarm: Snoozing for $snoozeDurationMinutes minutes")

            try {
                val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    createDeviceProtectedStorageContext()
                } else {
                    this
                }
                val prefs = dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("wakecheck_gate_active_$alarmId", false)
                    .putBoolean("wakecheck_pending_$alarmId", false)
                    .putBoolean("wakecheck_finalized_$alarmId", false)
                    .putBoolean("sequencer_completed_$alarmId", false)
                    .putLong("snooze_ts_$alarmId", System.currentTimeMillis())
                    .commit()

                val syncPrefs = dpsContext.getSharedPreferences("dps_completion_sync", Context.MODE_PRIVATE)
                syncPrefs.edit()
                    .putBoolean("alarm_completed_in_dps_$alarmId", false)
                    .commit()

                DpsMissionMirror(dpsContext).clearSnapshot(alarmId)
            } catch (e: Exception) {
                Log.e(TAG, "DPS_SNOOZE_COMMIT_ERROR: alarmId=$alarmId error=${e.message}")
            }

            // Stop TTS
            try { ttsHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
            try {
                tts?.apply {
                    stop()
                    shutdown()
                }
            } catch (e: Exception) {
                // Error stopping TTS
            }

            // Stop the foreground service
            val serviceIntent = Intent(this@AlarmActivity, AlarmForegroundService::class.java).apply {
                action = Constants.ACTION_STOP_FOREGROUND_SERVICE
                putExtra(AlarmReceiver.ALARM_ID, alarmId)
                putExtra("from_snooze", true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            // Cancel all alarm intents to stop current ring/vibration
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

            try {
                val fireIntent = Intent(this, AlarmReceiver::class.java).apply {
                    action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
                    data = android.net.Uri.parse("alarm://$alarmId")
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                }
                PendingIntent.getBroadcast(
                    this,
                    alarmId,
                    fireIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ).let { pi ->
                    alarmManager.cancel(pi)
                    pi.cancel()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling alarm for snooze: ${e.message}")
            }

            // Clear notifications
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(alarmId)
            notificationManager.cancel(Constants.NOTIFICATION_ID_UPCOMING_ALARM)

            // Schedule snooze alarm for X minutes from now
            try {
                val now = System.currentTimeMillis()
                val snoozeTimeMillis = now + (snoozeDurationMinutes * 60 * 1000)

                Log.d(TAG, "snoozeAlarm: Scheduling snooze at ${java.util.Date(snoozeTimeMillis)}")

                val snoozeIntent = Intent(this, AlarmReceiver::class.java).apply {
                    action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
                    data = android.net.Uri.parse("alarm://$alarmId")
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                    if (alarm != null) {
                        putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, alarm?.ringtoneUri)
                        putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, alarm?.days?.toIntArray())
                        putExtra("hour", alarm?.hour ?: 0)
                        putExtra("minute", alarm?.minute ?: 0)
                        putExtra("repeatDaily", alarm?.repeatDaily ?: false)
                        putExtra("mission_type", alarm?.missionType ?: "")
                        putExtra("mission_password", alarm?.missionPassword ?: "")
                        putExtra("is_protected", alarm?.isProtected ?: false)
                        putExtra("wake_check_enabled", alarm?.wakeCheckEnabled ?: false)
                        putExtra("wake_check_minutes", alarm?.wakeCheckMinutes ?: 5)
                        putExtra("enable_snooze", alarm?.enableSnooze ?: false)
                        Log.d(TAG, "[SNOOZE_DEBUG_3] Creating snoozeIntent from storage alarm")
                        Log.d(TAG, "[SNOOZE_DEBUG_3] enable_snooze=${alarm?.enableSnooze}")
                    } else {
                        putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, intent.getParcelableExtra<android.net.Uri>(AlarmReceiver.EXTRA_RINGTONE_URI))
                        putExtra(AlarmReceiver.EXTRA_REPEAT_DAYS, intent.getIntArrayExtra(AlarmReceiver.EXTRA_REPEAT_DAYS))
                        putExtra("hour", intent.getIntExtra("hour", 0))
                        putExtra("minute", intent.getIntExtra("minute", 0))
                        putExtra("repeatDaily", intent.getBooleanExtra("repeatDaily", false))
                        putExtra("mission_type", intent.getStringExtra("mission_type") ?: "")
                        putExtra("mission_password", intent.getStringExtra("mission_password") ?: "")
                        putExtra("is_protected", intent.getBooleanExtra("is_protected", false))
                        putExtra("wake_check_enabled", intent.getBooleanExtra("wake_check_enabled", false))
                        putExtra("wake_check_minutes", intent.getIntExtra("wake_check_minutes", 5))
                        putExtra("enable_snooze", intent.getBooleanExtra("enable_snooze", false))
                        Log.d(TAG, "[SNOOZE_DEBUG_3] Creating snoozeIntent from fallback (storage failed)")
                        Log.d(TAG, "[SNOOZE_DEBUG_3] enable_snooze=${intent.getBooleanExtra("enable_snooze", false)}")
                    }
                    putExtra("from_snooze", true)
                }
                Log.d(TAG, "[SNOOZE_DEBUG_3_FINAL] snoozeIntent extras: enable_snooze=${snoozeIntent.getBooleanExtra("enable_snooze", false)}")

                val snoozePendingIntent = PendingIntent.getBroadcast(
                    this,
                    alarmId + 100000,
                    snoozeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val showIntent = PendingIntent.getActivity(
                    this,
                    alarmId + 100000,
                    Intent(this, AlarmActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        putExtra(AlarmReceiver.ALARM_ID, alarmId)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                try {
                    val clockInfo = AlarmManager.AlarmClockInfo(snoozeTimeMillis, showIntent)
                    alarmManager.setAlarmClock(clockInfo, snoozePendingIntent)
                    Log.d(TAG, "[SNOOZE_DEBUG_4] Snooze scheduled via setAlarmClock")
                } catch (secEx: SecurityException) {
                    Log.w(TAG, "setAlarmClock failed in snoozeAlarm, trying fallback: ${secEx.message}")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, snoozeTimeMillis, snoozePendingIntent)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, snoozeTimeMillis, snoozePendingIntent)
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, snoozeTimeMillis, snoozePendingIntent)
                    }
                }

                Log.d(TAG, "[SNOOZE_DEBUG_4] Snooze scheduled successfully")
                Log.d(TAG, "[SNOOZE_DEBUG_4] snoozeTimeMillis=${java.util.Date(snoozeTimeMillis)}")
                Log.d(TAG, "[SNOOZE_DEBUG_4] alarmId=$alarmId")
                Log.d(TAG, "[SNOOZE_DEBUG_4] requestCode=${alarmId + 100000}")
                Log.d(TAG, "═════════════════════════════════════════════════════════")
                Toast.makeText(
                    this,
                    "Alarm snoozed for $snoozeDurationMinutes minutes",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Log.e(TAG, "snoozeAlarm: Error scheduling snooze: ${e.message}", e)
                Toast.makeText(this, "Failed to snooze alarm", Toast.LENGTH_SHORT).show()
                return
            }

            // CRITICAL FIX: RE-ENABLE ALARM SO TOGGLE SHOWS ON
            try {
                if (alarm != null) {
                    val updatedAlarm = alarm.copy(isEnabled = true)
                    alarmStorage?.updateAlarm(updatedAlarm)
                    Log.d(TAG, "[SNOOZE_TOGGLE_FIX] RE-ENABLED alarm ID=$alarmId for snooze (isEnabled=true)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[SNOOZE_TOGGLE_FIX] Error re-enabling alarm: ${e.message}")
            }

            // Clear device-protected storage flags
            try {
                val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    createDeviceProtectedStorageContext()
                } else {
                    this
                }
                val prefs = dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                prefs.edit()
                    .remove("was_ringing")
                    .remove("ring_alarm_id")
                    .commit()
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing alarm_dps preferences: ${e.message}", e)
            }

            // Finish the activity
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    finishAndRemoveTask()
                } else {
                    finish()
                }
                overridePendingTransition(0, 0)
            } catch (e: Exception) {
                Log.e(TAG, "snoozeAlarm: Error finishing activity: ${e.message}")
                finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "snoozeAlarm: Unexpected error: ${e.message}", e)
            Toast.makeText(this, "Error snoozing alarm", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Back press is disabled for this screen")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        // disable back
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isRequestingPermission) {
            Log.d(TAG, "ON_USER_LEAVE_HINT_SKIP: requesting permission, not bringing to front")
            return
        }
        // Bring the alarm UI back ONCE if the user explicitly navigates away (until dismissed)
        if (!activityDismissed && !isDismissed) {
            AlarmForegroundService.setActivityForeground(false)
            scheduleBringToFront(500)
        }
    }

    override fun onPause() {
        super.onPause()
        
        // CRITICAL FIX: Save mission state when activity pauses (e.g., during lockscreen)
        saveMissionState()
    }

    override fun onStop() {
        super.onStop()
        if (!activityDismissed && !isDismissed) {
            AlarmForegroundService.setActivityForeground(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Must run even on swipe-away, or the orphaned recognizer keeps the mic and the
        // relaunched activity cannot start listening again.
        sttHandler.removeCallbacksAndMessages(null)
        releaseSttRecognizer()
        sttListening = false
        if (!activityDismissed && !isDismissed) {
            AlarmForegroundService.setActivityForeground(false)
        }
        bringToFrontHandler.removeCallbacks(bringToFrontRunnable)
        bringToFrontPending = false
        
        // Preserve mission state if Android destroys the activity while the alarm is still active.
        if (activityDismissed || isDismissed) {
            clearMissionState()
        }
        
        try { unregisterReceiver(screenReceiver) } catch (e: Exception) {
            // Error unregistering receiver
        }
        try { unregisterReceiver(finishAlarmReceiver) } catch (e: Exception) {
            // Error unregistering finish alarm receiver
        }
        try {
            // DISABLED: ttsRunnable cleanup - legacy TTS repeater removed
            try { ttsHandler.removeCallbacksAndMessages(null) } catch (_: Exception) {}
            tts?.apply { stop(); shutdown() }
            tts = null
        } catch (e: Exception) {
            // Error cleaning up TTS resources
        }
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleWakeCheckFollowUp(
        context: Context,
        alarmId: Int,
        minutes: Int,
        cycleId: Long,
        persistedDueAt: Long = 0L
    ) {
        try {
            // CRITICAL: Cancel any existing wake-up check for this alarm first to prevent duplicates
            logWakeCheckForensic(
                "followup_schedule_start",
                alarmId,
                "minutes=$minutes cycleId=$cycleId"
            )
            cancelWakeCheckFollowUp(context, alarmId)
            
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val broadcast = Intent(context, AlarmReceiver::class.java).apply {
                action = "com.vaishnava.alarm.WAKE_UP"
                data = android.net.Uri.parse("alarm-wakecheck://$alarmId")
                putExtra(AlarmReceiver.ALARM_ID, alarmId)
                putExtra("from_wake_check", true)
                putExtra(Constants.EXTRA_WAKE_CHECK_CYCLE_ID, cycleId)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                WakeCheckCycleStore.wakeCheckRequestCode(alarmId),
                broadcast,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = if (persistedDueAt > 0L) {
                persistedDueAt
            } else {
                WakeCheckCycleStore.read(context, alarmId)?.dueAt
                    ?.takeIf { it > 0L }
                    ?: (System.currentTimeMillis() + minutes * 60_000L)
            }
            Log.d(
                TAG,
                "scheduleWakeCheckFollowUp: alarm=$alarmId cycleId=$cycleId at=${triggerAt} (+${minutes}m) rc=${WakeCheckCycleStore.wakeCheckRequestCode(alarmId)}"
            )
            // Provide a showIntent for setAlarmClock
            val showIntent = PendingIntent.getActivity(
                context,
                WakeCheckCycleStore.wakeCheckRequestCode(alarmId),
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
            logWakeCheckForensic(
                "followup_schedule_done",
                alarmId,
                "minutes=$minutes cycleId=$cycleId triggerAt=$triggerAt"
            )
        } catch (_: Exception) {}
    }

    private fun cancelWakeCheckFollowUp(context: Context, alarmId: Int) {
        try {
            logWakeCheckForensic("followup_cancel_start", alarmId)
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val broadcast = Intent(context, AlarmReceiver::class.java).apply {
                action = "com.vaishnava.alarm.WAKE_UP"
                data = android.net.Uri.parse("alarm-wakecheck://$alarmId")
                putExtra(AlarmReceiver.ALARM_ID, alarmId)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                WakeCheckCycleStore.wakeCheckRequestCode(alarmId),
                broadcast,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi); pi.cancel()

            // Also cancel AlarmClock showIntent used when scheduling follow-ups
            runCatching {
                val showIntent = Intent(context, AlarmActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                    putExtra("from_wake_check", true)
                }
                val showPi = PendingIntent.getActivity(
                    context,
                    WakeCheckCycleStore.wakeCheckRequestCode(alarmId),
                    showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.cancel(showPi); showPi.cancel()
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
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.cancel(legacyShowPi); legacyShowPi.cancel()
            }

            // Also cancel common variants defensively
            runCatching {
                val noData = Intent(context, AlarmReceiver::class.java).apply {
                    action = "com.vaishnava.alarm.WAKE_UP"
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                }
                val pi2 = PendingIntent.getBroadcast(
                    context,
                    WakeCheckCycleStore.wakeCheckRequestCode(alarmId),
                    noData,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.cancel(pi2); pi2.cancel()
            }
            runCatching {
                val withData = Intent(context, AlarmReceiver::class.java).apply {
                    action = "com.vaishnava.alarm.WAKE_UP"
                    data = android.net.Uri.parse("alarm-wakecheck://$alarmId")
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                }
                val pi3 = PendingIntent.getBroadcast(context, alarmId, withData, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.cancel(pi3); pi3.cancel()
            }
            runCatching {
                val withData = Intent(context, AlarmReceiver::class.java).apply {
                    action = "com.vaishnava.alarm.WAKE_UP"
                    data = android.net.Uri.parse("alarm-wakecheck://$alarmId")
                }
                val pi4 = PendingIntent.getBroadcast(context, 0, withData, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                am.cancel(pi4); pi4.cancel()
            }
            logWakeCheckForensic("followup_cancel_done", alarmId)
        } catch (_: Exception) {}
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !PermissionUtils.canDrawOverlays(this)) {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                ),
                REQUEST_CODE_OVERLAY_PERMISSION
            )
        }
    }
    
    // -------------------- Mission State Persistence --------------------
    
    private fun saveMissionState() {
        if (isPreview) return
        try {
            val prefs = getSharedPreferences("mission_state", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putInt("alarm_id", alarmId)
                putString("current_mission_id", currentMissionId)
                putBoolean("mission_tap_enabled", missionTapEnabled)
                putBoolean("mission_math_enabled", missionMathEnabled)
                putBoolean("mission_stt_enabled", missionSttEnabled)
                putBoolean("mission_face_enabled", missionFaceEnabled)
                putString("required_password", requiredPassword)
                putBoolean("is_sequencer_mission", isSequencerMission)
                putBoolean("is_sequencer_complete", isSequencerComplete)
                putString("sequencer_context", sequencerContext)
                putBoolean("mission_started", classMissionStarted)
                putLong("save_timestamp", System.currentTimeMillis())
                apply()
            }
            Log.d(TAG, "MISSION_STATE_SAVED: alarmId=$alarmId missionId=$currentMissionId tapEnabled=$missionTapEnabled hasPassword=${requiredPassword != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save mission state: ${e.message}")
        }
    }
    
    private fun restoreMissionState() {
        if (isPreview) return
        try {
            val prefs = getSharedPreferences("mission_state", Context.MODE_PRIVATE)
            val savedAlarmId = prefs.getInt("alarm_id", -1)
            val saveTimestamp = prefs.getLong("save_timestamp", 0)
            val now = System.currentTimeMillis()
            
            // Only restore if it's the same alarm and saved within last 5 minutes
            if (savedAlarmId == alarmId && (now - saveTimestamp) < 5 * 60 * 1000) {
                currentMissionId = prefs.getString("current_mission_id", null)
                
                // CRITICAL FIX: Block "none" missions from being restored from saved state
                if (currentMissionId == "none" || currentMissionId?.contains("none") == true) {
                    Log.w(TAG, "RESTORE_BLOCKED: Blocking 'none' mission from being restored - missionId=$currentMissionId")
                    currentMissionId = null
                    missionTapEnabled = false
                    missionMathEnabled = false
                    missionSttEnabled = false
                    missionFaceEnabled = false
                    requiredPassword = null
                    classMissionStarted = false
                    return
                }

                missionTapEnabled = prefs.getBoolean("mission_tap_enabled", false)
                missionMathEnabled = prefs.getBoolean("mission_math_enabled", false)
                missionSttEnabled = prefs.getBoolean("mission_stt_enabled", false)
                missionFaceEnabled = prefs.getBoolean("mission_face_enabled", false)
                if (missionSttEnabled && sttTargetPhrase.isNullOrBlank()) {
                    sttTargetPhrase = SttMissionSupport.getTargetPhrase(this, alarmId)
                }
                requiredPassword = prefs.getString("required_password", null)
                isSequencerMission = prefs.getBoolean("is_sequencer_mission", false)
                isSequencerComplete = prefs.getBoolean("is_sequencer_complete", false)
                sequencerContext = prefs.getString("sequencer_context", "") ?: ""
                classMissionStarted = prefs.getBoolean("mission_started", false)
                
                Log.d(TAG, "MISSION_STATE_RESTORED: alarmId=$alarmId missionId=$currentMissionId tapEnabled=$missionTapEnabled hasPassword=${requiredPassword != null}")
            } else {
                Log.d(TAG, "MISSION_STATE_NOT_RESTORED: alarmId mismatch or too old (saved=$savedAlarmId current=$alarmId, age=${(now - saveTimestamp) / 1000}s)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore mission state: ${e.message}")
        }
    }
    
    private fun clearMissionState() {
        try {
            val prefs = getSharedPreferences("mission_state", Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
            Log.d(TAG, "MISSION_STATE_CLEARED")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear mission state: ${e.message}")
        }
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        
        // CRITICAL FIX: Save mission state to instance state bundle
        outState.putInt("alarm_id", alarmId)
        outState.putString("current_mission_id", currentMissionId)
        outState.putBoolean("mission_tap_enabled", missionTapEnabled)
        outState.putBoolean("mission_math_enabled", missionMathEnabled)
        outState.putBoolean("mission_stt_enabled", missionSttEnabled)
        outState.putBoolean("mission_face_enabled", missionFaceEnabled)
        outState.putString("required_password", requiredPassword)
        outState.putBoolean("is_sequencer_mission", isSequencerMission)
        outState.putBoolean("is_sequencer_complete", isSequencerComplete)
        outState.putString("sequencer_context", sequencerContext)
        outState.putBoolean("mission_started", classMissionStarted)
        
        Log.d(TAG, "MISSION_STATE_SAVED_TO_INSTANCE: alarmId=$alarmId missionId=$currentMissionId tapEnabled=$missionTapEnabled")
    }
    
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        
        // CRITICAL FIX: Restore mission state from instance state bundle
        try {
            alarmId = savedInstanceState.getInt("alarm_id", -1)
            currentMissionId = savedInstanceState.getString("current_mission_id")
            missionTapEnabled = savedInstanceState.getBoolean("mission_tap_enabled", false)
            missionMathEnabled = savedInstanceState.getBoolean("mission_math_enabled", false)
            missionSttEnabled = savedInstanceState.getBoolean("mission_stt_enabled", false)
            missionFaceEnabled = savedInstanceState.getBoolean("mission_face_enabled", false)
            if (missionSttEnabled && sttTargetPhrase.isNullOrBlank()) {
                sttTargetPhrase = SttMissionSupport.getTargetPhrase(this, alarmId)
            }
            requiredPassword = savedInstanceState.getString("required_password")
            isSequencerMission = savedInstanceState.getBoolean("is_sequencer_mission", false)
            isSequencerComplete = savedInstanceState.getBoolean("is_sequencer_complete", false)
            sequencerContext = savedInstanceState.getString("sequencer_context", "")
            classMissionStarted = savedInstanceState.getBoolean("mission_started", false)
            
            Log.d(TAG, "MISSION_STATE_RESTORED_FROM_INSTANCE: alarmId=$alarmId missionId=$currentMissionId tapEnabled=$missionTapEnabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore mission state from instance: ${e.message}")
        }
    }
    
    /**
     * Check if alarm is in DPS mode
     */
    private fun isInDpsMode(alarmId: Int): Boolean {
        // CPS-ONLY: No DPS mission data - always return false
        return false
    }
}
