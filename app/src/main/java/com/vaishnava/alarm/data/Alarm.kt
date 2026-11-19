package com.vaishnava.alarm.data

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import com.vaishnava.alarm.WakeCheckCycleState
import com.vaishnava.alarm.WakeCheckCycleStore
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val isEnabled: Boolean,
    val ringtoneUri: Uri?,
    val days: List<Int>? = emptyList(),
    val alarmTime: Long = 0L,
    val isHidden: Boolean = false,
    val isProtected: Boolean = false,
    val missionType: String? = null,
    val missionPassword: String? = null,
    val wakeCheckEnabled: Boolean = false,
    val wakeCheckMinutes: Int = 5,
    val repeatDaily: Boolean = false,
    val selectedDates: List<Long> = emptyList(),
    val isShift: Boolean = false,
    val timeChangeUsed: Boolean = false,
    val lastModifiedTime: Long = System.currentTimeMillis(),
    val createdTime: Long = System.currentTimeMillis(),
    val enrolledFaceEmbedding: FloatArray? = null,
    val requiresFaceAuth: Boolean = false,
    val enableGradualVolume: Boolean = false,
    val enableAudioBoost: Boolean = false,
    val preventPowerOff: Boolean = false,
    val enableSnooze: Boolean = false
)

fun Alarm.isOneShot(): Boolean {
    val hasWeeklyDays = !days.isNullOrEmpty() || repeatDaily
    if (hasWeeklyDays) return false

    if (selectedDates.isNotEmpty()) {
        val now = System.currentTimeMillis()
        val hasFutureDate = selectedDates.any { dateMillis ->
            val cal = Calendar.getInstance().apply {
                timeInMillis = dateMillis
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis > now
        }
        if (hasFutureDate) return false
    }

    return true
}

fun resolveRingtoneTitle(context: Context, uri: Uri?): String {
    if (uri == null) return "🎵 Default Ringtone"

    return try {
        val uriString = uri.toString()
        if (uriString.contains("alarm_alert") || uriString.contains("settings/system") || uriString.contains("default")) {
            return "🎵 Default Ringtone"
        }
        val isGlassy = uriString.contains("glassy_bell")
        val isPyaro = uriString.contains("pyaro_vrindavan")
        val isHareKrishna = uriString.contains("harekrishna_chant")
        val isSilenceNoSound = uriString.contains("silence_no_sound")

        when {
            isGlassy -> "🎵 glassy_bell"
            isPyaro  -> "🎵 pyaro_vrindavan"
            isHareKrishna -> "🎵 harekrishna_chant"
            isSilenceNoSound -> "🔇 Silence No Sound"
            uriString.startsWith("android.resource://") && uriString.contains("/raw/") -> {
                val segments = uriString.split("/")
                if (segments.size > 3) "🎵 ${segments[3]}" else "🎵 Custom Ringtone"
            }
            uriString.startsWith("android.resource://") -> {
                val segments = uriString.split("/")
                if (segments.size > 3) {
                    val resId = segments[3].toIntOrNull()
                    if (resId != null) {
                        try {
                            context.resources.getResourceEntryName(resId)
                        } catch (_: Exception) {
                            "🎵 Custom Ringtone"
                        }
                    } else {
                        "🎵 Custom Ringtone"
                    }
                } else {
                    "🎵 Custom Ringtone"
                }
            }
            else -> {
                try {
                    val ringtone = RingtoneManager.getRingtone(context, uri)
                    ringtone?.getTitle(context) ?: "🎵 System Ringtone"
                } catch (e: Exception) {
                    "🎵 System Ringtone"
                }
            }
        }
    } catch (_: Exception) {
        val uriString = uri.toString()
        when {
            uriString.contains("glassy_bell")      -> "🎵 glassy_bell"
            uriString.contains("pyaro_vrindavan")  -> "🎵 pyaro_vrindavan"
            uriString.contains("harekrishna_chant") -> "🎵 harekrishna_chant"
            else                                   -> "🎵 Custom Ringtone"
        }
    }
}

// ---------- WakeCheckStore: authoritative ack + in-memory mirror ----------
// Centralized per-alarm wake-up-check acknowledgement store.
object WakeCheckStore {
    private val ackMap = ConcurrentHashMap<Int, Long>()

    private const val PREFS_NAME = "alarm_dps"
    private const val ACK_TS_KEY_PREFIX = "wakecheck_ack_ts_"
    private const val FINALIZED_KEY_PREFIX = "wakecheck_finalized_"
    private const val PENDING_KEY_PREFIX = "wakecheck_pending_"
    private const val GATE_ACTIVE_KEY_PREFIX = "wakecheck_gate_active_"

    fun isAcked(context: Context, alarmId: Int, recentWindowMs: Long = 10 * 60_000L): Boolean {
        val now = System.currentTimeMillis()
        val inMem = ackMap[alarmId] ?: 0L
        if (inMem > 0L && now - inMem < recentWindowMs) return true

        return runCatching {
            val dps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
                context.createDeviceProtectedStorageContext() else context
            val prefs = dps.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val ts = prefs.getLong("$ACK_TS_KEY_PREFIX$alarmId", 0L)
            if (ts > 0L) {
                ackMap[alarmId] = ts
                now - ts < recentWindowMs
            } else false
        }.getOrDefault(false)
    }

    fun markAck(context: Context, alarmId: Int, ts: Long = System.currentTimeMillis()) {
        try {
            ackMap[alarmId] = ts
            val dps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
                context.createDeviceProtectedStorageContext() else context
            val prefs = dps.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putLong("$ACK_TS_KEY_PREFIX$alarmId", ts)
                .putBoolean("$FINALIZED_KEY_PREFIX$alarmId", true)
                .putBoolean("$PENDING_KEY_PREFIX$alarmId", false)
                .putBoolean("$GATE_ACTIVE_KEY_PREFIX$alarmId", false)
                .commit()
        } catch (_: Exception) {}
    }

    fun clearAck(context: Context, alarmId: Int) {
        try {
            ackMap.remove(alarmId)
            val dps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
                context.createDeviceProtectedStorageContext() else context
            val prefs = dps.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putLong("$ACK_TS_KEY_PREFIX$alarmId", 0L)
                .putBoolean("$FINALIZED_KEY_PREFIX$alarmId", false)
                .commit()
        } catch (_: Exception) {}
    }

    fun markPending(context: Context, alarmId: Int, pending: Boolean) {
        try {
            val dps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
                context.createDeviceProtectedStorageContext() else context
            val prefs = dps.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean("$PENDING_KEY_PREFIX$alarmId", pending).commit()
        } catch (_: Exception) {}
    }

    fun setGateActive(context: Context, alarmId: Int, active: Boolean) {
        try {
            val dps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
                context.createDeviceProtectedStorageContext() else context
            val prefs = dps.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean("$GATE_ACTIVE_KEY_PREFIX$alarmId", active).commit()
        } catch (_: Exception) {}
    }

    fun readAckTs(context: Context, alarmId: Int): Long {
        return try {
            val dps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
                context.createDeviceProtectedStorageContext() else context
            val prefs = dps.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val ts = prefs.getLong("$ACK_TS_KEY_PREFIX$alarmId", 0L)
            if (ts > 0L) ackMap[alarmId] = ts
            ts
        } catch (_: Exception) { 0L }
    }

    fun isLoopActive(context: Context, alarm: Alarm?): Boolean {
        val currentAlarm = alarm ?: return false
        if (!currentAlarm.wakeCheckEnabled) return false
        if (isAcked(context, currentAlarm.id, 10 * 60_000L)) return false

        val cycle = WakeCheckCycleStore.read(context, currentAlarm.id) ?: return false
        return cycle.cycleId > 0L &&
            cycle.state != WakeCheckCycleState.WAKEUP_CHECK_PASSED &&
            cycle.state != WakeCheckCycleState.ALARM_CANCELLED_OR_INVALID
    }
}
