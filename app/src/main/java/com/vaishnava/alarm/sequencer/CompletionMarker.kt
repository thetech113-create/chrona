package com.vaishnava.alarm.sequencer

import com.vaishnava.alarm.ExtremeForensicLogger
import com.vaishnava.alarm.ExtremeForensicLogger.Chrona.LogType

import android.content.Context
import android.os.UserManager
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Completion Marker Persistence - Uses same proven pattern as ChronaLogger
 * Reuses exact DPS/CPS storage switching mechanism that survives DPS â†’ CPS transitions
 */
object CompletionMarker {
    private const val DIR = "chrona"
    private const val FILE = "completion_marker.log"
    private const val MISSION_FILE = "next_mission.json"
    private val seq = AtomicInteger(0)

    private fun isUserUnlocked(context: Context): Boolean {
        return try {
            (context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked
        } catch (_: Exception) {
            false
        }
    }

    private fun baseDir(context: Context, useDps: Boolean): File {
        val baseContext = if (useDps && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext() ?: context
        } else {
            context
        }
        return baseContext.filesDir
    }
    
    private fun getSafeDir(context: Context, dirName: String): File {
        val base = if (isUserUnlocked(context)) {
            baseDir(context, useDps = false)
        } else {
            baseDir(context, useDps = true)
        }

        val dir = File(base, dirName)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun scopedDir(context: Context, useDps: Boolean): File {
        val dir = File(baseDir(context, useDps), DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Get completion marker file using same DPS/CPS switching as ChronaLogger
     */
    private fun file(context: Context): File {
        val dir = getSafeDir(context, DIR)
        return File(dir, FILE)
    }

    private fun file(context: Context, useDps: Boolean): File {
        return File(scopedDir(context, useDps), FILE)
    }

    /**
     * Get next mission file for persistent storage
     */
    private fun missionFile(context: Context): File {
        val dir = getSafeDir(context, DIR)
        return File(dir, MISSION_FILE)
    }

    private fun missionFile(context: Context, useDps: Boolean): File {
        return File(scopedDir(context, useDps), MISSION_FILE)
    }

    fun syncDpsToCps(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N || !isUserUnlocked(context)) {
            return
        }

        try {
            val dpsMarker = file(context, useDps = true)
            val dpsMission = missionFile(context, useDps = true)
            val cpsMarker = file(context, useDps = false)
            val cpsMission = missionFile(context, useDps = false)

            var copiedMarker = false
            var copiedMission = false

            if (dpsMarker.exists()) {
                dpsMarker.copyTo(cpsMarker, overwrite = true)
                copiedMarker = true
            }
            if (dpsMission.exists()) {
                dpsMission.copyTo(cpsMission, overwrite = true)
                copiedMission = true
            }

            if (copiedMarker || copiedMission) {
                if (copiedMarker && cpsMarker.exists()) {
                    dpsMarker.delete()
                }
                if (copiedMission && cpsMission.exists()) {
                    dpsMission.delete()
                }
                ExtremeForensicLogger.Chrona.log(
                    context,
                    "CompletionMarker",
                    "COMPLETION_MARKER_SYNCED_TO_CPS: copiedMarker=$copiedMarker copiedMission=$copiedMission"
                )
            }
        } catch (e: Exception) {
            ExtremeForensicLogger.Chrona.log(context, LogType.ERROR, "CompletionMarker", "COMPLETION_MARKER_SYNC_ERROR: ${e.message}")
        }
    }
    
    /**
     * Write completion marker using same synchronous pattern as ChronaLogger
     */
    fun writeCompletionMarker(context: Context, alarmId: Int, completedMissionId: String, nextMission: MissionSpec) {
        try {
            // Write completion marker
            val markerFile = file(context)
            val timestamp = Date().toString()
            val markerEntry = "COMPLETION_MARKER|${seq.incrementAndGet()}|${timestamp}|alarmId=$alarmId|completedMissionId=$completedMissionId\n"
            
            // SYNCHRONOUS FileOutputStream with UTF-8 encoding and immediate flush (same as ChronaLogger)
            val fos = FileOutputStream(markerFile, false) // overwrite mode - only one marker at a time
            fos.write(markerEntry.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.close()
            
            // Write next mission to persistent storage using simple string format
            val missionFile = missionFile(context)
            val missionString = "${nextMission.id}|${nextMission.params.entries.joinToString(",") { "${it.key}=${it.value}" }}|${nextMission.timeoutMs}|${nextMission.retryCount}|${nextMission.sticky}|${nextMission.retryDelayMs}"
            
            val missionFos = FileOutputStream(missionFile, false)
            missionFos.write(missionString.toByteArray(Charsets.UTF_8))
            missionFos.flush()
            missionFos.close()
            
            ExtremeForensicLogger.Chrona.log(context, "CompletionMarker", "COMPLETION_MARKER_WRITTEN: alarmId=$alarmId completedMissionId=$completedMissionId nextMissionId=${nextMission.id}")
        } catch (e: Exception) {
            ExtremeForensicLogger.Chrona.log(context, LogType.ERROR, "CompletionMarker", "COMPLETION_MARKER_WRITE_ERROR: ${e.message}")
        }
    }
    
    /**
     * Read completion marker using same storage access as ChronaLogger
     */
    fun readCompletionMarker(context: Context): CompletionMarkerData? {
        try {
            var markerFile = file(context)
            if (!markerFile.exists() && isUserUnlocked(context) && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                val dpsMarker = file(context, useDps = true)
                if (dpsMarker.exists()) {
                    syncDpsToCps(context)
                    markerFile = file(context, useDps = false)
                }
            }
            if (!markerFile.exists()) {
                ExtremeForensicLogger.Chrona.log(context, "CompletionMarker", "COMPLETION_MARKER_NOT_FOUND: No marker file exists")
                return null
            }
            
            // Read the marker file
            val fis = FileInputStream(markerFile)
            val content = fis.readBytes().toString(Charsets.UTF_8)
            fis.close()
            
            // Parse the marker entry
            val lines = content.trim().split("\n")
            for (line in lines) {
                if (line.startsWith("COMPLETION_MARKER|")) {
                    val parts = line.split("|")
                    if (parts.size >= 5) {
                        val timestamp = parts[2]
                        val alarmIdStr = parts[3].substringAfter("alarmId=")
                        val completedMissionId = parts[4].substringAfter("completedMissionId=")
                        
                        val alarmId = alarmIdStr.toIntOrNull()
                        
                        if (alarmId != null) {
                            // Read next mission from persistent storage
                            val nextMission = readNextMission(context)
                            
                            ExtremeForensicLogger.Chrona.log(context, "CompletionMarker", "COMPLETION_MARKER_READ: alarmId=$alarmId completedMissionId=$completedMissionId timestamp=$timestamp nextMissionId=${nextMission?.id}")
                            return CompletionMarkerData(alarmId, completedMissionId, nextMission, timestamp)
                        }
                    }
                }
            }
            
            ExtremeForensicLogger.Chrona.log(context, "CompletionMarker", "COMPLETION_MARKER_INVALID: Marker file exists but contains invalid data")
            return null
        } catch (e: Exception) {
            ExtremeForensicLogger.Chrona.log(context, LogType.ERROR, "CompletionMarker", "COMPLETION_MARKER_READ_ERROR: ${e.message}")
            return null
        }
    }
    
    /**
     * Read next mission from persistent storage
     */
    private fun readNextMission(context: Context): MissionSpec? {
        try {
            val missionFile = missionFile(context)
            if (!missionFile.exists()) {
                ExtremeForensicLogger.Chrona.log(context, "CompletionMarker", "NEXT_MISSION_NOT_FOUND: No mission file exists")
                return null
            }
            
            val fis = FileInputStream(missionFile)
            val content = fis.readBytes().toString(Charsets.UTF_8)
            fis.close()
            
            // Parse mission string format: id|param1=value1,param2=value2|timeoutMs|retryCount|sticky|retryDelayMs
            val parts = content.split("|")
            if (parts.size >= 6) {
                val id = parts[0]
                val paramsStr = parts[1]
                val timeoutMs = parts[2].toLongOrNull() ?: 30000L
                val retryCount = parts[3].toIntOrNull() ?: 3
                val sticky = parts[4].toBooleanStrictOrNull() ?: false
                val retryDelayMs = parts[5].toLongOrNull() ?: 1000L
                
                // Parse params
                val params = mutableMapOf<String, String>()
                if (paramsStr.isNotEmpty()) {
                    val paramPairs = paramsStr.split(",")
                    for (pair in paramPairs) {
                        val keyValue = pair.split("=", limit = 2)
                        if (keyValue.size == 2) {
                            params[keyValue[0]] = keyValue[1]
                        }
                    }
                }
                
                return MissionSpec(
                    id = id,
                    params = params,
                    timeoutMs = timeoutMs,
                    retryCount = retryCount,
                    sticky = sticky,
                    retryDelayMs = retryDelayMs
                )
            }
            
            return null
        } catch (e: Exception) {
            ExtremeForensicLogger.Chrona.fatal(context, -1, "NEXT_MISSION_READ_ERROR: ${e.message}")
            return null
        }
    }
    
    /**
     * Clear completion marker
     */
    fun clearCompletionMarker(context: Context) {
        try {
            listOf(file(context, useDps = false), file(context, useDps = true)).forEach { markerFile ->
                if (markerFile.exists()) {
                    markerFile.delete()
                    ExtremeForensicLogger.Chrona.log(context, "CompletionMarker", "COMPLETION_MARKER_CLEARED: path=${markerFile.absolutePath}")
                }
            }

            listOf(missionFile(context, useDps = false), missionFile(context, useDps = true)).forEach { missionFile ->
                if (missionFile.exists()) {
                    missionFile.delete()
                    ExtremeForensicLogger.Chrona.log(context, "CompletionMarker", "NEXT_MISSION_CLEARED: path=${missionFile.absolutePath}")
                }
            }
        } catch (e: Exception) {
            ExtremeForensicLogger.Chrona.log(context, LogType.ERROR, "CompletionMarker", "COMPLETION_MARKER_CLEAR_ERROR: ${e.message}")
        }
    }
    
    /**
     * Data class for completion marker
     */
    data class CompletionMarkerData(
        val alarmId: Int,
        val completedMissionId: String,
        val nextMission: MissionSpec?,
        val timestamp: String
    )
}
