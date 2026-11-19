package com.vaishnava.alarm

import android.content.Context
import android.net.Uri

object RingtonePreferences {
    private const val PREFS_NAME = "ringtone_preferences"
    private const val LAST_RINGTONE_URI_KEY = "last_ringtone_uri"

    fun saveLastRingtone(context: Context, uri: Uri?) {
        try {
            val dps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
                context.createDeviceProtectedStorageContext() else context
            val prefs = dps.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val uriString = uri?.toString()
            prefs.edit()
                .putString(LAST_RINGTONE_URI_KEY, uriString)
                .apply()
        } catch (_: Exception) {}
    }

    fun getLastRingtone(context: Context): Uri? {
        return try {
            val dps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
                context.createDeviceProtectedStorageContext() else context
            val prefs = dps.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val uriString = prefs.getString(LAST_RINGTONE_URI_KEY, null)
            if (uriString != null) Uri.parse(uriString) else null
        } catch (_: Exception) {
            null
        }
    }
}
