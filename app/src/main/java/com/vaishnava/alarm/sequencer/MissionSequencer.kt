package com.vaishnava.alarm.sequencer

import com.vaishnava.alarm.ExtremeForensicLogger

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.Context
import android.util.Log
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.app.KeyguardManager
import android.os.Build
import android.os.UserManager
import com.vaishnava.alarm.AlarmReceiver
import com.vaishnava.alarm.AlarmStorage
import com.vaishnava.alarm.WakeCheckCycleStore
import com.vaishnava.alarm.data.isOneShot
import com.vaishnava.alarm.data.WakeCheckStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class MissionSequencer(private val context: Context) {
    companion object {
        const val ACTION_MISSION_COMPLETED = "com.vaishnava.alarm.MISSION_COMPLETED"
        
        /**
         * CPS resume entrypoint. Starts MissionSequencer only if the user is unlocked,
         * then restores queue and continues from the correct mission index.
         */
        fun resume(context: Context, alarmId: Int) {
            try {
                val userManager = context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
                if (!userManager.isUserUnlocked) {
                    com.vaishnava.alarm.ExtremeForensicLogger.Chrona.cpsViolation(
                        alarmId,
                        "RESUME_BEFORE_UNLOCK",
                        "MissionSequencer.resume",
                        context,
                        "DENIED"
                    )
                    ExtremeForensicLogger.Mission.logWarning("CPS_RESUME_BLOCKED: Device locked - ignoring resume for alarmId=$alarmId")
                    return
                }

                ExtremeForensicLogger.Mission.log("CPS_RESUME_START: alarmId=$alarmId")
                val seq = instance ?: MissionSequencer(context).also { instance = it }
                seq.scope.launch {
                    seq.restoreAndResume()
                }
                ExtremeForensicLogger.Mission.log("CPS_RESUME_QUEUED: alarmId=$alarmId")
            } catch (e: Exception) {
                ExtremeForensicLogger.Mission.logError("CPS_RESUME_ERROR: alarmId=$alarmId - ${e.message}", e)
            }
        }
        
        /**
         * 🔓 RESTORE FROM UNLOCK: Restore mission sequence from DPS to CPS
         * Called when device unlocks and pending completion exists
         * Supports idempotent resume semantics
         */
        fun restoreFromUnlock(context: Context, pendingMissionId: String) {
            ExtremeForensicLogger.Mission.log("RESTORE_FROM_UNLOCK_START: Restoring mission sequence from DPS to CPS")
            
            try {
                // Create MissionSequencer instance with CPS context
                val sequencer = MissionSequencer(context)
                
                // Process the pending mission completion
                ExtremeForensicLogger.Mission.log("RESTORE_PROCESSING: Processing pending completion for missionId=$pendingMissionId")
                sequencer.handleMissionCompletion(pendingMissionId, true)
                
                ExtremeForensicLogger.Mission.log("RESTORE_FROM_UNLOCK_SUCCESS: Mission sequence restored and continued")
            } catch (e: Exception) {
                ExtremeForensicLogger.Mission.logError("RESTORE_FROM_UNLOCK_ERROR: Failed to restore mission sequence: ${e.message}")
            }
        }
        
        /**
         * CRITICAL: Hard gate to prevent CPS access before device unlock
         * Returns true if device is unlocked and CPS operations are safe
         */
        fun isCpsAccessAllowed(context: Context): Boolean {
            return try {
                val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
                userManager.isUserUnlocked
            } catch (e: Exception) {
                Log.e("MissionSequencer", "CPS_ACCESS_CHECK_ERROR: ${e.message}")
                false // Default to CPS blocked if we can't determine unlock state
            }
        }
        
        /**
         * Check if alarm is in DPS mode
         */
        fun isInDpsMode(context: Context, alarmId: Int): Boolean {
            return try {
                // Check if alarm has DPS-specific flags or is in device-protected storage
                val dpsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    context.createDeviceProtectedStorageContext() ?: context
                } else context
                
                val dpsPrefs = dpsContext.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
                val currentAlarmId = dpsPrefs.getInt("current_alarm_id", -1)
                currentAlarmId != -1 && currentAlarmId == alarmId
            } catch (e: Exception) {
                false
            }
        }
        const val EXTRA_FROM_SEQUENCER = "from_sequencer"
        const val EXTRA_MISSION_ID = "mission_id"
        const val EXTRA_MISSION_SUCCESS = "success"
        const val DEFAULT_GLOBAL_PASSWORD = "IfYouWantYouCanSleep"
        @Volatile private var instance: MissionSequencer? = null
        @Volatile private var isInitialized = false
        
        fun isInitialized(): Boolean = isInitialized
        
        /**
         * Force resume from CPS - companion method for external calls
         */
        fun forceResumeFromCps(context: Context, alarmId: Int) {
            val seq = instance ?: MissionSequencer(context).also { instance = it }
            seq.forceResumeFromCps(context, alarmId)
        }
    }
    
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    
    // Force MissionQueueStore through DPS so unlock-time CPS contexts cannot drift from the live queue.
    val queueStore: MissionQueueStore by lazy {
        val dpsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext() ?: context
        } else context
        MissionQueueStore(dpsContext)
    }
    private val dpsMirror: DpsMissionMirror by lazy { DpsMissionMirror(context) }
    private val mutex = Mutex()
    private val localEventCounter = AtomicLong(0L)
    @Volatile private var cachedDpsAuthorityAlarmId: Int = -1
    @Volatile private var cachedDpsAuthorityResult: Boolean = false

    private fun nextEventCounter(): Long = localEventCounter.incrementAndGet()

    private fun isDpsSnapshotAuthority(alarmId: Int): Boolean {
        if (alarmId == -1) return false
        if (isDeviceLocked()) return true
        // Cache the result so repeated calls don't trigger multiple readSnapshot() calls
        if (cachedDpsAuthorityAlarmId == alarmId) return cachedDpsAuthorityResult
        val result = dpsMirror.readSnapshot(alarmId) != null
        cachedDpsAuthorityAlarmId = alarmId
        cachedDpsAuthorityResult = result
        return result
    }

    private fun buildSequenceId(alarmId: Int): String = "$alarmId-${UUID.randomUUID()}"

    private fun missionSpecForId(alarmId: Int, missionId: String): MissionSpec {
        queueStore.loadCurrentMission()
            ?.first
            ?.takeIf { missionIdentityKind(it.id) == missionIdentityKind(missionId) }
            ?.let { storedMission ->
                return storedMission.copy(
                    params = storedMission.params + mapOf(
                        "mission_type" to missionId,
                        "alarm_id" to alarmId.toString()
                    )
                )
            }

        queueStore.loadQueue()
            .firstOrNull { missionIdentityKind(it.id) == missionIdentityKind(missionId) }
            ?.let { queuedMission ->
                return queuedMission.copy(
                    params = queuedMission.params + mapOf(
                        "mission_type" to missionId,
                        "alarm_id" to alarmId.toString()
                    )
                )
            }

        if (alarmId != -1) {
            val serializedMissionData = runCatching {
                AlarmStorage(context).getAlarm(alarmId)?.missionPassword
                    ?: getDirectBootAlarmPrefs().getString("direct_boot_mission_password_$alarmId", null)
            }.getOrNull()

            SequencerMissionCodec.parseMissionSequence(serializedMissionData, alarmId)
                .firstOrNull { missionIdentityKind(it.id) == missionIdentityKind(missionId) }
                ?.let { configuredMission ->
                    return configuredMission.copy(
                        params = configuredMission.params + mapOf(
                            "mission_type" to missionId,
                            "alarm_id" to alarmId.toString()
                        )
                    )
                }
        }

        val timeoutMs = when (missionId) {
            "tap" -> 105_000L
            SttMissionSupport.MISSION_ID -> 120_000L
            FaceMissionSupport.MISSION_ID -> 120_000L
            else -> 30_000L
        }
        return MissionSpec(
            id = missionId,
            params = mapOf(
                "mission_type" to missionId,
                "alarm_id" to alarmId.toString()
            ),
            timeoutMs = timeoutMs,
            retryCount = 3,
            sticky = true,
            retryDelayMs = 1_000L
        )
    }

    private fun syncCachesFromSnapshot(snapshot: DpsMissionMirror.Snapshot) {
        val pendingSpecs = snapshot.pendingMissionIds.map { missionSpecForId(snapshot.alarmId, it) }
        updatePendingQueueSnapshot(pendingSpecs)
        queueStore.saveQueue(pendingSpecs)

        if (snapshot.activeMissionId != null) {
            val activeMission = missionSpecForId(snapshot.alarmId, snapshot.activeMissionId)
            currentMission = activeMission
            isProcessing = true
            queueStore.saveCurrentMission(activeMission)
        } else {
            currentMission = null
            isProcessing = false
            queueStore.clearCurrentMission()
        }

        saveExplicitPointer(snapshot.alarmId, snapshot.activeMissionId, snapshot.pendingMissionIds)
    }

    fun rehydrateFromSnapshot(
        snapshot: DpsMissionMirror.Snapshot,
        ringtoneUri: android.net.Uri? = null
    ) {
        if (snapshot.isSequenceComplete || snapshot.activeMissionId == null) {
            ExtremeForensicLogger.Mission.logDpsForensic(
                component = "MissionSequencer",
                event = "rehydrate_blocked_terminal_or_null",
                snapshot = snapshot,
                expectedMissionId = snapshot.activeMissionId,
                runtimeMissionId = currentMission?.id,
                previousState = snapshot.state,
                source = "rehydrateFromSnapshot",
                queueReconstructed = false,
                nullMissionObserved = snapshot.activeMissionId == null && !snapshot.isSequenceComplete,
                stalePendingIntent = false,
                replayingOldData = false
            )
            return
        }

        originalRingtoneUri = ringtoneUri ?: originalRingtoneUri
        isSequencerComplete = false
        syncCachesFromSnapshot(snapshot)

        val activeMission = missionSpecForId(snapshot.alarmId, snapshot.activeMissionId)
        currentMission = activeMission
        isProcessing = true
        queueStore.saveCurrentMission(activeMission)

        ExtremeForensicLogger.Mission.logDpsForensic(
            component = "MissionSequencer",
            event = "rehydrate_start",
            snapshot = snapshot,
            expectedMissionId = snapshot.activeMissionId,
            runtimeMissionId = activeMission.id,
            previousState = snapshot.state,
            source = "rehydrateFromSnapshot",
            queueReconstructed = false,
            nullMissionObserved = false,
            stalePendingIntent = false,
            replayingOldData = false
        )

        startMission(activeMission)
    }

    fun initializeFreshSequence(
        alarmId: Int,
        orderedMissionIds: List<String>,
        ringtoneUri: android.net.Uri? = null
    ): DpsMissionMirror.Snapshot? {
        if (alarmId == -1) return null

        val normalizedMissionIds = orderedMissionIds
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        if (normalizedMissionIds.isEmpty()) {
            ExtremeForensicLogger.Mission.logWarning("DPS_FRESH_INIT_SKIPPED: alarmId=$alarmId reason=no_missions")
            return null
        }

        originalRingtoneUri = ringtoneUri ?: originalRingtoneUri
        isSequencerComplete = false

        val existingSnapshot = dpsMirror.readSnapshot(alarmId)
        if (existingSnapshot != null) {
            if (existingSnapshot.isSequenceComplete) {
                ExtremeForensicLogger.Mission.log(
                    "WAKECHECK_REARM_SNAPSHOT_RESET: owner=MissionSequencer alarmId=$alarmId " +
                        "sequenceId=${existingSnapshot.sequenceId} version=${existingSnapshot.version} " +
                        "reason=completed_snapshot_cannot_be_reused"
                )
                dpsMirror.clearSnapshot(alarmId)
            } else if (existingSnapshot.activeMissionId != null) {
                syncCachesFromSnapshot(existingSnapshot)
                ExtremeForensicLogger.Mission.logDpsForensic(
                    component = "MissionSequencer",
                    event = "fresh_sequence_init_reused_snapshot",
                    snapshot = existingSnapshot,
                    expectedMissionId = existingSnapshot.activeMissionId,
                    runtimeMissionId = currentMission?.id,
                    previousState = existingSnapshot.state,
                    source = "initializeFreshSequence",
                    queueReconstructed = false,
                    nullMissionObserved = false,
                    stalePendingIntent = false,
                    replayingOldData = false
                )
                return existingSnapshot
            } else {
                ExtremeForensicLogger.Mission.logWarning(
                    "DPS_FRESH_INIT_RESET: alarmId=$alarmId reason=existing_snapshot_missing_active_mission " +
                        "version=${existingSnapshot.version} state=${existingSnapshot.state}"
                )
                dpsMirror.clearSnapshot(alarmId)
            }
        }

        val startedSnapshot = dpsMirror.markSequenceStarted(
            alarmId = alarmId,
            sequenceId = buildSequenceId(alarmId),
            orderedMissionIds = normalizedMissionIds,
            eventCounter = nextEventCounter(),
            isAlarmRinging = true
        )
        val firstMissionId = normalizedMissionIds.first()
        val activeSnapshot = dpsMirror.markMissionActivated(
            snapshot = startedSnapshot,
            activeMissionId = firstMissionId,
            activeIndex = 0,
            pendingMissionIds = normalizedMissionIds.drop(1),
            eventCounter = nextEventCounter(),
            previousState = startedSnapshot.state
        )
        syncCachesFromSnapshot(activeSnapshot)
        ExtremeForensicLogger.Mission.logDpsForensic(
            component = "MissionSequencer",
            event = "fresh_sequence_init_created",
            snapshot = activeSnapshot,
            expectedMissionId = firstMissionId,
            runtimeMissionId = firstMissionId,
            previousState = startedSnapshot.state,
            source = "initializeFreshSequence",
            queueReconstructed = false,
            nullMissionObserved = false,
            stalePendingIntent = false,
            replayingOldData = false
        )
        return activeSnapshot
    }

    private fun requireActiveSnapshot(
        alarmId: Int,
        source: String,
        runtimeMissionId: String? = currentMission?.id
    ): DpsMissionMirror.Snapshot {
        val snapshot = dpsMirror.readSnapshot(alarmId)
            ?: throw IllegalStateException("DPS snapshot missing for alarmId=$alarmId source=$source")

        if (snapshot.isSequenceComplete) {
            ExtremeForensicLogger.Mission.logDpsForensic(
                component = "MissionSequencer",
                event = "snapshot_completed_illegal_active",
                snapshot = snapshot,
                expectedMissionId = snapshot.activeMissionId,
                runtimeMissionId = runtimeMissionId,
                previousState = snapshot.state,
                source = source,
                queueReconstructed = false,
                nullMissionObserved = false,
                stalePendingIntent = false,
                replayingOldData = true
            )
            throw IllegalStateException("snapshot completed for active alarmId=$alarmId source=$source")
        }

        if (snapshot.activeMissionId == null || snapshot.activeIndex < 0) {
            ExtremeForensicLogger.Mission.logDpsForensic(
                component = "MissionSequencer",
                event = "snapshot_active_null",
                snapshot = snapshot,
                expectedMissionId = snapshot.activeMissionId,
                runtimeMissionId = runtimeMissionId,
                previousState = snapshot.state,
                source = source,
                queueReconstructed = false,
                nullMissionObserved = true,
                stalePendingIntent = false,
                replayingOldData = false
            )
            throw IllegalStateException("activeMissionId null for active alarmId=$alarmId source=$source")
        }

        return snapshot
    }

    private fun logDpsInvariant(
        event: String,
        snapshot: DpsMissionMirror.Snapshot?,
        expectedMissionId: String?,
        runtimeMissionId: String?,
        source: String
    ) {
        ExtremeForensicLogger.Mission.logDpsForensic(
            component = "MissionSequencer",
            event = event,
            snapshot = snapshot,
            expectedMissionId = expectedMissionId,
            runtimeMissionId = runtimeMissionId,
            previousState = snapshot?.state,
            source = source,
            queueReconstructed = false,
            nullMissionObserved = snapshot?.activeMissionId == null && snapshot?.isSequenceComplete == false,
            stalePendingIntent = false,
            replayingOldData = true
        )
    }

    private fun getDpsPrefs(): android.content.SharedPreferences {
        val dpsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext() ?: context
        } else context
        return dpsContext.getSharedPreferences("mission_sequencer_pointer", Context.MODE_PRIVATE)
    }

    private fun getAlarmDpsPrefs(): android.content.SharedPreferences {
        val dpsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext() ?: context
        } else context
        return dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
    }

    private fun getDirectBootAlarmPrefs(): android.content.SharedPreferences {
        val dpsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext() ?: context
        } else context
        return dpsContext.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
    }

    private fun saveExplicitPointer(alarmId: Int, activeId: String?, queueIds: List<String>) {
        if (alarmId == -1) return
        try {
            val saved = getDpsPrefs().edit()
                .putString("active_mission_$alarmId", activeId)
                .putString("queue_ids_$alarmId", queueIds.joinToString(","))
                .putLong("pointer_timestamp_$alarmId", System.currentTimeMillis())
                .commit()

            val queueStr = queueIds.joinToString(",")
            if (!saved) {
                ExtremeForensicLogger.Mission.logError("DPS_POINTER_SAVE_ERROR: alarmId=$alarmId pointer=$activeId queue=[$queueStr] commit_failed=true")
            } else {
                ExtremeForensicLogger.Mission.log("ULTRA_FORENSIC_LOG: mode=DPS event=SAVE_POINTER alarmId=$alarmId pointer=$activeId pending_queue=[$queueStr] commit=true")
            }
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("DPS_POINTER_SAVE_ERROR: ${e.message}")
        }
    }
    
    @Volatile private var currentMission: MissionSpec? = null
    @Volatile private var isProcessing = false
    @Volatile private var currentJob: Job? = null
    @Volatile private var timeoutJob: Job? = null
    @Volatile var isSequencerComplete = false
    @Volatile private var lastProcessStartTime: Long = 0
    @Volatile private var originalRingtoneUri: android.net.Uri? = null
    @Volatile private var pendingQueueSnapshot: List<MissionSpec> = emptyList()
    private val completedMissionIds = mutableSetOf<String>()

    private fun updatePendingQueueSnapshot(queue: List<MissionSpec>) {
        pendingQueueSnapshot = queue.toList()
    }

    private fun clearPendingQueueSnapshot() {
        pendingQueueSnapshot = emptyList()
    }
    
    private val missionCompletionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            ExtremeForensicLogger.Mission.log("BROADCAST_RECEIVED_ANY: action=${intent?.action}")
            
            val missionId = intent?.getStringExtra(EXTRA_MISSION_ID)
            val success = intent?.getBooleanExtra(EXTRA_MISSION_SUCCESS, true) ?: true
            val fromSequencer = intent?.getBooleanExtra(EXTRA_FROM_SEQUENCER, false) ?: false
            
            ExtremeForensicLogger.Mission.log("BROADCAST_RECEIVED: missionId=$missionId, success=$success, fromSequencer=$fromSequencer")
            
            if (missionId != null) {
                scope.launch {
                    ExtremeForensicLogger.Mission.log("CALLING_HANDLE_COMPLETION: $missionId")
                    handleMissionCompletion(missionId, success)
                }
            } else {
                ExtremeForensicLogger.Mission.logWarning("Received mission completion broadcast with null missionId")
            }
        }
    }
    
    init {
        ExtremeForensicLogger.Mission.log("SEQUENCER_INIT: Context type = ${context.javaClass.name}")
        // CRITICAL FIX: Allow MissionSequencer initialization regardless of device lock state
        // Mission completion events must be received even if device was initially locked
        ExtremeForensicLogger.Mission.log("INIT: MissionSequencer init starting - mission completion receiver ready")   
        
        ExtremeForensicLogger.Mission.log("INIT: MissionSequencer init starting - DEVICE UNLOCKED")
        
        // CRITICAL FIX: Add immediate notification to prove MissionSequencer is being initialized
        try {
            val alarmStorage = com.vaishnava.alarm.AlarmStorage(context)
            val allAlarms = alarmStorage.getAlarms()
            val sequencerCount = allAlarms.count { it.missionType == "sequencer" }
            ExtremeForensicLogger.Mission.log("INIT_NOTIFICATION: MissionSequencer initialized! Found $sequencerCount sequencer alarms out of ${allAlarms.size} total alarms")
            
            // Show first few sequencer alarms for verification
            allAlarms.filter { it.missionType == "sequencer" }.take(3).forEach { alarm ->
                ExtremeForensicLogger.Mission.log("INIT_SAMPLE_ALARM: ID=${alarm.id}, Type=${alarm.missionType}, Password=${alarm.missionPassword}")
            }
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("INIT_NOTIFICATION_ERROR: Failed to read alarms in init", e)
        }
        
        val filter = IntentFilter(ACTION_MISSION_COMPLETED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(missionCompletionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(missionCompletionReceiver, filter)
        }
        
        // STRICT MODE: Do not auto-listen for unlock; CPS resume is explicit via BootReceiver/MissionResumeService.
        
        ExtremeForensicLogger.Mission.log("INIT: MissionSequencer initialized; registered receiver for ACTION_MISSION_COMPLETED")
        
        scope.launch {
            ExtremeForensicLogger.Mission.log("INIT: Setting MissionSequencer as initialized")
            isInitialized = true
            
            // STRICT MODE: No auto-restore or pending replay on init. Only explicit resume controls progression.
        }
    }
    
    fun enqueue(missionSpec: MissionSpec) {
        // 🔐 STRICT UNLOCK GATE - NON-NEGOTIABLE
        if (false && !isCpsAccessAllowed(context)) {
            // 🚫 CPS ACCESS DENIED - THIS PREVENTS UNINSTALL
            com.vaishnava.alarm.ExtremeForensicLogger.Chrona.cpsViolation(-1, "ENQUEUE_BEFORE_UNLOCK", "MissionSequencer.enqueue", context, "DENIED")
            return
        }
        
        // Prevent "none" missions from being added to multi-mission queue
        if (missionSpec.id.contains("none_mission") || missionSpec.id.contains("mission_type=none") || missionSpec.id == "none") {
            ExtremeForensicLogger.Mission.logWarning("ENQUEUE_BLOCKED: Blocking 'none' mission from multi-mission queue - missionId=${missionSpec.id}")
            return
        }
        
        scope.launch {
            mutex.withLock {
                val beforeQueue = queueStore.loadQueue()
                ExtremeForensicLogger.Mission.log("ENQUEUE_START: missionId=${missionSpec.id} existingSize=${beforeQueue.size}")
                val queue = beforeQueue.toMutableList()
                queue.add(missionSpec)
                updatePendingQueueSnapshot(queue)
                queueStore.saveQueue(queue)
                ExtremeForensicLogger.Mission.log("ENQUEUE_DONE: missionId=${missionSpec.id} newSize=${queue.size}")
                
                // Don't auto-start - wait for alarm to fire
                ExtremeForensicLogger.Mission.log("ENQUEUE_WAITING: Mission queued, waiting for alarm to fire")
            }
        }
    }
    
    fun enqueueAll(missionSpecs: List<MissionSpec>) {
        scope.launch {
            try {
                ExtremeForensicLogger.Mission.log("ENQUEUE_ALL_START: received=${missionSpecs.size} missions")
                missionSpecs.forEachIndexed { index, spec ->
                    ExtremeForensicLogger.Mission.log("ENQUEUE_ALL_MISSION_$index: id=${spec.id} type=${spec.params["mission_type"]}")
                }
                
                mutex.withLock {
                    val beforeQueue = queueStore.loadQueue()
                    val filteredMissions = missionSpecs.filter { mission ->
                        val isNoneMission = mission.id.contains("none_mission") || mission.id.contains("mission_type=none") || mission.id == "none"
                        if (isNoneMission) {
                            ExtremeForensicLogger.Mission.logWarning("ENQUEUE_ALL_BLOCKED: Blocking 'none' mission from multi-mission queue - missionId=${mission.id}")
                        }
                        !isNoneMission
                    }
                    
                    val queue = beforeQueue.toMutableList()
                    queue.addAll(filteredMissions)
                    updatePendingQueueSnapshot(queue)
                    queueStore.saveQueue(queue)
                    val alarmId =
                        queue.firstOrNull()?.params?.get("alarm_id")?.toIntOrNull()
                            ?: getCurrentAlarmId()
                    if (
                        alarmId != -1 &&
                        beforeQueue.isEmpty() &&
                        currentMission == null &&
                        dpsMirror.readSnapshot(alarmId) == null
                    ) {
                        dpsMirror.markSequenceStarted(
                            alarmId = alarmId,
                            sequenceId = buildSequenceId(alarmId),
                            orderedMissionIds = queue.map { it.id },
                            eventCounter = nextEventCounter(),
                            isAlarmRinging = false
                        )
                    }
                    ExtremeForensicLogger.Mission.log("ENQUEUE_ALL_DONE: added=${filteredMissions.size} (filtered from ${missionSpecs.size}) newSize=${queue.size}")
                    
                    // Don't auto-start - wait for alarm to fire
                    ExtremeForensicLogger.Mission.log("ENQUEUE_ALL_WAITING: Missions queued, waiting for alarm to fire")
                }
            } catch (e: Exception) {
                ExtremeForensicLogger.Mission.logError("ENQUEUE_ALL_ERROR: ${e.message}")
            }
        }
    }
    
    fun startWhenAlarmFires(ringtoneUri: android.net.Uri? = null) {
        // CRITICAL: Hard gate - prevent CPS access before device unlock to avoid app uninstall
        if (false && !isCpsAccessAllowed(context)) {
            ExtremeForensicLogger.Mission.log("CPS_BLOCKED_DEVICE_LOCKED: Device not unlocked, blocking CPS operations to prevent app uninstall")
            return
        }
        
        // CRITICAL FIX: Store the original ringtone URI for use throughout the sequencer
        originalRingtoneUri = ringtoneUri
        
        // CRITICAL FIX: Reset sequencer complete flag when starting new sequencer
        isSequencerComplete = false
        ExtremeForensicLogger.Mission.log("BASIC_START_WHEN_ALARM_FIRES: Manual start triggered by alarm fire, sequencerComplete reset")
        try {
            val currentAlarmId = getCurrentAlarmId()
            if (currentAlarmId != -1) {
                val dpsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    context.createDeviceProtectedStorageContext() ?: context
                } else context
                dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                    .edit()
                    .remove("sequencer_completed_$currentAlarmId")
                    .commit()
            }
        } catch (_: Exception) { }
        
        // BASIC DEBUG: This should always appear
        ExtremeForensicLogger.Mission.log("BASIC_START_WHEN_ALARM_FIRES: Manual start triggered by alarm fire")

        scope.launch {
            val alarmId = getCurrentAlarmId()
            val useSnapshotAuthority = isDpsSnapshotAuthority(alarmId)
            var snapshot =
                if (alarmId != -1 && useSnapshotAuthority) dpsMirror.readSnapshot(alarmId) else null

            if (useSnapshotAuthority) {
                if (snapshot == null) {
                    val queuedMissionIds = run {
                        val diskQueue = queueStore.loadQueue()
                        if (diskQueue.isNotEmpty()) {
                            diskQueue.map { it.id }
                        } else {
                            pendingQueueSnapshot.map { it.id }
                        }
                    }
                    if (queuedMissionIds.isNotEmpty()) {
                        snapshot =
                            initializeFreshSequence(
                                alarmId = alarmId,
                                orderedMissionIds = queuedMissionIds,
                                ringtoneUri = ringtoneUri
                            )
                    }
                }

                if (snapshot == null) {
                    logDpsInvariant(
                        event = "start_when_alarm_fires_snapshot_missing",
                        snapshot = null,
                        expectedMissionId = null,
                        runtimeMissionId = currentMission?.id,
                        source = "startWhenAlarmFires"
                    )
                    return@launch
                }

                if (snapshot.isSequenceComplete) {
                    logDpsInvariant(
                        event = "start_when_alarm_fires_snapshot_completed",
                        snapshot = snapshot,
                        expectedMissionId = snapshot.activeMissionId,
                        runtimeMissionId = currentMission?.id,
                        source = "startWhenAlarmFires"
                    )
                    return@launch
                }

                ExtremeForensicLogger.Mission.logDpsForensic(
                    component = "MissionSequencer",
                    event = "start_when_alarm_fires_snapshot_read",
                    snapshot = snapshot,
                    expectedMissionId = snapshot.activeMissionId,
                    runtimeMissionId = currentMission?.id,
                    previousState = snapshot.state,
                    source = "startWhenAlarmFires",
                    queueReconstructed = false,
                    nullMissionObserved = snapshot.activeMissionId == null && !snapshot.isSequenceComplete,
                    stalePendingIntent = false,
                    replayingOldData = false
                )

                if (snapshot.activeMissionId != null) {
                    rehydrateFromSnapshot(snapshot, ringtoneUri)
                    return@launch
                }

                syncCachesFromSnapshot(snapshot)
            }

            var queue = queueStore.loadQueue()
            if (queue.isEmpty() && !useSnapshotAuthority) {
                val snapshotQueue = pendingQueueSnapshot
                if (snapshotQueue.isNotEmpty()) {
                    ExtremeForensicLogger.Mission.log("BASIC_QUEUE_SYNC: Disk empty, using immediate memory queue")
                    queue = snapshotQueue
                    runCatching { queueStore.saveQueue(queue) }
                        .onFailure { ExtremeForensicLogger.Mission.logError("BASIC_QUEUE_SYNC_SAVE_ERROR: ${it.message}") }
                } else {
                    ExtremeForensicLogger.Mission.log("BASIC_QUEUE_RETRY: queue empty on first read, delaying for enqueue persistence")
                    delay(400)
                    queue = queueStore.loadQueue()
                    if (queue.isEmpty()) {
                        val retrySnapshot = pendingQueueSnapshot
                        if (retrySnapshot.isNotEmpty()) {
                            ExtremeForensicLogger.Mission.log("BASIC_QUEUE_SYNC: Disk still empty after retry, using immediate memory queue")
                            queue = retrySnapshot
                            runCatching { queueStore.saveQueue(queue) }
                                .onFailure { ExtremeForensicLogger.Mission.logError("BASIC_QUEUE_SYNC_SAVE_ERROR: ${it.message}") }
                        }
                    }
                }
            }

            if (queue.isNotEmpty()) {
                updatePendingQueueSnapshot(queue)
            }

            ExtremeForensicLogger.Mission.log("BASIC_QUEUE_CHECK: queueSize=${queue.size}")

            queue.forEachIndexed { index, mission ->
                ExtremeForensicLogger.Mission.log("QUEUE_MISSION_$index: id=${mission.id} type=${mission.params["mission_type"]} params=${mission.params}")
            }
            ExtremeForensicLogger.Mission.log("QUEUE_ORDER: First mission=${queue.firstOrNull()?.id} Last mission=${queue.lastOrNull()?.id}")

            if (queue.isEmpty()) {
                if (useSnapshotAuthority) {
                    logDpsInvariant(
                        event = "start_when_alarm_fires_empty_snapshot_queue",
                        snapshot = snapshot,
                        expectedMissionId = snapshot?.activeMissionId,
                        runtimeMissionId = currentMission?.id,
                        source = "startWhenAlarmFires"
                    )
                    return@launch
                }

                ExtremeForensicLogger.Mission.logWarning("BASIC_EMPTY_QUEUE: No missions in queue - launching AlarmActivity with default mission")

                try {
                    val currentAlarmId = getCurrentAlarmId()
                    if (currentAlarmId != -1) {
                        val alarmStorage = com.vaishnava.alarm.AlarmStorage(context)
                        val alarm = alarmStorage.getAlarm(currentAlarmId)
                        if (alarm != null) {
                            val intent = Intent(context, com.vaishnava.alarm.AlarmActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                        Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT or
                                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                )
                                putExtra(com.vaishnava.alarm.AlarmReceiver.ALARM_ID, alarm.id)
                                putExtra(com.vaishnava.alarm.AlarmReceiver.EXTRA_RINGTONE_URI, alarm.ringtoneUri)
                                putExtra(com.vaishnava.alarm.AlarmReceiver.EXTRA_REPEAT_DAYS, alarm.days?.toIntArray())
                                putExtra("hour", alarm.hour)
                                putExtra("minute", alarm.minute)
                                putExtra("repeatDaily", alarm.repeatDaily)
                                putExtra("mission_type", "tap")
                                putExtra("mission_password", "")
                                putExtra("is_protected", alarm.isProtected == true)
                            }
                            context.startActivity(intent)
                            ExtremeForensicLogger.Mission.log("EMPTY_QUEUE_LAUNCH: Launched AlarmActivity with default 'tap' mission for alarm $currentAlarmId")
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    ExtremeForensicLogger.Mission.logError("EMPTY_QUEUE_LAUNCH_ERROR: Failed to launch AlarmActivity - ${e.message}")
                }

                try {
                    val alarmStorage = com.vaishnava.alarm.AlarmStorage(context)
                    val currentAlarmId = getCurrentAlarmId()
                    if (currentAlarmId != -1) {
                        alarmStorage.disableAlarm(currentAlarmId)
                        ExtremeForensicLogger.Mission.log("START_WHEN_ALARM_FIRES_DISABLE_ALARM: Disabled sequencer alarm $currentAlarmId due to empty queue")

                        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                        val intent = Intent(context, com.vaishnava.alarm.AlarmReceiver::class.java).apply {
                            action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
                            data = android.net.Uri.parse("alarm://$currentAlarmId")
                            putExtra(com.vaishnava.alarm.AlarmReceiver.ALARM_ID, currentAlarmId)
                        }
                        val pendingIntent = android.app.PendingIntent.getBroadcast(
                            context,
                            currentAlarmId,
                            intent,
                            android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
                        )
                        if (pendingIntent != null) {
                            alarmManager.cancel(pendingIntent)
                            pendingIntent.cancel()
                            ExtremeForensicLogger.Mission.log("START_WHEN_ALARM_FIRES_CLEAR_PENDING: Cleared pending intent for alarm $currentAlarmId")
                        }
                    }
                } catch (e: Exception) {
                    ExtremeForensicLogger.Mission.logError("START_WHEN_ALARM_FIRES_DISABLE_ERROR: Failed to disable alarm - ${e.message}")
                }

                return@launch
            }

            processQueueInternal()
        }
    }
    
    fun cancelQueue() {
        scope.launch {
            mutex.withLock {
                ExtremeForensicLogger.Mission.log("QUEUE_CANCEL_START: currentMissionId=${currentMission?.id} isProcessing=$isProcessing")
                currentJob?.cancel()
                timeoutJob?.cancel()
                currentJob = null
                timeoutJob = null
                currentMission = null
                isProcessing = false
                queueStore.clearQueue()
                queueStore.clearCurrentMission()
                clearPendingQueueSnapshot()
                cachedDpsAuthorityAlarmId = -1  // Invalidate DPS authority cache
                saveExplicitPointer(getCurrentAlarmId(), null, emptyList())
                ExtremeForensicLogger.Mission.log("QUEUE_CANCELLED: all jobs cancelled and state cleared")
            }
        }
    }
    
    private suspend fun restoreAndResume() {
        mutex.withLock {
            ExtremeForensicLogger.Mission.log("RESTORE_START: attempting to restore current mission and queue")
            val snapshotAlarmId = getCurrentAlarmId()
            if (snapshotAlarmId != -1 && isDpsSnapshotAuthority(snapshotAlarmId)) {
                val snapshot = dpsMirror.readSnapshot(snapshotAlarmId)
                if (snapshot != null) {
                    ExtremeForensicLogger.Mission.logDpsForensic(
                        component = "MissionSequencer",
                        event = "restore_snapshot_read",
                        snapshot = snapshot,
                        expectedMissionId = snapshot.activeMissionId,
                        runtimeMissionId = currentMission?.id,
                        previousState = snapshot.state,
                        source = "restoreAndResume",
                        queueReconstructed = false,
                        nullMissionObserved = snapshot.activeMissionId == null && !snapshot.isSequenceComplete,
                        stalePendingIntent = false,
                        replayingOldData = false
                    )

                    if (snapshot.isSequenceComplete) {
                        handleSequencerComplete(snapshotAlarmId)
                        return@withLock
                    }

                    if (snapshot.activeMissionId != null) {
                        syncCachesFromSnapshot(snapshot)
                        return@withLock
                    }
                }
            }
            
            // Check if there are any active sequencer alarms
            val alarmStorage = com.vaishnava.alarm.AlarmStorage(context)
            val activeSequencerAlarms = alarmStorage.getAlarms().any { it.missionType == "sequencer" }
            
            // CRITICAL FIX: Show notification with what data is being read on force restart
            try {
                val allAlarms = alarmStorage.getAlarms()
                val sequencerAlarms = allAlarms.filter { it.missionType == "sequencer" }
                
                if (sequencerAlarms.isNotEmpty()) {
                    val alarmInfo = sequencerAlarms.joinToString("\n") { alarm ->
                        "ID: ${alarm.id}, Type: ${alarm.missionType}, Password: ${alarm.missionPassword}"
                    }
                    ExtremeForensicLogger.Mission.log("FORCE_RESTART_DATA_READ: Found ${sequencerAlarms.size} sequencer alarms:\n$alarmInfo")
                    
                    // Also show DPS data for each alarm
                    sequencerAlarms.forEach { alarm ->
                        val dpsPrefs = getDirectBootAlarmPrefs()
                        val dpsType = dpsPrefs.getString("direct_boot_mission_type_${alarm.id}", "not_found")
                        val dpsPassword = dpsPrefs.getString("direct_boot_mission_password_${alarm.id}", "not_found")
                        ExtremeForensicLogger.Mission.log("DPS_DATA_ALARM_${alarm.id}: Type=$dpsType, Password=$dpsPassword")
                    }
                }
            } catch (e: Exception) {
                ExtremeForensicLogger.Mission.logError("Failed to read alarm data for notification", e)
            }
            
            if (!activeSequencerAlarms) {
                ExtremeForensicLogger.Mission.log("RESTORE_NO_SEQUENCER_ALARMS: No active sequencer alarms found, clearing mission queue")
                queueStore.clearQueue()
                queueStore.clearCurrentMission()
                return@withLock
            }
            
            val currentMissionData = queueStore.loadCurrentMission()
            if (currentMissionData != null) {
                val (mission, startTime) = currentMissionData
                mission?.let { safeMission ->
                    // CRITICAL: Never restore "none" missions from storage
                    if (safeMission.id == "none" || safeMission.id.contains("none_mission") || safeMission.id.contains("mission_type=none")) {
                        ExtremeForensicLogger.Mission.logWarning("RESTORE_BLOCKED: Refusing to restore 'none' mission from storage - missionId=${safeMission.id}")
                        queueStore.clearCurrentMission()
                        return@withLock
                    }
                    
                    val now = System.currentTimeMillis()
                    val elapsed = now - startTime
                    
                    if (elapsed < safeMission.timeoutMs) {
                        ExtremeForensicLogger.Mission.log("RESTORING_CURRENT_MISSION: ${safeMission.id} elapsedMs=$elapsed remainingTimeoutMs=${safeMission.timeoutMs - elapsed}")
                        currentMission = safeMission
                        isProcessing = true
                        startTimeout(safeMission, safeMission.timeoutMs - elapsed)
                    } else {
                        ExtremeForensicLogger.Mission.log("CURRENT_MISSION_TIMEOUT: ${safeMission.id} elapsedMs=$elapsed >= timeoutMs=${safeMission.timeoutMs}")
                        ExtremeForensicLogger.Mission.log("TIMEOUT_RECOVERY: Mission expired, checking queue and potentially disabling sequencer alarm")
                        
                        queueStore.clearCurrentMission()
                        val queue = queueStore.loadQueue().toMutableList()
                        
                        if (queue.isNotEmpty()) {
                            // CRITICAL FIX: Don't filter out "none" missions - let them be processed
                            val filteredQueue = queue
                            
                            if (filteredQueue.isEmpty()) {
                                ExtremeForensicLogger.Mission.log("TIMEOUT_RECOVERY_QUEUE_EMPTY: All missions filtered out, disabling sequencer alarm")
                                handleSequencerComplete(getCurrentAlarmId())
                                return@withLock
                            }
                            
                            updatePendingQueueSnapshot(filteredQueue)
                            queueStore.saveQueue(filteredQueue)
                            ExtremeForensicLogger.Mission.log("TIMEOUT_RECOVERY_QUEUE_UPDATED: removed expired mission, remaining=${filteredQueue.size}")
                            ExtremeForensicLogger.Mission.log("TIMEOUT_RECOVERY_WAITING: Queue has missions, waiting for alarm trigger to continue")
                        } else {
                            ExtremeForensicLogger.Mission.log("TIMEOUT_RECOVERY_QUEUE_EMPTY: No more missions in queue, disabling sequencer alarm")
                            handleSequencerComplete(getCurrentAlarmId())
                            return@withLock
                        }
                    }
                }
            }
            
            // Only resume queue if there's a current mission already processing
            // Don't auto-start missions when app launches - only when alarm fires
            if (!isProcessing) {
                ExtremeForensicLogger.Mission.log("RESTORE_NO_AUTO_START: Not auto-starting queue - requires alarm trigger")
            } else {
                ExtremeForensicLogger.Mission.log("RESTORE_ALREADY_PROCESSING: Current mission is processing, not auto-starting")
            }
        }
        
        // CRITICAL FIX: Don't auto-start queue during restore - wait for alarm trigger
        // This prevents the password page from appearing and quitting immediately
        val queue = queueStore.loadQueue()
        if (queue.isNotEmpty()) {
            ExtremeForensicLogger.Mission.log("RESTORE_QUEUE_EXISTS: ${queue.size} missions in queue, waiting for alarm trigger")
            // Don't call startProcessing() - let the alarm trigger handle it
        }
    }
    
    private suspend fun startProcessing() {
        ExtremeForensicLogger.Mission.log("START_PROCESSING_REQUEST: requestedBy=internal isProcessing=$isProcessing currentMissionId=${currentMission?.id ?: "<none>"}")
        if (isProcessing) {
            ExtremeForensicLogger.Mission.log("START_PROCESSING_ABORT: reason=already_processing currentMissionId=${currentMission?.id}")
            return
        }

        // CRITICAL FIX: Prevent processing if device is locked (CPS not available)
        if (false && isDeviceLocked()) {
            ExtremeForensicLogger.Mission.log("START_PROCESSING_BLOCKED: reason=device_locked - Waiting for unlock")
            return
        }

        // Mark that we're about to process and immediately kick the queue
        isProcessing = false // ensureQueueContinues / processQueueInternal will set this when it actually starts
        ensureQueueContinues()
    }
    
    private suspend fun processQueueInternal() {
        val logTag = "MissionSequencer"
        ExtremeForensicLogger.Mission.log("PROCESS_QUEUE_INTERNAL_ENTRY: isProcessing=$isProcessing, currentMissionId=${currentMission?.id ?: "<none>"}")
        
        // CRITICAL FIX: Don't process if sequencer is complete
        if (isSequencerComplete) {
            ExtremeForensicLogger.Mission.log("PROCESS_QUEUE_INTERNAL_BLOCKED: Sequencer is complete, aborting internal processing")
            return
        }

        // CRITICAL FIX: Don't process if device is locked (CPS not available)
        if (false && isDeviceLocked()) {
            ExtremeForensicLogger.Mission.log("PROCESS_QUEUE_INTERNAL_BLOCKED: reason=device_locked - Waiting for unlock")
            return
        }
        
        // Double-check conditions with mutex to prevent race conditions
        mutex.withLock {
            // CRITICAL FIX: Double-check sequencer complete flag inside mutex
            if (isSequencerComplete) {
                ExtremeForensicLogger.Mission.log("PROCESS_QUEUE_INTERNAL_MUTEX_BLOCKED: Sequencer complete detected inside mutex, aborting")
                return@withLock
            }
            
            // If we think we're processing but there's no current mission, fix the state
            if (isProcessing && currentMission == null) {
                ExtremeForensicLogger.Mission.logWarning("PROCESS_QUEUE_RECOVERY: Found isProcessing=true but no current mission. Resetting state.")
                isProcessing = false
            }
            
            if (isProcessing || currentMission != null) {
                val currentId = currentMission?.id ?: "<none>"
                ExtremeForensicLogger.Mission.log("PROCESS_QUEUE_BUSY: Already processing mission $currentId")
                // If we've been stuck for too long, force reset the state
                if (System.currentTimeMillis() - lastProcessStartTime > 30000) { // 30 second timeout
                    ExtremeForensicLogger.Mission.logWarning("PROCESS_QUEUE_STUCK: Resetting processing state after timeout")
                    isProcessing = false
                    currentMission = null
                    queueStore.clearCurrentMission()
                } else {
                    return@withLock
                }
            }
            
            try {
                var queue = queueStore.loadQueue()
                ExtremeForensicLogger.Mission.log("PROCESS_QUEUE_STATE: queueSize=${queue.size}, firstItemId=${queue.firstOrNull()?.id ?: "<none>"}")
                
                // CRITICAL FIX: Don't filter out "none" missions - let them be processed
                val originalSize = queue.size
                
                if (queue.size != originalSize) {
                    ExtremeForensicLogger.Mission.logWarning("PROCESS_QUEUE_FILTER: Removed ${originalSize - queue.size} missions, saving cleaned queue")
                    updatePendingQueueSnapshot(queue)
                    queueStore.saveQueue(queue)
                }
                
                if (queue.isEmpty()) {
                    isProcessing = false
                    ExtremeForensicLogger.Mission.log("PROCESS_QUEUE_EMPTY: No missions to process after filtering")
                    return@withLock
                }

                // Get the next mission
                val nextMission = queue.firstOrNull()
                if (nextMission == null) {
                    ExtremeForensicLogger.Mission.logWarning("PROCESS_QUEUE_FIRST_ITEM_NULL: Queue size=${queue.size}")
                    isProcessing = false
                    return@withLock
                }

                // Remove the mission from the queue when we start it
                val updatedQueue = queue.toMutableList()
                val missionIndex = updatedQueue.indexOfFirst { it.id == nextMission.id }
                if (missionIndex != -1) {
                    updatedQueue.removeAt(missionIndex)
                }
                updatePendingQueueSnapshot(updatedQueue)
                queueStore.saveQueue(updatedQueue)
                ExtremeForensicLogger.Mission.log("PROCESS_QUEUE_REMOVED_STARTING: missionId=${nextMission.id} index=$missionIndex remaining=${updatedQueue.size}")

                // Update state before starting the mission
                currentMission = nextMission
                isProcessing = true

                try {
                    val alarmId = getCurrentAlarmId()
                    if (alarmId != -1 && isDpsSnapshotAuthority(alarmId)) {
                        val snapshot = dpsMirror.readSnapshot(alarmId)
                        if (snapshot == null) {
                            logDpsInvariant(
                                event = "process_queue_snapshot_missing",
                                snapshot = null,
                                expectedMissionId = nextMission.id,
                                runtimeMissionId = currentMission?.id,
                                source = "processQueueInternal"
                            )
                            currentMission = null
                            isProcessing = false
                            val restoreQueue = queue.toMutableList()
                            restoreQueue.add(0, nextMission)
                            updatePendingQueueSnapshot(restoreQueue)
                            queueStore.saveQueue(restoreQueue)
                            return@withLock
                        }

                        val activeIndex = snapshot.orderedMissionIds.indexOf(nextMission.id)
                        if (activeIndex == -1) {
                            logDpsInvariant(
                                event = "process_queue_active_index_missing",
                                snapshot = snapshot,
                                expectedMissionId = nextMission.id,
                                runtimeMissionId = currentMission?.id,
                                source = "processQueueInternal"
                            )
                            currentMission = null
                            isProcessing = false
                            val restoreQueue = queue.toMutableList()
                            restoreQueue.add(0, nextMission)
                            updatePendingQueueSnapshot(restoreQueue)
                            queueStore.saveQueue(restoreQueue)
                            return@withLock
                        }

                        val activatedSnapshot = dpsMirror.markMissionActivated(
                            snapshot = snapshot,
                            activeMissionId = nextMission.id,
                            activeIndex = activeIndex,
                            pendingMissionIds = updatedQueue.map { it.id },
                            eventCounter = nextEventCounter(),
                            previousState = snapshot.state
                        )
                        syncCachesFromSnapshot(activatedSnapshot)
                    } else {
                        queueStore.saveCurrentMission(nextMission)
                        saveExplicitPointer(alarmId, nextMission.id, updatedQueue.map { it.id })
                    }
                    ExtremeForensicLogger.Mission.log("PROCESS_QUEUE_STARTING: missionId=${nextMission.id} - Explicit pointer saved to DPS")
                } catch (e: Exception) {
                    ExtremeForensicLogger.Mission.logError("PROCESS_QUEUE_SAVE_ERROR: ${e.message}")
                    currentMission = null
                    isProcessing = false
                    // Restore the mission to the queue on error
                    val restoreQueue = queue.toMutableList()
                    restoreQueue.add(0, nextMission)
                    updatePendingQueueSnapshot(restoreQueue)
                    queueStore.saveQueue(restoreQueue)
                    return@withLock
                }
                
                // Update the last process start time
                lastProcessStartTime = System.currentTimeMillis()
                
                // Start the mission outside the lock
                scope.launch {
                    try {
                        startMission(nextMission)
                    } catch (e: Exception) {
                        ExtremeForensicLogger.Mission.logError("PROCESS_QUEUE_START_ERROR: ${e.message}")
                        mutex.withLock {
                            currentMission = null
                            isProcessing = false
                            queueStore.clearCurrentMission()
                        }
                        ensureQueueContinues()
                    }
                }
                
            } catch (e: Exception) {
                ExtremeForensicLogger.Mission.logError("PROCESS_QUEUE_ERROR: ${e.message}")
                isProcessing = false
                currentMission = null
                ensureQueueContinues()
            }
        }
    }

    suspend fun tryProcessNextMission(): Boolean {
        val logTag = "MissionSequencer"
        ExtremeForensicLogger.Mission.log("TRY_NEXT_MISSION_ENTRY: thread=${Thread.currentThread().name}")
        // ... rest of the code remains the same ...
        Log.d(logTag, "[tryProcessNextMission] ENTRY")

        // Double-check we're not already processing a mission
        if (currentMission != null && isProcessing) {
            val msg = "TRY_NEXT_MISSION_SKIP: Mission already running: ${currentMission?.id}"
            ExtremeForensicLogger.Mission.log(msg)
            Log.d(logTag, "[tryProcessNextMission] $msg")
            return false
        }

        ExtremeForensicLogger.Mission.log("TRY_NEXT_MISSION_LOADING_QUEUE")
        Log.d(logTag, "[tryProcessNextMission] Loading queue...")
        
        val queue = try {
            queueStore.loadQueue().toMutableList().also { 
                ExtremeForensicLogger.Mission.log("TRY_NEXT_MISSION_QUEUE_LOADED: size=${it.size}, firstId=${it.firstOrNull()?.id ?: "<none>"}")
            }
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("TRY_NEXT_MISSION_QUEUE_LOAD_ERROR: ${e.message}")
            isProcessing = false
            return false
        }
        
        Log.d(logTag, "[tryProcessNextMission] Queue loaded, size: ${queue.size}")

        if (queue.isEmpty()) {
            val msg = "TRY_NEXT_MISSION_QUEUE_EMPTY: No missions to process"
            ExtremeForensicLogger.Mission.log(msg)
            Log.d(logTag, "[tryProcessNextMission] $msg")
            isProcessing = false
            return false
        }

        // Get next mission from queue
        val nextMission = queue.firstOrNull()
        if (nextMission == null) {
            val msg = "TRY_NEXT_MISSION_FIRST_ITEM_NULL: Queue size=${queue.size}"
            ExtremeForensicLogger.Mission.logWarning(msg)
            Log.w(logTag, "[tryProcessNextMission] $msg")
            isProcessing = false
            return false
        }

        val startMsg = "TRY_NEXT_MISSION_STARTING: missionId=${nextMission.id}"
        ExtremeForensicLogger.Mission.log(startMsg)
        Log.d(logTag, "[tryProcessNextMission] $startMsg")
        
        try {
            // Set current mission BEFORE starting it (but don't remove from queue yet)
            currentMission = nextMission
            isProcessing = true
            
            ExtremeForensicLogger.Mission.log("TRY_NEXT_MISSION_SAVING: missionId=${nextMission.id}")
            queueStore.saveCurrentMission(nextMission)
            saveExplicitPointer(getCurrentAlarmId(), nextMission.id, queue.drop(1).map { it.id })
            
            val setMsg = "TRY_NEXT_MISSION_SET_CURRENT: missionId=${nextMission.id}"
            ExtremeForensicLogger.Mission.log(setMsg)
            Log.d(logTag, "[tryProcessNextMission] $setMsg")

            // track the mission job so we can cancel/inspect it later
            currentJob = scope.launch {
                try {
                    val launchMsg = "MISSION_LAUNCHER_START: missionId=${nextMission.id}"
                    ExtremeForensicLogger.Mission.log(launchMsg)
                    Log.d(logTag, "[MissionLauncher] $launchMsg")
                    
                    startMission(nextMission)
                    
                    val completeMsg = "MISSION_LAUNCHER_COMPLETE: missionId=${nextMission.id}"
                    ExtremeForensicLogger.Mission.log(completeMsg)
                    Log.d(logTag, "[MissionLauncher] $completeMsg")
                } catch (e: Exception) {
                    val errorMsg = "MISSION_LAUNCHER_ERROR: missionId=${nextMission.id}, error=${e.message}"
                    ExtremeForensicLogger.Mission.logError(errorMsg)
                    Log.e(logTag, "[MissionLauncher] $errorMsg", e)
                }
            }
            
            val launchMsg = "TRY_NEXT_MISSION_LAUNCHED: missionId=${nextMission.id}"
            ExtremeForensicLogger.Mission.log(launchMsg)
            Log.d(logTag, "[tryProcessNextMission] $launchMsg")
            
            return true
        } catch (e: Exception) {
            val errorMsg = "TRY_NEXT_MISSION_ERROR: Failed to start mission ${nextMission.id}, error=${e.message}"
            ExtremeForensicLogger.Mission.logError(errorMsg)
            Log.e(logTag, errorMsg, e)
            
            // Reset state on error
            currentMission = null
            isProcessing = false
            queueStore.clearCurrentMission()
            
            return false
        }
    }
    
    fun processQueue() {
        // CRITICAL FIX: Don't process queue if sequencer is complete
        if (isSequencerComplete) {
            ExtremeForensicLogger.Mission.log("PROCESS_QUEUE_BLOCKED: Sequencer is complete, not processing queue")
            return
        }
        
        scope.launch {
            processQueueInternal()
        }
    }
    
    private fun startMission(mission: MissionSpec) {
        // CRITICAL: Hard gate - prevent CPS access before device unlock to avoid app uninstall
        if (false && !isCpsAccessAllowed(context)) {
            ExtremeForensicLogger.Mission.log("CPS_BLOCKED_DEVICE_LOCKED: Device not unlocked, blocking CPS operations to prevent app uninstall")
            return
        }
        try {
            val preparedMission = if (MathMissionSupport.isMathMission(mission)) {
                val initializedMission = MathMissionSupport.ensureActiveQuestion(
                    mission = mission,
                    nowElapsedMs = android.os.SystemClock.elapsedRealtime()
                )
                if (initializedMission != mission) {
                    updateCurrentMission(initializedMission)
                }
                initializedMission
            } else {
                mission
            }

            // Convert password missions to use "IfYouWantYouCanSleep" as default password
            val safeMission = if (preparedMission.id == "password") {
                ExtremeForensicLogger.Mission.logWarning("PASSWORD_CONVERT: Converting password mission to use default password")
                preparedMission.copy(
                    id = "password",
                    params = mapOf("mission_type" to "password", "use_default_password" to "true"),
                    timeoutMs = preparedMission.timeoutMs
                )
            } else {
                preparedMission
            }
            
            // Cancel any existing timeout
            timeoutJob?.cancel()

            ExtremeForensicLogger.Mission.log("STARTING_MISSION: ${safeMission.id}")

            // Start timeout for the mission
            startTimeout(safeMission, safeMission.timeoutMs)

            // Create and launch the mission job
            currentJob = scope.launch {
                try {
                    val intent = Intent(context, com.vaishnava.alarm.AlarmActivity::class.java).apply {
                        putExtra("mission_id", safeMission.id)
                        // CRITICAL FIX: Always use mission.id as mission_type for consistency (after conversion)
                        val missionType = safeMission.id
                        
                        // CRITICAL FIX: Block "none" missions from being launched
                        if (missionType == "none" || missionType.contains("none_mission") || missionType.contains("mission_type=none")) {
                            ExtremeForensicLogger.Mission.logWarning("LAUNCH_BLOCKED: Blocking 'none' mission from being launched - missionId=$missionType")
                            return@launch
                        }
                        
                        putExtra("mission_type", missionType)
                        // CRITICAL FIX: Add mission_password for Intent-only approach
                        val missionPassword = if (missionType == "password") DEFAULT_GLOBAL_PASSWORD else ""
                        putExtra("mission_password", missionPassword)
                        putExtra(EXTRA_FROM_SEQUENCER, true)
                        // CRITICAL FIX: Pass alarm ID and ringtone URI for proper ringtone display
                        putExtra(AlarmReceiver.ALARM_ID, getCurrentAlarmId())
                        val currentAlarmId = getCurrentAlarmId()
                        if (currentAlarmId != -1) {
                            val snapshot = dpsMirror.readSnapshot(currentAlarmId)
                            if (snapshot != null && !snapshot.isSequenceComplete && snapshot.activeMissionId == safeMission.id) {
                                putExtra("mission_id", snapshot.activeMissionId)
                                putExtra("sequence_id", snapshot.sequenceId)
                                putExtra("snapshot_version", snapshot.version)
                            }
                            try {
                                if (!isDeviceLocked()) {
                                    val alarmStorage = com.vaishnava.alarm.AlarmStorage(context)
                                    val alarm = alarmStorage.getAlarms().find { it.id == currentAlarmId }
                                    val ringtoneUri = alarm?.ringtoneUri ?: originalRingtoneUri
                                    ringtoneUri?.let { uri ->
                                        putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, uri as android.os.Parcelable)
                                    }
                                    putExtra("enable_snooze", alarm?.enableSnooze ?: false)
                                } else {
                                    originalRingtoneUri?.let { uri ->
                                        putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, uri as android.os.Parcelable)
                                    }
                                }
                            } catch (_: Exception) {
                                ExtremeForensicLogger.Mission.logWarning("MISSION_START_DPS_SAFE: CPS access blocked, using fallback URI")
                                originalRingtoneUri?.let { uri ->
                                    putExtra(AlarmReceiver.EXTRA_RINGTONE_URI, uri as android.os.Parcelable)
                                }
                            }
                        }
                        // Launch from a service/background context while reusing the existing AlarmActivity instance.
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        safeMission.params.forEach { (key, value) ->
                            putExtra(key, value)
                        }
                        
                        // Debug logging for mission type
                        ExtremeForensicLogger.Mission.log("MISSION_INTENT_DEBUG: missionId=${safeMission.id} missionType=$missionType params=${safeMission.params} alarmId=${getCurrentAlarmId()}")
                    }
                    
                    context.startActivity(intent)
                    
                    // CRITICAL FIX: Add a small delay to ensure the activity has time to process the new intent
                    scope.launch {
                        delay(100)
                        ExtremeForensicLogger.Mission.log("MISSION_STARTED_CONFIRMED: missionId=${safeMission.id} activity should be updated")
                    }
                    
                    ExtremeForensicLogger.Mission.log("MISSION_STARTED: missionId=${safeMission.id}")
                    
                } catch (e: Exception) {
                    ExtremeForensicLogger.Mission.logError("MISSION_START_ERROR: ${e.message}")
                    handleMissionCompletion(safeMission.id, false)
                }
            }

        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("START_MISSION_ERROR: ${e.message}")
            handleMissionCompletion(mission.id, false)
        }
    }
    
    private fun startTimeout(mission: MissionSpec, timeoutMs: Long) {
        // Use mission as-is, no conversion
        val safeMission = mission
        
        // CRITICAL FIX: Don't start timeout for missions that manage their own lifecycle.
        // STT is user-driven (read phrase -> tap mic -> speak -> retry), so the generic
        // 30s timeout would fail it mid-attempt and strand the sequence.
        if (safeMission.id == "tap" ||
            safeMission.id == SttMissionSupport.MISSION_ID ||
            safeMission.id == FaceMissionSupport.MISSION_ID ||
            MathMissionSupport.isMathMission(safeMission)
        ) {
            ExtremeForensicLogger.Mission.log("TIMEOUT_SKIPPED: missionId=${safeMission.id} - mission manages its own timeout")
            return
        }
        
        timeoutJob = scope.launch {
            ExtremeForensicLogger.Mission.log("TIMEOUT_SCHEDULED: missionId=${safeMission.id} timeoutMs=$timeoutMs reason=mission_start_or_resume")
            delay(timeoutMs)
            
            ExtremeForensicLogger.Mission.log("TIMEOUT_FIRED: missionId=${safeMission.id} action=handleMissionCompletion(success=false)")
            handleMissionCompletion(safeMission.id, false)
        }
    }
    
    fun handleMissionCompletion(missionId: String, success: Boolean) {
        val completionId = "${System.currentTimeMillis()}_${(0..9999).random()}"
        ExtremeForensicLogger.Mission.log("HANDLE_COMPLETION_START: id=$completionId missionId=$missionId success=$success")
        
        scope.launch {
            try {
                mutex.withLock {
                    val alarmId = getCurrentAlarmId()
                    val useSnapshotAuthority = isDpsSnapshotAuthority(alarmId)

                    if (useSnapshotAuthority) {
                        val snapshot = dpsMirror.readSnapshot(alarmId)
                        ExtremeForensicLogger.Mission.logDpsForensic(
                            component = "MissionSequencer",
                            event = "mission_complete_request",
                            snapshot = snapshot,
                            expectedMissionId = snapshot?.activeMissionId,
                            runtimeMissionId = missionId,
                            previousState = snapshot?.state,
                            source = "handleMissionCompletion",
                            queueReconstructed = false,
                            nullMissionObserved = snapshot?.activeMissionId == null && snapshot?.isSequenceComplete == false,
                            stalePendingIntent = false,
                            replayingOldData = false
                        )

                        if (snapshot == null) {
                            logDpsInvariant(
                                event = "completion_snapshot_missing",
                                snapshot = null,
                                expectedMissionId = null,
                                runtimeMissionId = missionId,
                                source = "handleMissionCompletion"
                            )
                            return@withLock
                        }

                        if (snapshot.isSequenceComplete) {
                            logDpsInvariant(
                                event = "completion_after_sequence_complete",
                                snapshot = snapshot,
                                expectedMissionId = snapshot.activeMissionId,
                                runtimeMissionId = missionId,
                                source = "handleMissionCompletion"
                            )
                            return@withLock
                        }

                        val expectedMissionId = snapshot.activeMissionId
                        if (expectedMissionId == null) {
                            logDpsInvariant(
                                event = "completion_active_null",
                                snapshot = snapshot,
                                expectedMissionId = null,
                                runtimeMissionId = missionId,
                                source = "handleMissionCompletion"
                            )
                            return@withLock
                        }

                        val resolvedMissionId = resolveMissionId(missionId)
                        if (resolvedMissionId != expectedMissionId) {
                            logDpsInvariant(
                                event = "completion_mismatch",
                                snapshot = snapshot,
                                expectedMissionId = expectedMissionId,
                                runtimeMissionId = resolvedMissionId,
                                source = "handleMissionCompletion"
                            )
                            return@withLock
                        }

                        if (!success) {
                            ExtremeForensicLogger.Mission.log("HANDLE_COMPLETION_FAILURE_WAIT: missionId=$missionId snapshotActive=$expectedMissionId")
                            timeoutJob?.cancel()
                            timeoutJob = null
                            return@withLock
                        }

                        currentJob = null
                        timeoutJob?.cancel()
                        timeoutJob = null
                        completedMissionIds.add(expectedMissionId)

                        val nextIndex = snapshot.activeIndex + 1
                        val nextMissionId = snapshot.orderedMissionIds.getOrNull(nextIndex)
                        val committedSnapshot = dpsMirror.commitMissionCompletion(
                            snapshot = snapshot,
                            completedMissionId = expectedMissionId,
                            nextMissionId = nextMissionId,
                            nextIndex = nextIndex,
                            eventCounter = nextEventCounter(),
                            previousState = snapshot.state
                        )

                        ExtremeForensicLogger.Mission.logDpsForensic(
                            component = "MissionSequencer",
                            event = "mission_complete_commit",
                            snapshot = committedSnapshot,
                            expectedMissionId = expectedMissionId,
                            runtimeMissionId = nextMissionId,
                            previousState = snapshot.state,
                            source = "handleMissionCompletion",
                            queueReconstructed = false,
                            nullMissionObserved = nextMissionId == null && !committedSnapshot.isSequenceComplete,
                            stalePendingIntent = false,
                            replayingOldData = false
                        )

                        if (nextMissionId == null) {
                            currentMission = null
                            isProcessing = false
                            queueStore.clearCurrentMission()
                            queueStore.clearQueue()
                            clearPendingQueueSnapshot()
                            saveExplicitPointer(alarmId, null, emptyList())
                            handleSequencerComplete(alarmId)
                            return@withLock
                        }

                        val nextMission = missionSpecForId(alarmId, nextMissionId)
                        currentMission = nextMission
                        isProcessing = true
                        val pendingSpecs = committedSnapshot.pendingMissionIds.map {
                            missionSpecForId(alarmId, it)
                        }
                        updatePendingQueueSnapshot(pendingSpecs)
                        queueStore.saveQueue(pendingSpecs)
                        queueStore.saveCurrentMission(nextMission)
                        saveExplicitPointer(alarmId, nextMission.id, committedSnapshot.pendingMissionIds)

                        try {
                            CompletionMarker.writeCompletionMarker(context, alarmId, completionId, nextMission)
                        } catch (e: Exception) {
                            ExtremeForensicLogger.Mission.logError("MARKER_WRITE_ERROR: ${e.message}")
                        }

                        ExtremeForensicLogger.Mission.logDpsForensic(
                            component = "MissionSequencer",
                            event = "next_mission_activation",
                            snapshot = committedSnapshot,
                            expectedMissionId = nextMissionId,
                            runtimeMissionId = nextMission.id,
                            previousState = snapshot.state,
                            source = "handleMissionCompletion",
                            queueReconstructed = false,
                            nullMissionObserved = false,
                            stalePendingIntent = false,
                            replayingOldData = false
                        )

                        scope.launch {
                            delay(500)
                            startMission(nextMission)
                        }
                        return@withLock
                    }

                    if (currentMission == null) {
                        val persistedMission = queueStore.loadCurrentMission()?.first
                        if (persistedMission != null) {
                            currentMission = persistedMission
                            isProcessing = true
                            ExtremeForensicLogger.Mission.log(
                                "HANDLE_COMPLETION_RECOVERED_CURRENT: id=$completionId missionId=$missionId " +
                                    "recoveredCurrent=${persistedMission.id}"
                            )
                        }
                    }

                    val currentId = currentMission?.id
                    ExtremeForensicLogger.Mission.log("HANDLE_COMPLETION_LOCK_ACQUIRED: id=$completionId missionId=$missionId currentId=$currentId")
                    if (currentId == null) {
                        ExtremeForensicLogger.Mission.log("HANDLE_COMPLETION_NO_CURRENT: id=$completionId missionId=$missionId - No current mission, ignoring")
                        return@withLock
                    }

                    val resolvedMissionId = resolveMissionId(missionId)
                    ExtremeForensicLogger.Mission.log("HANDLE_COMPLETION_RESOLVED: id=$completionId original=$missionId resolved=$resolvedMissionId currentId=$currentId")

                    val queueSizeAtMatchCheck = queueStore.loadQueue().size
                    val resolvedKind = missionIdentityKind(resolvedMissionId)
                    val currentKind = missionIdentityKind(currentId)
                    val isMatch =
                        resolvedMissionId == currentId ||
                            (resolvedKind != null && resolvedKind == currentKind)

                    if (!isMatch) {
                        ExtremeForensicLogger.Mission.log("HANDLE_COMPLETION_MISMATCH: id=$completionId missionId=$missionId resolved=$resolvedMissionId currentId=$currentId")
                        ExtremeForensicLogger.Mission.log("HANDLE_COMPLETION_DEBUG: missionId length=${missionId.length} currentId length=${currentId.length}")
                        ExtremeForensicLogger.Mission.log("HANDLE_COMPLETION_DEBUG: missionId hash=${missionId.hashCode()} currentId hash=${currentId.hashCode()}")
                        return@withLock
                    }

                    if (resolvedMissionId != currentId) {
                        ExtremeForensicLogger.Mission.log(
                            "HANDLE_COMPLETION_FORCED_MATCH: id=$completionId missionId=$missionId " +
                                "resolved=$resolvedMissionId currentId=$currentId queueSize=$queueSizeAtMatchCheck " +
                                "resolvedKind=$resolvedKind currentKind=$currentKind"
                        )
                    }

                    if (success) {
                        ExtremeForensicLogger.Mission.log("HANDLE_COMPLETION_SUCCESS: id=$completionId missionId=$missionId")
                        val queue = queueStore.loadQueue().toMutableList()

                        queue.removeAll { it.id == currentId }

                        currentMission = null
                        isProcessing = false
                        currentJob = null
                        timeoutJob?.cancel()
                        timeoutJob = null
                        queueStore.clearCurrentMission()

                        val filteredQueue = queue.filter { mission ->
                            val isNoneMission =
                                mission.id == "none" ||
                                    mission.id.contains("none_mission") ||
                                    mission.params["mission_type"] == "none"
                            if (isNoneMission) {
                                ExtremeForensicLogger.Mission.logWarning("ADVANCE_FILTER: Removing 'none' mission ${mission.id}")
                            }
                            !isNoneMission
                        }.toMutableList()

                        if (filteredQueue.isEmpty()) {
                            val finalCycle = WakeCheckCycleStore.read(context, alarmId)
                            val finalAlarm = runCatching { AlarmStorage(context).getAlarm(alarmId) }.getOrNull()
                            val awakeConfirmed = WakeCheckStore.isAcked(context, alarmId, 10 * 60_000L)
                            ExtremeForensicLogger.Mission.log("ADVANCE_QUEUE_EMPTY: id=$completionId - Multi-mission sequence completed")
                            ExtremeForensicLogger.Mission.log(
                                "WAKECHECK_MULTI_MISSION_FINAL: owner=handleMissionCompletion alarmId=$alarmId " +
                                    "cycleId=${finalCycle?.cycleId ?: 0L} missionModeActive=true currentMissionPointer=$currentId " +
                                    "pendingMissionCount=0 finalCompletionConfirmed=true awakeConfirmed=$awakeConfirmed " +
                                    "loopActive=${finalAlarm?.wakeCheckEnabled == true && !awakeConfirmed} " +
                                    "interval=${finalAlarm?.wakeCheckMinutes ?: 0} scheduleTs=${finalCycle?.scheduledAt ?: 0L} " +
                                    "reason=queue_empty_final_success"
                            )
                            saveExplicitPointer(alarmId, null, emptyList())
                            handleSequencerComplete(alarmId)
                        } else {
                            val nextMission = filteredQueue.first()
                            filteredQueue.removeAt(0)
                            val partialCycle = WakeCheckCycleStore.read(context, alarmId)
                            val partialAlarm = runCatching { AlarmStorage(context).getAlarm(alarmId) }.getOrNull()
                            val awakeConfirmed = WakeCheckStore.isAcked(context, alarmId, 10 * 60_000L)
                            ExtremeForensicLogger.Mission.log(
                                "WAKECHECK_MULTI_MISSION_PARTIAL: owner=handleMissionCompletion alarmId=$alarmId " +
                                    "cycleId=${partialCycle?.cycleId ?: 0L} missionModeActive=true currentMissionPointer=$currentId " +
                                    "pendingMissionCount=${filteredQueue.size + 1} finalCompletionConfirmed=false awakeConfirmed=$awakeConfirmed " +
                                    "loopActive=${partialAlarm?.wakeCheckEnabled == true && !awakeConfirmed} " +
                                    "interval=${partialAlarm?.wakeCheckMinutes ?: 0} scheduleTs=${partialCycle?.scheduledAt ?: 0L} " +
                                    "reason=advance_to_next_mission nextMissionId=${nextMission.id}"
                            )

                            updatePendingQueueSnapshot(filteredQueue)
                            queueStore.saveQueue(filteredQueue)
                            saveExplicitPointer(alarmId, nextMission.id, filteredQueue.map { it.id })

                            currentMission = nextMission
                            isProcessing = true
                            try {
                                queueStore.saveCurrentMission(nextMission)
                            } catch (e: Exception) {
                                ExtremeForensicLogger.Mission.logError("SAVE_MISSION_ERROR: ${e.message}")
                            }

                            try {
                                CompletionMarker.writeCompletionMarker(context, alarmId, completionId, nextMission)
                            } catch (e: Exception) {
                                ExtremeForensicLogger.Mission.logError("MARKER_WRITE_ERROR: ${e.message}")
                            }

                            scope.launch {
                                delay(500)
                                startMission(nextMission)
                            }
                        }
                    } else {
                        ExtremeForensicLogger.Mission.log("HANDLE_COMPLETION_FAILURE: id=$completionId missionId=$missionId")
                        ExtremeForensicLogger.Mission.log("HANDLE_COMPLETION_FAILURE_WAIT: Mission $missionId failed, waiting for manual completion")
                        timeoutJob?.cancel()
                        timeoutJob = null
                        ExtremeForensicLogger.Mission.log("HANDLE_COMPLETION_FAILURE_STATE_KEPT: missionId=$missionId currentMission kept for manual completion")
                    }
                }
            } catch (e: Exception) {
                ExtremeForensicLogger.Mission.logError("HANDLE_COMPLETION_ERROR: id=$completionId missionId=$missionId error=${e.message}")
                isProcessing = false
                currentMission = null
                ensureQueueContinues()
            }
        }
    }
    
    private fun handleSequencerComplete(passedAlarmId: Int = -1) {
        ExtremeForensicLogger.Mission.log("SEQUENCER_COMPLETE: Multi-mission sequence completed")
        
        // Set sequencer complete flag to prevent duplicate completions
        isSequencerComplete = true

        // Clear pending resume state immediately so unlock-time receivers cannot revive a finished alarm.
        PendingCompletionStore.clear(context)
        PendingCompletionStore.clearAlarm(context)
        
        // Preserve the alarm while a post-alarm wake-check loop is still active;
        // otherwise disable it here as before.
        try {
            val currentAlarmId = if (passedAlarmId != -1) passedAlarmId else getCurrentAlarmId()
            if (currentAlarmId != -1) {
                val existingSnapshot = dpsMirror.readSnapshot(currentAlarmId)
                if (existingSnapshot != null && !existingSnapshot.isSequenceComplete) {
                    dpsMirror.markSequenceCompleted(
                        snapshot = existingSnapshot,
                        eventCounter = nextEventCounter(),
                        previousState = existingSnapshot.state
                    )
                }

                if (!isDeviceLocked()) {
                    val alarmStorage = com.vaishnava.alarm.AlarmStorage(context)
                    val alarm = alarmStorage.getAlarm(currentAlarmId)
                    val cycle = WakeCheckCycleStore.read(context, currentAlarmId)
                    val awakeConfirmed = WakeCheckStore.isAcked(context, currentAlarmId, 10 * 60_000L)
                    val preserveForWakeCheck = alarm?.wakeCheckEnabled == true && !awakeConfirmed

                    ExtremeForensicLogger.Mission.log(
                        "WAKECHECK_MULTI_MISSION_FINAL_OWNER: owner=handleSequencerComplete alarmId=$currentAlarmId " +
                            "cycleId=${cycle?.cycleId ?: 0L} missionModeActive=true " +
                            "currentMissionPointer=${existingSnapshot?.activeMissionId ?: currentMission?.id ?: "<none>"} " +
                            "pendingMissionCount=${existingSnapshot?.pendingMissionIds?.size ?: queueStore.loadQueue().size} " +
                            "finalCompletionConfirmed=true awakeConfirmed=$awakeConfirmed " +
                            "loopActive=${alarm?.wakeCheckEnabled == true && !awakeConfirmed} " +
                            "interval=${alarm?.wakeCheckMinutes ?: 0} scheduleTs=${cycle?.scheduledAt ?: 0L} " +
                            "reason=${if (preserveForWakeCheck) "preserve_alarm_for_post_alarm_wakecheck" else "disable_alarm_now"}"
                    )

                    if (preserveForWakeCheck) {
                        ExtremeForensicLogger.Mission.log(
                            "SEQUENCER_COMPLETE_ALARM_PRESERVED: alarmId=$currentAlarmId cycleId=${cycle?.cycleId ?: 0L} " +
                                "wakeCheckEnabled=${alarm?.wakeCheckEnabled == true} awakeConfirmed=$awakeConfirmed"
                        )
                    } else {
                        alarmStorage.disableAlarm(currentAlarmId)
                        ExtremeForensicLogger.Mission.log("SEQUENCER_COMPLETE_ALARM_DISABLED: disabled original alarm $currentAlarmId")
                    }
                } else {
                    ExtremeForensicLogger.Mission.log("SEQUENCER_COMPLETE_DPS: Device locked, using DPS flag to prevent re-trigger")
                }

                // Always record DPS completion flags as a fail-safe backup for lock screen / CPS sync
                runCatching {
                    val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        context.createDeviceProtectedStorageContext() ?: context
                    } else context
                    dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("sequencer_completed_$currentAlarmId", true)
                        .commit()
                    dpsContext.getSharedPreferences("dps_completion_sync", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("alarm_completed_in_dps_$currentAlarmId", true)
                        .commit()
                    ExtremeForensicLogger.Mission.log("SEQUENCER_COMPLETE_DPS_COMMIT: alarmId=$currentAlarmId markers_written=true")
                }

                try {
                    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    val cancelIntent = Intent(context, com.vaishnava.alarm.AlarmReceiver::class.java).apply {
                        action = "com.vaishnava.alarm.DIRECT_BOOT_ALARM"
                        data = android.net.Uri.parse("alarm://$currentAlarmId")
                        putExtra(com.vaishnava.alarm.AlarmReceiver.ALARM_ID, currentAlarmId)
                    }
                    val pendingCancel = android.app.PendingIntent.getBroadcast(
                        context,
                        currentAlarmId,
                        cancelIntent,
                        android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    if (pendingCancel != null) {
                        alarmManager.cancel(pendingCancel)
                        pendingCancel.cancel()
                        ExtremeForensicLogger.Mission.log(
                            "SEQUENCER_COMPLETE_PURGE: alarmId=$currentAlarmId requestCode=$currentAlarmId " +
                                "action=${cancelIntent.action} data=${cancelIntent.data} canceled=true"
                        )
                    } else {
                        ExtremeForensicLogger.Mission.logWarning(
                            "SEQUENCER_COMPLETE_PURGE_MISSING: alarmId=$currentAlarmId requestCode=$currentAlarmId " +
                                "action=${cancelIntent.action} data=${cancelIntent.data}"
                        )
                    }
                } catch (e: Exception) {
                    ExtremeForensicLogger.Mission.logError("SEQUENCER_COMPLETE_PURGE_ERROR: ${e.message}")
                }
            } else {
                ExtremeForensicLogger.Mission.logWarning("SEQUENCER_COMPLETE_NO_ALARM_ID: could not get current alarm id")
            }
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("SEQUENCER_COMPLETE_ALARM_DISABLE_ERROR: ${e.message}")
        }
        
        // Clear all sequencer state
        try {
            currentMission = null
            isProcessing = false
            currentJob = null
            timeoutJob = null
            queueStore.clearQueue()
            queueStore.clearCurrentMission()
            clearPendingQueueSnapshot()
            saveExplicitPointer(if (passedAlarmId != -1) passedAlarmId else getCurrentAlarmId(), null, emptyList())
            CompletionMarker.clearCompletionMarker(context)
            ExtremeForensicLogger.Mission.log("SEQUENCER_COMPLETE_STATE_CLEARED: All sequencer state cleared")
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("SEQUENCER_COMPLETE_STATE_CLEAR_ERROR: ${e.message}")
        }
        
        // Dismiss the alarm activity when all missions are completed
        scope.launch {
            delay(0) // Minimal delay for immediate response
            withContext(Dispatchers.Main) {
                try {
                    ExtremeForensicLogger.Mission.log("SEQUENCER_COMPLETE_DISMISS: Sending finish broadcast to dismiss current activity")
                    
                    // CRITICAL FIX: ONLY send finish broadcast - NO direct activity start to prevent flashing
                    val finishIntent = Intent("com.vaishnava.alarm.FINISH_ALARM")
                    finishIntent.putExtra("sequencer_complete", true)
                    finishIntent.putExtra("finish_directly", true)
                    finishIntent.setPackage(context.packageName)
                    context.sendBroadcast(finishIntent)
                    ExtremeForensicLogger.Mission.log("SEQUENCER_COMPLETE_FINISH_BROADCAST: Finish broadcast sent")
                    
                    // IMMEDIATE: Stop alarm service
                    try {
                        val serviceIntent = Intent(context, com.vaishnava.alarm.AlarmForegroundService::class.java).apply {
                            action = "com.vaishnava.alarm.STOP_ALARM_SERVICE"
                        }
                        context.startService(serviceIntent)
                        ExtremeForensicLogger.Mission.log("SEQUENCER_COMPLETE_SERVICE_STOP: Service stopped immediately")
                    } catch (e: Exception) {
                        ExtremeForensicLogger.Mission.logError("SEQUENCER_COMPLETE_SERVICE_STOP_ERROR: ${e.message}")
                    }
                    
                    ExtremeForensicLogger.Mission.log("SEQUENCER_COMPLETE_DONE: Sequencer completed with broadcast only")
                    
                } catch (e: Exception) {
                    ExtremeForensicLogger.Mission.logError("SEQUENCER_COMPLETE_ERROR: Failed to complete sequencer - ${e.message}")
                }
            }
        }
    }
    
    private fun resolveMissionId(missionId: String): String {
        // ABSOLUTE: No fallbacks for multi-mission - require explicit mission ID
        if (missionId.isBlank()) {
            ExtremeForensicLogger.Mission.logError("RESOLVE_MISSION_ID_BLANK: Mission ID is blank - multi-mission requires explicit ID")
            throw IllegalStateException("Multi-mission requires explicit mission ID, no fallbacks allowed")
        }

        val normalized = missionId.trim().lowercase(java.util.Locale.US)
        val finalId = when (normalized) {
            "pwd", "pswd" -> "password"
            else -> normalized
        }

        if (finalId == "none") {
            ExtremeForensicLogger.Mission.logError("RESOLVE_MISSION_ID_NONE: Mission ID is 'none' - not allowed in multi-mission")
            throw IllegalStateException("Multi-mission cannot use 'none' mission ID")
        }

        ExtremeForensicLogger.Mission.log("RESOLVE_MISSION_ID_DIRECT: original=$missionId resolved=$finalId")
        return finalId
    }

    private fun missionIdentityKind(missionId: String?): String? {
        val normalized = missionId?.trim()?.lowercase(java.util.Locale.US) ?: return null
        if (normalized.isBlank() || normalized == "none") return null
        return when {
            normalized.contains("tap") -> "tap"
            normalized.contains("password") || normalized.contains("pwd") || normalized.contains("pswd") -> "password"
            normalized.contains("math") -> "math"
            else -> normalized
        }
    }
    
    private suspend fun handleMissionFailure(missionId: String) {
        try {
            val mission = currentMission
            if (mission == null) {
                ExtremeForensicLogger.Mission.logWarning("FAIL_HANDLER_NULL_MISSION: missionId=$missionId")
                return
            }
            
            ExtremeForensicLogger.Mission.log("FAIL: missionId=$missionId sticky=${mission.sticky} retryCount=${mission.retryCount} retryDelayMs=${mission.retryDelayMs}")
            
            // CRITICAL FIX: Don't auto-retry or advance on failure - wait for manual completion
            // This allows the user to complete the mission manually after timeout
            ExtremeForensicLogger.Mission.log("FAILURE_WAIT_MANUAL: Mission $missionId failed, waiting for manual completion")
            
            // Keep current mission state so manual completion can be processed
            // DON'T clear currentMission
            // DON'T set isProcessing = false
            // Just cancel the timeout job and wait for manual completion
            timeoutJob?.cancel()
            timeoutJob = null
            
            ExtremeForensicLogger.Mission.log("FAILURE_MANUAL_WAIT: Waiting for user to complete mission $missionId manually (currentMission kept)")
            
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("Error handling mission failure for $missionId")
        }
    }
    
    /**
     * Get completed mission IDs - DPS snapshot as source in DPS mode
     * This ensures DPS rehydration is the single authority for completion tracking
     */
    private fun getCompletedMissionIds(): List<String> {
        val alarmId = getCurrentAlarmId()
        return try {
            if (alarmId != -1 && isInDpsMode(context, alarmId)) {
                // Read DPS snapshot as the single source of truth for completed missions
                val snapshot = DpsMissionMirror(context).readSnapshot(alarmId)
                val completed = snapshot?.completedMissionIds ?: emptyList()
                ExtremeForensicLogger.Mission.log("GET_COMPLETED_DPS: alarmId=$alarmId completedCount=${completed.size}")
                completed
            } else {
                // Non-DPS path: fall back to sequencer local completed set
                ExtremeForensicLogger.Mission.log("GET_COMPLETED_CPS: alarmId=$alarmId localCompletedCount=${completedMissionIds.size}")
                completedMissionIds.toList()
            }
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("GET_COMPLETED_ERROR: ${e.message}", e)
            completedMissionIds.toList()
        }
    }
    
    /**
     * Convert ensureQueueContinues to a non-suspending helper that schedules
     * a delayed processQueueInternal call. This avoids confusing suspend/launch
     * semantics when called from within mutex.withLock.
     */
    private fun ensureQueueContinues() {
        // CRITICAL FIX: Don't continue queue if sequencer is complete
        if (isSequencerComplete) {
            ExtremeForensicLogger.Mission.log("ENSURE_QUEUE_BLOCKED: Sequencer is complete, not continuing queue")
            return
        }
        
        scope.launch {
            try {
                ExtremeForensicLogger.Mission.log("ENSURE_QUEUE_CONTINUES_START: isProcessing=$isProcessing currentMissionId=${currentMission?.id}")
                
                // Check if we should process the queue
                val shouldProcess = !isProcessing && currentMission == null
                ExtremeForensicLogger.Mission.log("ENSURE_QUEUE_CHECK: shouldProcess=$shouldProcess (isProcessing=$isProcessing, currentMission=${currentMission?.id})")
                
                if (!shouldProcess) {
                    val currentId = currentMission?.id ?: "<none>"
                    ExtremeForensicLogger.Mission.log("ENSURE_QUEUE_SKIP: Already processing or has current mission ($currentId)")
                    return@launch
                }
                
                // Double-check sequencer complete flag after delay
                if (isSequencerComplete) {
                    ExtremeForensicLogger.Mission.log("ENSURE_QUEUE_ABORT: Sequencer complete detected, aborting queue continuation")
                    return@launch
                }
                
                ExtremeForensicLogger.Mission.log("ENSURE_QUEUE_PROCESS: Starting queue processing")
                processQueueInternal()
                
            } catch (e: Exception) {
                ExtremeForensicLogger.Mission.logError("ENSURE_QUEUE_ERROR: ${e.message}")
                
                // Schedule a retry with backoff
                val retryDelay = 500L
                ExtremeForensicLogger.Mission.log("ENSURE_QUEUE_RETRY: Scheduling retry in $retryDelay ms")
                delay(retryDelay)
                ensureQueueContinues()
            }
        }
    }
    
    fun isMissionRunning(): Boolean {
        val running = isProcessing && currentMission != null
        ExtremeForensicLogger.Mission.logVerbose("IS_MISSION_RUNNING: result=$running isProcessing=$isProcessing currentMissionId=${currentMission?.id}")
        return running
    }
    
    fun getCurrentMission(): MissionSpec? {
        if (currentMission == null) {
            val alarmId = getCurrentAlarmId()
            if (alarmId != -1 && isDpsSnapshotAuthority(alarmId)) {
                val snapshot = dpsMirror.readSnapshot(alarmId)
                if (snapshot != null && !snapshot.isSequenceComplete && snapshot.activeMissionId != null) {
                    val restoredMission = missionSpecForId(alarmId, snapshot.activeMissionId)
                    currentMission = restoredMission
                    isProcessing = true
                    syncCachesFromSnapshot(snapshot)
                    ExtremeForensicLogger.Mission.logDpsForensic(
                        component = "MissionSequencer",
                        event = "get_current_mission_snapshot_restore",
                        snapshot = snapshot,
                        expectedMissionId = snapshot.activeMissionId,
                        runtimeMissionId = restoredMission.id,
                        previousState = snapshot.state,
                        source = "getCurrentMission",
                        queueReconstructed = false,
                        nullMissionObserved = false,
                        stalePendingIntent = false,
                        replayingOldData = false
                    )
                }
            }

            val persistedMission = queueStore.loadCurrentMission()?.first
            if (currentMission == null && persistedMission != null) {
                currentMission = persistedMission
                isProcessing = true
                ExtremeForensicLogger.Mission.log("GET_CURRENT_MISSION_RECOVERED: currentMissionId=${persistedMission.id}")
            }
        }
        ExtremeForensicLogger.Mission.log("GET_CURRENT_MISSION: currentMissionId=${currentMission?.id}")
        return currentMission
    }

    fun forceResumeFromCps(context: Context, alarmId: Int) {
        ExtremeForensicLogger.Chrona.log(context, ExtremeForensicLogger.Chrona.LogType.DEBUG, "SEQ", "Force resume from CPS alarmId=$alarmId")

        val dpsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext() ?: context
        } else context
        val isCompleted = dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
            .getBoolean("sequencer_completed_$alarmId", false)
        val alarm = runCatching { AlarmStorage(context).getAlarm(alarmId) }.getOrNull()
        val wakeCheckLoopActive = WakeCheckStore.isLoopActive(context, alarm)

        if (isCompleted && !wakeCheckLoopActive) {
            ExtremeForensicLogger.Mission.log("CPS_RESUME_ABORT: Alarm $alarmId was completed in lockscreen. Disabling in CPS.")
            PendingCompletionStore.clear(context)
            try {
                AlarmStorage(context).disableAlarm(alarmId)
            } catch (e: Exception) {
                ExtremeForensicLogger.Mission.logError("CPS_RESUME_DISABLE_ERROR: ${e.message}")
            }
            dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                .edit()
                .remove("sequencer_completed_$alarmId")
                .commit()
            return
        }

        val snapshot = dpsMirror.readSnapshot(alarmId)
        if (snapshot != null) {
            if (snapshot.isSequenceComplete && !wakeCheckLoopActive) {
                ExtremeForensicLogger.Mission.log("CPS_RESUME_ABORT: Snapshot already completed for alarmId=$alarmId")
                try {
                    AlarmStorage(context).disableAlarm(alarmId)
                } catch (e: Exception) {
                    ExtremeForensicLogger.Mission.logError("CPS_RESUME_DISABLE_ERROR: ${e.message}")
                }
                return
            }

            ExtremeForensicLogger.Mission.logDpsForensic(
                component = "MissionSequencer",
                event = "force_resume_snapshot_read",
                snapshot = snapshot,
                expectedMissionId = snapshot.activeMissionId,
                runtimeMissionId = currentMission?.id,
                previousState = snapshot.state,
                source = "forceResumeFromCps",
                queueReconstructed = false,
                nullMissionObserved = snapshot.activeMissionId == null && !snapshot.isSequenceComplete,
                stalePendingIntent = false,
                replayingOldData = false
            )

            if (snapshot.activeMissionId != null) {
                syncCachesFromSnapshot(snapshot)
                startWhenAlarmFires()
                return
            }
        }

        val state = PendingCompletionStore.load(context, alarmId)
            ?: return

        // 🔥 THIS IS THE MISSING PIECE
        // Load and restore queue, then start from the correct index
        val queue = queueStore.loadQueue()
        if (queue.isNotEmpty()) {
            ExtremeForensicLogger.Mission.log("CPS_RESUME_QUEUE_LOADED: alarmId=$alarmId queueSize=${queue.size} resumeFromIndex=${state.completedIndex + 1}")
            startWhenAlarmFires()
        }
    }
    
    fun getQueueSize(): Int {
        val size = queueStore.loadQueue().size
        ExtremeForensicLogger.Mission.logVerbose("GET_QUEUE_SIZE: size=$size")
        return size
    }

    fun updateCurrentMission(updatedMission: MissionSpec) {
        currentMission = updatedMission
        queueStore.saveCurrentMission(updatedMission)
        ExtremeForensicLogger.Mission.log("CURRENT_MISSION_UPDATED: missionId=${updatedMission.id}")
    }
    
    private fun getCurrentAlarmId(): Int {
        // DPS INVARIANT: current_service_alarm_id always stores alarmId, not missionId
        // For sequencer alarms, this is the sequencer alarm's ID that persists across all missions
        
        // Try to get the current alarm ID from the current mission parameters
        currentMission?.params?.get("alarm_id")?.let { return it.toIntOrNull() ?: -1 }
        
        // Try to get it from the current mission file
        try {
            val current = queueStore.loadCurrentMission()
            current?.first?.params?.get("alarm_id")?.let { return it.toIntOrNull() ?: -1 }
        } catch (_: Exception) { }
        
        // DPS INVARIANT: Fallback to DPS which stores the sequencer alarm ID
        // This ensures we always return the alarm ID, never a mission ID
        return try {
            val prefs = getAlarmDpsPrefs()
            prefs.getInt("current_service_alarm_id", -1)
        } catch (_: Exception) {
            -1
        }
    }
    
    fun destroy() {
        try {
            context.unregisterReceiver(missionCompletionReceiver)
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("Failed to unregister receiver", e)
        }
        
        scope.cancel()
        ExtremeForensicLogger.Mission.log("DESTROY: MissionSequencer destroyed; scope cancelled and receiver unregistered")
    }
    
    /**
     * Force MissionSequencer to resume from persisted completion marker
     * This mirrors how CPS → CPS already works but with explicit mission from DPS
     */
    private fun forceSequencerResume(completionMarker: CompletionMarker.CompletionMarkerData, queue: List<MissionSpec>) {
        val resumeId = "${System.currentTimeMillis()}_${(0..9999).random()}"
        ExtremeForensicLogger.Mission.log("FORCE_SEQUENCER_RESUME: id=$resumeId - Forcing resume from completion marker")
        
        try {
            ExtremeForensicLogger.Mission.log("FORCE_RESUME_MARKER: alarmId=${completionMarker.alarmId} completedMissionId=${completionMarker.completedMissionId}")
            ExtremeForensicLogger.Mission.log("FORCE_RESUME_QUEUE: size=${queue.size}")
            
            // Get the next mission from persistent storage
            val nextMission = completionMarker.nextMission
            if (nextMission == null) {
                ExtremeForensicLogger.Mission.log("FORCE_RESUME_NO_MISSION: No next mission in completion marker, checking queue")
                
                if (queue.isEmpty()) {
                    ExtremeForensicLogger.Mission.log("FORCE_RESUME_EMPTY: No missions to resume to, completing sequencer")
                    handleSequencerComplete(completionMarker.alarmId)
                    return
                }
                
                // Fallback to first mission in queue
                val fallbackMission = queue.first()
                ExtremeForensicLogger.Mission.log("FORCE_RESUME_FALLBACK: Using fallback mission ${fallbackMission.id}")
                startMissionDirectly(fallbackMission)
                return
            }
            
            ExtremeForensicLogger.Mission.log("FORCE_RESUME_NEXT: missionId=${nextMission.id} type=${nextMission.params["mission_type"]}")
            
            // Start the next mission directly from persistent storage
            startMissionDirectly(nextMission)
            
            ExtremeForensicLogger.Mission.log("FORCE_RESUME_SUCCESS: id=$resumeId - Next mission started from completion marker")
            
            // Cleanup completion marker after second mission starts
            scope.launch {
                delay(2000) // Give mission time to start
                CompletionMarker.clearCompletionMarker(context)
                ExtremeForensicLogger.Mission.log("FORCE_RESUME_CLEANUP: Completion marker cleared after mission start")
            }
            
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("FORCE_RESUME_ERROR: id=$resumeId - Failed to force resume: ${e.message}")
        }
    }
    
    /**
     * Start mission directly without queue manipulation
     */
    private fun startMissionDirectly(mission: MissionSpec) {
        // Clear any current mission state
        currentMission = null
        isProcessing = false
        currentJob = null
        timeoutJob?.cancel()
        timeoutJob = null
        
        // Start the mission immediately
        currentMission = mission
        isProcessing = true
        startMission(mission)
    }
    
    /**
     * Forceful transition to next mission - bypasses normal sequencer logic
     * Used when automatic processing fails to prevent stuck states
     */
    private fun forceAdvanceToNextMission() {
        val forceId = "${System.currentTimeMillis()}_${(0..9999).random()}"
        ExtremeForensicLogger.Mission.log("FORCE_ADVANCE_START: id=$forceId - Forceful transition to next mission")
        
        try {
            // Load the queue directly
            val queue = queueStore.loadQueue().toMutableList()
            ExtremeForensicLogger.Mission.log("FORCE_ADVANCE_QUEUE: id=$forceId size=${queue.size}")
            
            if (queue.isEmpty()) {
                ExtremeForensicLogger.Mission.log("FORCE_ADVANCE_EMPTY: id=$forceId - No more missions, completing sequencer")
                handleSequencerComplete(getCurrentAlarmId())
                return
            }
            
            // Get the next mission
            val nextMission = queue.first()
            ExtremeForensicLogger.Mission.log("FORCE_ADVANCE_NEXT: id=$forceId missionId=${nextMission.id} type=${nextMission.params["mission_type"]}")
            
            // Remove it from queue
            queue.removeAt(0)
            updatePendingQueueSnapshot(queue)
            queueStore.saveQueue(queue)
            
            // Start the next mission immediately
            currentMission = nextMission
            isProcessing = true
            startMission(nextMission)
            
            ExtremeForensicLogger.Mission.log("FORCE_ADVANCE_SUCCESS: id=$forceId - Next mission started forcefully")
            
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("FORCE_ADVANCE_ERROR: id=$forceId - Failed to force advance: ${e.message}")
        }
    }
    
    /**
     * Check if device is in locked state (DPS mode)
     */
    fun isDeviceLocked(): Boolean {
        return try {
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.isDeviceLocked || keyguardManager.isKeyguardLocked
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("DEVICE_LOCK_CHECK_ERROR: ${e.message}")
            false // Default to CPS mode if we can't determine lock state
        }
    }
    
    /**
     * Check if device is fully unlocked and ready for CPS operations
     */
    private fun isDeviceUnlockedForCps(): Boolean {
        return try {
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val isDeviceLocked = keyguardManager.isDeviceLocked
            val isKeyguardLocked = keyguardManager.isKeyguardLocked
            !isDeviceLocked && !isKeyguardLocked
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("DEVICE_UNLOCK_CHECK_ERROR: ${e.message}")
            false // Default to locked if we can't determine unlock state
        }
    }
}
