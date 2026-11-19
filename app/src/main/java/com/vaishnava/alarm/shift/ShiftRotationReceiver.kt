package com.vaishnava.alarm.shift

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ShiftRotationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleReceiveAsync(context, action, intent)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleReceiveAsync(context: Context, action: String, intent: Intent) {
        val coordinator = ShiftRotationCoordinator(context)
        val store = ShiftRotationStore(context)
        val token = intent.getLongExtra(ShiftRotationCoordinator.EXTRA_REASSIGNMENT_TOKEN, 0L)

        fun tokenMatches(): Boolean {
            val pending = store.read()?.pendingReassignment ?: return false
            return token > 0L && pending.token == token
        }

        when (action) {
            ShiftRotationCoordinator.ACTION_RECOVER -> {
                coordinator.recoverAfterRestart()
            }
            ShiftRotationCoordinator.ACTION_RECONCILE_ROTATION -> {
                // Applying a due company reassignment before regeneration keeps
                // the handoff correct even when both exact alarms fire together.
                coordinator.recoverAfterRestart()
            }
            ShiftRotationCoordinator.ACTION_APPLY_REASSIGNMENT -> {
                if (tokenMatches()) {
                    coordinator.applyPendingReassignmentIfDue()
                } else {
                    Log.d(TAG, "SHIFT_REASSIGNMENT_STALE_APPLY_IGNORED: token=$token")
                }
            }
            ShiftRotationCoordinator.ACTION_SAFETY_REMINDER -> {
                if (tokenMatches()) {
                    coordinator.showPendingReminderAndReschedule()
                } else {
                    Log.d(TAG, "SHIFT_REASSIGNMENT_STALE_REMINDER_IGNORED: token=$token")
                }
            }
            ShiftRotationCoordinator.ACTION_CONFIRM_STILL_REAL -> {
                if (tokenMatches()) {
                    coordinator.keepPendingReassignment()
                } else {
                    Log.d(TAG, "SHIFT_REASSIGNMENT_STALE_KEEP_IGNORED: token=$token")
                }
            }
            ShiftRotationCoordinator.ACTION_CANCEL_REASSIGNMENT -> {
                if (tokenMatches()) {
                    coordinator.cancelPendingReassignment()
                } else {
                    Log.d(TAG, "SHIFT_REASSIGNMENT_STALE_CANCEL_IGNORED: token=$token")
                }
            }
        }
    }

    companion object {
        private const val TAG = "ShiftRotationReceiver"
    }
}
