package com.vaishnava.alarm

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder

class AlarmDataService : Service() {

    companion object {
        const val PREFS_NAME = "alarm_data_cache"
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            createDeviceProtectedStorageContext().getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        } else {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "CACHE_ALARM_DATA" -> {
                val alarmId = intent.getIntExtra("alarm_id", -1)
                val missionType = intent.getStringExtra("mission_type") ?: ""
                val missionPassword = intent.getStringExtra("mission_password") ?: ""
                prefs.edit()
                    .putString("mission_type_$alarmId", missionType)
                    .putString("mission_password_$alarmId", missionPassword)
                    .putLong("cache_time_$alarmId", System.currentTimeMillis())
                    .apply() 
            }
            "GET_ALARM_DATA" -> {
                val alarmId = intent.getIntExtra("alarm_id", -1)
                val missionType = prefs.getString("mission_type_$alarmId", "")
                val missionPassword = prefs.getString("mission_password_$alarmId", "")
                val resultIntent = Intent().apply {
                    putExtra("mission_type", missionType)
                    putExtra("mission_password", missionPassword)
                }
                sendBroadcast(resultIntent, "ALARM_DATA_RESPONSE")
            }
            else -> return START_NOT_STICKY
        }
        return START_STICKY
    }
}
