package com.vaishnava.alarm.util

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.Locale

object PermissionUtils {
    
    /**
     * Check if the app has permission to schedule exact alarms
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            try {
                alarmManager.canScheduleExactAlarms()
            } catch (_: Exception) {
                true
            }
        } else {
            true // Older versions don't require this permission
        }
    }
    
    /**
     * Request the SCHEDULE_EXACT_ALARM permission
     */
    fun requestScheduleExactAlarmsPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perPackageIntent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val generalListIntent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            runCatching {
                context.startActivity(perPackageIntent)
            }.recoverCatching {
                context.startActivity(generalListIntent)
            }.recoverCatching {
                context.startActivity(appDetailsIntent)
            }
        }
    }
    
    /**
     * Check if the app has been whitelisted from battery optimization
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true // Older versions don't have battery optimization
        }
    }
    
    /**
     * Request to be whitelisted from battery optimization
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }
    
    /**
     * Check if all necessary permissions are granted for the alarm to work properly
     */
    fun hasAllRequiredPermissions(context: Context): Boolean {
        // Check basic permissions
        val hasWakeLockPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WAKE_LOCK
        ) == PackageManager.PERMISSION_GRANTED
        
        val hasBootCompletedPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECEIVE_BOOT_COMPLETED
        ) == PackageManager.PERMISSION_GRANTED
        
        // Check exact alarm permission (Android 12+)
        val hasExactAlarmPermission = canScheduleExactAlarms(context)
        
        return hasWakeLockPermission && 
               hasBootCompletedPermission && 
               hasExactAlarmPermission
    }
    
    /**
     * Check if the app has WAKE_LOCK permission
     */
    fun hasWakeLockPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WAKE_LOCK
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if the app can draw overlays (SYSTEM_ALERT_WINDOW)
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // Older versions don't require this permission
        }
    }

    /**
     * Open the standard "Display over other apps" permission screen.
     */
    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }

        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Open OEM-specific permission pages used for lock screen and background pop-up access.
     *
     * Xiaomi/Redmi/POCO devices commonly require users to manually enable:
     * - Show on lock screen
     * - Display pop-up windows while running in background
     */
    fun requestBackgroundWindowAndLockScreenPermissions(context: Context) {
        val manufacturer = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.getDefault())
        val isOppoOrOplus = manufacturer.contains("oppo") ||
                manufacturer.contains("realme") ||
                manufacturer.contains("oneplus") ||
                manufacturer.contains("oplus")

        val miuiIntent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity"
            )
            putExtra("extra_pkgname", context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val oplusPopupIntent = Intent("com.oplus.action.BACKGROUND_POPUP_SETTINGS").apply {
            putExtra("extra_pkgname", context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (isOppoOrOplus) {
            runCatching {
                context.startActivity(oplusPopupIntent)
            }.recoverCatching {
                context.startActivity(fallbackIntent)
            }
        } else {
            runCatching {
                context.startActivity(miuiIntent)
            }.recoverCatching {
                context.startActivity(fallbackIntent)
            }
        }
    }
    
    /**
     * Check if the app has notification access
     */
    fun hasNotificationAccess(                 context: Context): Boolean {
        // This is a simplified check - in a real implementation you might want to check
        // if your notification listener service is enabled
        return true
    }
}
