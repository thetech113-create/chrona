package com.vaishnava.alarm.shift

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson

class ShiftRotationStore(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val dpsContext: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        appContext.createDeviceProtectedStorageContext() ?: appContext
    } else {
        appContext
    }
    private val storageContext: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isUserUnlocked(appContext)) {
        appContext
    } else {
        dpsContext
    }
    private val gson = Gson()

    fun read(): ShiftRotationConfig? {
        val primaryPrefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dpsPrefs = dpsContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val primary = primaryPrefs.getString(KEY_CONFIG, null)
        val fallback = dpsPrefs.getString(KEY_CONFIG, null)

        if (storageContext != dpsContext) {
            val primaryUpdatedAt = primaryPrefs.getLong(KEY_UPDATED_AT, 0L)
            val dpsUpdatedAt = dpsPrefs.getLong(KEY_UPDATED_AT, 0L)
            if (!fallback.isNullOrBlank() && dpsUpdatedAt > primaryUpdatedAt) {
                val merged = primaryPrefs.edit()
                    .putString(KEY_CONFIG, fallback)
                    .putLong(KEY_UPDATED_AT, dpsUpdatedAt)
                    .commit()
                Log.d(TAG, "SHIFT_STORE_MERGE_DPS_TO_CPS: success=$merged updatedAt=$dpsUpdatedAt")
                return decode(fallback)
            }
        }
        return decode(primary ?: fallback)
    }

    fun save(config: ShiftRotationConfig): Boolean {
        val json = gson.toJson(config)
        val updatedAt = System.currentTimeMillis()
        val primarySaved = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIG, json)
            .putLong(KEY_UPDATED_AT, updatedAt)
            .commit()
            
        var dpsSaved = true
        if (storageContext != dpsContext) {
            dpsSaved = dpsContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CONFIG, json)
                .putLong(KEY_UPDATED_AT, updatedAt)
                .commit()
        }
        Log.d(TAG, "SHIFT_STORE_SAVE: enabled=${config.enabled} primary=$primarySaved dps=$dpsSaved pending=${config.pendingReassignment != null}")
        return primarySaved && dpsSaved
    }

    fun clear(): Boolean {
        val primarySaved = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CONFIG)
            .remove(KEY_UPDATED_AT)
            .commit()
            
        var dpsSaved = true
        if (storageContext != dpsContext) {
            dpsSaved = dpsContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_CONFIG)
                .remove(KEY_UPDATED_AT)
                .commit()
        }
        return primarySaved && dpsSaved
    }

    private fun decode(json: String?): ShiftRotationConfig? {
        if (json.isNullOrBlank()) return null
        return runCatching { gson.fromJson(json, ShiftRotationConfig::class.java) }
            .onFailure { Log.e(TAG, "SHIFT_STORE_DECODE_FAILED: ${it.message}") }
            .getOrNull()
    }

    private fun isUserUnlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        return try {
            val userManager = context.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
            userManager?.isUserUnlocked == true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "ShiftRotationStore"
        private const val PREFS_NAME = "shift_rotation_prefs"
        private const val KEY_CONFIG = "shift_rotation_config"
        private const val KEY_UPDATED_AT = "shift_rotation_updated_at"
    }
}
