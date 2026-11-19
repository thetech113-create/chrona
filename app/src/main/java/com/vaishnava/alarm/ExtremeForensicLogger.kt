package com.vaishnava.alarm

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.UserManager
import android.text.format.DateFormat
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.vaishnava.alarm.sequencer.DpsMissionMirror
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Objects
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * EXTREME LEVEL FORENSIC LOGGING
 * Logs EVERYTHING: Every decision, every field, every exception, every state change.
 * No silent operations. No assumptions. Complete traceability.
 */
object ExtremeForensicLogger {

    private const val TAG_MAIN = "EXTREME_FORENSIC"
    private const val TAG_TRACE = "EXTREME_TRACE"
    private const val TAG_STATE = "EXTREME_STATE"
    private const val TAG_ERROR = "EXTREME_ERROR"
    private const val TAG_PERF = "EXTREME_PERF"

    private var reportBuilder: StringBuilder? = null
    private var reportFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var startTime: Long = 0L
    private var operationCounter = 0

    // ==================== UNIFIED INCIDENT/EVENT BUFFER ====================
    //
    // Every logger in this file (this outer object, Mission, Chrona, DebugLog)
    // writes to its own destination (Logcat, a file, a UI overlay) but none of
    // them previously fed a single place that answers "what actually happened
    // recently" - the device-info screen only had static specs (manufacturer,
    // RAM, etc.), not a trace of real events. This buffer is that place: every
    // logger pushes its formatted line here, and captureSessionSnapshotText()/
    // the export button read it back so the report shows the incident, not
    // just the environment it happened in.

    private const val MAX_EVENT_BUFFER = 500
    private val eventBuffer = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val eventTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun recordEvent(source: String, line: String) {
        val entry = "[${eventTimeFormat.format(Date())}] [$source] $line"
        synchronized(eventBuffer) {
            eventBuffer.add(entry)
            if (eventBuffer.size > MAX_EVENT_BUFFER) eventBuffer.removeAt(0)
        }
    }

    /** Most recent captured events across every logger, oldest first. */
    fun recentEvents(limit: Int = 200): List<String> = synchronized(eventBuffer) {
        eventBuffer.takeLast(limit)
    }

    // ==================== INITIALIZATION ====================

    fun initialize(context: Context) {
        startTime = System.currentTimeMillis()
        reportBuilder = StringBuilder()

        val timestamp = System.currentTimeMillis()
        // Save to file manager accessible location: Documents/Chrona_Forensics
        val reportDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "Chrona_Forensics")
        if (!reportDir.exists()) reportDir.mkdirs()

        reportFile = File(reportDir, "chrona_oppo_ringtone_forensic_EXTREME_$timestamp.txt")

        logSectionStart("EXTREME LEVEL FORENSIC INVESTIGATION")
        logProcessInfo()
        logMemoryState("INITIALIZATION")
        logDeviceFingerprint(context)
        logApplicationContext(context)
    }

    // ==================== PROCESS & MEMORY ====================

    private fun logProcessInfo() {
        logSection("PROCESS INFORMATION")
        val pid = Process.myPid()
        val uid = Process.myUid()
        val tid = Process.myTid()

        log("Process ID (PID)", pid.toString())
        log("User ID (UID)", uid.toString())
        log("Thread ID (TID)", tid.toString())
        log("Thread Name", Thread.currentThread().name)

        Log.d(TAG_STATE, "PID=$pid UID=$uid TID=$tid Thread=${Thread.currentThread().name}")
    }

    fun logMemoryState(context: String) {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val usedMemory = totalMemory - freeMemory

        val memoryPercentage = (usedMemory * 100) / maxMemory

        logSubsection("MEMORY STATE: $context")
        log("Total Memory", formatBytes(totalMemory))
        log("Used Memory", formatBytes(usedMemory))
        log("Free Memory", formatBytes(freeMemory))
        log("Max Memory", formatBytes(maxMemory))
        log("Memory Usage %", "$memoryPercentage%")

        val nativeHeapAllocated = Debug.getNativeHeapAllocatedSize()
        log("Native Memory", formatBytes(nativeHeapAllocated))

        Log.d(TAG_PERF, "Memory: $usedMemory/$maxMemory bytes ($memoryPercentage%)")
    }

    // ==================== DEVICE FINGERPRINT ====================

    private fun logDeviceFingerprint(context: Context) {
        logSection("DEVICE FINGERPRINT")

        log("Manufacturer", Build.MANUFACTURER)
        log("Brand", Build.BRAND)
        log("Model", Build.MODEL)
        log("Device", Build.DEVICE)
        log("Product", Build.PRODUCT)
        log("Android Version", Build.VERSION.RELEASE)
        log("API Level", Build.VERSION.SDK_INT.toString())
        log("Base OS", Build.VERSION.BASE_OS)
        log("Incremental", Build.VERSION.INCREMENTAL)
        log("Build Fingerprint", Build.FINGERPRINT)
        log("Build ID", Build.ID)
        log("Build Type", Build.TYPE)
        log("Build Tags", Build.TAGS)
        log("Build User", Build.USER)
        log("Build Host", Build.HOST)
        log("Hardware", Build.HARDWARE)
        log("Serial", Build.SERIAL)
        log("Display", Build.DISPLAY)
        log("Bootloader", Build.BOOTLOADER)
        log("Board", Build.BOARD)
        log("Locale", Locale.getDefault().toString())
        log("Timezone", TimeZone.getDefault().id)
        log("ISO 3166-1 Alpha-2 Country Code", Locale.getDefault().country)
        log("Language", Locale.getDefault().language)

        Log.d(TAG_MAIN, "Device: ${Build.MANUFACTURER} ${Build.MODEL} ${Build.VERSION.RELEASE}")
    }

    private fun logApplicationContext(context: Context) {
        logSection("APPLICATION CONTEXT")

        try {
            val appInfo = context.applicationInfo
            log("Package Name", context.packageName)
            log("App Name", appInfo.loadLabel(context.packageManager).toString())
            log("Target SDK", appInfo.targetSdkVersion.toString())
            log("Min SDK", appInfo.minSdkVersion.toString())
            log("Flags", appInfo.flags.toString())
            log("Source Dir", appInfo.sourceDir)
            log("Native Library Dir", appInfo.nativeLibraryDir)
            log("Data Dir", context.dataDir.absolutePath)
            log("Cache Dir", context.cacheDir.absolutePath)

            Log.d(TAG_STATE, "App: ${context.packageName} SDK ${appInfo.minSdkVersion}-${appInfo.targetSdkVersion}")
        } catch (e: Exception) {
            logException("Failed to log application context", e)
        }
    }

    // ==================== CURSOR ENUMERATION ====================

    fun logCursorStart(type: String) {
        logSection("CURSOR ENUMERATION START - Type: $type")
        log("Timestamp", getCurrentTimestamp())
        log("Operation #", (++operationCounter).toString())
    }

    fun logCursorColumns(columns: Array<String>?) {
        logSubsection("CURSOR COLUMNS")

        if (columns == null) {
            log("Columns", "NULL")
            logWarning("Cursor columns are NULL")
            return
        }

        log("Column Count", columns.size.toString())
        columns.forEachIndexed { index, column ->
            log("Column[$index]", column)
        }

        Log.d(TAG_TRACE, "Cursor has ${columns.size} columns")
    }

    fun logCursorMove(position: Int, success: Boolean) {
        if (!success) {
            logWarning("Cursor moveToNext() returned FALSE at position $position")
            Log.w(TAG_TRACE, "Cursor moveToNext failed at position $position")
        } else {
            Log.d(TAG_TRACE, "Cursor moved to position $position")
        }
    }

    // ==================== RINGTONE RECORD ====================

    fun logRingtoneRecordStart(position: Int, uri: String?) {
        logSubsection("RINGTONE RECORD #${String.format("%03d", position)}")
        log("Position", position.toString())
        log("Record Timestamp", getCurrentTimestamp())
        log("Thread", Thread.currentThread().name)

        if (uri == null) {
            logError("URI is NULL for position $position")
        } else {
            log("URI", uri)
            log("URI Length", uri.length.toString())
            log("URI Scheme", extractUriScheme(uri))
            log("URI Authority", extractUriAuthority(uri))
        }
    }

    fun logRingtoneMetadata(
        position: Int,
        fieldName: String,
        value: Any?,
        columnIndex: Int,
        dataType: String
    ) {
        val valueStr = when {
            value == null -> "NULL"
            value is String -> {
                val len = value.length
                if (len > 1000) "${value.substring(0, 1000)}... (truncated from $len chars)" else value
            }
            value is Long -> "$value (0x${value.toString(16)})"
            else -> value.toString()
        }

        log("[$fieldName]", "$valueStr (Type: $dataType, Index: $columnIndex)")

        if (value == null) {
            Log.w(TAG_STATE, "[$position] $fieldName is NULL")
        }
    }

    fun logRingtoneRecordEnd(position: Int) {
        logMemoryState("After record $position")
        Log.d(TAG_TRACE, "Ringtone record $position complete")
    }

    // ==================== RESOLUTION PIPELINE ====================

    fun logResolutionStart(position: Int) {
        logSubsection("RESOLUTION START - Position $position")
        log("Timestamp", getCurrentTimestamp())
    }

    fun logCursorFieldRead(
        position: Int,
        fieldName: String,
        columnIndex: Int,
        value: String?,
        isEmpty: Boolean
    ) {
        log("[CURSOR_READ] $fieldName", "Index=$columnIndex Value='$value' Empty=$isEmpty")

        if (isEmpty && fieldName in listOf("TITLE", "DISPLAY_NAME", "_ID")) {
            logWarning("Critical field $fieldName is empty for position $position")
        }
    }

    fun logResolutionDecision(
        position: Int,
        fieldChecked: String,
        fieldValue: String?,
        isEmpty: Boolean,
        selected: Boolean,
        reason: String
    ) {
        if (selected) {
            log("[SELECTED] $fieldChecked", "Value='$fieldValue' Reason=$reason")
            Log.d(TAG_TRACE, "Selected $fieldChecked for position $position: $reason")
        } else {
            log("[REJECTED] $fieldChecked", "Empty=$isEmpty Reason=$reason")
            Log.d(TAG_TRACE, "Rejected $fieldChecked for position $position: $reason")
        }
    }

    fun logResolvedTitle(position: Int, title: String, source: String) {
        log("[RESOLVED_TITLE]", "$title (Source: $source)")
        Log.d(TAG_TRACE, "Position $position resolved to: $title from $source")
    }

    // ==================== HARDCODED MAP ====================

    fun logMapLookupStart(position: Int, key: String) {
        logSubsection("HARDCODED MAP LOOKUP - Position $position")
        log("Lookup Key", key)
        log("Key Length", key.length.toString())
        log("Key Lowercase", key.lowercase())
    }

    fun logMapLookupAttempt(position: Int, key: String, keyLowercase: String) {
        log("[LOOKUP_ATTEMPT]", "Original='$key' Lowercase='$keyLowercase'")
    }

    fun logMapLookupResult(position: Int, key: String, result: String?) {
        if (result == null) {
            log("[LOOKUP_RESULT]", "NO ENTRY (null)")
            Log.d(TAG_TRACE, "Position $position: no map entry for '$key'")
        } else {
            log("[LOOKUP_RESULT]", "FOUND: '$result'")
            Log.d(TAG_TRACE, "Position $position: mapped '$key' → '$result'")
        }
    }

    fun logMapFallback(position: Int, original: String, result: String, reason: String) {
        log("[FALLBACK]", "Using original value: '$original' → '$result' ($reason)")
        Log.d(TAG_TRACE, "Position $position fallback to original: $reason")
    }

    fun logFinalName(position: Int, beforeMapping: String, afterMapping: String, changed: Boolean) {
        if (changed) {
            log("[NAME_CHANGED]", "'$beforeMapping' → '$afterMapping'")
            Log.d(TAG_TRACE, "Position $position name changed")
        } else {
            log("[NAME_UNCHANGED]", "'$beforeMapping' (no map entry)")
            Log.d(TAG_TRACE, "Position $position name unchanged")
        }
    }

    // ==================== DISPLAY ====================

    fun logDisplayProcessing(position: Int, name: String, isDefault: Boolean, emoji: String) {
        logSubsection("DISPLAY PROCESSING - Position $position")
        log("Base Name", name)
        log("Is Default", isDefault.toString())
        log("Emoji", emoji)
    }

    fun logDisplayLabel(position: Int, prefix: String, name: String, finalLabel: String) {
        log("Prefix", prefix)
        log("Name", name)
        log("Final Label", finalLabel)
        log("Label Length", finalLabel.length.toString())

        Log.d(TAG_TRACE, "Position $position display: $finalLabel")
    }

    fun logDisplayURIMapping(position: Int, label: String, uri: String) {
        log("Label", label)
        log("URI", uri)
        logMemoryState("After display $position")
    }

    // ==================== EXCEPTION HANDLING ====================

    fun logException(label: String, exception: Exception, context: Map<String, String>? = null) {
        logSubsection("EXCEPTION: $label")

        log("Exception Type", exception.javaClass.name)
        log("Exception Message", exception.message ?: "NULL")
        log("Timestamp", getCurrentTimestamp())
        log("Thread", Thread.currentThread().name)

        // Context
        context?.forEach { (key, value) ->
            log("Context: $key", value)
        }

        // Stack trace
        val stackTrace = exception.stackTraceToString()
        logMultiline("StackTrace", stackTrace)

        // Cause chain
        var cause = exception.cause
        var causeLevel = 1
        while (cause != null) {
            logSubsection("CAUSE #$causeLevel")
            log("Type", cause.javaClass.name)
            log("Message", cause.message ?: "NULL")
            logMultiline("StackTrace", cause.stackTraceToString())
            cause = cause.cause
            causeLevel++
        }

        Log.e(TAG_ERROR, label, exception)
        logMemoryState("After exception: $label")
        recordEvent("Extreme", "EXCEPTION $label: ${exception.message}")
    }

    fun logWarning(message: String) {
        log("[WARNING]", message)
        Log.w(TAG_MAIN, message)
        recordEvent("Extreme", "WARNING $message")
    }

    fun logError(message: String) {
        log("[ERROR]", message)
        Log.e(TAG_ERROR, message)
        recordEvent("Extreme", "ERROR $message")
    }

    /**
     * Generic event, for investigations other than ringtone enumeration.
     * Writes the report immediately so the file survives a process death mid-alarm.
     */
    fun logEvent(category: String, detail: String) {
        log("[$category]", detail)
        Log.d(TAG_TRACE, "$category: $detail")
        recordEvent(category, detail)
        runCatching {
            if (reportBuilder != null && reportFile != null) {
                reportFile!!.writeText(reportBuilder!!.toString())
            }
        }
    }

    /** True once initialize() has run, so callers can avoid initialising twice. */
    fun isInitialized(): Boolean = reportBuilder != null && reportFile != null

    // ==================== COMPLETION ====================

    fun logEnumerationComplete(totalRingtones: Int, builtInCount: Int, systemCount: Int, localCount: Int) {
        logSection("ENUMERATION COMPLETE")

        val elapsedTime = System.currentTimeMillis() - startTime

        log("Total Ringtones", totalRingtones.toString())
        log("Built-in Ringtones", builtInCount.toString())
        log("System Ringtones", systemCount.toString())
        log("Local Ringtones", localCount.toString())
        log("Elapsed Time", "$elapsedTime ms")
        log("Total Operations", operationCounter.toString())
        log("Avg Time Per Ringtone", "${elapsedTime / maxOf(totalRingtones, 1)} ms")

        logMemoryState("COMPLETION")
        Log.d(TAG_MAIN, "Enumeration complete: $totalRingtones ringtones in ${elapsedTime}ms")
    }

    fun saveReport(): String? {
        if (reportBuilder == null || reportFile == null) return null

        val saveStartTime = System.currentTimeMillis()

        try {
            logSection("REPORT PERSISTENCE")
            log("Save Start", getCurrentTimestamp())
            log("File Path", reportFile!!.absolutePath)
            log("Report Size", reportBuilder!!.length.toString())

            reportBuilder!!.append("\n" + "=".repeat(85) + "\n")
            reportBuilder!!.append("REPORT FINALIZED\n")
            reportBuilder!!.append("Save Time: ${getCurrentTimestamp()}\n")
            reportBuilder!!.append("File Path: ${reportFile!!.absolutePath}\n")
            reportBuilder!!.append("Report Size: ${reportBuilder!!.length} bytes\n")
            reportBuilder!!.append("Total Elapsed: ${System.currentTimeMillis() - startTime} ms\n")
            reportBuilder!!.append("=".repeat(85) + "\n")

            reportFile!!.writeText(reportBuilder!!.toString())

            val saveTime = System.currentTimeMillis() - saveStartTime
            log("Save Duration", "$saveTime ms")
            log("File Exists", reportFile!!.exists().toString())
            log("File Size", reportFile!!.length().toString())
            log("File Readable", reportFile!!.canRead().toString())

            Log.d(TAG_MAIN, "Report saved: ${reportFile!!.absolutePath} (${reportFile!!.length()} bytes)")
            Log.d("FORENSIC_SAVED", "Report path: ${reportFile!!.absolutePath}")
            Log.d("FORENSIC_REPORT_CONTENT", reportBuilder!!.toString())

            return reportFile!!.absolutePath
        } catch (e: Exception) {
            logException("Failed to save report", e, mapOf(
                "File Path" to (reportFile?.absolutePath ?: "NULL"),
                "Report Size" to (reportBuilder?.length?.toString() ?: "NULL")
            ))
            return null
        }
    }

    // ==================== DEVICE / RUNTIME SNAPSHOT (JSON) ====================
    //
    // Structured session/event snapshots for UI display and export, distinct from
    // the freeform ringtone-investigation report above. Two capture levels,
    // matching how the values actually change:
    // - captureSessionSnapshot(): the full picture, taken once when needed (e.g.
    //   opening the device-info screen), not on every log line.
    // - captureEventSnapshot(): a handful of fast-changing values (battery,
    //   network, screen lock, available RAM) to attach to one specific event.
    //
    // Privacy: only technical environment fields are read. No IMEI/IMSI, phone
    // number, contacts, SMS, call data, precise location, credentials, or other
    // personally identifying information is collected here.

    /** Stable for the lifetime of this process. */
    val snapshotSessionId: String by lazy { UUID.randomUUID().toString() }
    private val snapshotSessionStartMs: Long by lazy { System.currentTimeMillis() }

    private fun safeStr(block: () -> String?): String = try {
        block() ?: "unavailable"
    } catch (_: Exception) {
        "unavailable"
    }

    private fun safeInt(block: () -> Int): Int = try {
        block()
    } catch (_: Exception) {
        -1
    }

    private fun safeBoolText(block: () -> Boolean): String = try {
        block().toString()
    } catch (_: Exception) {
        "unavailable"
    }

    private fun isDebugBuild(context: Context): Boolean = try {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    } catch (_: Exception) {
        false
    }

    private fun snapshotAppInfo(context: Context): JSONObject {
        val pkg = context.packageName
        val pInfo = try { context.packageManager.getPackageInfo(pkg, 0) } catch (_: Exception) { null }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pInfo?.longVersionCode ?: -1L
        } else {
            @Suppress("DEPRECATION")
            pInfo?.versionCode?.toLong() ?: -1L
        }
        return JSONObject().apply {
            put("package", pkg)
            put("version_name", safeStr { pInfo?.versionName })
            put("version_code", versionCode)
            put("build_type", if (isDebugBuild(context)) "debug" else "release")
            put("target_sdk", safeInt { context.applicationInfo.targetSdkVersion })
            put("logger_version", "1.0")
        }
    }

    private fun snapshotDeviceInfo(): JSONObject = JSONObject().apply {
        put("manufacturer", safeStr { Build.MANUFACTURER })
        put("brand", safeStr { Build.BRAND })
        put("model", safeStr { Build.MODEL })
        put("device", safeStr { Build.DEVICE })
        put("product", safeStr { Build.PRODUCT })
        put("hardware", safeStr { Build.HARDWARE })
        put("android_version", safeStr { Build.VERSION.RELEASE })
        put("sdk", Build.VERSION.SDK_INT)
        put("build_id", safeStr { Build.ID })
        put("build_display", safeStr { Build.DISPLAY })
        put("build_fingerprint", safeStr { Build.FINGERPRINT })
        put("security_patch", safeStr {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else null
        })
        put("abi", safeStr { Build.SUPPORTED_ABIS.firstOrNull() })
        put("supported_abis", safeStr { Build.SUPPORTED_ABIS.joinToString(",") })
    }

    private fun snapshotHardwareInfo(context: Context): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        try { am?.getMemoryInfo(memInfo) } catch (_: Exception) {}

        var totalStorage = -1L
        var availStorage = -1L
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            totalStorage = stat.blockCountLong * stat.blockSizeLong
            availStorage = stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {}

        return JSONObject().apply {
            put("cpu_architecture", safeStr { Build.SUPPORTED_ABIS.firstOrNull() })
            put("cpu_cores", safeInt { Runtime.getRuntime().availableProcessors() })
            put("ram_total_bytes", if (memInfo.totalMem > 0) memInfo.totalMem else -1L)
            put("ram_available_bytes", memInfo.availMem)
            put("ram_low_memory", memInfo.lowMemory)
            put("storage_total_bytes", totalStorage)
            put("storage_available_bytes", availStorage)
        }
    }

    private fun snapshotDisplayInfo(context: Context): JSONObject {
        val dm: DisplayMetrics = context.resources.displayMetrics
        val refreshRate = safeStr {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            }
            display?.refreshRate?.let { "%.1f".format(it) }
        }
        val orientation = if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            "landscape"
        } else {
            "portrait"
        }
        return JSONObject().apply {
            put("resolution", "${dm.widthPixels}x${dm.heightPixels}")
            put("density_dpi", dm.densityDpi)
            put("density_scale", dm.density)
            put("refresh_rate", refreshRate)
            put("orientation", orientation)
        }
    }

    private fun snapshotRuntimeInfo(context: Context): JSONObject = JSONObject().apply {
        put("session_id", snapshotSessionId)
        put("process_id", Process.myPid())
        put("main_thread", safeStr { android.os.Looper.getMainLooper().thread.name })
        put("current_thread", safeStr { Thread.currentThread().name })
        put("locale", safeStr { Locale.getDefault().toString() })
        put("timezone", safeStr { TimeZone.getDefault().id })
        put("is_24_hour", safeBoolText { DateFormat.is24HourFormat(context) })
        put("session_start_ts", snapshotSessionStartMs)
        put("current_ts", System.currentTimeMillis())
        put("elapsed_realtime_ms", SystemClock.elapsedRealtime())
    }

    private fun snapshotPowerInfo(context: Context): JSONObject {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return JSONObject().apply {
            put("battery_percent", safeInt { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1 })
            put("charging", safeBoolText { bm?.isCharging == true })
            put("power_save_mode", safeBoolText {
                (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode
            })
        }
    }

    private fun snapshotConnectivityInfo(context: Context): JSONObject {
        var transport = "unavailable"
        var connected = false
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            connected = caps != null
            transport = when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
                else -> "other"
            }
        } catch (_: Exception) {}
        return JSONObject().apply {
            put("transport", transport)
            put("connected", connected)
        }
    }

    private fun snapshotScreenLocked(context: Context): Boolean = try {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    } catch (_: Exception) {
        false
    }

    /**
     * Full session snapshot: app, device, hardware, display, runtime, power,
     * connectivity. Safe to call more than once; each call re-reads current
     * values, but this is meant for on-demand capture, not per log line.
     */
    fun captureSessionSnapshot(context: Context): JSONObject {
        val session = JSONObject()
        session.put("session_id", snapshotSessionId)
        session.put("timestamp", System.currentTimeMillis())
        session.put("app", snapshotAppInfo(context))
        session.put("device", snapshotDeviceInfo())
        session.put("hardware", snapshotHardwareInfo(context))
        session.put("display", snapshotDisplayInfo(context))
        session.put("runtime", snapshotRuntimeInfo(context))
        session.put("power", snapshotPowerInfo(context))
        session.put("connectivity", snapshotConnectivityInfo(context))
        session.put("screen_locked", snapshotScreenLocked(context))
        return JSONObject().put("forensic_session", session)
    }

    /**
     * Lightweight dynamic values worth attaching to one specific forensic event
     * instead of the whole session snapshot.
     */
    fun captureEventSnapshot(context: Context): JSONObject {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        try { am?.getMemoryInfo(memInfo) } catch (_: Exception) {}

        return JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("battery_percent", safeInt { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1 })
            put("charging", safeBoolText { bm?.isCharging == true })
            put("network", snapshotConnectivityInfo(context))
            put("screen_locked", snapshotScreenLocked(context))
            put("ram_available_bytes", memInfo.availMem)
        }
    }

    // ==================== HELPERS ====================

    private fun logSection(title: String) {
        val separator = "=".repeat(85)
        val line = "\n$separator\n$title\n$separator\n"
        reportBuilder?.append(line)
        Log.d(TAG_MAIN, title)
    }

    private fun logSectionStart(title: String) {
        val separator = "=".repeat(85)
        val line = "$separator\n$title\n$separator\n"
        reportBuilder?.append(line)
        Log.d(TAG_MAIN, title)
    }

    private fun logSubsection(title: String) {
        val line = "\n--- $title ---\n"
        reportBuilder?.append(line)
        Log.d(TAG_TRACE, title)
    }

    private fun log(key: String, value: String?) {
        val line = "$key: ${value ?: "NULL"}\n"
        reportBuilder?.append(line)
        Log.d(TAG_STATE, "$key: ${value ?: "NULL"}")
    }

    private fun logMultiline(key: String, value: String) {
        val line = "$key:\n$value\n"
        reportBuilder?.append(line)
        Log.d(TAG_STATE, key)
        value.split("\n").take(50).forEach { Log.d(TAG_STATE, it) }
        if (value.split("\n").size > 50) {
            Log.d(TAG_STATE, "... (${value.split("\n").size - 50} more lines)")
        }
    }

    private fun getCurrentTimestamp(): String = dateFormat.format(Date())

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return String.format("%.2f %s", size, units[unitIndex])
    }

    private fun extractUriScheme(uri: String): String {
        return uri.substringBefore("://").takeIf { it.isNotEmpty() } ?: "UNKNOWN"
    }

    private fun extractUriAuthority(uri: String): String {
        val afterScheme = uri.substringAfter("://").takeIf { it.isNotEmpty() } ?: return "UNKNOWN"
        return afterScheme.substringBefore("/").takeIf { it.isNotEmpty() } ?: "UNKNOWN"
    }

    // ==================== MERGED: formerly sequencer/MissionLogger.kt ====================
    // Throttled/deduped structured logger feeding the live UI log overlay
    // (MainActivity.addSequencerLog) and /sdcard/Download/alarm_debug.log.
    // Kept in its own nested namespace (not flattened into the outer object)
    // because logWarning/logError/log here have different signatures than the
    // ringtone-investigation logWarning/logError/log above - flattening would
    // create ambiguous overloads at every call site.
    object Mission {
        private const val TAG = "ChronaLog"
        @Volatile private var correlationId: String? = null
        @Volatile private var lastByComponent: MutableMap<String, Pair<String, Long>> = mutableMapOf()
        @Volatile private var lastByCompPrefix: MutableMap<String, Long> = mutableMapOf()
        @Volatile private var lastByBody: MutableMap<String, Long> = mutableMapOf()
        @Volatile private var suppressedPrefix: MutableMap<String, Int> = mutableMapOf()
        @Volatile private var suppressedBody: MutableMap<String, Int> = mutableMapOf()
        @Volatile private var verboseEnabled: Boolean = false
        @Volatile private var verboseThrottleMs: Long = 1500L
        @Volatile private var timestampEnabled: Boolean = false
        @Volatile private var perComponentThrottleMs: MutableMap<String, Long> = mutableMapOf()
        @Volatile private var globalDedupeMs: Long = 1000L
        private val forensicCounter = AtomicLong(0L)
        private val processSessionId = "${Process.myPid()}-${SystemClock.elapsedRealtime()}"
        @Volatile private var suppressedVerbosePrefixes: MutableSet<String> = mutableSetOf(
            "GET_QUEUE_SIZE",
            "IS_MISSION_RUNNING",
            "QUEUE_STORE_LOAD_EMPTY_VERBOSE"
        )

        fun setCorrelation(id: String?) { correlationId = id }
        fun setVerboseEnabled(enabled: Boolean) { verboseEnabled = enabled }
        fun setVerboseThrottle(windowMs: Long) { verboseThrottleMs = windowMs.coerceAtLeast(0L) }
        fun setTimestampEnabled(enabled: Boolean) { timestampEnabled = enabled }
        fun setComponentThrottle(component: String, windowMs: Long) {
            synchronized(this) { perComponentThrottleMs[component] = windowMs.coerceAtLeast(0L) }
        }
        fun setGlobalDedupeWindow(windowMs: Long) { globalDedupeMs = windowMs.coerceAtLeast(0L) }
        fun suppressVerbosePrefix(prefix: String) { synchronized(this) { suppressedVerbosePrefixes.add(prefix) } }
        fun allowVerbosePrefix(prefix: String) { synchronized(this) { suppressedVerbosePrefixes.remove(prefix) } }

        private fun kv(fields: Map<String, Any?>): String =
            fields.entries.joinToString(" ") { (k, v) -> "$k=${(v ?: "-")}" }

        private fun emit(level: String, component: String, message: String?, fields: Map<String, Any?>) {
            val cid = correlationId
            val comp = component.ifBlank { "App" }
            val msg = message?.takeIf { it.isNotBlank() }
            val body = buildString {
                append("[")
                append(level)
                append("]")
                if (cid != null) {
                    append(" ")
                    append(cid)
                }
                append(" ")
                append(comp)
                if (msg != null) {
                    append(" ")
                    append(msg)
                }
                val kvs = kv(fields)
                if (kvs.isNotBlank()) {
                    append(" ")
                    append(kvs)
                }
            }
            // Optional throttle for noisy components by message prefix (applies to all components)
            val now = System.currentTimeMillis()
            val prefix = run {
                // Verbose global switch
                if (comp == "Verbose" && !verboseEnabled) return
                val p = when {
                    msg != null && ':' in msg -> msg.substringBefore(':')
                    msg != null && ' ' in msg -> msg.substringBefore(' ')
                    else -> msg ?: ""
                }
                if (comp == "Verbose") {
                    synchronized(this) {
                        if (suppressedVerbosePrefixes.contains(p)) return
                    }
                }
                val key = "$comp|$p"
                val window = synchronized(this) { perComponentThrottleMs[comp] } ?: (
                    if (comp == "Verbose") verboseThrottleMs else 0L
                )
                if (window > 0) {
                    synchronized(this) {
                        val prevTs = lastByCompPrefix[key]
                        if (prevTs != null && (now - prevTs) < window) {
                            val cur = suppressedPrefix[key] ?: 0
                            suppressedPrefix[key] = cur + 1
                            return
                        }
                        lastByCompPrefix[key] = now
                    }
                }
                p
            }

            // Deduplicate: if the same component emits the same body within the window, skip
            synchronized(this) {
                val prev = lastByComponent[comp]
                if (prev != null) {
                    val (prevBody, prevTs) = prev
                    if (prevBody == body && (now - prevTs) < globalDedupeMs) {
                        val cur = suppressedBody[body] ?: 0
                        suppressedBody[body] = cur + 1
                        return
                    }
                }
                lastByComponent[comp] = body to now
            }

            // Global body-based dedupe window (covers cross-component duplicates)
            synchronized(this) {
                val prevTs = lastByBody[body]
                if (prevTs != null && (now - prevTs) < globalDedupeMs) {
                    val cur = suppressedBody[body] ?: 0
                    suppressedBody[body] = cur + 1
                    return
                }
                lastByBody[body] = now
            }

            // Build final line. Avoid timestamp here to prevent double time markers in UI overlay.
            val baseLine = if (timestampEnabled) {
                val ts = try { SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(now)) } catch (_: Exception) { "--:--:--.---" }
                "$ts $body"
            } else body
            // Append suppression summaries, if any
            val summary = synchronized(this) {
                val pKey = "$comp|$prefix"
                val s1 = suppressedPrefix.remove(pKey) ?: 0
                val s2 = suppressedBody.remove(body) ?: 0
                val total = s1 + s2
                if (total > 0) " (+$total suppressed)" else ""
            }
            val line = baseLine + summary
            recordEvent("Mission", line)

            // Write to external log file
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))
                val logFile = File("/sdcard/Download/alarm_debug.log")

                // CRITICAL: Clear old log file when basic START_WHEN_ALARM_FIRES logs are detected
                val shouldClearFile = msg?.contains("BASIC_START_WHEN_ALARM_FIRES") == true
                val writer = FileWriter(logFile, !shouldClearFile) // Don't append if clearing

                if (shouldClearFile) {
                    writer.write("=== LOG CLEARED FOR NEW ALARM SESSION ===\n")
                }

                writer.append("[$timestamp] [SEQUENCER] $line\n")
                writer.close()
            } catch (e: Exception) {
                // Silently fail if we can't write to external storage
            }

            try {
                MainActivity.getInstance()?.let { activity ->
                    activity.runOnUiThread {
                        try { activity.addSequencerLog(line) } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) { }
        }

        fun d(component: String, message: String? = null, fields: Map<String, Any?> = emptyMap()) =
            emit("D", component, message, fields)
        fun w(component: String, message: String? = null, fields: Map<String, Any?> = emptyMap()) =
            emit("W", component, message, fields)
        fun e(component: String, message: String? = null, fields: Map<String, Any?> = emptyMap()) =
            emit("E", component, message, fields)

        // Backward-compat convenience
        fun log(message: String) = d("Sequencer", message)
        fun logWarning(message: String) = w("Sequencer", message)
        fun logError(message: String, throwable: Throwable? = null) = e("Sequencer", message, mapOf("err" to (throwable?.message ?: "")))
        fun logVerbose(message: String) = d("Verbose", message)

        fun logDpsForensic(
            component: String,
            event: String,
            snapshot: DpsMissionMirror.Snapshot?,
            expectedMissionId: String?,
            runtimeMissionId: String?,
            previousState: String?,
            source: String,
            queueReconstructed: Boolean,
            nullMissionObserved: Boolean,
            stalePendingIntent: Boolean,
            replayingOldData: Boolean
        ) = d(
            component,
            "DPS_FORENSIC",
            mapOf(
                "event" to event,
                "eventCounter" to forensicCounter.incrementAndGet(),
                "ts" to System.currentTimeMillis(),
                "pidSession" to processSessionId,
                "storageMode" to (snapshot?.sourceMode ?: "unknown"),
                "sequenceId" to snapshot?.sequenceId,
                "currentMissionId" to snapshot?.activeMissionId,
                "expectedMissionId" to expectedMissionId,
                "runtimeMissionId" to runtimeMissionId,
                "pointerIndex" to snapshot?.activeIndex,
                "orderedIds" to snapshot?.orderedMissionIds?.joinToString(","),
                "completedIds" to snapshot?.completedMissionIds?.joinToString(","),
                "pendingIds" to snapshot?.pendingMissionIds?.joinToString(","),
                "state" to snapshot?.state,
                "previousState" to previousState,
                "source" to source,
                "queueReconstructed" to queueReconstructed,
                "snapshotMatchesRuntime" to (snapshot?.activeMissionId == runtimeMissionId),
                "completionMatchesSnapshot" to (expectedMissionId == runtimeMissionId),
                "nullMissionObserved" to nullMissionObserved,
                "stalePendingIntent" to stalePendingIntent,
                "replayingOldData" to replayingOldData
            )
        )
    }

    // ==================== MERGED: formerly logging/chronalogger.kt (ChronaLogger) ====================
    // CPS/invariant safety enforcement - re-entrancy detection, CPS-before-unlock
    // access gate, snapshot replay/stuck-mission detection. Kept in its own
    // nested namespace for the same overload-collision reason as Mission above.
    object Chrona {

        // UNLOCK GATE - NON-NEGOTIABLE
        private fun isCpsAccessAllowed(context: Context): Boolean {
            return try {
                val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
                userManager.isUserUnlocked
            } catch (e: Exception) {
                // If we can't determine unlock state, DENY CPS access
                false
            }
        }

        private const val DIR = "chrona"
        private const val FILE = "chrona_forensic.log"

        private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        private val seq = AtomicLong(0)

        // HARD STATE TRACKERS
        private val snapshotHistory = ConcurrentHashMap<Int, MutableSet<Int>>()
        private val advancementAttempts = ConcurrentHashMap<String, Int>()
        private val lastKnownMission = ConcurrentHashMap<Int, String?>()
        private val restoreSources = ConcurrentHashMap<Int, MutableSet<String>>()
        private val entryPoints = ConcurrentHashMap<String, Int>()

        private fun getSafeLogFile(context: Context): File {
            val base = if (
                (context.getSystemService(Context.USER_SERVICE) as UserManager)
                    .isUserUnlocked
            ) {
                context.filesDir   // CPS
            } else {
                context.createDeviceProtectedStorageContext().filesDir // DPS
            }

            val dir = File(base, "chrona")
            if (!dir.exists()) dir.mkdirs()
            return File(dir, "chrona.log")
        }

        private fun logInternal(context: Context, tag: String, msg: String) {
            // SYNCHRONOUS LOGGING: Write to storage with immediate flush and proper UTF-8 encoding
            try {
                val logFile = getSafeLogFile(context)
                val logEntry = "${time.format(Date())} | " +
                        "SEQ=${seq.incrementAndGet()} | " +
                        "THREAD=${Thread.currentThread().name} | " +
                        "$tag | $msg\n"
                recordEvent("Chrona", "$tag | $msg")

                // SYNCHRONOUS FileOutputStream with UTF-8 encoding and immediate flush
                val fos = FileOutputStream(logFile, true) // append mode
                fos.write(logEntry.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.close()
            } catch (_: Throwable) {
                // Fallback: Try to write to emergency location
                try {
                    val emergencyFile = File("/data/local/tmp/chrona_emergency.log")
                    val emergencyFos = FileOutputStream(emergencyFile, true)
                    emergencyFos.write("EMERGENCY: $tag | $msg\n".toByteArray(Charsets.UTF_8))
                    emergencyFos.flush()
                    emergencyFos.close()
                } catch (_: Throwable) {
                    // Last resort: System log
                    System.err.println("CHRONALOGGER_EMERGENCY: $tag | $msg")
                }
            }
        }

        enum class LogType {
            DEBUG, INFO, WARN, ERROR, FATAL
        }

        fun log(context: Context, logType: LogType, tag: String, msg: String, e: Throwable? = null) {
            val fullMsg = if (e != null) "$msg \n Exception: ${e.stackTraceToString()}" else msg
            logInternal(context, "${logType.name}/$tag", fullMsg)
        }

        fun log(context: Context, tag: String, msg: String) {
            log(context, LogType.DEBUG, tag, msg)
        }

        // =====================================================
        // 🔴 ENTRY POINT GUARD (RE-ENTRANCY DETECTOR)
        // =====================================================
        fun enter(context: Context, alarmId: Int, point: String) {
            val key = "$alarmId:$point"
            val count = (entryPoints[key] ?: 0) + 1
            entryPoints[key] = count

            log(context, "ENTER", "alarmId=$alarmId point=$point count=$count")

            if (count > 1) {
                fatal(context, alarmId, "REENTRANT_CALL_DETECTED at=$point")
            }
        }

        // =====================================================
        // 🔴 CPS ACCESS GATE (NON-NEGOTIABLE)
        // =====================================================
        fun logCpsOnly(context: Context, alarmId: Int, tag: String, msg: String): Boolean {
            if (!isCpsAccessAllowed(context)) {
                // 🚫 CPS ACCESS DENIED - THIS PREVENTS UNINSTALL
                cpsViolation(alarmId, "CPS_ACCESS_BEFORE_UNLOCK", "logCpsOnly", context, "DENIED")
                return false
            }
            log(context, tag, msg)
            return true
        }

        // =====================================================
        // 🔴 SNAPSHOT STATE (HASH + HISTORY) - CPS ONLY
        // =====================================================
        fun snapshot(
            context: Context,
            alarmId: Int,
            index: Int,
            missions: List<String>,
            current: String?,
            completed: List<String>
        ): Boolean {
            if (!isCpsAccessAllowed(context)) {
                // 🚫 CPS ACCESS DENIED - THIS PREVENTS UNINSTALL
                cpsViolation(alarmId, "SNAPSHOT_BEFORE_UNLOCK", "snapshot", context, "DENIED")
                return false
            }

            val hash = Objects.hash(index, missions, current, completed)
            val set = snapshotHistory.getOrPut(alarmId) { mutableSetOf() }

            log(
                context,
                "SNAPSHOT",
                "alarmId=$alarmId index=$index current=$current " +
                "missions=$missions completed=$completed hash=$hash"
            )

            if (!set.add(hash)) {
                fatal(context, alarmId, "SNAPSHOT_REPLAY_NO_PROGRESS hash=$hash")
            }

            lastKnownMission[alarmId]?.let {
                if (it == current) {
                    fatal(context, alarmId, "MISSION_STUCK mission=$current")
                }
            }

            lastKnownMission[alarmId] = current
            return true
        }

        // =====================================================
        // INVARIANT ENFORCER (NON-NEGOTIABLE)
        // =====================================================
        fun invariant(
            context: Context,
            alarmId: Int,
            index: Int,
            missions: List<String>,
            current: String?
        ) {
            val expected = missions.getOrNull(index)

            log(
                 context,
                 "INVARIANT",
                 "alarmId=$alarmId index=$index expected=$expected actual=$current"
             )

             if (expected != current) {
                fatal(context, alarmId, "INVARIANT_BROKEN expected=$expected actual=$current")
            }
        }

        // =====================================================
        // 🔴 COMPLETION VALIDATOR
        // =====================================================
        fun completion(
            context: Context,
            alarmId: Int,
            completionId: String,
            snapshotCurrent: String?
        ) {
            log(
                context,
                "COMPLETION",
                "alarmId=$alarmId completion=$completionId snapshotCurrent=$snapshotCurrent"
            )

            if (completionId != snapshotCurrent) {
                fatal(context, alarmId, "INVALID_COMPLETION completion=$completionId snapshot=$snapshotCurrent")
            }
        }

        // =====================================================
        // 🔴 ADVANCEMENT LOOP KILLER
        // =====================================================
        fun advanceAttempt(
            context: Context,
            alarmId: Int,
            source: String,
            allowed: Boolean,
            reason: String
        ) {
            val key = "$alarmId:$source:$reason"
            val count = (advancementAttempts[key] ?: 0) + 1
            advancementAttempts[key] = count

            log(
                context,
                "ADVANCE_ATTEMPT",
                "alarmId=$alarmId source=$source allowed=$allowed reason=$reason count=$count"
            )

            if (count > 2) {
                fatal(context, alarmId, "ADVANCEMENT_LOOP source=$source reason=$reason")
            }
        }

        fun advanceResult(
            context: Context,
            alarmId: Int,
            from: Int,
            to: Int,
            mission: String?
        ) {
            log(
                context,
                "ADVANCE_RESULT",
                "alarmId=$alarmId from=$from to=$to mission=$mission"
            )
        }

        // =====================================================
        // 🔴 RESTORE COLLISION ABSOLUTE GUARD - CPS ONLY
        // =====================================================
        fun restore(
            context: Context,
            alarmId: Int,
            source: String
        ): Boolean {
            if (!isCpsAccessAllowed(context)) {
                // 🚫 CPS ACCESS DENIED - THIS PREVENTS UNINSTALL
                cpsViolation(alarmId, "RESTORE_BEFORE_UNLOCK", "restore", context, "DENIED")
                return false
            }

            val set = restoreSources.getOrPut(alarmId) { mutableSetOf() }
            set.add(source)

            log(
                context,
                "RESTORE",
                "alarmId=$alarmId source=$source allSources=$set"
            )

            if (set.size > 1) {
                fatal(context, alarmId, "MULTIPLE_RESTORE_SOURCES sources=$set")
            }
            return true
        }

        // =====================================================
        // 🔴 TERMINATION ENFORCER
        // =====================================================
        fun terminationCheck(
            context: Context,
            alarmId: Int,
            index: Int,
            total: Int
        ) {
            log(
                 context,
                 "TERMINATION_CHECK",
                 "alarmId=$alarmId index=$index total=$total"
             )

             if (index > total) {
                fatal(context, alarmId, "INDEX_OUT_OF_BOUNDS index=$index total=$total")
            }
        }

        fun terminated(
            context: Context,
            alarmId: Int,
            source: String
        ) {
            log(
                context,
                "TERMINATED",
                "alarmId=$alarmId source=$source"
            )
        }

        // =====================================================
        // 🔴 CPS VIOLATION DETECTOR
        // =====================================================
        fun cpsViolation(
            alarmId: Int,
            violationType: String,
            method: String,
            context: Context,
            storageType: String = "UNKNOWN"
        ) {
            log(
                context,
                "🚨CPS_VIOLATION",
                "alarmId=$alarmId type=$violationType method=$method context=$context storage=$storageType ANDROID_WILL_UNINSTALL_APP"
            )
            fatal(
                context,
                alarmId,
                "CPS_VIOLATION: $violationType at $method - $context - $storageType"
            )
            try {
                val logFile = getSafeLogFile(context)
                val content = logFile.readText()
                val fos = FileOutputStream(logFile, false)
                fos.write(content.toByteArray())
            } catch (_: Throwable) {}
        }

        // =====================================================
        // 🔴 CRITICAL LOG SHUTDOWN
        // =====================================================
        fun shutdown(context: Context) {
            // SYNCHRONOUS: Final flush before app termination
            try {
                val logFile = getSafeLogFile(context)
                val content = logFile.readText()
                val fos = FileOutputStream(logFile, false)
                fos.write(content.toByteArray())
                fos.flush()
                fos.close()
            } catch (_: Throwable) {}
        }

        // =====================================================
        // 🔴 FATAL — NO RECOVERY
        // =====================================================
        fun fatal(
             context: Context,
             alarmId: Int,
             msg: String
         ) {
             log(
                 context,
                 "🔥FATAL",
                 "alarmId=$alarmId $msg"
             )
         }
    }

    // ==================== MERGED: formerly DebugLog.kt ====================
    object DebugLog {
        private const val TAG = "DirectBootAlarm"

        fun log(phase: String, event: String, message: String? = null) {
            val json = JSONObject().apply {
                put("phase", phase)
                put("event", event)
                put("ts", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
                message?.let { put("message", it) }
            }
            Log.d(TAG, json.toString())
            recordEvent("Boot", "$phase/$event${message?.let { " - $it" } ?: ""}")
        }
    }
}
