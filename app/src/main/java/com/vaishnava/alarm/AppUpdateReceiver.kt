package com.vaishnava.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class AppUpdateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AppUpdateReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }
                Log.d(
                    TAG,
                    "App updated. Preserving stored alarms and mission state. " +
                        "versionName=${packageInfo.versionName} versionCode=$versionCode"
                )
            } catch (e: Exception) {
                Log.e(TAG, "App updated. Preserving stored data, but version lookup failed: ${e.message}", e)
            }
        }
    }
}
