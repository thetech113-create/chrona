@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.vaishnava.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.core.content.ContextCompat

class NotificationRepostReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "NotificationRepostReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            Log.e(TAG, "Context or intent is null")
            return
        }

        try {
            val action = intent.action
            val alarmId = intent.getIntExtra(AlarmReceiver.ALARM_ID, -1).takeIf { it != -1 }
                ?: intent.getIntExtra("ALARM_ID", -1)
            
            Log.d(TAG, "Received intent with action: $action for alarm ID: $alarmId")
            
            // Handle notification dismissed action - DO NOT CANCEL ALARM, REPOST NOTIFICATION
            if (action == Constants.ACTION_NOTIFICATION_DISMISSED) {
                Log.d(TAG, "Notification dismissed for alarm ID: $alarmId, reposting notification...")
                
                val repostIntent = Intent(context, AlarmForegroundService::class.java).apply {
                    this.action = Constants.ACTION_REPOST_NOTIFICATION
                    putExtra(AlarmReceiver.ALARM_ID, alarmId)
                    putExtra("ALARM_ID", alarmId)
                }
                
                ContextCompat.startForegroundService(context, repostIntent)
                Log.d(TAG, "Sent repost request to AlarmForegroundService for alarm ID: $alarmId")
            }
            else if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
                Log.d(TAG, "Boot completed broadcast received in NotificationRepostReceiver")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle intent: ${e.message}", e)
        }
    }
}
