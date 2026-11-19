package com.vaishnava.alarm.sequencer

import com.vaishnava.alarm.ExtremeForensicLogger

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MissionQueueStore(private val context: Context) {
    companion object {
        private const val QUEUE_FILE_NAME = "mission_queue.json"
        private const val CURRENT_MISSION_FILE_NAME = "current_mission.json"
        @Volatile
        private var hasLoggedMissingQueueOnce: Boolean = false

        fun purgeLegacyStateFiles(context: Context): Int {
            val appContext = context.applicationContext ?: context
            val candidateFiles = linkedSetOf<File>()

            candidateFiles += File(appContext.filesDir, QUEUE_FILE_NAME)
            candidateFiles += File(appContext.filesDir, CURRENT_MISSION_FILE_NAME)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val dpsContext = appContext.createDeviceProtectedStorageContext() ?: appContext
                candidateFiles += File(dpsContext.filesDir, QUEUE_FILE_NAME)
                candidateFiles += File(dpsContext.filesDir, CURRENT_MISSION_FILE_NAME)
            }

            var deletedCount = 0
            candidateFiles.forEach { file ->
                if (file.exists() && file.delete()) {
                    deletedCount += 1
                    ExtremeForensicLogger.Mission.log("STATE_INTEGRITY: Deleted legacy mission state file path=${file.absolutePath}")
                }
            }
            return deletedCount
        }
    }
    
    private val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.createDeviceProtectedStorageContext() ?: context
    } else context
    
    private val queueFile = File(dpsContext.filesDir, QUEUE_FILE_NAME)
    private val currentMissionFile = File(dpsContext.filesDir, CURRENT_MISSION_FILE_NAME)
    
    // CRITICAL FIX: Clear corrupted JSON files since we now use DPS SharedPreferences
    fun clearCorruptedFiles() {
        try {
            if (queueFile.exists()) {
                queueFile.delete()
                ExtremeForensicLogger.Mission.log("QUEUE_STORE_CLEAR: Deleted corrupted queue file")
            }
            if (currentMissionFile.exists()) {
                currentMissionFile.delete()
                ExtremeForensicLogger.Mission.log("QUEUE_STORE_CLEAR: Deleted corrupted current mission file")
            }
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("Failed to clear corrupted files", e)
        }
    }
    
    fun saveQueue(queue: List<MissionSpec>) {
        val dpsAlarmId = try {
            val dpsPrefs = dpsContext.getSharedPreferences("direct_boot_alarm_prefs", Context.MODE_PRIVATE)
            dpsPrefs.getInt("current_alarm_id", -1)
        } catch (e: Exception) {
            -1
        }
        
        try {
            if (dpsAlarmId != -1) {
                ExtremeForensicLogger.Mission.log("QUEUE_STORE_SAVE_DPS: alarmId=$dpsAlarmId size=${queue.size}")
            }
            val file = File(dpsContext.filesDir, QUEUE_FILE_NAME)
            val json = JSONArray()
            for (mission in queue) {
                val missionJson = JSONObject().apply {
                    put("id", mission.id)
                    put("params", JSONObject(mission.params))
                    put("timeoutMs", mission.timeoutMs)
                    put("retryCount", mission.retryCount)
                    put("sticky", mission.sticky)
                    put("retryDelayMs", mission.retryDelayMs)
                }
                json.put(missionJson)
            }
            file.writeText(json.toString())
            ExtremeForensicLogger.Mission.log("QUEUE_STORE_SAVE_SUCCESS: saved ${queue.size} missions")
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("QUEUE_STORE_SAVE_ERROR: ${e.message}")
        }
    }
    
    fun loadQueue(): List<MissionSpec> {
        try {
            if (!queueFile.exists()) {
                // Avoid spamming logs when the app is fresh and the queue file has
                // never been created. We still return an empty list, but only log
                // the missing-file condition once per app process.
                if (!hasLoggedMissingQueueOnce) {
                    hasLoggedMissingQueueOnce = true
                    ExtremeForensicLogger.Mission.log("QUEUE_STORE_LOAD_EMPTY: file_missing path=${queueFile.absolutePath}")
                } else {
                    ExtremeForensicLogger.Mission.logVerbose("QUEUE_STORE_LOAD_EMPTY_VERBOSE: file_missing path=${queueFile.absolutePath}")
                }
                return emptyList()
            }
            
            val fileContent = queueFile.readText()
            
            // Check if file content is valid JSON
            if (!fileContent.trim().startsWith("[") || !fileContent.trim().endsWith("]")) {
                ExtremeForensicLogger.Mission.logError("Queue file corrupted - invalid JSON format", Exception("File content: $fileContent"))
                // Clear corrupted file
                queueFile.delete()
                return emptyList()
            }
            
            val jsonArray = JSONArray(fileContent)
            val queue = mutableListOf<MissionSpec>()
            
            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                val paramsMap = mutableMapOf<String, String>()
                val paramsJson = json.getJSONObject("params")
                paramsJson.keys().forEach { key ->
                    paramsMap[key] = paramsJson.getString(key)
                }
                
                val missionId = json.getString("id")
                // CRITICAL FIX: Block "none" missions from being loaded from storage
                if (missionId == "none" || missionId.contains("none_mission") || missionId.contains("mission_type=none")) {
                    ExtremeForensicLogger.Mission.logWarning("QUEUE_LOAD_BLOCKED: Skipping 'none' mission from storage - missionId=$missionId")
                    continue
                }
                
                queue.add(MissionSpec(
                    id = missionId,
                    params = paramsMap,
                    timeoutMs = json.getLong("timeoutMs"),
                    retryCount = json.getInt("retryCount"),
                    sticky = json.getBoolean("sticky"),
                    retryDelayMs = json.getLong("retryDelayMs")
                ))
            }
            
            ExtremeForensicLogger.Mission.logVerbose("QUEUE_STORE_LOAD_DONE: size=${queue.size}")
            return queue
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("Failed to load queue", e)
            return emptyList()
        }
    }
    
    fun saveCurrentMission(mission: MissionSpec) {
        try {
            ExtremeForensicLogger.Mission.log("CURRENT_STORE_SAVE_START: missionId=${mission.id} path=${currentMissionFile.absolutePath}")
            val json = JSONObject().apply {
                put("id", mission.id)
                put("params", JSONObject(mission.params))
                put("timeoutMs", mission.timeoutMs)
                put("retryCount", mission.retryCount)
                put("sticky", mission.sticky)
                put("retryDelayMs", mission.retryDelayMs)
                put("startTime", System.currentTimeMillis())
            }
            currentMissionFile.writeText(json.toString())
            ExtremeForensicLogger.Mission.log("CURRENT_STORE_SAVE_DONE: missionId=${mission.id}")
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("Failed to save current mission", e)
        }
    }
    
    fun loadCurrentMission(): Pair<MissionSpec?, Long>? {
        try {
            if (!currentMissionFile.exists()) {
                ExtremeForensicLogger.Mission.log("CURRENT_STORE_LOAD_EMPTY: file_missing path=${currentMissionFile.absolutePath}")
                return null
            }
            
            val json = JSONObject(currentMissionFile.readText())
            val paramsMap = mutableMapOf<String, String>()
            val paramsJson = json.getJSONObject("params")
            paramsJson.keys().forEach { key ->
                paramsMap[key] = paramsJson.getString(key)
            }
            
            val missionId = json.getString("id")
            // CRITICAL FIX: Block "none" missions from being loaded from current mission storage
            if (missionId == "none" || missionId.contains("none_mission") || missionId.contains("mission_type=none")) {
                ExtremeForensicLogger.Mission.logWarning("CURRENT_LOAD_BLOCKED: Skipping 'none' mission from storage - missionId=$missionId")
                return null
            }
            
            val mission = MissionSpec(
                id = missionId,
                params = paramsMap,
                timeoutMs = json.getLong("timeoutMs"),
                retryCount = json.getInt("retryCount"),
                sticky = json.getBoolean("sticky"),
                retryDelayMs = json.getLong("retryDelayMs")
            )
            
            val startTime = json.getLong("startTime")
            ExtremeForensicLogger.Mission.log("CURRENT_STORE_LOAD_DONE: missionId=${mission.id} startTime=$startTime")
            return Pair(mission, startTime)
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("Failed to load current mission", e)
            return null
        }
    }
    
    fun clearQueue() {
        try {
            ExtremeForensicLogger.Mission.log("QUEUE_STORE_CLEAR_START: path=${queueFile.absolutePath}")
            if (queueFile.exists()) {
                queueFile.delete()
                ExtremeForensicLogger.Mission.log("QUEUE_STORE_CLEAR_DONE: queue file deleted")
            } else {
                ExtremeForensicLogger.Mission.log("QUEUE_STORE_CLEAR_SKIP: queue file does not exist")
            }
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("Failed to clear queue", e)
        }
    }
    
    fun clearCurrentMission() {
        try {
            ExtremeForensicLogger.Mission.log("CURRENT_STORE_CLEAR_START: path=${currentMissionFile.absolutePath}")
            if (currentMissionFile.exists()) {
                currentMissionFile.delete()
                ExtremeForensicLogger.Mission.log("CURRENT_STORE_CLEAR_DONE: current mission file deleted")
            } else {
                ExtremeForensicLogger.Mission.log("CURRENT_STORE_CLEAR_SKIP: current mission file does not exist")
            }
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("Failed to clear current mission", e)
        }
    }
    
    fun getQueueFilePath(): String {
        return queueFile.absolutePath
    }
    
    /**
     * CRITICAL: Get the original configured mission list for DPS
     * This returns the user-configured missions, not the expanded sequencer queue
     */
    fun getConfiguredMissionList(alarmId: Int): List<MissionSpec> {
        try {
            // Get original mission configuration from alarm
            val alarmStorage = com.vaishnava.alarm.AlarmStorage(context)
            val alarm = alarmStorage.getAlarm(alarmId)
            
            if (alarm != null && alarm.missionType == "sequencer") {
                // Create mission specs based on alarm configuration
                val missionSpecs = mutableListOf<MissionSpec>()
                
                // Add password mission if configured
                if (!alarm.missionPassword.isNullOrEmpty()) {
                    missionSpecs.add(MissionSpec(
                        id = "password",
                        params = mapOf("mission_type" to "password", "password" to alarm.missionPassword),
                        timeoutMs = 60000L,
                        retryCount = 3,
                        sticky = false,
                        retryDelayMs = 1000L
                    ))
                }
                
                // Always add tap mission as the final mission
                missionSpecs.add(MissionSpec(
                    id = "tap",
                    params = mapOf("mission_type" to "tap"),
                    timeoutMs = 30000L,
                    retryCount = 3,
                    sticky = false,
                    retryDelayMs = 1000L
                ))
                
                ExtremeForensicLogger.Mission.log("CONFIGURED_MISSIONS_LOADED: alarmId=$alarmId count=${missionSpecs.size}")
                return missionSpecs
            } else {
                ExtremeForensicLogger.Mission.logWarning("NO_CONFIGURED_MISSIONS: alarmId=$alarmId - No sequencer alarm found")
                return emptyList()
            }
        } catch (e: Exception) {
            ExtremeForensicLogger.Mission.logError("FAILED_TO_LOAD_CONFIGURED_MISSIONS: alarmId=$alarmId - ${e.message}", e)
            return emptyList()
        }
    }
}
