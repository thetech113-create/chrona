package com.vaishnava.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vaishnava.alarm.sequencer.PendingCompletionStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_UNLOCKED) {
            Log.d(
                "AlarmApp",
                "BootReceiver: User unlocked, clearing legacy pending-mission resume path only. " +
                    "Wake-check cycles are preserved for DirectBootRestoreReceiver."
            )
            PendingCompletionStore.clear(context)
            PendingCompletionStore.clearAlarm(context)
        }
    }
}
