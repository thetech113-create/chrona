package com.vaishnava.alarm.sequencer

import com.vaishnava.alarm.ExtremeForensicLogger

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class DpsMissionMirror(private val context: Context) {
    companion object {
        private val snapshotCache = ConcurrentHashMap<Int, Snapshot?>()

        fun clearSnapshotCache() {
            snapshotCache.clear()
        }

        fun invalidateAlarmSnapshot(alarmId: Int) {
            snapshotCache.remove(alarmId)
        }
    }

    data class Snapshot(
        val alarmId: Int,
        val sequenceId: String,
        val version: Long,
        val sourceMode: String,
        val orderedMissionIds: List<String>,
        val activeIndex: Int,
        val activeMissionId: String?,
        val completedMissionIds: List<String>,
        val pendingMissionIds: List<String>,
        val state: String,
        val isSequenceComplete: Boolean,
        val isAlarmRinging: Boolean,
        val updatedAt: Long,
        val lastEventCounter: Long
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("alarmId", alarmId)
            put("sequenceId", sequenceId)
            put("version", version)
            put("sourceMode", sourceMode)
            put("orderedMissionIds", JSONArray(orderedMissionIds))
            put("activeIndex", activeIndex)
            put("activeMissionId", activeMissionId)
            put("completedMissionIds", JSONArray(completedMissionIds))
            put("pendingMissionIds", JSONArray(pendingMissionIds))
            put("state", state)
            put("isSequenceComplete", isSequenceComplete)
            put("isAlarmRinging", isAlarmRinging)
            put("updatedAt", updatedAt)
            put("lastEventCounter", lastEventCounter)
        }

        companion object {
            fun fromJson(json: JSONObject): Snapshot {
                fun readStringList(key: String): List<String> {
                    val array = json.optJSONArray(key) ?: JSONArray()
                    return buildList {
                        for (i in 0 until array.length()) {
                            add(array.optString(i))
                        }
                    }.filter { it.isNotBlank() }
                }

                return Snapshot(
                    alarmId = json.getInt("alarmId"),
                    sequenceId = json.optString("sequenceId"),
                    version = json.optLong("version", 0L),
                    sourceMode = json.optString("sourceMode", "DPS"),
                    orderedMissionIds = readStringList("orderedMissionIds"),
                    activeIndex = json.optInt("activeIndex", -1),
                    activeMissionId = json.optString("activeMissionId").ifBlank { null },
                    completedMissionIds = readStringList("completedMissionIds"),
                    pendingMissionIds = readStringList("pendingMissionIds"),
                    state = json.optString("state", "QUEUED"),
                    isSequenceComplete = json.optBoolean("isSequenceComplete", false),
                    isAlarmRinging = json.optBoolean("isAlarmRinging", false),
                    updatedAt = json.optLong("updatedAt", 0L),
                    lastEventCounter = json.optLong("lastEventCounter", 0L)
                )
            }
        }
    }

    private val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.createDeviceProtectedStorageContext() ?: context
    } else {
        context
    }

    private fun file(alarmId: Int) = File(dpsContext.filesDir, "dps_sequence_$alarmId.json")
    private fun tmpFile(alarmId: Int) = File(dpsContext.filesDir, "dps_sequence_$alarmId.json.tmp")

    fun readSnapshot(alarmId: Int): Snapshot? {
        // Check cache first to avoid repeated file I/O during password entry
        if (snapshotCache.containsKey(alarmId)) {
            ExtremeForensicLogger.Mission.log("SNAPSHOT_CACHE_HIT: alarmId=$alarmId (avoiding disk I/O)")
            return snapshotCache[alarmId]
        }
        ExtremeForensicLogger.Mission.log("SNAPSHOT_CACHE_MISS: alarmId=$alarmId (reading from disk)")

        return try {
            val snapshotFile = file(alarmId)
            if (!snapshotFile.exists()) {
                ExtremeForensicLogger.Mission.logDpsForensic(
                    component = "DpsMissionMirror",
                    event = "snapshot_read_missing",
                    snapshot = null,
                    expectedMissionId = null,
                    runtimeMissionId = null,
                    previousState = null,
                    source = "dps_file",
                    queueReconstructed = false,
                    nullMissionObserved = false,
                    stalePendingIntent = false,
                    replayingOldData = false
                )
                null.also { snapshotCache[alarmId] = null }
            } else {
                val snapshot = Snapshot.fromJson(JSONObject(snapshotFile.readText()))
                ExtremeForensicLogger.Mission.logDpsForensic(
                    component = "DpsMissionMirror",
                    event = "snapshot_read",
                    snapshot = snapshot,
                    expectedMissionId = snapshot.activeMissionId,
                    runtimeMissionId = null,
                    previousState = null,
                    source = "dps_file",
                    queueReconstructed = false,
                    nullMissionObserved = snapshot.activeMissionId == null && !snapshot.isSequenceComplete,
                    stalePendingIntent = false,
                    replayingOldData = false
                )
                snapshot.also { snapshotCache[alarmId] = it }
            }
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("DPS_MIRROR_READ_ERROR: ${e.message}")
            null
        }
    }

    fun writeSnapshot(snapshot: Snapshot) {
        try {
            val target = file(snapshot.alarmId)
            val tmp = tmpFile(snapshot.alarmId)
            tmp.writeText(snapshot.toJson().toString())
            invalidateAlarmSnapshot(snapshot.alarmId)  // Invalidate cache when writing
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("Failed deleting old snapshot for alarmId=${snapshot.alarmId}")
            }
            if (!tmp.renameTo(target)) {
                throw IllegalStateException("snapshot rename failed for alarmId=${snapshot.alarmId}")
            }
            ExtremeForensicLogger.Mission.logDpsForensic(
                component = "DpsMissionMirror",
                event = "snapshot_write",
                snapshot = snapshot,
                expectedMissionId = snapshot.activeMissionId,
                runtimeMissionId = snapshot.activeMissionId,
                previousState = null,
                source = "dps_file",
                queueReconstructed = false,
                nullMissionObserved = snapshot.activeMissionId == null && !snapshot.isSequenceComplete,
                stalePendingIntent = false,
                replayingOldData = false
            )
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("DPS_MIRROR_WRITE_ERROR: ${e.message}")
            throw e
        }
    }

    fun clearSnapshot(alarmId: Int) {
        try {
            invalidateAlarmSnapshot(alarmId)  // Invalidate cache when clearing
            val target = file(alarmId)
            if (target.exists()) {
                target.delete()
            }
            val tmp = tmpFile(alarmId)
            if (tmp.exists()) {
                tmp.delete()
            }
            ExtremeForensicLogger.Mission.log("DPS_MIRROR_CLEAR: alarmId=$alarmId")
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("DPS_MIRROR_CLEAR_ERROR: ${e.message}")
        }
    }

    fun markSequenceStarted(
        alarmId: Int,
        sequenceId: String,
        orderedMissionIds: List<String>,
        eventCounter: Long,
        isAlarmRinging: Boolean
    ): Snapshot {
        val snapshot = Snapshot(
            alarmId = alarmId,
            sequenceId = sequenceId,
            version = 1L,
            sourceMode = "DPS",
            orderedMissionIds = orderedMissionIds,
            activeIndex = -1,
            activeMissionId = null,
            completedMissionIds = emptyList(),
            pendingMissionIds = orderedMissionIds,
            state = "QUEUED",
            isSequenceComplete = false,
            isAlarmRinging = isAlarmRinging,
            updatedAt = System.currentTimeMillis(),
            lastEventCounter = eventCounter
        )
        writeSnapshot(snapshot)
        return snapshot
    }

    fun markMissionActivated(
        snapshot: Snapshot,
        activeMissionId: String,
        activeIndex: Int,
        pendingMissionIds: List<String>,
        eventCounter: Long,
        previousState: String?
    ): Snapshot {
        val next = snapshot.copy(
            version = snapshot.version + 1,
            activeIndex = activeIndex,
            activeMissionId = activeMissionId,
            pendingMissionIds = pendingMissionIds,
            state = "ACTIVE",
            isSequenceComplete = false,
            isAlarmRinging = true,
            updatedAt = System.currentTimeMillis(),
            lastEventCounter = eventCounter
        )
        writeSnapshot(next)
        ExtremeForensicLogger.Mission.logDpsForensic(
            component = "DpsMissionMirror",
            event = "snapshot_activate",
            snapshot = next,
            expectedMissionId = activeMissionId,
            runtimeMissionId = activeMissionId,
            previousState = previousState,
            source = "dps_file",
            queueReconstructed = false,
            nullMissionObserved = false,
            stalePendingIntent = false,
            replayingOldData = false
        )
        return next
    }

    fun commitMissionCompletion(
        snapshot: Snapshot,
        completedMissionId: String,
        nextMissionId: String?,
        nextIndex: Int,
        eventCounter: Long,
        previousState: String?
    ): Snapshot {
        val nextPending = if (nextMissionId != null) {
            snapshot.orderedMissionIds.drop(nextIndex + 1)
        } else {
            emptyList()
        }
        val nextState = snapshot.copy(
            version = snapshot.version + 1,
            activeIndex = if (nextMissionId != null) nextIndex else -1,
            activeMissionId = nextMissionId,
            completedMissionIds = snapshot.completedMissionIds + completedMissionId,
            pendingMissionIds = nextPending,
            state = if (nextMissionId != null) "ACTIVE" else "COMPLETED",
            isSequenceComplete = nextMissionId == null,
            isAlarmRinging = nextMissionId != null,
            updatedAt = System.currentTimeMillis(),
            lastEventCounter = eventCounter
        )
        writeSnapshot(nextState)
        ExtremeForensicLogger.Mission.logDpsForensic(
            component = "DpsMissionMirror",
            event = "snapshot_commit_completion",
            snapshot = nextState,
            expectedMissionId = completedMissionId,
            runtimeMissionId = nextMissionId,
            previousState = previousState,
            source = "dps_file",
            queueReconstructed = false,
            nullMissionObserved = nextMissionId == null && !nextState.isSequenceComplete,
            stalePendingIntent = false,
            replayingOldData = false
        )
        return nextState
    }

    fun markSequenceCompleted(
        snapshot: Snapshot,
        eventCounter: Long,
        previousState: String?
    ): Snapshot {
        val completed = snapshot.copy(
            version = snapshot.version + 1,
            activeIndex = -1,
            activeMissionId = null,
            pendingMissionIds = emptyList(),
            state = "COMPLETED",
            isSequenceComplete = true,
            isAlarmRinging = false,
            updatedAt = System.currentTimeMillis(),
            lastEventCounter = eventCounter
        )
        writeSnapshot(completed)
        ExtremeForensicLogger.Mission.logDpsForensic(
            component = "DpsMissionMirror",
            event = "snapshot_complete",
            snapshot = completed,
            expectedMissionId = null,
            runtimeMissionId = null,
            previousState = previousState,
            source = "dps_file",
            queueReconstructed = false,
            nullMissionObserved = false,
            stalePendingIntent = false,
            replayingOldData = false
        )
        return completed
    }
}
