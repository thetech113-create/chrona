package com.vaishnava.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class ExactAlarmPermissionReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED") {
            Log.d("ExactAlarmPermission", "SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED received")
            
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager?.canScheduleExactAlarms() == true
            } else {
                true
            }
            
            Log.d("ExactAlarmPermission", "Current permission status: $hasPermission")
            
            if (hasPermission) {
                try {
                    val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        context.createDeviceProtectedStorageContext() ?: context
                    } else {
                        context
                    }
                    val alarmStorage = AlarmStorage(storageContext)
                    val alarmScheduler = AndroidAlarmScheduler(context.applicationContext)
                    
                    val alarms = alarmStorage.getAlarms().filter { it.isEnabled }
                    alarms.forEach { alarm ->
                        try {
                            alarmScheduler.cancel(alarm)
                            alarmScheduler.schedule(alarm)
                        } catch (e: Exception) {
                            Log.e("ExactAlarmPermission", "Error scheduling alarm ${alarm.id}", e)
                        }
                    }
                    
                    Log.d("ExactAlarmPermission", "Rescheduled ${alarms.size} enabled alarms after permission state change")
                } catch (e: Exception) {
                    Log.e("ExactAlarmPermission", "Error rescheduling alarms after permission change", e)
                }
            }
        }
    }
}
