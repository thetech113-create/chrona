package com.vaishnava.alarm.sequencer

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Face-verification mission.
 *
 * Deliberately keeps its OWN enrolled face, separate from the alarm's `requiresFaceAuth`
 * protection. Those are different features - protection guards editing and deleting an
 * alarm, this mission guards dismissing a ringing one - and tying them together would force
 * protection on whenever the mission is chosen, making that toggle meaningless.
 *
 * Like Tap and Speech, this mission is user-paced and must therefore be listed in
 * MissionSequencer.startTimeout's skip-list, or the generic 30s timeout auto-fails it.
 */
object FaceMissionSupport {
    const val MISSION_ID = "face"
    private const val TAG = "FaceMissionSupport"
    private const val PREFS_NAME = "face_mission_prefs"
    private const val EMBEDDING_KEY = "face_embedding_alarm_"
    private const val PENDING_KEY = "face_pending_embedding"

    private fun prefs(context: Context) = run {
        val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext() ?: context
        } else context
        storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun encode(embedding: FloatArray): String =
        embedding.joinToString(",")

    private fun decode(raw: String?): FloatArray? {
        if (raw.isNullOrBlank()) return null
        return try {
            raw.split(",")
                .mapNotNull { it.trim().toFloatOrNull() }
                .toFloatArray()
                .takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** Holds the face enrolled in the add-alarm dialog until an alarm id exists. */
    fun savePendingEmbedding(context: Context, embedding: FloatArray) {
        try {
            prefs(context).edit().putString(PENDING_KEY, encode(embedding)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stage face: ${e.message}")
        }
    }

    fun hasPendingEmbedding(context: Context): Boolean =
        getPendingEmbedding(context) != null

    /**
     * The face to verify against in PREVIEW only, staged by the add-alarm dialog before any
     * alarm id exists. Real alarms never call this; they read their own committed embedding.
     *
     * Deliberately has no fallback to an earlier enrolment. Preview must reflect what THIS
     * alarm will do - passing on a face left over from a different alarm would hide a
     * missing enrolment until the alarm actually rang.
     */
    fun getPendingEmbedding(context: Context): FloatArray? =
        decode(runCatching { prefs(context).getString(PENDING_KEY, null) }.getOrNull())

    fun clearPendingEmbedding(context: Context) {
        runCatching { prefs(context).edit().remove(PENDING_KEY).apply() }
    }

    fun commitPendingEmbedding(context: Context, alarmId: Int) {
        try {
            val store = prefs(context)
            val pending = store.getString(PENDING_KEY, null)
            if (!pending.isNullOrBlank()) {
                store.edit()
                    .putString("$EMBEDDING_KEY$alarmId", pending)
                    .remove(PENDING_KEY)
                    .apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to commit face for alarm $alarmId: ${e.message}")
        }
    }

    /**
     * The face enrolled for this mission on this alarm, or null if there is none.
     * Null must never be treated as a pass - there is nothing to verify against.
     */
    fun getMissionEmbedding(context: Context, alarmId: Int): FloatArray? =
        decode(runCatching { prefs(context).getString("$EMBEDDING_KEY$alarmId", null) }.getOrNull())
}
