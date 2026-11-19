package com.vaishnava.alarm.logging

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.UserManager
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends Chrona's logs to the private GitHub repository [REPOSITORY], so they can be read from anywhere: no USB, the
 * phone does not have to be near the PC.
 *
 * Chrona writes its logs to several places, and most of the sequencer's lines never reach logcat, so every one of them
 * is read: the logcat recorder ([AppLogRecorder]), chrona.log (normal and Direct Boot storage) and the alarm_debug.log
 * files. Only the lines added since the last upload are sent, passwords hidden ([maskSecrets]); one upload is one file
 * logs/<date>/<time>_<model>.txt. Runs every 15 minutes while Chrona's process is alive and right after an alarm
 * stops. The token is typed in on the phone (Device & Runtime Info), never built into the public APK.
 */
object RemoteLogUploader {
    private const val TAG = "RemoteLogUploader"
    const val REPOSITORY = "thetech113-create/chrona-logs"
    private const val PREFS = "remote_logs"
    private const val KEY_TOKEN = "token"
    private const val KEY_STATUS = "status"
    private const val OFFSET_PREFIX = "offset:"
    private const val INTERVAL_MS = 15 * 60 * 1000L
    private const val FIRST_DELAY_MS = 60 * 1000L
    // The newest part of a file is sent when more than this was added since the last upload.
    private const val MAX_BYTES_PER_FILE = 300_000

    private val started = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "remote-log-uploader").apply { isDaemon = true } }
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    /** Starts the 15-minute uploads once per process; later calls do nothing. */
    fun start(context: Context) {
        val appContext = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        Thread({
            runCatching { Thread.sleep(FIRST_DELAY_MS) }
            while (true) {
                uploadSoon(appContext, "periodic")
                runCatching { Thread.sleep(INTERVAL_MS) }
            }
        }, "remote-log-timer").apply { isDaemon = true }.start()
    }

    /** Uploads the new lines in the background (after an alarm, or when asked on the screen). */
    fun uploadSoon(context: Context, reason: String) {
        val appContext = context.applicationContext
        executor.execute { runCatching { upload(appContext, reason) }.onFailure { Log.w(TAG, "upload failed", it) } }
    }

    fun token(context: Context): String =
        if (isUnlocked(context)) prefs(context).getString(KEY_TOKEN, "").orEmpty() else ""

    fun saveToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    fun removeToken(context: Context) {
        prefs(context).edit().remove(KEY_TOKEN).apply()
    }

    fun lastStatus(context: Context): String =
        _status.value.ifEmpty { if (isUnlocked(context)) prefs(context).getString(KEY_STATUS, "").orEmpty() else "" }

    private fun upload(context: Context, reason: String) {
        // The token and offsets are in normal storage, which is closed until the phone is unlocked once after a boot.
        if (!isUnlocked(context)) return
        val prefs = prefs(context)
        val token = prefs.getString(KEY_TOKEN, "").orEmpty()
        if (token.isBlank()) return

        val sources = sourceFiles(context)
        val offsets = sources.associate { it.absolutePath to prefs.getLong(OFFSET_PREFIX + it.absolutePath, -1L) }.toMutableMap()
        // The recorder renames its full file to the old one: the old file then holds what was not sent yet.
        AppLogRecorder.files().let { recorded ->
            val current = recorded.lastOrNull()
            val old = recorded.firstOrNull()?.takeIf { recorded.size == 2 }
            val sentOfCurrent = current?.let { offsets[it.absolutePath] } ?: -1L
            if (current != null && old != null && current.length() < sentOfCurrent) {
                offsets[old.absolutePath] = sentOfCurrent
                offsets[current.absolutePath] = 0L
            }
        }

        val body = StringBuilder()
        val newOffsets = mutableMapOf<String, Long>()
        for (file in sources) {
            val length = file.length()
            var from = offsets[file.absolutePath] ?: -1L
            if (from < 0 || from > length) from = 0L // never sent, or the file was emptied
            if (length == from) continue
            val skipped = maxOf(0L, length - from - MAX_BYTES_PER_FILE)
            val text = readRange(file, from + skipped, length, startsMidLine = skipped > 0) ?: continue
            body.append("===== ").append(file.absolutePath).append(" (bytes ").append(from + skipped).append('-').append(length)
            if (skipped > 0) body.append(", ").append(skipped).append(" older bytes not sent")
            body.append(") =====\n").append(maskSecrets(text)).append('\n')
            newOffsets[file.absolutePath] = length
        }
        if (body.isEmpty()) {
            setStatus(context, "Nothing new to upload (${stamp()})")
            return
        }

        val now = Date()
        val header = "Chrona logs, reason=$reason, uploaded ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(now)}\n" +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
            "App: ${appVersion(context)}\n\n"
        val model = Build.MODEL.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val path = "logs/${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)}/" +
            "${SimpleDateFormat("HHmmss", Locale.US).format(now)}_${model}_$reason.txt"
        val json = JSONObject()
            .put("message", "Chrona logs $reason ${model}")
            .put("content", Base64.encodeToString((header + body).toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPOSITORY/contents/$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Chrona-Android-Logs")
            .put(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val code = runCatching { http.newCall(request).execute().use { it.code } }
            .getOrElse { setStatus(context, "Upload failed: ${it.message ?: "network error"} (${stamp()})"); return }
        if (code !in 200..299) {
            val why = when (code) {
                401 -> "the token is wrong or expired"
                403, 404 -> "the token has no write access to $REPOSITORY"
                else -> "GitHub returned HTTP $code"
            }
            setStatus(context, "Upload failed: $why (${stamp()})")
            return
        }
        // Only lines that GitHub accepted count as sent.
        val editor = prefs.edit()
        newOffsets.forEach { (filePath, offset) -> editor.putLong(OFFSET_PREFIX + filePath, offset) }
        editor.apply()
        setStatus(context, "Uploaded ${body.length / 1024} KB at ${stamp()}")
    }

    /** Every place Chrona writes log lines, the ones that exist. */
    private fun sourceFiles(context: Context): List<File> {
        val files = mutableListOf<File>()
        files += AppLogRecorder.files()
        val dps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.createDeviceProtectedStorageContext() else context
        runCatching { files += File(context.filesDir, "chrona/chrona.log") }
        runCatching { files += File(dps.filesDir, "chrona/chrona.log") }
        runCatching { files += File(context.filesDir, "alarm_debug.log") }
        runCatching { context.getExternalFilesDir(null)?.let { files += File(it, "alarm_debug.log") } }
        @Suppress("DEPRECATION")
        runCatching { files += File(Environment.getExternalStorageDirectory(), "Download/alarm_debug.log") }
        return files.distinctBy { it.absolutePath }.filter { runCatching { it.isFile }.getOrDefault(false) }
    }

    private fun readRange(file: File, from: Long, to: Long, startsMidLine: Boolean): String? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            val bytes = ByteArray((to - from).toInt())
            raf.readFully(bytes)
            val text = String(bytes, Charsets.UTF_8)
            if (!startsMidLine) text else text.substring(text.indexOf('\n') + 1)
        }
    }.getOrNull()

    private fun setStatus(context: Context, text: String) {
        _status.value = text
        prefs(context).edit().putString(KEY_STATUS, text).apply()
        Log.i(TAG, text)
    }

    private fun stamp(): String = SimpleDateFormat("dd MMM HH:mm", Locale.US).format(Date())

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        "${info.versionName} ($code)"
    }.getOrDefault("unknown")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isUnlocked(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
        runCatching { (context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked }.getOrDefault(false)
}

private val passwordValue = Regex("""(?i)(\b\w*pass(?:word)?\w*\b[^=:\n]{0,30}?[=:]\s*)('[^']*'|"[^"]*"|[^\s,;)\]}]+)""")
private val typedValue = Regex("""(?i)(\b(?:input|keystroke)\s*[=:]\s*)('[^']*'|"[^"]*"|[^\s,;)\]}]+)""")
private val shortsValue = Regex("""(?i)(from Shorts:\s*)([^)\s]+)""")
private val keptValues = setOf("true", "false", "null")

/**
 * The log text with alarm passwords hidden: the value after any "...password...:" or "=", typed input and keystrokes,
 * and Chrona's built-in default password. true/false/null stay, so "hasPassword=true" still reads.
 */
internal fun maskSecrets(text: String): String {
    fun hide(match: MatchResult): String {
        val value = match.groupValues[2]
        return if (value.trim('\'', '"').lowercase() in keptValues) match.value else match.groupValues[1] + "***"
    }
    return text
        .replace(passwordValue, ::hide)
        .replace(typedValue, ::hide)
        .replace(shortsValue, ::hide)
        .replace("IfYouWantYouCanSleep", "***")
}
