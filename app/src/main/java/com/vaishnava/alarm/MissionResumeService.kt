package com.vaishnava.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vaishnava.alarm.sequencer.PendingCompletionStore

/**
 * MissionResumeService
 * Small foreground service used after device unlock to resume mission state
 * from the pending completion store and transition from DPS to CPS.
 */
class  MissionResumeService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val appCtx = applicationContext
            // Legacy pending-completion resume path is no longer authoritative.
            // The DPS snapshot/completion marker owns unlock truth now.
            PendingCompletionStore.clear(appCtx)
            PendingCompletionStore.clearAlarm(appCtx)
        } catch (_: Exception) {
            // Swallow exceptions to avoid crash during boot/unlock flow
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureForeground() {
        val channelId = "mission_resume_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Mission Resume",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Restoring mission state…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // Use a stable ID for the foreground notification
        startForeground(1003, notification)
    }
}
