@file:OptIn(
    ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class,
    DelicateCoroutinesApi::class
)

package com.vaishnava.alarm

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.vaishnava.alarm.logging.RemoteLogUploader
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlarmAdd
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.vaishnava.alarm.data.Alarm
import com.vaishnava.alarm.faceauth.FaceAuthDialog
import com.vaishnava.alarm.faceauth.FaceEnrollmentDialog
import com.vaishnava.alarm.faceauth.MatchAction
import com.vaishnava.alarm.sequencer.FaceMissionSupport
import com.vaishnava.alarm.sequencer.MathDifficulty
import com.vaishnava.alarm.sequencer.MathMissionConfig
import com.vaishnava.alarm.sequencer.MathMissionSupport
import com.vaishnava.alarm.sequencer.MissionSequencer
import com.vaishnava.alarm.sequencer.MissionSpec
import com.vaishnava.alarm.sequencer.SequencerMissionCodec
import com.vaishnava.alarm.sequencer.SttMissionSupport
import com.vaishnava.alarm.shift.ShiftAlarmPattern
import com.vaishnava.alarm.shift.ShiftDayRule
import com.vaishnava.alarm.shift.ShiftRotationConfig
import com.vaishnava.alarm.shift.ShiftRotationCoordinator
import com.vaishnava.alarm.shift.ShiftRotationDefinition
import com.vaishnava.alarm.shift.ShiftRotationStore
import com.vaishnava.alarm.ui.theme.AlarmTheme
import com.vaishnava.alarm.util.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import androidx.appcompat.app.AlertDialog as AppCompatAlertDialog

/**
 * Belt-and-suspenders for dialog screenshot security.
 *
 * Every AlertDialog/Dialog in this file already gets FLAG_SECURE for free via
 * DialogProperties' default SecureFlagPolicy.Inherit, which copies the flag from
 * the hosting Activity window (itself secured globally in AlarmApplication's
 * ActivityLifecycleCallbacks). This composable just re-applies the flag directly
 * to the dialog's own window as a fallback, in case a specific OEM/Android build
 * ever fails to propagate the inherited flag correctly.
 *
 * Call this as the first line inside a dialog's `text = { ... }` (or any other
 * content slot), so `LocalView.current` resolves to a view hosted inside the
 * dialog's own window, not the parent screen's.
 */
@Composable
private fun SecureDialogWindow() {
    if (BuildConfig.DEBUG) return
    val view = LocalView.current
    DisposableEffect(view) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        dialogWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { }
    }
}

@Suppress("RedundantVisibilityModifier", "RedundantVisibilityModifier")
class MainActivity : BaseActivity() {

    private lateinit var alarmStorage: AlarmStorage
    private lateinit var alarmScheduler: AlarmScheduler
    var missionSequencer: MissionSequencer? = null
    private val alarmPermissionRequestCode = 1001
    private val batteryOptimizationRequestCode = 1002
    private val scheduleExactAlarmRequestCode = 1003

    // In-app logging state
    private val sequencerLogs = mutableStateListOf<String>()

    // Alarm refresh trigger for UI recomposition
    private val alarmRefreshTrigger = mutableStateOf(0)

    companion object {
        private const val TAG = "MainActivity"

        @Volatile
        private var instance: MainActivity? = null

        fun getInstance(): MainActivity? = instance

        fun getMissionSequencer(): MissionSequencer? {
            return instance?.missionSequencer
        }

        /**
         * Opens OPPO/Realme/OnePlus Background Popup Settings with comprehensive user guidance.
         *
         * CRITICAL FOR ALARM FUNCTIONALITY:
         * This setting controls whether the app can show notifications/alarm popups while running in the background.
         * If disabled, alarms may not trigger properly or notifications may be silently blocked.
         *
         * USER GUIDANCE - WHAT TO ENABLE:
         * 1. Enable "Display pop-up window while running in the background"
         * 2. Allow "Background pop-up window" for this app
         * 3. Enable "Show on lock screen" if available
         * 4. Grant "Notification" access if prompted
         *
         * WHY THIS IS CRITICAL:
         * - OPPO/Realme/OnePlus devices aggressively block background notifications by default
         * - Without this setting enabled, alarms may fail to trigger when the phone is locked or app is backgrounded
         * - This is NOT exposed in standard Android settings - it's OPPO-specific
         * - Many users miss this setting, leading to "alarms not working" issues
         *
         * DEVICES AFFECTED:
         * - OPPO (all models)
         * - Realme (all models)
         * - OnePlus (all models)
         * - Any device running ColorOS or OxygenOS
         *
         * @param context Application context
         * @return true if settings were successfully opened, false otherwise
         */
        fun openOppoBackgroundPopupSettings(context: Context): Boolean {
            val pkg = context.packageName

            fun safeStart(intent: Intent): Boolean {
                return try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    true
                } catch (_: Exception) {
                    false
                }
            }

            fun safeStartComponent(pkgName: String, clsName: String): Boolean {
                return safeStart(Intent().setComponent(ComponentName(pkgName, clsName)))
            }

            // 1. Try known OPPO / ColorOS / Realme / OnePlus Auto-start & SafeCenter activities
            val oppoComponents = listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.privacypermissionsentry.PermissionTopActivity",
                "com.coloros.phonemanager" to "com.coloros.phonemanager.appcleaner.AutoStartAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
                "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
                "com.coloros.securitypermission" to "com.coloros.securitypermission.autostart.AutoStartManagementActivity",
                "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            )

            for ((p, c) in oppoComponents) {
                if (safeStartComponent(p, c)) return true
            }

            // 2. Try ColorOS / OPlus action intents
            val oplusActions = listOf(
                "oplus.intent.action.BACKGROUND_POPUP_SETTINGS",
                "com.oplus.action.BACKGROUND_POPUP_SETTINGS",
                "com.oplus.settings.action.BACKGROUND_POPUP"
            )

            for (action in oplusActions) {
                val intent = Intent(action).apply {
                    data = Uri.parse("package:$pkg")
                    putExtra("packageName", pkg)
                    putExtra("package", pkg)
                    putExtra("package_name", pkg)
                }
                if (safeStart(intent)) return true
            }

            // 3. Fallback to general settings or special app access (never App Info page)
            if (safeStart(Intent(Settings.ACTION_SETTINGS))) return true
            return safeStart(Intent("android.settings.MANAGE_SPECIAL_APP_ACCESS"))
        }

        /**
         * Shows comprehensive user guidance for OPPO background popup settings
         * This ensures users understand exactly what to enable
         */
        private fun showOppoBackgroundPopupGuidance(context: Context) {
            try {
                // For Context that's not Activity, we can't show AlertDialog directly
                // Use Toast as fallback, but the instance method will show proper dialog
                val guidanceText = """
                    ⚠️ CRITICAL ALARM SETTING FOR OPPO/REALME/ONEPLUS ⚠️
                    
                    Your alarm app needs special permission to work properly on this device.
                    
                    🔧 ENABLE THESE SETTINGS:
                    ✓ "Display pop-up window while running in the background"
                    ✓ "Background pop-up window" 
                    ✓ "Show on lock screen" (if available)
                    ✓ Allow notifications when prompted
                    
                    📱 WHY THIS IS NEEDED:
                    OPPO/Realme/OnePlus devices block background notifications by default.
                    Without this, your alarms may not trigger when the phone is locked.
                    
                    ✅ After enabling, your alarms will work reliably!
                """.trimIndent()

                Toast.makeText(context, guidanceText, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                // Fallback to simple message if formatting fails
                Toast.makeText(context,
                    "Enable 'Display pop-up window while running in background' for reliable alarms",
                    Toast.LENGTH_LONG).show()
            }
        }

        /**
         * Shows comprehensive OPPO background popup guidance dialog
         * Does NOT automatically open settings - user must click button to proceed
         * Works with both Context and Activity for maximum compatibility
         */
        fun showOppoBackgroundPopupDialog(context: Context) {
            try {
                // Try to show dialog if we have Activity context
                if (context is Activity) {
                    val dialog = AppCompatAlertDialog.Builder(context)
                        .setTitle("⚠️ Critical Alarm Setting Required")
                        .setMessage(
                            """
                            Your OPPO/Realme/OnePlus device requires a special setting for alarms to work reliably.
                            
                            🔧 STEP-BY-STEP INSTRUCTIONS:
                            1. Click "Open Settings" button below
                            2. Find "Display pop-up window while running in background"
                            3. Enable it for this app
                            4. Enable "Background pop-up window" if available
                            5. Enable "Show on lock screen" if available
                            
                            📱 WHY THIS IS NEEDED:
                            These devices block background notifications by default. Without this setting, 
                            your alarms may not trigger when the phone is locked or the app is in background.
                            
                            ✅ After enabling, your alarms will work perfectly!
                            """.trimIndent()
                        )
                        .setPositiveButton("Open Settings") { _, _ ->
                            // User clicked to open settings - now open them
                            openOppoBackgroundPopupSettings(context)
                        }
                        .setNegativeButton("Cancel", null)
                        .setNeutralButton("View Full Guide") { _, _ ->
                            // Show comprehensive guide dialog
                            showOppoBackgroundPopupGuideDialog(context)
                        }
                        .setCancelable(false)
                        .create()

                    if (!BuildConfig.DEBUG) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    dialog.show()
                } else {
                    // Fallback to Toast for non-Activity context
                    showOppoBackgroundPopupGuidance(context)
                }
            } catch (_: Exception) {
                // Fallback to Toast if dialog fails
                Toast.makeText(context,
                    "Enable background popup settings for reliable alarms",
                    Toast.LENGTH_LONG).show()
            }
        }

        /**
         * Provides comprehensive OPPO/Realme/OnePlus background popup settings guide
         * This can be called from anywhere in the app to show users what they need to enable
         *
         * @param context Application context
         * @param showInstructions If true, shows detailed step-by-step instructions
         */
        fun getOppoBackgroundPopupGuide(context: Context, showInstructions: Boolean = true): String {
            return if (showInstructions) {
                """
                🔧 OPPO/REALME/ONEPLUS BACKGROUND POPUP SETTINGS GUIDE 🔧
                
                ⚠️ CRITICAL FOR ALARM FUNCTIONALITY ⚠️
                
                📱 WHAT TO ENABLE:
                1. "Display pop-up window while running in the background"
                2. "Background pop-up window" 
                3. "Show on lock screen" (if available)
                4. Allow notifications when prompted
                
                📍 HOW TO FIND THIS SETTING:
                • Settings → Apps → Manage Apps → [Your Alarm App]
                • Look for "Display pop-up window while running in background"
                • Enable it and restart the app
                
                🔄 ALTERNATIVE PATH:
                • Settings → Special Access → Background Popup Manager
                • Find your app and enable the popup permission
                
                ❓ WHY THIS IS NEEDED:
                OPPO/Realme/OnePlus devices (ColorOS/OxygenOS) block background notifications 
                by default to save battery. Without this setting, alarms may not trigger when:
                • Phone is locked
                • App is running in background  
                • Device is in doze mode
                
                ✅ AFTER ENABLING:
                • Your alarms will trigger reliably
                • Notifications will appear on lock screen
                • No more missed alarms due to background restrictions
                
                📞 STILL HAVING ISSUES?
                • Restart your phone after enabling
                • Check if "Battery Optimization" is disabled for this app
                • Ensure "Autostart" is enabled in device settings
                """.trimIndent()
            } else {
                """
                ⚠️ CRITICAL: Enable "Display pop-up window while running in background" 
                in OPPO/Realme/OnePlus settings for reliable alarms.
                
                Path: Settings → Apps → [Your App] → Display pop-up window while running in background
                """.trimIndent()
            }
        }

        /**
         * Shows OPPO background popup guide as a dialog
         * Call this when user needs detailed instructions
         */
        fun showOppoBackgroundPopupGuideDialog(context: Context) {
            try {
                val guideText = getOppoBackgroundPopupGuide(context, showInstructions = true)

                if (context is Activity) {
                    val dialog = AppCompatAlertDialog.Builder(context)
                        .setTitle("📱 OPPO/Realme/OnePlus Settings Guide")
                        .setMessage(guideText)
                        .setPositiveButton("Open Settings Now") { _, _ ->
                            openOppoBackgroundPopupSettings(context)
                        }
                        .setNegativeButton("Later", null)
                        .setNeutralButton("Copy Guide") { _, _ ->
                            // Copy guide to clipboard for reference
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("OPPO Settings Guide", guideText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Guide copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                        .create()

                    if (!BuildConfig.DEBUG) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    dialog.show()
                } else {
                    // Fallback to Toast for non-Activity context
                    Toast.makeText(context, guideText, Toast.LENGTH_LONG).show()
                }
            } catch (_: Exception) {
                // Fallback to simple Toast
                Toast.makeText(context,
                    "Enable background popup settings: Settings → Apps → [Your App] → Display pop-up window",
                    Toast.LENGTH_LONG).show()
            }
        }

        fun isDeviceAdminActive(context: Context): Boolean {
            return runCatching {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
                val adminComponent = ComponentName(context, MyDeviceAdminReceiver::class.java)
                dpm?.isAdminActive(adminComponent) == true
            }.getOrDefault(false)
        }

        fun getMissingRequiredPermissions(context: Context): List<String> {
            val missing = mutableListOf<String>()

            // 1. Exact Alarms (Android 12+)
            val exactAlarmsOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarmManager?.canScheduleExactAlarms() == true
            } else {
                true
            }
            if (!exactAlarmsOk) missing.add("Exact Alarms")

            // 2. Notifications (Android 13+ runtime + system toggle)
            val notificationsOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED &&
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
            } else {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
            if (!notificationsOk) missing.add("Notifications")

            // 3. Camera (Face & Photo Missions)
            val cameraOk = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (!cameraOk) missing.add("Camera")

            // 4. Microphone (Speech-to-Text Mission)
            val microphoneOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!microphoneOk) missing.add("Microphone")

            // 5. Music & Audio (Custom Ringtones)
            val audioStorageOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
            if (!audioStorageOk) missing.add("Music & Audio")

            // 6. Battery Optimization ("Don't Optimize")
            if (!isBatteryOptimizationIgnored(context)) {
                missing.add("Battery Optimization")
            }

            // 7. Modify System Settings (Write Settings)
            val writeSettingsOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.System.canWrite(context)
            } else {
                true
            }
            if (!writeSettingsOk) missing.add("Modify System Settings")

            // 8. Display Over Other Apps (Overlay)
            val overlayOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PermissionUtils.canDrawOverlays(context)
            } else {
                true
            }
            if (!overlayOk) missing.add("Display Over Other Apps")

            // 9. Device Admin
            if (!isDeviceAdminActive(context)) {
                missing.add("Device Admin")
            }

            // 10. Accessibility Service (Power Menu Prevention)
            if (!isAccessibilityServiceEnabled(context)) {
                missing.add("Accessibility Service")
            }

            return missing
        }

        fun hasAllRequiredPermissions(context: Context): Boolean {
            return getMissingRequiredPermissions(context).isEmpty()
        }

        fun isBatteryOptimizationIgnored(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            } else {
                true
            }
        }

        fun requestIgnoreBatteryOptimization(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            val pkg = context.packageName

            val directIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$pkg")
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(directIntent) }.isSuccess) return

            val oppoIntents = listOf(
                Intent().setComponent(
                    ComponentName(
                        "com.oplus.battery",
                        "com.oplus.powermanager.fuelgaue.PowerControlActivity"
                    )
                ),
                Intent().setComponent(
                    ComponentName(
                        "com.coloros.oppoguardelf",
                        "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                    )
                ),
                Intent().setComponent(
                    ComponentName(
                        "com.coloros.oppoguardelf",
                        "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity"
                    )
                ),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )

            for (intent in oppoIntents) {
                intent.putExtra("packageName", pkg)
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(intent) }.isSuccess) {
                    Toast.makeText(
                        context,
                        "Select CHRONA -> Allow background activity / Don't optimize",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
            }

            runCatching {
                val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$pkg")
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(appDetails)
                Toast.makeText(
                    context,
                    "Tap 'Battery usage' -> 'Allow background activity'",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            return runCatching {
                val expected = ComponentName(context, PowerMenuPreventionService::class.java)
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                enabledServices.split(':').any {
                    ComponentName.unflattenFromString(it) == expected
                }
            }.getOrDefault(false)
        }

        fun openAccessibilitySettingsForChrona(context: Context) {
            val componentString = ComponentName(context, PowerMenuPreventionService::class.java).flattenToString()
            val highlightIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                val bundle = Bundle()
                bundle.putString(":settings:fragment_args_key", componentString)
                putExtra(":settings:fragment_args_key", componentString)
                putExtra(":settings:show_fragment_args", bundle)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(highlightIntent) }.isFailure) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }
        }

        /**
         * Safe access to MissionSequencer - only returns if device is unlocked
         */
        fun getSafeMissionSequencer(): MissionSequencer? {
            val instance = instance ?: return null
            return if (MissionSequencer.isCpsAccessAllowed(instance)) {
                instance.missionSequencer
            } else {
                null
            }
        }
    }
    fun addSequencerLog(message: String) {
        Log.d("MainActivity", "SEQUENCER: $message")
    }

    fun addSequencerLogWarning(message: String) {
        Log.w("MainActivity", "SEQUENCER: $message")
    }

    private fun parseMissionData(missionData: String): List<com.vaishnava.alarm.sequencer.MissionSpec> {
        return SequencerMissionCodec.parseMissionSequence(missionData)
    }


    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }

        if (allGranted) {
            Log.d("AlarmApp", "All alarm permissions granted")
            promptExactAlarmIfNeeded()
        } else {
            Log.e("AlarmApp", "Some alarm permissions denied")
        }
    }

    override fun onResume() {
        super.onResume()
        // Try to restore again in case sign-in just completed
        tryRestoreFromCloud()
        AppUpdateManager.checkAndInstallPending(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // This triggers Jetpack Compose to re-read theme and update instantly
        forceUpdateUI()
        addSequencerLog("Theme change detected: Smooth transition applied.")
    }

    private fun forceUpdateUI() {
        runOnUiThread {
            // Trigger UI recomposition without data reload
            alarmRefreshTrigger.value += 1
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // A throttled, non-interactive check.  The updater only downloads a release
        // whose manifest versionCode is greater than this installed build.
        AppUpdateManager.checkForUpdate(this)

        // Clear the force_restart_in_progress flag when the app fully starts
        val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            createDeviceProtectedStorageContext() ?: this
        } else this
        val sharedPrefs = dpsContext.getSharedPreferences(DirectBootRestoreReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        try {
            dpsContext.deleteFile(DirectBootRestoreReceiver.FORCE_RESTART_FLAG_FILE)
            Log.d("AlarmApp", "MainActivity: FORCE_RESTART_FLAG_FILE deleted.")
        } catch (e: Exception) {
            Log.e("AlarmApp", "MainActivity: Failed to delete FORCE_RESTART_FLAG_FILE: ${e.message}")
        }

        alarmStorage = AlarmStorage(this)
        alarmScheduler = AndroidAlarmScheduler(this)

        // ENTERPRISE SOLUTION: Export ringtones to MediaStore for cross-app access
        this.exportRingtonesToMediaStore()

        // Check if this intent was launched to show a permission message
        if (intent.getBooleanExtra("show_permission_message", false)) {
            val message = intent.getStringExtra("permission_message") ?: "Enable all permissions to create alarms"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            Log.w("AlarmApp", "Showing permission message: $message")
        }

        // CRITICAL FIX: Add notification to check if MainActivity is being called during force restart
        try {
            val allAlarms = alarmStorage.getAlarms()
            val sequencerAlarms = allAlarms.filter { it.missionType == "sequencer" }
            Log.d("MainActivity_FORCE_RESTART", "MainActivity onCreate: Found ${sequencerAlarms.size} sequencer alarms out of ${allAlarms.size} total")
            sequencerAlarms.take(3).forEach { alarm ->
                Log.d("MainActivity_SAMPLE", "ID=${alarm.id}, Type=${alarm.missionType}, Password=${alarm.missionPassword}")
            }
        } catch (e: Exception) {
            Log.e("MainActivity_FORCE_RESTART", "Failed to read alarms in onCreate", e)
        }

        // CRITICAL: DPS MUST NEVER initialize MissionSequencer - CPS only after unlock
        missionSequencer = if (MissionSequencer.isCpsAccessAllowed(this)) {
            MissionSequencer(this)
        } else {
            ExtremeForensicLogger.Chrona.cpsViolation(
                -1,
                "MISSION_SEQUENCER_INIT",
                "MainActivity.onCreate",
                this,
                "CPS_VIOLATION"
            )
            null
        }

        // CRITICAL FIX: Clear corrupted JSON files since we now use DPS SharedPreferences
        missionSequencer?.queueStore?.clearCorruptedFiles()

        // Set static instance for AlarmActivity access
        instance = this

        // CRITICAL FIX: Check if MainActivity was launched from sequencer alarm notification
        val sequencerAlarmId = intent.getIntExtra("sequencer_alarm_id", -1)
        val sequencerRingtoneUri: String? = intent.getStringExtra("sequencer_ringtone_uri")
        val autoStartSequencer = intent.getBooleanExtra("auto_start_sequencer", false)

        // Check if launched from missed sequencer alarm
        val fromMissedSequencerAlarm = intent.getBooleanExtra("from_missed_sequencer_alarm", false)
        val missedSequencerAlarmId = intent.getIntExtra(AlarmReceiver.ALARM_ID, -1)

        // Check if launched from CPS handoff (DPS trigger)
        val fromCpsHandoff = intent.getBooleanExtra("from_cps_handoff", false)
        val cpsAlarmId = intent.getIntExtra("cps_alarm_id", -1)
        val cpsMissionData = intent.getStringExtra("cps_mission_data") ?: ""

        // Check if launched from multi-mission alarm (legacy)
        val fromMultiMissionAlarm = intent.getBooleanExtra("from_multi_mission_alarm", false)
        val fromWakeMultiMission = intent.getBooleanExtra("from_wake_multi_mission", false)
        val multiMissionAlarmId = intent.getIntExtra(AlarmReceiver.ALARM_ID, -1)
        val multiMissionData = intent.getStringExtra("multi_mission_data") ?: ""

        if (fromMissedSequencerAlarm && missedSequencerAlarmId != -1) {
            addSequencerLog("MainActivity launched from missed sequencer alarm: alarmId=$missedSequencerAlarmId")
            addSequencerLog("Sequencer missed alarm is now handled by AlarmForegroundService")
        }

        // Handle CPS handoff from DPS trigger (NEW ARCHITECTURE) - ONE-WAY ONLY
        if (fromCpsHandoff && cpsAlarmId != -1) {
            // 🔐 STRICT UNLOCK GATE - NON-NEGOTIABLE
            if (!MissionSequencer.isCpsAccessAllowed(this)) {
                // 🚫 CPS ACCESS DENIED - THIS PREVENTS UNINSTALL
                ExtremeForensicLogger.Chrona.cpsViolation(
                    cpsAlarmId,
                    "CPS_HANDOFF_BEFORE_UNLOCK",
                    "MainActivity.handleCpsHandoff",
                    this,
                    "DENIED"
                )
                addSequencerLogWarning("CPS_HANDOFF_DENIED: Device not unlocked - cannot start CPS engine")
                return
            }

            addSequencerLog("MainActivity launched from CPS handoff: alarmId=$cpsAlarmId data=$cpsMissionData - DEVICE UNLOCKED")
            addSequencerLog("CPS_ENGINE_STARTING: alarmId=$cpsAlarmId - Starting CPS mission engine after DPS trigger")

            // CRITICAL: CPS must rebuild mission state from scratch, ignore DPS snapshots
            val missionSpecs = parseMissionData(cpsMissionData)
            if (missionSpecs.isNotEmpty()) {
                addSequencerLog("CPS_REBUILD_QUEUE: Rebuilding mission queue from handoff data")
                missionSequencer?.enqueueAll(missionSpecs)
                missionSequencer?.startWhenAlarmFires(sequencerRingtoneUri?.let { android.net.Uri.parse(it) })
            } else {
                addSequencerLogWarning("CPS_REBUILD_SKIP: No valid missions in handoff data")
            }
        }

        // Handle multi-mission data from AlarmReceiver (LEGACY)
        if ((fromMultiMissionAlarm || fromWakeMultiMission) && multiMissionAlarmId != -1 && multiMissionData.isNotEmpty()) {
            addSequencerLog("MainActivity launched from multi-mission alarm: alarmId=$multiMissionAlarmId data=$multiMissionData")

            // Parse and enqueue multi-mission data
            val missionIds = multiMissionData.split("+").map { it.trim().lowercase() }
                .map { mission ->
                    when (mission) {
                        "tap" -> "tap"
                        "pwd", "password", "pswd" -> "password"
                        else -> "" // CRITICAL FIX: Block invalid mission names
                    }
                }
                .filter { it.isNotEmpty() }

            addSequencerLog("Multi-mission parsed: $missionIds")

            // Create mission specs and enqueue
            val specs = missionIds.map { missionId ->
                val timeoutMs = if (missionId == "tap") 105000L else 30000L
                com.vaishnava.alarm.sequencer.MissionSpec(
                    id = missionId,
                    params = mapOf("mission_type" to missionId),
                    timeoutMs = timeoutMs,
                    retryCount = 5,
                    sticky = true,
                    retryDelayMs = 1000L
                )
            }

            missionSequencer?.enqueueAll(specs)
            addSequencerLog("Multi-mission: Enqueued ${specs.size} missions")

            // Auto-start sequencer after a short delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    val alarmStorage = com.vaishnava.alarm.AlarmStorage(this)
                    val alarm = alarmStorage.getAlarm(multiMissionAlarmId)
                    if (alarm != null) {
                        missionSequencer?.startWhenAlarmFires(alarm.ringtoneUri)
                        addSequencerLog("Multi-mission: Started sequencer for alarmId=$multiMissionAlarmId")
                    }
                } catch (e: Exception) {
                    addSequencerLog("Multi-mission: Failed to start sequencer: ${e.message}")
                    // NO FALLBACK: Multi-mission requires sequencer - do not proceed
                    return@postDelayed
                }
            }, 200)
        }

        if (sequencerAlarmId != -1 && sequencerRingtoneUri != null) {
            addSequencerLog("MainActivity launched from sequencer alarm notification: alarmId=$sequencerAlarmId")

            if (autoStartSequencer) {
                addSequencerLog("Auto-start sequencer requested, enqueuing missions and starting")
                // Auto-start sequencer after a short delay to ensure initialization
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        val ringtoneUri = android.net.Uri.parse(sequencerRingtoneUri ?: "")

                        // CRITICAL FIX: Enqueue missions from alarm configuration before starting
                        val alarmStorage = com.vaishnava.alarm.AlarmStorage(this)
                        val alarm = alarmStorage.getAlarm(sequencerAlarmId)
                        if (alarm != null && alarm.missionType == "sequencer") {
                            val specs = SequencerMissionCodec.parseMissionSequence(
                                serialized = alarm.missionPassword,
                                alarmId = alarm.id
                            )

                            // Enqueue missions first, then start
                            missionSequencer?.enqueueAll(specs)
                            missionSequencer?.startWhenAlarmFires(ringtoneUri)
                            addSequencerLog("Auto-start sequencer completed successfully with ${specs.size} missions")
                        } else {
                            addSequencerLog("Auto-start sequencer failed: alarm not found or not sequencer type")
                        }
                    } catch (e: Exception) {
                        addSequencerLog("Auto-start sequencer failed: ${e.message}")
                    }
                }, 500) // 500ms delay for proper initialization
            } else {
                addSequencerLog("Sequencer already running, MainActivity synchronized")
            }
        }

        // Start in-app log capture
        addSequencerLog("MissionSequencer initialized")
        addSequencerLog("Queue size: ${missionSequencer?.getQueueSize()}")
        addSequencerLog("Is mission running: ${missionSequencer?.isMissionRunning()}")

        // Log signing fingerprints in debug builds to help configure Firebase
        if (0 != (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)) {
            logSigningFingerprints()
        }

        // Cloud restore is intentionally disabled on startup.
        tryRestoreFromCloud()

        // Permissions will be handled manually by user through permission button
        // Removed automatic permission requests

        // Start a background task to check for upcoming alarms
        startAlarmNotificationChecker()
        lifecycleScope.launch(Dispatchers.IO) {
            ShiftRotationCoordinator(this@MainActivity).recoverAfterRestart()
        }

        // Register broadcast receiver for clearing notifications
        val clearFilter = IntentFilter("com.vaishnava.alarm.CLEAR_NOTIFICATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(clearNotificationReceiver, clearFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(clearNotificationReceiver, clearFilter)
        }

        // Register broadcast receiver for alarm refresh signals
        val refreshFilter = IntentFilter("com.vaishnava.alarm.REFRESH_ALARMS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(alarmRefreshReceiver, refreshFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(alarmRefreshReceiver, refreshFilter)
        }

        setContent {
            // Theme detection MUST be inside Composable setContent
            val isDark = androidx.compose.foundation.isSystemInDarkTheme()
            val view = androidx.compose.ui.platform.LocalView.current
            val context = LocalContext.current

            var showFaceEnrollmentDialog by remember { mutableStateOf(false) }
            var pendingFaceAuthAction by remember { mutableStateOf<MatchAction?>(null) }
            var pendingActionCallback by remember { mutableStateOf<((embedding: FloatArray?) -> Unit)?>(null) }
            var pendingAlarmId by remember { mutableStateOf(0) }
            var pendingAlarm by remember { mutableStateOf<Alarm?>(null) }
            var pendingEnrollment by remember { mutableStateOf<FloatArray?>(null) }

            Log.d("FaceAuthFlow", "🚀 MainActivity initialized: showFaceEnrollmentDialog=false, pendingFaceAuthAction=null")

            val performWithFaceAuth: (MatchAction, Int, Alarm?, (embedding: FloatArray?) -> Unit) -> Unit = { action, alarmId, currentAlarm, callback ->
                Log.d("FaceAuthFlow", "🎬 performWithFaceAuth called: action=$action, alarmId=$alarmId, hasEmbedding=${currentAlarm?.enrolledFaceEmbedding != null}")
                pendingAlarmId = alarmId
                pendingAlarm = currentAlarm
                pendingActionCallback = callback
                pendingFaceAuthAction = action
                if (currentAlarm?.enrolledFaceEmbedding == null) {
                    Log.d("FaceAuthFlow", "📹 No embedding found - showing enrollment dialog for alarmId=$alarmId")
                    showFaceEnrollmentDialog = true
                } else {
                    Log.d("FaceAuthFlow", "✅ Embedding exists - no enrollment needed")
                }
            }

            AlarmTheme(darkTheme = isDark) {
                // Handle system bar colors based on theme
                if (!view.isInEditMode) {
                    androidx.compose.runtime.SideEffect {
                        val window = (view.context as android.app.Activity).window
                        window.statusBarColor = android.graphics.Color.TRANSPARENT
                        window.navigationBarColor = android.graphics.Color.TRANSPARENT
                        // Dark icons for light mode, light icons for dark mode
                        androidx.core.view.WindowCompat.getInsetsController(window, view)
                            .isAppearanceLightStatusBars = !isDark
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AlarmScreenContent(
                        alarmStorage = alarmStorage,
                        alarmScheduler = alarmScheduler,
                        scheduleAlarmCallback = scheduleAlarmCallbackLabel@ { hour, minute, ringtoneUri, days, missionType, missionPassword, wakeCheckEnabled, wakeCheckMinutes, enableGradualVolume, enableAudioBoost, isProtected, requiresFaceAuth, preventPowerOff, targetAlarmDates, enableSnooze, snoozeMinutes ->
                            // Check all permissions before creating any alarm
                            if (!hasAllRequiredPermissions(this)) {
                                Log.w("AlarmApp", "❌ Cannot create alarm - missing required permissions")
                                Toast.makeText(this, "Enable all permissions to create alarms", Toast.LENGTH_LONG).show()
                                com.vaishnava.alarm.data.Alarm(
                                    id = -1,
                                    hour = hour,
                                    minute = minute,
                                    isEnabled = false,
                                    ringtoneUri = null,
                                    days = null,
                                    isProtected = false,
                                    missionType = "",
                                    missionPassword = "",
                                    wakeCheckEnabled = false,
                                    wakeCheckMinutes = 0,
                                    requiresFaceAuth = false,
                                    repeatDaily = false,
                                    timeChangeUsed = false,
                                    createdTime = System.currentTimeMillis(),
                                    enableSnooze = true
                                )
                            } else {
                                val alarmId = alarmStorage.getNextAlarmId()
                                val finalDays = if (days.isEmpty()) null else days.toList()

                                val validDates = targetAlarmDates?.filter { it > 0L } ?: emptyList()
                                val firstTrigger = validDates.minOrNull() ?: 0L

                                val actualMissionType = if (SequencerMissionCodec.isSequencerConfig(missionPassword)) {
                                    "sequencer"
                                } else if (missionType == "none" || missionType.isNullOrBlank()) {
                                    ""
                                } else {
                                    missionType
                                }

                                val finalMissionPassword = when {
                                    actualMissionType == "password" && missionPassword.isNullOrEmpty() -> "IfYouWantYouCanSleep"
                                    actualMissionType.isNullOrBlank() -> null
                                    else -> missionPassword
                                }

                                var enrolledAlarm = Alarm(
                                    id = alarmId,
                                    hour = hour,
                                    minute = minute,
                                    isEnabled = true,
                                    ringtoneUri = ringtoneUri,
                                    days = finalDays,
                                    alarmTime = firstTrigger,
                                    selectedDates = validDates,
                                    isProtected = isProtected,
                                    missionType = actualMissionType,
                                    missionPassword = finalMissionPassword,
                                    wakeCheckEnabled = wakeCheckEnabled,
                                    wakeCheckMinutes = if (enableSnooze) snoozeMinutes else wakeCheckMinutes,
                                    enableGradualVolume = enableGradualVolume,
                                    enableAudioBoost = enableAudioBoost,
                                    requiresFaceAuth = requiresFaceAuth,
                                    repeatDaily = finalDays != null && finalDays.size == 7,
                                    timeChangeUsed = false,
                                    createdTime = System.currentTimeMillis(),
                                    enableSnooze = enableSnooze
                                )

                                // Save the ringtone as last used when alarm is created
                                RingtonePreferences.saveLastRingtone(this@MainActivity, ringtoneUri)

                                Log.d("FaceAuthFlow", "🆕 New alarm: requiresFaceAuth=$requiresFaceAuth, alarmId=$alarmId")
                                if (requiresFaceAuth) {
                                    fun finishCreateWithProtectionEmbedding(embedding: FloatArray) {
                                        enrolledAlarm = enrolledAlarm.copy(enrolledFaceEmbedding = embedding)
                                        alarmStorage.addAlarm(enrolledAlarm)
                                        // Save power-off prevention preference
                                        getSharedPreferences("prevent_power_off_prefs", MODE_PRIVATE).edit().putBoolean("alarm_$alarmId", preventPowerOff).apply()
                                        SttMissionSupport.commitPendingTargetPhrase(this@MainActivity, alarmId)
                                        FaceMissionSupport.commitPendingEmbedding(this@MainActivity, alarmId)
                                        alarmRefreshTrigger.value++

                                        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
                                        val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                            try {
                                                alarmManager.canScheduleExactAlarms()
                                            } catch (e: Exception) { false }
                                        } else { true }

                                        if (canScheduleExact) {
                                            try {
                                                alarmScheduler.schedule(enrolledAlarm)
                                            } catch (_: Exception) {}
                                        }

                                        if (missionType == "sequencer") {
                                            val currentQueue = missionSequencer?.queueStore?.loadQueue() ?: emptyList()
                                            val updatedQueue = currentQueue.map { mission ->
                                                mission.copy(params = mission.params + ("alarm_id" to alarmId.toString()))
                                            }
                                            missionSequencer?.queueStore?.saveQueue(updatedQueue)
                                        }
                                    }

                                    // Face mission and face-auth protection are separately toggled, but if
                                    // both are on and the Face mission was just enrolled in this same create
                                    // flow, reuse that scan for protection instead of asking the user to scan
                                    // their face a second time back-to-back. Face mission is queued through
                                    // queuedMissions, which serializes actualMissionType as "sequencer" even
                                    // for a single Face selection - so this must not gate on missionType.
                                    val sharedMissionEmbedding = FaceMissionSupport.getPendingEmbedding(this@MainActivity)

                                    if (sharedMissionEmbedding != null) {
                                        Log.d("FaceAuthFlow", "🔗 Reusing Face mission enrollment for alarm protection - skipping second scan")
                                        finishCreateWithProtectionEmbedding(sharedMissionEmbedding)
                                    } else {
                                        Log.d("FaceAuthFlow", "🔐 Face auth ENABLED - enrolling face for new alarm")
                                        performWithFaceAuth(MatchAction.CREATE_OR_EDIT, alarmId, null) { embedding ->
                                            if (embedding == null) {
                                                Log.w("FaceAuthFlow", "❌ Face enrollment failed or canceled - alarm not created")
                                                Toast.makeText(this@MainActivity, "Face enrollment required to create this alarm", Toast.LENGTH_LONG).show()
                                                return@performWithFaceAuth
                                            }
                                            finishCreateWithProtectionEmbedding(embedding)
                                        }
                                    }
                                } else {
                                    alarmStorage.addAlarm(enrolledAlarm)
                                    // Save power-off prevention preference
                                    getSharedPreferences("prevent_power_off_prefs", MODE_PRIVATE).edit().putBoolean("alarm_$alarmId", preventPowerOff).apply()
                                    SttMissionSupport.commitPendingTargetPhrase(this@MainActivity, alarmId)
                                    FaceMissionSupport.commitPendingEmbedding(this@MainActivity, alarmId)
                                    alarmRefreshTrigger.value++

                                    val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
                                    val canScheduleExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        try {
                                            alarmManager.canScheduleExactAlarms()
                                        } catch (e: Exception) { false }
                                    } else { true }

                                    if (canScheduleExact) {
                                        try {
                                            alarmScheduler.schedule(enrolledAlarm)
                                        } catch (_: Exception) {}
                                    }

                                    if (missionType == "sequencer") {
                                        val currentQueue = missionSequencer?.queueStore?.loadQueue() ?: emptyList()
                                        val updatedQueue = currentQueue.map { mission ->
                                            mission.copy(params = mission.params + ("alarm_id" to alarmId.toString()))
                                        }
                                        missionSequencer?.queueStore?.saveQueue(updatedQueue)
                                    }
                                }

                                enrolledAlarm
                            }
                        },
                        cancelAlarmCallback = { alarmId ->
                            val alarm = alarmStorage.getAlarms().find { it.id == alarmId }
                            if (alarm != null) {
                                Log.d("FaceAuthFlow", "🗑️ Delete callback: alarmId=$alarmId, requiresFaceAuth=${alarm.requiresFaceAuth}")
                                if (alarm.requiresFaceAuth) {
                                    Log.d("FaceAuthFlow", "🔐 Face auth ENABLED for delete - calling performWithFaceAuth")
                                    performWithFaceAuth(MatchAction.DELETE, alarmId, alarm) { embedding ->
                                        if (embedding == null) {
                                            Log.w("FaceAuthFlow", "❌ Face auth failed or canceled - alarm delete canceled")
                                            return@performWithFaceAuth
                                        }
                                        val deleted = alarmStorage.deleteAlarm(alarmId)
                                        if (deleted) {
                                            alarmScheduler.cancel(alarm)
                                            alarmRefreshTrigger.value++
                                        } else {
                                            Log.w("DeleteFlow", "⚠️ Deletion blocked: alarm is currently snoozing (alarmId=$alarmId)")
                                            Toast.makeText(this@MainActivity, "Cannot delete alarm while snoozing", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } else {
                                    val deleted = alarmStorage.deleteAlarm(alarmId)
                                    if (deleted) {
                                        alarmScheduler.cancel(alarm)
                                        alarmRefreshTrigger.value++
                                    } else {
                                        Log.w("DeleteFlow", "⚠️ Deletion blocked: alarm is currently snoozing (alarmId=$alarmId)")
                                        Toast.makeText(this, "Cannot delete alarm while snoozing", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        alarmRefreshTrigger = alarmRefreshTrigger,
                        performWithFaceAuth = performWithFaceAuth
                    )

                    if (showFaceEnrollmentDialog) {
                        Log.d("FaceAuthFlow", "🎬 Composing FaceEnrollmentDialog, alarmId=$pendingAlarmId")
                        FaceEnrollmentDialog(
                            alarmId = pendingAlarmId,
                            onDismiss = {
                                // Must clear the whole pending flow, not just this dialog.
                                // Leaving pendingFaceAuthAction set made the auth dialog
                                // compose instead - which opened the camera on its own and
                                // kept doing so every time the app resumed from recents.
                                showFaceEnrollmentDialog = false
                                pendingFaceAuthAction = null
                                pendingActionCallback = null
                                pendingEnrollment = null
                            },
                            onEnrollmentComplete = { enrollment ->
                                showFaceEnrollmentDialog = false
                                if (enrollment != null && pendingFaceAuthAction == MatchAction.CREATE_OR_EDIT) {
                                    // For new alarms, enrollment's 5-frame liveness is sufficient verification
                                    val cb = pendingActionCallback
                                    pendingFaceAuthAction = null
                                    pendingActionCallback = null
                                    pendingEnrollment = null
                                    cb?.invoke(enrollment)
                                } else if (enrollment != null) {
                                    // For edit/delete, proceed to auth dialog
                                    pendingEnrollment = enrollment
                                } else {
                                    // Enrollment failed, cancel the operation
                                    pendingFaceAuthAction = null
                                    pendingActionCallback = null
                                    pendingEnrollment = null
                                }
                            }
                        )
                    }

                    // Never compose alongside the enrollment dialog: both bind CameraX to the
                    // front camera, and two bindings contend for the same device.
                    if (!showFaceEnrollmentDialog &&
                        pendingFaceAuthAction != null &&
                        (pendingAlarm?.enrolledFaceEmbedding != null || pendingEnrollment != null)
                    ) {
                        val enrolledFaceEmbedding = pendingEnrollment ?: pendingAlarm?.enrolledFaceEmbedding
                        val currentAlarm = pendingAlarm
                        val alarmForAuth = if (currentAlarm != null) {
                            currentAlarm.copy(enrolledFaceEmbedding = enrolledFaceEmbedding)
                        } else if (enrolledFaceEmbedding != null) {
                            Alarm(
                                id = pendingAlarmId,
                                hour = 0,
                                minute = 0,
                                isEnabled = false,
                                ringtoneUri = null,
                                enrolledFaceEmbedding = enrolledFaceEmbedding
                            )
                        } else {
                            null
                        }

                        if (alarmForAuth != null) {
                            FaceAuthDialog(
                                action = pendingFaceAuthAction!!,
                                alarmId = pendingAlarmId,
                                alarm = alarmForAuth,
                            onDismiss = {
                                pendingFaceAuthAction = null
                                pendingActionCallback = null
                                pendingEnrollment = null
                            },
                            onVerificationSuccess = {
                                val cb = pendingActionCallback
                                val embedding = pendingEnrollment ?: pendingAlarm?.enrolledFaceEmbedding
                                pendingFaceAuthAction = null
                                pendingActionCallback = null
                                pendingEnrollment = null
                                cb?.invoke(embedding)
                            },
                                onRequestEnrollment = {
                                    showFaceEnrollmentDialog = true
                                }
                            )
                        }
                    }
                }

                // Permissions button is shown inside AlarmScreenContent where its state lives
            }

        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Check permissions before handling any request from other apps (like SHORTS)
        if (!MainActivity.hasAllRequiredPermissions(this)) {
            Log.w("AlarmApp", "❌ Cannot handle request - missing required permissions")
            Toast.makeText(this, "Enable all permissions to create alarms", Toast.LENGTH_LONG).show()
            return
        }

        if (requestCode == 1001 && resultCode == RESULT_OK) {
            // Refresh UI silently when returning from GoogleSignInActivity
            alarmRefreshTrigger.value++
        }
    }

    private fun requestNecessaryPermissions() {
        // Auto permission popups on app launch disabled.
        // Permissions are requested on-demand when user taps settings or features.
    }

    private fun promptExactAlarmIfNeeded() {
        // Auto settings page redirect on app launch disabled.
    }

    private fun promptIgnoreBatteryOptimizationsIfNeeded() {
        // Disabled: Requesting battery optimization exemption causes Android's
        // AppStateAlarmsAndRemindersBridge to hide the app from "Alarms & reminders" settings.
        // setAlarmClock() is already Doze-exempt by design.
    }

    private fun promptAutoStartIfNeeded() {
        // Many OEMs gate auto-start under their own settings. Attempt best-effort intents.
        try {
            val prefs = this@MainActivity.getSharedPreferences("oem_prompts", Context.MODE_PRIVATE)
            if (prefs.getBoolean("auto_start_prompted_once", false)) {
                return
            }
            val manufacturer = Build.MANUFACTURER.lowercase()
            val tried = when {
                manufacturer.contains("xiaomi") -> {
                    // Redmi 13C (MIUI) often needs SecurityCenter + PowerKeeper pages
                    tryStart(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")) ||
                            tryStart(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")) ||
                            tryStart(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")).also { started ->
                                if (!started) {
                                    // Try explicit Intent with extras for HiddenAppsConfigActivity
                                    val i = Intent().apply {
                                        component = ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                                        putExtra("package_name", packageName)
                                        putExtra("package_label", getString(R.string.app_name))
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    tryStartIntent(i)
                                }
                            } ||
                            tryStart(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.PowerHideModeActivity")) ||
                            tryStart(ComponentName("com.miui.securitycenter", "com.miui.appmanager.AppManagerMainActivity")) ||
                            tryStartIntent(Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST"))
                }
                manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oplus") -> {
                    // Show comprehensive guidance dialog - user will open settings via button
                    showOppoBackgroundPopupDialog(this@MainActivity)
                    true // Dialog was shown successfully
                }
                manufacturer.contains("vivo") -> tryStart(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
                        || tryStart(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
                manufacturer.contains("huawei") -> tryStart(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                manufacturer.contains("oneplus") -> tryStart(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))
                else -> false
            }
            if (!tried) {
                // Fallback to app details where user can enable autostart/battery settings
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                prefs.edit().putBoolean("auto_start_prompted_once", true).commit()
                return
            }
            // Mark as prompted so we don't nag repeatedly across launches
            prefs.edit().putBoolean("auto_start_prompted_once", true).commit()
        } catch (_: Exception) { }
    }

    private fun tryStart(cn: android.content.ComponentName): Boolean {
        return try {
            val intent = Intent().apply {
                component = cn
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun tryStartIntent(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (_: Exception) { false }
    }

    private fun startAlarmNotificationChecker() {
        // Check more frequently to ensure we catch the 1-minute window
        Thread {
            while (true) {
                try {
                    Thread.sleep(2000) // Check every 2 seconds for even better precision
                    checkAndShowUpcomingAlarmNotification()

                    // Also check if we need to reschedule any daily alarms
                    rescheduleDailyAlarmsIfNeeded()
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.start()
    }

    private fun rescheduleDailyAlarmsIfNeeded() {
        try {
            val now = Calendar.getInstance()
            val currentHour = now.get(Calendar.HOUR_OF_DAY)
            val currentMinute = now.get(Calendar.MINUTE)

            // If it's around midnight (between 12:00 AM and 12:05 AM), reschedule all daily alarms
            // This ensures daily alarms are properly set for the new day
            if (currentHour == 0 && currentMinute <= 5) {
                val alarms = alarmStorage.getAlarms().filter { it.isEnabled && !it.days.isNullOrEmpty() }
                alarms.forEach { alarm ->
                    // Reschedule the alarm to ensure it's set for the correct day
                    alarmScheduler.schedule(alarm)
                    Log.d("AlarmApp", "Rescheduled daily alarm ID: ${alarm.id} at ${alarm.hour}:${alarm.minute}")
                }
            }
        } catch (e: Exception) {
            Log.e("AlarmApp", "Error rescheduling daily alarms: ${e.message}", e)
        }
    }

    // =====================================================
    // Fields
    // =====================================================
    private var lastNotifiedAlarmId: Int? = null
    private var lastNotificationTime: Long = 0

    // =====================================================
    // Main Function (original kept; new logic appended)
    // =====================================================
    fun resetNotificationTracking() {
        lastNotifiedAlarmId = null
    }

    fun checkAndShowUpcomingAlarmNotification() {
        try {
            val nextAlarm = getNextScheduledAlarm()
            if (nextAlarm != null) {
                val timeUntilAlarm = getTimeUntilNextAlarmInMinutes(nextAlarm)
                val now = System.currentTimeMillis()

                // =====================================================
                // ✅ ORIGINAL LOGIC  (ONLY BOUNDARY FIXED)
                // Was: if (timeUntilAlarm <= 1.05 && timeUntilAlarm > 0.95)
                // NOW: trigger only when < 1.0 min
                // =====================================================
                if (timeUntilAlarm <= 1.0 && timeUntilAlarm > 0.0) {
                    // Only show notification once per alarm when it enters the 1-minute window
                    // Allow notification if it's a different alarm or if we haven't shown it for this alarm
                    if (lastNotifiedAlarmId != nextAlarm.id) {
                        showNotification(
                            "Next Alarm",
                            "${formatTime12Hour(nextAlarm.hour, nextAlarm.minute)}"
                        )
                        lastNotifiedAlarmId = nextAlarm.id
                        lastNotificationTime = now
                    }
                } else {
                    // Reset the notification tracking when the alarm is more than 1 minute away
                    if (timeUntilAlarm > 1.2) {
                        lastNotifiedAlarmId = null
                    }
                }
                // ✅ END ORIGINAL LOGIC
                // =====================================================
            }
        } catch (e: Exception) {
            Log.e("AlarmApp", "Error checking upcoming alarms: ${e.message}", e)
        }
    }

    // =====================================================
    // Helper (NEW) — compute millis until next occurrence
    // =====================================================
    private fun getMillisUntilNextAlarm(alarm: Alarm): Long {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            set(java.util.Calendar.HOUR_OF_DAY, alarm.hour)
            set(java.util.Calendar.MINUTE, alarm.minute)
        }

        val now = System.currentTimeMillis()
        var target = cal.timeInMillis
        if (target <= now) {
            // if today's time already passed, roll to next day
            target += 24L * 60 * 60 * 1000
        }
        return target - now
    }

    private fun getNextScheduledAlarm(): Alarm? {
        val alarms = alarmStorage.getAlarms().filter { it.isEnabled }
        if (alarms.isEmpty()) return null

        var nextAlarm: Alarm? = null
        var nextAlarmTime = Long.MAX_VALUE

        for (alarm in alarms) {
            val alarmTime = calculateNextAlarmTime(alarm)
            if (alarmTime < nextAlarmTime) {
                nextAlarmTime = alarmTime
                nextAlarm = alarm
            }
        }

        return nextAlarm
    }

    private fun calculateNextAlarmTime(alarm: Alarm): Long {
        val now = Calendar.getInstance()
        if (alarm.days.isNullOrEmpty() && !alarm.repeatDaily && alarm.alarmTime > now.timeInMillis) {
            return alarm.alarmTime
        }
        val alarmCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (!alarm.days.isNullOrEmpty()) {
            val currentDay = now.get(Calendar.DAY_OF_WEEK)
            val currentHour = now.get(Calendar.HOUR_OF_DAY)
            val currentMinute = now.get(Calendar.MINUTE)

            // Check if today is a valid day and the alarm time hasn't passed yet
            if (alarm.days.contains(currentDay)) {
                if (alarm.hour > currentHour ||
                    (alarm.hour == currentHour && alarm.minute > currentMinute)) {
                    return alarmCalendar.timeInMillis
                }
            }

            // Look for the next valid day
            for (i in 1..7) {
                val checkDay = (currentDay + i - 1) % 7 + 1
                if (alarm.days.contains(checkDay)) {
                    alarmCalendar.add(Calendar.DAY_OF_YEAR, i)
                    return alarmCalendar.timeInMillis
                }
            }
        } else {
            // For one-time alarms (including the protected 5 AM alarm),
            // if the time has passed today, schedule for tomorrow
            if (alarmCalendar.timeInMillis <= now.timeInMillis) {
                alarmCalendar.add(Calendar.DAY_OF_YEAR, 1)
            }
            return alarmCalendar.timeInMillis
        }

        // Fallback - add one day
        alarmCalendar.add(Calendar.DAY_OF_YEAR, 1)
        return alarmCalendar.timeInMillis
    }

    private fun getTimeUntilNextAlarmInMinutes(alarm: Alarm): Double {
        val nextAlarmTime = calculateNextAlarmTime(alarm)
        val now = System.currentTimeMillis()
        val diffInMillis = nextAlarmTime - now
        return diffInMillis.toDouble() / (1000.0 * 60.0)
    }

    private fun showNotification(title: String, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("MainActivity", "Skipping notification post: POST_NOTIFICATIONS permission not granted")
            return
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        // Use a fresh channel ID to avoid previously muted channel settings on the device
        val channelId = "next_alarm_conv_v2"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Next Alarm Alerts",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Shows information about your next alarm"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
                // Let users enable bubbles (Android 11+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try { setAllowBubbles(true) } catch (_: Exception) { }
                }
                // Explicitly set a default notification sound
                try {
                    val soundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                    val attrs = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setSound(soundUri, attrs)
                } catch (_: Exception) { }
            }
            notificationManager.createNotificationChannel(channel)
        }

        // No full-screen intent for WhatsApp-like conversation behavior

        val now = System.currentTimeMillis()
        val me = androidx.core.app.Person.Builder()
            .setName("Alarm")
            .setImportant(true)
            .build()
        val messaging = androidx.core.app.NotificationCompat.MessagingStyle(me)
            .addMessage(content, now, me)
            .setConversationTitle("Next Alarm")
            .setGroupConversation(false)

        // Ensure a dynamic shortcut exists for this conversation id so the
        // system can rank it under Conversations like WhatsApp.
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
                val sm = getSystemService(android.content.pm.ShortcutManager::class.java)
                val b = android.content.pm.ShortcutInfo.Builder(this, "next_alarm_conv")
                    .setShortLabel("Next Alarm")
                    .setLongLabel("Next Alarm")
                    .setIntent(android.content.Intent(this, MainActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra("from_next_alarm_notice", true)
                    })
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    try { b.setLongLived(true) } catch (_: Exception) { }
                }
                val shortcut = b.build()
                try { sm?.addDynamicShortcuts(java.util.Collections.singletonList(shortcut)) } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(android.graphics.Color.parseColor("#1976D2"))
            .setContentTitle(title)
            .setContentText(content)
            .setWhen(now)
            .setShowWhen(true)
            .setStyle(messaging)
            .setShortcutId("next_alarm_conv")
            .setLocusId(androidx.core.content.LocusIdCompat("next_alarm_conv"))
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()

        // Post as a standard conversation notification so MIUI places it in Conversations at the top.
        notificationManager.notify(2, notification)
    }

    private fun clearNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(2)
    }

    private fun scheduleProtectedDefaultAlarm() {
        val defaultAlarmHour = 5
        val defaultAlarmMinute = 0
        val glossyId = resources.getIdentifier("glassy_bell", "raw", packageName)
        val pyaroId = resources.getIdentifier("pyaro_vrindavan", "raw", packageName)

        val defaultRingtoneUri = when {
            pyaroId != 0 -> Uri.parse("android.resource://${packageName}/$pyaroId")
            glossyId != 0 -> Uri.parse("android.resource://${packageName}/$glossyId")
            else -> Uri.parse("android.resource://${packageName}/raw/glassy_bell")
        }

        val defaultDays = listOf(
            Calendar.SUNDAY,
            Calendar.MONDAY,
            Calendar.TUESDAY,
            Calendar.WEDNESDAY,
            Calendar.THURSDAY,
            Calendar.FRIDAY,
            Calendar.SATURDAY
        )

        val existingDefaultAlarm = alarmStorage.getAlarms().firstOrNull {
            it.hour == defaultAlarmHour &&
                    it.minute == defaultAlarmMinute &&
                    it.ringtoneUri == defaultRingtoneUri &&
                    it.days?.containsAll(defaultDays) == true &&
                    it.isProtected
        }

        if (existingDefaultAlarm == null) {
            val alarmId = alarmStorage.getNextAlarmId()
            val protectedDefaultAlarm = Alarm(
                id = alarmId,
                hour = defaultAlarmHour,
                minute = defaultAlarmMinute,
                isEnabled = true,
                ringtoneUri = defaultRingtoneUri,
                days = defaultDays,
                isHidden = false,
                isProtected = true
            )
            // Check permissions before creating protected default alarm
            if (!MainActivity.hasAllRequiredPermissions(this)) {
                Log.w("AlarmApp", "❌ Cannot create protected default alarm - missing permissions")
                Toast.makeText(this, "Enable all permissions to create alarms", Toast.LENGTH_LONG).show()
                return
            }
            alarmStorage.addAlarm(protectedDefaultAlarm)
            alarmScheduler.schedule(protectedDefaultAlarm)
            alarmRefreshTrigger.value++
            markAlarmSet(true)
            Log.d("AlarmApp", "✅ Protected default 5 AM alarm scheduled")
        } else {
            Log.d("AlarmApp", "ℹ️ Protected default 5 AM alarm already exists.")
        }
    }

    private fun scheduleAndSaveAlarm(
        hour: Int,
        minute: Int,
        ringtoneUri: Uri?,
        days: Set<Int>
    ): Alarm? {
        // Check if all required permissions are granted
        if (!MainActivity.hasAllRequiredPermissions(this)) {
            Log.w("AlarmApp", "❌ Cannot create alarm - missing required permissions")
            Toast.makeText(this, "Enable all permissions to create alarms", Toast.LENGTH_LONG).show()
            return null
        }

        val alarmId = alarmStorage.getNextAlarmId()
        val ringtoneUriString = ringtoneUri?.toString()

        val alarm = Alarm(
            id = alarmId,
            hour = hour,
            minute = minute,
            isEnabled = true,
            ringtoneUri = ringtoneUri ?: Settings.System.DEFAULT_ALARM_ALERT_URI,
            days = days.toList(),
            isHidden = false,
            isProtected = false
        )
        alarmStorage.addAlarm(alarm)
        alarmScheduler.schedule(alarm)
        alarmRefreshTrigger.value++

        markAlarmSet(true)
        // Remove the notification update as we don't want to show notifications when alarms are set
        // updateNextAlarmNotification()
        return alarm
    }

    private fun cancelAlarm(alarmId: Int) {
        val alarm = alarmStorage.getAlarms().firstOrNull { it.id == alarmId }
        if (alarm != null && !alarm.isProtected) {
            alarmScheduler.cancel(alarm)
            val deleted = alarmStorage.deleteAlarm(alarmId)
            if (deleted) {
                markAlarmSet(false)
                Log.d("AlarmApp", "Alarm with ID $alarmId cancelled.")
                // Remove the notification update as we don't want to show notifications when alarms are set
                // updateNextAlarmNotification()
            } else {
                Log.w("DeleteFlow", "⚠️ Deletion blocked: alarm is currently snoozing (alarmId=$alarmId)")
                Toast.makeText(this, "Cannot delete alarm while snoozing", Toast.LENGTH_SHORT).show()
            }
        }
    }

    public fun markAlarmSet(value: Boolean) {
        val prefs = this@MainActivity.getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("alarm_set", value).commit()
    }
    private val clearNotificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.vaishnava.alarm.CLEAR_NOTIFICATION") {
                clearNotification()
            }
        }
    }

    private val alarmRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.vaishnava.alarm.REFRESH_ALARMS") {
                // Refresh UI silently (WhatsApp style)
                Log.d("MainActivity", "REFRESH_ALARMS received: refreshing list silently")
                alarmRefreshTrigger.value++
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(clearNotificationReceiver)
        } catch (e: Exception) {
            // Ignore if receiver was not registered
        }
        try {
            unregisterReceiver(alarmRefreshReceiver)
        } catch (e: Exception) {
            // Ignore if receiver was not registered
        }

        // Cleanup mission sequencer
        if (missionSequencer != null) {
            missionSequencer?.destroy()
        }

        // Cleanup completed
    }

    private fun tryRestoreFromCloud() {
        // Auto-restore remains disabled. Google sign-in should not modify alarms on startup.
        // Intentionally left empty to avoid modifying alarms silently on startup.
    }

    private fun logSigningFingerprints() {
        try {
            val pm = packageManager
            val pkg = packageName
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                val current = info.signingInfo?.apkContentsSigners
                val history = info.signingInfo?.signingCertificateHistory
                val signatures = current ?: history
                signatures?.forEach { sig ->
                    val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(sig.toByteArray())
                    val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                    fun ByteArray.hex() = joinToString(":") { "%02X".format(it) }
                    Log.i("AppSHA", "SHA-1: ${sha1.hex()}")
                    Log.i("AppSHA", "SHA-256: ${sha256.hex()}")
                } ?: run {
                    Log.w("AppSHA", "No signing signatures available")
                }
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.forEach { sig ->
                    val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(sig.toByteArray())
                    val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                    fun ByteArray.hex() = joinToString(":") { "%02X".format(it) }
                    Log.i("AppSHA", "SHA-1: ${sha1.hex()}")
                    Log.i("AppSHA", "SHA-256: ${sha256.hex()}")
                } ?: run {
                    Log.w("AppSHA", "No legacy signatures available")
                }
            }
        } catch (e: Exception) {
            Log.e("AppSHA", "Failed to compute fingerprints", e)
        }
    }
}

fun formatDays(days: List<Int>?, alarmTimeMillis: Long? = null, selectedDates: List<Long>? = null): String {
    if (!selectedDates.isNullOrEmpty()) {
        val sortedDates = selectedDates.sorted()
        val cal = Calendar.getInstance()
        val grouped = sortedDates.groupBy { millis ->
            cal.timeInMillis = millis
            cal.get(Calendar.MONTH) to cal.get(Calendar.YEAR)
        }
        val parts = mutableListOf<String>()
        val monthSdf = SimpleDateFormat("MMM", Locale.getDefault())
        grouped.forEach { (_, millisList) ->
            cal.timeInMillis = millisList.first()
            val monthName = monthSdf.format(cal.time)
            val daysStr = millisList.joinToString(", ") { m ->
                cal.timeInMillis = m
                cal.get(Calendar.DAY_OF_MONTH).toString()
            }
            parts.add("$monthName $daysStr")
        }
        return parts.joinToString("; ")
    }
    if (days.isNullOrEmpty()) {
        if (alarmTimeMillis != null && alarmTimeMillis > 0L) {
            val sdf = java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault())
            return sdf.format(java.util.Date(alarmTimeMillis))
        }
        return "No days selected"
    }

    // Check if all 7 days are selected
    val allDays = setOf(Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY)
    if (days.toSet() == allDays) return "Everyday"

    val dayMap = mapOf(
        Calendar.SUNDAY to "Su",
        Calendar.MONDAY to "Mo",
        Calendar.TUESDAY to "Tu",
        Calendar.WEDNESDAY to "We",
        Calendar.THURSDAY to "Th",
        Calendar.FRIDAY to "Fr",
        Calendar.SATURDAY to "Sa"
    )
    return days.sorted().joinToString(", ") { dayMap[it] ?: "" }
}

public fun formatTime12Hour(hour: Int, minute: Int): String {
    val period = if (hour >= 12) "PM" else "AM"
    val formattedHour = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return String.format("%d:%02d %s", formattedHour, minute, period)
}


@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AlarmScreenContent(
    alarmStorage: AlarmStorage,
    alarmScheduler: AlarmScheduler,
    scheduleAlarmCallback: (Int, Int, Uri?, Set<Int>, String?, String?, Boolean, Int, Boolean, Boolean, Boolean, Boolean, Boolean, List<Long>, Boolean, Int) -> Alarm,
    cancelAlarmCallback: (Int) -> Unit,
    alarmRefreshTrigger: MutableState<Int>,
    performWithFaceAuth: (MatchAction, Int, Alarm?, (embedding: FloatArray?) -> Unit) -> Unit = { _, _, _, cb -> cb(null) }
) {
    val context = LocalContext.current
    val alarms = remember { mutableStateListOf<Alarm>() }
    val showAddDialog = remember { mutableStateOf(false) }
    val showDiagnosticsDialog = remember { mutableStateOf(false) }
    val showForensicsDialog = remember { mutableStateOf(false) }
    val showPermissionsDialog = remember { mutableStateOf(!MainActivity.hasAllRequiredPermissions(context)) }
    val showQueueMissionsDialog = remember { mutableStateOf(false) }
    val showShiftRotationDialog = remember { mutableStateOf(false) }
    val showShiftCalibrationDialog = remember { mutableStateOf(false) }
    val showShiftReassignmentWarning = remember { mutableStateOf(false) }
    val pendingShiftConfig = remember { mutableStateOf<ShiftRotationConfig?>(null) }
    val requestedReassignmentIndex = remember { mutableStateOf(-1) }
    val shiftConfigRefresh = remember { mutableStateOf(0) }
    val shiftRotationStore = remember { ShiftRotationStore(context) }
    val shiftRotationCoordinator = remember { ShiftRotationCoordinator(context, shiftRotationStore) }
    val showPermissionsButton = remember { mutableStateOf(true) }
    val showPowerSaveWarning = remember { mutableStateOf(false) }
    val isShiftRotationEnabled = remember { mutableStateOf(false) }

    fun recomputePermissionsVisibility() {
        try {
            showPowerSaveWarning.value = try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                pm.isPowerSaveMode
            } catch (_: Exception) {
                false
            }

            val allGranted = MainActivity.hasAllRequiredPermissions(context)
            showPermissionsButton.value = !allGranted
            Log.d("UI_Permissions", "=== UI PERMISSION VISIBILITY ===")
            Log.d("UI_Permissions", "All permissions OK: $allGranted")
            Log.d("UI_Permissions", "Show permission button: ${showPermissionsButton.value}")
            Log.d("UI_Permissions", "================================")
        } catch (_: Exception) {
            showPermissionsButton.value = true
        }
    }

    // Check permissions automatically on app start and resume
    LaunchedEffect(Unit) {
        recomputePermissionsVisibility()
        if (!MainActivity.hasAllRequiredPermissions(context)) {
            showPermissionsDialog.value = true
        }
    }

    // Also check when app resumes
    val lifecycleOwner2 = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner2) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                recomputePermissionsVisibility()
                if (!MainActivity.hasAllRequiredPermissions(context)) {
                    showPermissionsDialog.value = true
                }
            }
        }
        lifecycleOwner2.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner2.lifecycle.removeObserver(obs) }
    }

    // We'll keep a simple state for hour/minute (same logic preserved)
    val timeHour = remember { mutableStateOf(7) }
    val timeMinute = remember { mutableStateOf(0) }

    val selectedDaysState: MutableState<Set<Int>> = remember { mutableStateOf(setOf<Int>()) }
    val selectedRingtoneUriState: MutableState<Uri?> = remember {
        mutableStateOf(RingtonePreferences.getLastRingtone(context))
    }
    val mediaPlayerState: MutableState<MediaPlayer?> = remember { mutableStateOf(null) }
    val showRingtonePicker = remember { mutableStateOf(false) }
    // Wake-up check (per-alarm)
    val wakeCheckEnabledState = remember { mutableStateOf(false) }
    val wakeCheckMinutesState = remember { mutableStateOf(5) }
    // Audio settings (per-alarm)
    val enableGradualVolumeState = remember { mutableStateOf(false) }
    val enableAudioBoostState = remember { mutableStateOf(false) }
    // Snooze button setting (per-alarm)
    val enableSnoozeState = remember { mutableStateOf(false) }
    val snoozeMinutesState = remember { mutableStateOf(5) }
    // Protected alarm state
    val protectedAlarmState = remember { mutableStateOf(false) }
    // Power menu prevention state (per-alarm)
    val preventPowerOffState = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        alarms.clear()
        alarms.addAll(alarmStorage.getAlarms())
    }

    // Reload list when refresh trigger changes (e.g., after cloud restore)
    LaunchedEffect(alarmRefreshTrigger.value) {
        alarms.clear()
        alarms.addAll(alarmStorage.getAlarms())
    }

    // Reload list when activity resumes so the list stays current after returning to the app.
    val listLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(listLifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                alarms.clear()
                alarms.addAll(alarmStorage.getAlarms())
            }
        }
        listLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { listLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayerState.value?.stop()
            mediaPlayerState.value?.release()
            mediaPlayerState.value = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        IconButton(
            onClick = { showForensicsDialog.value = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 20.dp, end = 16.dp)
                .size(36.dp)
                .background(
                    MaterialTheme.colorScheme.secondaryContainer,
                    CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Device Info & Diagnostics",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Replace the alarm icon with the app logo using Image composable
                // Use the webp file directly from mipmap
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = "App Logo",
                    modifier = Modifier.size(72.dp)
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "CHRONA",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Precision Time Management",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(12.dp))

                val firebaseAuth = remember { FirebaseAuth.getInstance() }
                val accountState = remember { mutableStateOf(firebaseAuth.currentUser) }
                DisposableEffect(firebaseAuth) {
                    val listener = FirebaseAuth.AuthStateListener { auth ->
                        accountState.value = auth.currentUser
                    }
                    firebaseAuth.addAuthStateListener(listener)
                    onDispose { firebaseAuth.removeAuthStateListener(listener) }
                }
                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            accountState.value = firebaseAuth.currentUser
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                val currentAccount = accountState.value
                if (currentAccount == null) {
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(context, GoogleSignInActivity::class.java)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(context, "Unable to open Google Sign-In: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(48.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.onPrimary)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.googleg_standard_color_18),
                                    contentDescription = "Google Logo",
                                    tint = Color.Unspecified,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .align(Alignment.Center)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Sign in with Google",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    // Show signed-in state
                    Column(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(context, GoogleSignInActivity::class.java)
                                    (context as? Activity)?.startActivityForResult(intent, 1001)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "Unable to open Google account: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            ),
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .height(48.dp)
                        ) {
                            Text(
                                text = "Signed in",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (showPermissionsButton.value) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showPermissionsDialog.value = true },
                        modifier = Modifier.fillMaxWidth(0.9f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onBackground
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                    ) { Text("Permissions & Power Settings", color = MaterialTheme.colorScheme.onBackground) }
                }

                if (showPowerSaveWarning.value) {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF332200))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "⚠️ Battery Saver is ON",
                                color = Color(0xFFFFB74D),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Alarms have been observed ringing several minutes late while Battery Saver is active on this device. Turn it off (or exclude Chrona from it) before bed for on-time alarms.",
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            TextButton(
                                onClick = {
                                    try {
                                        context.startActivity(
                                            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    } catch (_: Exception) {
                                        try {
                                            context.startActivity(
                                                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        } catch (_: Exception) { }
                                    }
                                },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Open Battery Settings", color = Color(0xFFFFB74D))
                            }
                        }
                    }
                }
            }

            // The alarms header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "YOUR ALARMS",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = "${alarms.size} items",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // alarms list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (alarms.isEmpty()) {
                    // Show empty state without any card
                } else {
                    items(alarms.sortedBy { it.hour * 60 + it.minute }, key = { it.id }) { alarm ->
                        val context = LocalContext.current
                        AlarmItem(
                            alarm = alarm,
                            onRemove = { alarmToRemove ->
                                val deleteAction = {
                                    alarmScheduler.cancel(alarmToRemove)
                                    val deleted = alarmStorage.deleteAlarm(alarmToRemove.id)
                                    if (deleted) {
                                        alarms.remove(alarmToRemove)

                                        // CRITICAL FIX: Cancel all possible notifications when alarm is deleted
                                        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                        notificationManager.cancel(alarmToRemove.id)
                                        notificationManager.cancel(Constants.NOTIFICATION_ID_UPCOMING_ALARM)
                                        notificationManager.cancel(2) // Main notification ID
                                        notificationManager.cancel(1001) // Boot receiver notification ID
                                    } else {
                                        Log.w("DeleteFlow", "⚠️ Deletion blocked: alarm is currently snoozing (alarmId=${alarmToRemove.id})")
                                        Toast.makeText(context, "Cannot delete alarm while snoozing", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                if (alarmToRemove.requiresFaceAuth) {
                                    performWithFaceAuth(MatchAction.DELETE, alarmToRemove.id, alarmToRemove) { embedding ->
                                        if (embedding == null) {
                                            Log.w("FaceAuthFlow", "❌ Face auth failed or canceled - alarm delete canceled")
                                            return@performWithFaceAuth
                                        }
                                        deleteAction()
                                    }
                                } else {
                                    deleteAction()
                                }
                            },
                            onToggleEnable = { alarmToToggle, isEnabled ->
                                val toggleAction = {
                                    // Do not allow toggle while wake-up-check gate is active for this alarm
                                    val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                        context.createDeviceProtectedStorageContext() ?: context
                                    } else context
                                    val prefs = dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                                    val gateActive = prefs.getBoolean("wakecheck_gate_active_${alarmToToggle.id}", false)
                                    val pending = prefs.getBoolean("wakecheck_pending_${alarmToToggle.id}", false)

                                    if (!gateActive && !pending) {
                                        val updatedAlarm = alarmToToggle.copy(isEnabled = isEnabled)
                                        alarmStorage.updateAlarm(updatedAlarm)
                                        alarmRefreshTrigger.value++
                                        if (isEnabled) {
                                            alarmScheduler.schedule(updatedAlarm)
                                        } else {
                                            alarmScheduler.cancel(updatedAlarm)
                                        }
                                        val index = alarms.indexOfFirst { it.id == updatedAlarm.id }
                                        if (index != -1) {
                                            alarms[index] = updatedAlarm
                                        }

                                        Toast.makeText(context, "Saved. Final", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                if (alarmToToggle.requiresFaceAuth) {
                                    performWithFaceAuth(MatchAction.CREATE_OR_EDIT, alarmToToggle.id, alarmToToggle) { embedding ->
                                        if (embedding == null) {
                                            Log.w("FaceAuthFlow", "❌ Face auth failed or canceled - alarm toggle canceled")
                                            Toast.makeText(context, "Face authentication required", Toast.LENGTH_LONG).show()
                                            return@performWithFaceAuth
                                        }
                                        toggleAction()
                                    }
                                } else {
                                    toggleAction()
                                }
                            },
                            onTimeEdit = onTimeEditLabel@ { alarmToEdit, newHour, newMinute, newRingtoneUri, newDays, newMissionType, newMissionPassword, newIsProtected, newWakeCheckEnabled, newWakeCheckMinutes, newEnableGradualVolume, newEnableAudioBoost, newRequiresFaceAuth, newEnableSnooze, newSnoozeMinutes ->
                                val editAction = {
                                    // CRITICAL FIX: Never store "none" - convert to empty string or infer from missionPassword
                                    val actualMissionType = if (SequencerMissionCodec.isSequencerConfig(newMissionPassword)) {
                                        Log.d("MainActivity", "Auto-setting missionType to 'sequencer' for updated sequence password: $newMissionPassword")
                                        "sequencer"
                                    } else if (newMissionType == "none" || newMissionType.isNullOrEmpty()) {
                                        // If user selected "none" or no mission, infer from missionPassword
                                        when {
                                            newMissionPassword.isNullOrEmpty() -> ""
                                            SequencerMissionCodec.isSequencerConfig(newMissionPassword) -> "sequencer"
                                            newMissionPassword.lowercase().contains("pwd") ||
                                                    newMissionPassword.lowercase().contains("password") -> "password"
                                            else -> "tap"
                                        }
                                    } else {
                                        newMissionType
                                    }

                                    // CRITICAL FIX: For password missions, use default password if none provided
                                    val finalMissionPassword = when {
                                        actualMissionType == "password" && newMissionPassword.isNullOrEmpty() -> {
                                            Log.d("MainActivity", "Using default password for updated password mission")
                                            "IfYouWantYouCanSleep"
                                        }
                                        else -> newMissionPassword
                                    }

                                    // Update the alarm with all new values and mark time change as used
                                    val updatedAlarm = alarmToEdit.copy(
                                        hour = newHour,
                                        minute = newMinute,
                                        ringtoneUri = newRingtoneUri,
                                        days = newDays?.toList(),
                                        missionType = actualMissionType,
                                        missionPassword = finalMissionPassword,
                                        isProtected = newIsProtected,
                                        wakeCheckEnabled = newWakeCheckEnabled,
                                        wakeCheckMinutes = if (newEnableSnooze) newSnoozeMinutes else newWakeCheckMinutes,
                                        enableGradualVolume = newEnableGradualVolume,
                                        enableAudioBoost = newEnableAudioBoost,
                                        requiresFaceAuth = newRequiresFaceAuth,
                                        enableSnooze = newEnableSnooze,
                                        repeatDaily = newDays?.size == 7,
                                        timeChangeUsed = true
                                    )
                                    // Save the ringtone as last used when alarm is updated
                                    RingtonePreferences.saveLastRingtone(context, newRingtoneUri)

                                    alarmStorage.updateAlarm(updatedAlarm)
                                    SttMissionSupport.commitPendingTargetPhrase(context, alarmToEdit.id)
                                    FaceMissionSupport.commitPendingEmbedding(context, alarmToEdit.id)
                                    alarmRefreshTrigger.value++
                                    alarmScheduler.schedule(updatedAlarm)

                                    // Update the alarm in the list
                                    val index = alarms.indexOfFirst { it.id == updatedAlarm.id }
                                    if (index != -1) {
                                        alarms[index] = updatedAlarm
                                    }

                                    Toast.makeText(context, "Saved. Final", Toast.LENGTH_SHORT).show()
                                }
                                if (newRequiresFaceAuth) {
                                    performWithFaceAuth(MatchAction.CREATE_OR_EDIT, alarmToEdit.id, alarmToEdit) { embedding ->
                                        if (embedding == null) {
                                            Log.w("FaceAuthFlow", "❌ Face enrollment failed or canceled - alarm not updated")
                                            Toast.makeText(context, "Face enrollment required to save this alarm", Toast.LENGTH_LONG).show()
                                            return@performWithFaceAuth
                                        }
                                        editAction()
                                    }
                                } else {
                                    editAction()
                                }
                            },
                            onEditShift = { showShiftRotationDialog.value = true },
                            onRemoveShift = {
                                performWithFaceAuth(MatchAction.DELETE, -1, null) { _ ->
                                    val config = shiftRotationStore.read()
                                    if (config != null) {
                                        shiftRotationStore.clear()
                                        val storage = AlarmStorage(context)
                                        val allAlarms = storage.getAlarms()
                                        val shiftAlarmIds = config.runtime.generatedAlarmIds.toSet()
                                        val shiftAlarms = allAlarms.filter { it.id in shiftAlarmIds }
                                        shiftAlarms.forEach { a ->
                                            alarmScheduler.cancel(a)
                                            storage.deleteAlarm(a.id)
                                        }
                                        Toast.makeText(context, "Shift rotation and alarms cleared", Toast.LENGTH_SHORT).show()
                                        shiftConfigRefresh.value++
                                        alarmRefreshTrigger.value++
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()

        // FAB
        FloatingActionButton(
            onClick = {
                // Initialize dialog time from system state or defaults
                val now = Calendar.getInstance()
                // Set default to current time + 1 minute
                now.add(Calendar.MINUTE, 1)
                timeHour.value = now.get(Calendar.HOUR_OF_DAY)
                timeMinute.value = now.get(Calendar.MINUTE)

                // Reset dialog states for new alarm
                selectedDaysState.value = emptySet()
                selectedRingtoneUriState.value = RingtonePreferences.getLastRingtone(context)
                wakeCheckEnabledState.value = false
                wakeCheckMinutesState.value = 0
                enableGradualVolumeState.value = false
                enableAudioBoostState.value = false
                enableSnoozeState.value = false
                snoozeMinutesState.value = 5
                protectedAlarmState.value = false
                preventPowerOffState.value = false
                isShiftRotationEnabled.value = false

                showAddDialog.value = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .navigationBarsPadding(),
            containerColor = if (isDarkTheme) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
            elevation = FloatingActionButtonDefaults.elevation(6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Add Alarm",
                tint = if (isDarkTheme) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
            )
        }
    }

    // Add Alarm Dialog
    if (showAddDialog.value) {
        AddAlarmDialog(
            timeHour = timeHour,
            timeMinute = timeMinute,
            selectedDaysState = selectedDaysState,
            selectedRingtoneUriState = selectedRingtoneUriState,
            wakeCheckEnabledState = wakeCheckEnabledState,
            wakeCheckMinutesState = wakeCheckMinutesState,
            enableGradualVolumeState = enableGradualVolumeState,
            enableAudioBoostState = enableAudioBoostState,
            enableSnoozeState = enableSnoozeState,
            snoozeMinutesState = snoozeMinutesState,
            protectedAlarmState = protectedAlarmState,
            requiresFaceAuthState = remember { mutableStateOf(false) },
            preventPowerOffState = preventPowerOffState,
            alarms = alarms,
            onDismissRequest = { showAddDialog.value = false },
            onConfirm = { hour, minute, ringtoneUri, days, missionType, missionPassword, wakeCheckEnabled, wakeCheckMinutes, enableGradualVolume, enableAudioBoost, isProtected, requiresFaceAuth, preventPowerOff, targetAlarmTime, enableSnooze, snoozeMinutes ->
                scheduleAlarmCallback(
                    hour,
                    minute,
                    ringtoneUri,
                    days,
                    missionType,
                    missionPassword,
                    wakeCheckEnabled,
                    wakeCheckMinutes,
                    enableGradualVolume,
                    enableAudioBoost,
                    isProtected,
                    requiresFaceAuth,
                    preventPowerOff,
                    targetAlarmTime,
                    enableSnooze,
                    snoozeMinutes
                )
                showAddDialog.value = false
                // Refresh the alarms list
                alarms.clear()
                alarms.addAll(alarmStorage.getAlarms())

                // CRITICAL FIX: Reset notification tracking and check for upcoming notification after adding alarm
                (context as MainActivity).resetNotificationTracking()
                (context as MainActivity).checkAndShowUpcomingAlarmNotification()
            },
            mediaPlayerState = mediaPlayerState,
            showRingtonePicker = showRingtonePicker,
            initialMissionType = "",
            initialMissionPassword = null,
            isShiftRotation = isShiftRotationEnabled,
            onOpenShiftRotation = { showShiftRotationDialog.value = true }
        )
    }

    if (showShiftRotationDialog.value) {
        val persistedShiftConfig = remember(shiftConfigRefresh.value) { shiftRotationStore.read() }
        val coroutineScope = rememberCoroutineScope()
        ShiftRotationDialog(
            existingConfig = persistedShiftConfig,
            onDismiss = {
                showShiftRotationDialog.value = false
                showAddDialog.value = true // Reopen Add Alarm dialog
            },
            onSaveRules = { config ->
                coroutineScope.launch(Dispatchers.IO) {
                    val result = shiftRotationCoordinator.saveCalibratedConfig(config, persistedShiftConfig?.anchorRotationIndex ?: 0, generateAlarms = false)
                    withContext(Dispatchers.Main) {
                        pendingShiftConfig.value = config
                        showShiftRotationDialog.value = false
                        showShiftCalibrationDialog.value = true
                    }
                }
            },
            onRequestReassignment = { index ->
                requestedReassignmentIndex.value = index
                showShiftReassignmentWarning.value = true
            },
            onCancelPending = {
                shiftConfigRefresh.value += 1
                alarms.clear()
                alarms.addAll(alarmStorage.getAlarms())
                showAddDialog.value = true // Reopen Add Alarm dialog
            }
        )
    }

    if (showShiftCalibrationDialog.value) {
        val config = pendingShiftConfig.value
        if (config != null) {
            val coroutineScope = rememberCoroutineScope()
            ShiftCalibrationDialog(
                config = config,
                onDismiss = {
                    showShiftCalibrationDialog.value = false
                    pendingShiftConfig.value = null
                    showAddDialog.value = true // Reopen Add Alarm dialog
                },
                onConfirm = { currentRotationIndex ->
                    coroutineScope.launch(Dispatchers.IO) {
                        val result = shiftRotationCoordinator.saveCalibratedConfig(config, currentRotationIndex, generateAlarms = true)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                            showShiftCalibrationDialog.value = false
                            pendingShiftConfig.value = null
                            shiftConfigRefresh.value += 1
                            showAddDialog.value = true // Reopen Add Alarm dialog
                        }
                    }
                }
            )
        }
    }

    if (showShiftReassignmentWarning.value) {
        val coroutineScope = rememberCoroutineScope()
        AlertDialog(
            properties = DialogProperties(),
            onDismissRequest = { showShiftReassignmentWarning.value = false },
            title = { Text("Confirm Company Shift Change") },
            text = {
                SecureDialogWindow()
                Text(ShiftRotationCoordinator.COMPANY_REASSIGNMENT_WARNING)
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            val result = shiftRotationCoordinator.requestCompanyReassignment(
                                requestedRotationIndex = requestedReassignmentIndex.value,
                                userExplicitlyConfirmedWarning = true
                            )
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                shiftConfigRefresh.value += 1
                                showShiftReassignmentWarning.value = false
                            }
                        }
                    }
                ) {
                    Text("Proceed")
                }
            },
            dismissButton = {
                TextButton(onClick = { showShiftReassignmentWarning.value = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showPermissionsDialog.value) {
        val isMandatory = !MainActivity.hasAllRequiredPermissions(context)
        PermissionsCenterDialog(
            onDismiss = {
                if (!MainActivity.hasAllRequiredPermissions(context)) {
                    Toast.makeText(
                        context,
                        "Please enable required permissions to use Chrona",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    showPermissionsDialog.value = false
                    recomputePermissionsVisibility()
                }
            },
            isMandatoryOnboarding = isMandatory
        )
    }

    if (showForensicsDialog.value) {
        DeviceForensicsDialog(
            onDismiss = { showForensicsDialog.value = false }
        )
    }

    if (showQueueMissionsDialog.value) {
        QueueMissionsDialog(
            onDismiss = { showQueueMissionsDialog.value = false },
            onEnqueue = { missionIds ->
                val mainActivity = context as MainActivity
                val specs = missionIds.split(",").map { it.trim() }.filter { it.isNotEmpty() && it != "none" }.map { missionId ->
                    // CRITICAL FIX: Block "none" missions from being created
                    val safeMissionId = missionId
                    val timeoutMs = if (safeMissionId == "tap") 120000L else 30000L // 2 minutes for tap, 30s for others
                    Log.d("MainActivity", "MISSION_TIMEOUT_ENQUEUE: missionId=$safeMissionId timeoutMs=$timeoutMs")
                    MissionSpec(
                        id = safeMissionId,
                        params = mapOf("mission_type" to safeMissionId),
                        timeoutMs = timeoutMs,
                        retryCount = 3,
                        sticky = true,
                        retryDelayMs = 1000L
                    )
                }
                mainActivity.missionSequencer?.enqueueAll(specs)
                // Immediate overlay-friendly logging after missions are added
                val ids = specs.joinToString(" , ") { it.id }
                val queueSize = mainActivity.missionSequencer?.getQueueSize()
                val currentId = mainActivity.missionSequencer?.getCurrentMission()?.id ?: "<none>"
                mainActivity.addSequencerLog("ENQUEUED_MISSIONS: [$ids] queueSize=$queueSize currentMissionId=$currentId")
                Toast.makeText(context, "Queued ${specs.size} missions", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun DayToggleButtons(selectedDays: Set<Int>, onDayToggle: (Int) -> Unit) {
    val days = listOf(
        Calendar.SUNDAY to "Su",
        Calendar.MONDAY to "Mo",
        Calendar.TUESDAY to "Tu",
        Calendar.WEDNESDAY to "We",
        Calendar.THURSDAY to "Th",
        Calendar.FRIDAY to "Fr",
        Calendar.SATURDAY to "Sa"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        days.forEach { (day, label) ->
            Card(
                // Each chip takes an equal share of the row rather than a fixed 36dp, so
                // seven of them always fit regardless of screen width.
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clickable { onDayToggle(day) },
                shape = CircleShape,
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedDays.contains(day)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (selectedDays.contains(day)) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun AlarmItem(
    alarm: Alarm,
    onRemove: (Alarm) -> Unit,
    onToggleEnable: (Alarm, Boolean) -> Unit,
    onTimeEdit: (Alarm, Int, Int, Uri?, Set<Int>?, String?, String?, Boolean, Boolean, Int, Boolean, Boolean, Boolean, Boolean, Int) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
    onEditShift: (() -> Unit)? = null,
    onRemoveShift: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }

    // Identify shift alarms: use explicit isShift flag
    val isShiftAlarm = alarm.isShift

    // Update current time every second to check edit button visibility
    LaunchedEffect(Unit) {
        while (true) {
            delay(1.seconds) // Update every second
            currentTime = System.currentTimeMillis()
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isShiftAlarm)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = formatTime12Hour(alarm.hour, alarm.minute),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isShiftAlarm) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                text = "SHIFT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = formatDays(alarm.days, alarm.alarmTime, alarm.selectedDates),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                // Show all indicators on the same line
                val indicator = remember(alarm.id, alarm.missionType, alarm.missionPassword) {
                    when {
                        // CRITICAL: Check sequencer first to override single mission logic
                        alarm.missionType == "sequencer" -> {
                            val summary = SequencerMissionCodec.describeMissionSequence(alarm.missionPassword)
                            if (!summary.isNullOrBlank()) {
                                summary
                            } else {
                                "Seq"
                            }
                        }
                        alarm.missionType == MathMissionSupport.MISSION_ID -> "Math"
                        alarm.missionType == "tap" -> "Tap"
                        alarm.missionType == "password" || (alarm.missionPassword?.isNotBlank() == true) -> "Pwd"
                        else -> null
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mission type indicator
                    if (indicator != null) {
                        Text(
                            text = indicator,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }

                    // Protected indicator
                    if (alarm.isProtected) {
                        Text(
                            text = "🛡️",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }

                    // Wake-up check indicator
                    if (alarm.wakeCheckEnabled) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Wake-up check enabled",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Check and show permission status for enabled alarms
                if (alarm.isEnabled) {
                    val hasPermission = remember {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            try {
                                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                                alarmManager.canScheduleExactAlarms()
                            } catch (e: Exception) {
                                false
                            }
                        } else {
                            true
                        }
                    }

                    if (!hasPermission) {
                        Text(
                            text = "⚠️ Permission Required",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF9800), // Orange warning color
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                val ringtoneTitle = remember(alarm.ringtoneUri) {
                    RingtoneHelper.getRingtoneTitle(context, alarm.ringtoneUri)
                }
                Text(
                    text = ringtoneTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Check permission status for switch styling
                val hasPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    try {
                        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                        alarmManager.canScheduleExactAlarms()
                    } catch (e: Exception) {
                        false
                    }
                } else {
                    true
                }

                Switch(
                    checked = alarm.isEnabled,
                    onCheckedChange = { enabled ->
                        if (alarm.isProtected && !enabled) {
                            // Prevent turning off protected alarm
                            android.widget.Toast.makeText(
                                context,
                                "Cannot turn off protected alarm",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            return@Switch
                        }

                        if (!enabled && isAlarmWithinLockWindow(context, alarm)) {
                            // Prevent turning off an alarm about to ring (matches delete guard)
                            android.widget.Toast.makeText(
                                context,
                                "Cannot turn off within 10 minutes of ring time",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            return@Switch
                        }

                        // If enabling without permission, show guidance
                        if (enabled && !hasPermission) {
                            android.widget.Toast.makeText(
                                context,
                                "⚠️ Enable alarm permission first! Settings → Apps → Special app access → Alarms & Reminders",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }

                        onToggleEnable(alarm, enabled)
                    },
                    enabled = !alarm.isProtected || alarm.isEnabled, // Allow turning ON but not OFF for protected alarms
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = if (hasPermission) MaterialTheme.colorScheme.primary else Color(0xFFFF9800),
                        checkedTrackColor = if (hasPermission) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color(0xFFFF9800).copy(alpha = 0.5f),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color.Gray.copy(alpha = 0.5f)
                    )
                )
                Spacer(Modifier.width(8.dp))

                // Edit button for protected alarms (one-time time change)
                if (alarm.isProtected && !alarm.timeChangeUsed) {
                    val timeSinceCreation = currentTime - alarm.createdTime
                    val timeWindowInMs = 5 * 60 * 1000L // 5 minutes for production use
                    val timeRemaining = (timeWindowInMs - timeSinceCreation) / 1000 // seconds remaining

                    // Only show edit button within the time window of creation
                    if (timeSinceCreation < timeWindowInMs) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    // Open the full alarm dialog for editing
                                    showEditDialog = true
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "Edit Time (One-time)",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Show countdown timer
                            Text(
                                text = "${timeRemaining}s",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )

                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }

                if (isShiftAlarm) {
                    IconButton(
                        onClick = { onEditShift?.invoke() },
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer,
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Edit Shift Rotation",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }

                IconButton(
                    onClick = {
                        // Block delete if alarm is protected or wake-up-check is active
                        val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            context.createDeviceProtectedStorageContext() ?: context
                        } else context
                        val prefs = dpsContext.getSharedPreferences("alarm_dps", Context.MODE_PRIVATE)
                        val gateActive = prefs.getBoolean("wakecheck_gate_active_${alarm.id}", false)
                        val pending = prefs.getBoolean("wakecheck_pending_${alarm.id}", false)

                        when {
                            gateActive || pending -> {
                                android.widget.Toast.makeText(
                                    context,
                                    "Cannot delete - wake-up check is active",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            alarm.isProtected -> {
                                android.widget.Toast.makeText(
                                    context,
                                    "Cannot delete protected alarm",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            isAlarmWithinLockWindow(context, alarm) -> {
                                android.widget.Toast.makeText(
                                    context,
                                    "Cannot delete within 10 minutes of ring time",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            isShiftAlarm -> {
                                showDeleteConfirm = true
                            }
                            else -> {
                                showDeleteConfirm = true
                            }
                        }
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            MaterialTheme.colorScheme.errorContainer,
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete Alarm",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text(text = "Delete alarm?", color = MaterialTheme.colorScheme.onSurface) },
                    text = {
                        SecureDialogWindow()
                        Text(text = "Confirm?", color = MaterialTheme.colorScheme.onSurface)
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showDeleteConfirm = false
                            if (isShiftAlarm) {
                                onRemoveShift?.invoke()
                            } else {
                                onRemove(alarm)
                            }
                        }) {
                            Text("Delete", color = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) {
                            Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                )
            }

            if (showEditDialog) {
                // CRITICAL: Initialize mission type from alarm data for edit dialog
                val initialMissionType = alarm.missionType ?: ""
                val initialMissionPassword = alarm.missionPassword
                val safeInitialMissionType = initialMissionType // Allow "none" mission type

                // Create states for edit dialog
                val editTimeHour = remember { mutableStateOf(alarm.hour) }
                val editTimeMinute = remember { mutableStateOf(alarm.minute) }
                val editSelectedDaysState = remember { mutableStateOf(alarm.days?.toSet() ?: emptySet()) }
                val editSelectedRingtoneUriState = remember { mutableStateOf(alarm.ringtoneUri) }
                val editWakeCheckEnabledState = remember { mutableStateOf(alarm.wakeCheckEnabled) }
                val editWakeCheckMinutesState = remember { mutableStateOf(alarm.wakeCheckMinutes) }
                val editEnableGradualVolumeState = remember { mutableStateOf(alarm.enableGradualVolume) }
                val editEnableAudioBoostState = remember { mutableStateOf(alarm.enableAudioBoost) }
                val editEnableSnoozeState = remember { mutableStateOf(alarm.enableSnooze) }
                val editSnoozeMinutesState = remember { mutableStateOf(alarm.wakeCheckMinutes) }
                val editProtectedAlarmState = remember { mutableStateOf(alarm.isProtected) }
                val editRequiresFaceAuthState = remember { mutableStateOf(alarm.requiresFaceAuth) }

                AddAlarmDialog(
                    timeHour = editTimeHour,
                    timeMinute = editTimeMinute,
                    selectedDaysState = editSelectedDaysState,
                    selectedRingtoneUriState = editSelectedRingtoneUriState,
                    wakeCheckEnabledState = editWakeCheckEnabledState,
                    wakeCheckMinutesState = editWakeCheckMinutesState,
                    enableGradualVolumeState = editEnableGradualVolumeState,
                    enableAudioBoostState = editEnableAudioBoostState,
                    enableSnoozeState = editEnableSnoozeState,
                    snoozeMinutesState = editSnoozeMinutesState,
                    protectedAlarmState = editProtectedAlarmState,
                    requiresFaceAuthState = editRequiresFaceAuthState,
                    preventPowerOffState = remember { mutableStateOf(context.getSharedPreferences("prevent_power_off_prefs", Context.MODE_PRIVATE).getBoolean("alarm_${alarm.id}", false)) },
                    alarms = emptyList(), // Not needed for edit mode
                    onDismissRequest = { showEditDialog = false },
                    onConfirm = { hour, minute, ringtoneUri, days, missionType, missionPassword, wakeCheckEnabled, wakeCheckMinutes, enableGradualVolume, enableAudioBoost, isProtected, requiresFaceAuth, preventPowerOff, targetAlarmDates, enableSnooze, snoozeMinutes ->
                        showEditDialog = false
                        // Save power-off prevention preference
                        context.getSharedPreferences("prevent_power_off_prefs", Context.MODE_PRIVATE).edit().putBoolean("alarm_${alarm.id}", preventPowerOff).apply()
                        // CRITICAL: Never allow "none" mission type from edit dialog
                        val safeMissionType = missionType // Allow "none" mission type
                        onTimeEdit(alarm, hour, minute, ringtoneUri, days, safeMissionType, missionPassword, isProtected, wakeCheckEnabled, wakeCheckMinutes, enableGradualVolume, enableAudioBoost, requiresFaceAuth, enableSnooze, snoozeMinutes)
                    },
                    mediaPlayerState = remember { mutableStateOf(null) },
                    showRingtonePicker = remember { mutableStateOf(false) },
                    initialMissionType = safeInitialMissionType, // Pass safe initial mission type
                    initialMissionPassword = initialMissionPassword,
                    isShiftRotation = remember { mutableStateOf(false) }, // Edit mode doesn't use shift rotation
                    onOpenShiftRotation = {} // Edit mode doesn't need this callback
                )
            }

        }
    }
}

private fun parseShiftTime(value: String): Pair<Int, Int>? {
    val parts = value.trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..24 || minute !in 0..59) return null
    if (hour == 24 && minute > 0) return null
    return hour to minute
}

private fun minuteOfDay(value: String): Int? {
    val parsed = parseShiftTime(value) ?: return null
    return parsed.first * 60 + parsed.second
}

private fun shiftDayLabel(day: Int): String = when (day) {
    Calendar.MONDAY -> "Mon"
    Calendar.TUESDAY -> "Tue"
    Calendar.WEDNESDAY -> "Wed"
    Calendar.THURSDAY -> "Thu"
    Calendar.FRIDAY -> "Fri"
    Calendar.SATURDAY -> "Sat"
    Calendar.SUNDAY -> "Sun"
    else -> "?"
}

@Composable
fun ShiftRotationDialog(
    existingConfig: ShiftRotationConfig?,
    onDismiss: () -> Unit,
    onSaveRules: (ShiftRotationConfig) -> Unit,
    onRequestReassignment: (Int) -> Unit,
    onCancelPending: () -> Unit
) {
    val context = LocalContext.current
    val dayOrder = listOf(
        Calendar.MONDAY,
        Calendar.TUESDAY,
        Calendar.WEDNESDAY,
        Calendar.THURSDAY,
        Calendar.FRIDAY,
        Calendar.SATURDAY,
        Calendar.SUNDAY
    )

    val initialCount = existingConfig?.rotations?.size?.coerceIn(1, 3) ?: 1
    var rotationCount by remember(existingConfig) { mutableStateOf(initialCount) }

    var notificationStartInput by remember(existingConfig) {
        mutableStateOf(existingConfig?.notificationWindowStartMinuteOfDay?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "09:00")
    }
    var notificationEndInput by remember(existingConfig) {
        mutableStateOf(existingConfig?.notificationWindowEndMinuteOfDay?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "20:00")
    }
    var offsetMinutesInput by remember(existingConfig) {
        mutableStateOf(existingConfig?.alarmPattern?.offsetMinutesBeforeShift?.takeIf { it > 0 }?.toString() ?: "")
    }
    val enabledStates = remember(existingConfig) {
        List(3) { rotationIndex ->
            val rules = existingConfig?.rotations?.getOrNull(rotationIndex)?.dayRules.orEmpty()
            dayOrder.map { day ->
                mutableStateOf(rules.firstOrNull { it.dayOfWeek == day }?.enabled ?: (day != Calendar.SUNDAY))
            }
        }
    }
    val startStates = remember(existingConfig) {
        List(3) { rotationIndex ->
            val rules = existingConfig?.rotations?.getOrNull(rotationIndex)?.dayRules.orEmpty()
            dayOrder.map { day ->
                val rule = rules.firstOrNull { it.dayOfWeek == day }
                mutableStateOf(rule?.let { "%02d:%02d".format(it.startHour, it.startMinute) } ?: "")
            }
        }
    }
    val endStates = remember(existingConfig) {
        List(3) { rotationIndex ->
            val rules = existingConfig?.rotations?.getOrNull(rotationIndex)?.dayRules.orEmpty()
            dayOrder.map { day ->
                val rule = rules.firstOrNull { it.dayOfWeek == day }
                mutableStateOf(rule?.let { "%02d:%02d".format(it.endHour, it.endMinute) } ?: "")
            }
        }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shift Rotation") },
        text = {
            SecureDialogWindow()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(520.dp)
            ) {
                // Top 1, 2, 3 Selection Buttons
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Total shifts in rotation:")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1, 2, 3).forEach { count ->
                            OutlinedButton(
                                onClick = {
                                    rotationCount = count
                                    coroutineScope.launch {
                                        // PERFECT FIX: Dynamically wait just enough for Android to generate the new items
                                        // This completely prevents the app from getting stuck or crashing
                                        while (listState.layoutInfo.totalItemsCount < count) {
                                            delay(10.milliseconds)
                                        }
                                        try {
                                            // Instantly snap exactly to the item without any slow sliding animation
                                            listState.scrollToItem(count - 1)
                                        } catch (e: Exception) {
                                            Log.e("ShiftRotation", "Safe scroll failed", e)
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.weight(1f),
                                colors = if (rotationCount == count) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ButtonDefaults.outlinedButtonColors()
                            ) {
                                Text("$count")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = offsetMinutesInput,
                    onValueChange = { offsetMinutesInput = it.filter { ch -> ch.isDigit() }.take(4) },
                    label = { Text("Alarm offset (minutes before shift)") },
                    placeholder = { Text("e.g. 60 or 120") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Scrollable list using LazyColumn to allow physical "shifting" to positions safely
                androidx.compose.foundation.lazy.LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        count = rotationCount,
                        key = { it } // Critical: Stops Android from rebuilding existing UI components from scratch
                    ) { rotationIndex ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Shift ${rotationIndex + 1} (Week ${rotationIndex + 1})",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                TextButton(
                                    onClick = {
                                        val mondayStart = startStates[rotationIndex][0].value
                                        val mondayEnd = endStates[rotationIndex][0].value
                                        for (i in 1 until dayOrder.size) {
                                            if (enabledStates[rotationIndex][i].value) {
                                                startStates[rotationIndex][i].value = mondayStart
                                                endStates[rotationIndex][i].value = mondayEnd
                                            }
                                        }
                                        Toast.makeText(context, "Applied Monday's time to enabled days", Toast.LENGTH_SHORT).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                ) {
                                    Text("Copy Mon to Enabled", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            dayOrder.forEachIndexed { dayIndex, day ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = enabledStates[rotationIndex][dayIndex].value,
                                        onCheckedChange = { enabledStates[rotationIndex][dayIndex].value = it },
                                        modifier = Modifier.scale(0.8f)
                                    )
                                    Text(
                                        text = shiftDayLabel(day),
                                        modifier = Modifier.width(30.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    BasicTextField(
                                        value = startStates[rotationIndex][dayIndex].value,
                                        onValueChange = { startStates[rotationIndex][dayIndex].value = it.filter { ch -> ch.isDigit() || ch == ':' }.take(5) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        textStyle = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center
                                        ),
                                        modifier = Modifier.width(64.dp),
                                        decorationBox = { innerTextField ->
                                            OutlinedTextFieldDefaults.DecorationBox(
                                                value = startStates[rotationIndex][dayIndex].value,
                                                innerTextField = innerTextField,
                                                enabled = true,
                                                singleLine = true,
                                                visualTransformation = VisualTransformation.None,
                                                interactionSource = remember { MutableInteractionSource() },
                                                placeholder = { Text("Start", style = MaterialTheme.typography.labelSmall) },
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                                                container = {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .border(
                                                                width = 1.dp,
                                                                color = MaterialTheme.colorScheme.outline,
                                                                shape = RoundedCornerShape(4.dp)
                                                            )
                                                    )
                                                }
                                            )
                                        }
                                    )
                                    BasicTextField(
                                        value = endStates[rotationIndex][dayIndex].value,
                                        onValueChange = { endStates[rotationIndex][dayIndex].value = it.filter { ch -> ch.isDigit() || ch == ':' }.take(5) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        textStyle = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center
                                        ),
                                        modifier = Modifier.width(64.dp),
                                        decorationBox = { innerTextField ->
                                            OutlinedTextFieldDefaults.DecorationBox(
                                                value = endStates[rotationIndex][dayIndex].value,
                                                innerTextField = innerTextField,
                                                enabled = true,
                                                singleLine = true,
                                                visualTransformation = VisualTransformation.None,
                                                interactionSource = remember { MutableInteractionSource() },
                                                placeholder = { Text("End", style = MaterialTheme.typography.labelSmall) },
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                                                container = {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .border(
                                                                width = 1.dp,
                                                                color = MaterialTheme.colorScheme.outline,
                                                                shape = RoundedCornerShape(4.dp)
                                                            )
                                                    )
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                        if (rotationIndex < rotationCount - 1) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }

                    item {
                        val pending = existingConfig?.pendingReassignment
                        if (existingConfig != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (pending == null) "Company reassignment" else "Pending company reassignment",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (rotationCount == existingConfig.rotations.size) {
                                    existingConfig.rotations.forEachIndexed { index, _ ->
                                        OutlinedButton(onClick = { onRequestReassignment(index) }) {
                                            Text("Set Shift ${index + 1} Active")
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "Save changes first to unlock reassignment.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (pending != null) {
                                    val shiftCoordinator = remember { com.vaishnava.alarm.shift.ShiftRotationCoordinator(context, com.vaishnava.alarm.shift.ShiftRotationStore(context)) }
                                    OutlinedButton(onClick = {
                                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            val result = shiftCoordinator.cancelPendingReassignment()
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                                onCancelPending()
                                            }
                                        }
                                    }) {
                                        Text("Cancel Pending")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = rotationCount > 0,
                onClick = {
                    if (rotationCount <= 0) {
                        Toast.makeText(context, "Select how many shifts you rotate through first", Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    val notificationStart = minuteOfDay(notificationStartInput)
                    val notificationEnd = minuteOfDay(notificationEndInput)
                    if (notificationStart == null || notificationEnd == null || notificationStart >= notificationEnd) {
                        Toast.makeText(context, "Invalid reminder window", Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    val rotations = mutableListOf<ShiftRotationDefinition>()
                    var invalidTimeMessage: String? = null
                    var rotationIndex = 0
                    while (rotationIndex < rotationCount && invalidTimeMessage == null) {
                        val rules = mutableListOf<ShiftDayRule>()
                        for ((dayIndex, day) in dayOrder.withIndex()) {
                            val isEnabled = enabledStates[rotationIndex][dayIndex].value
                            val start = parseShiftTime(startStates[rotationIndex][dayIndex].value)
                            val end = parseShiftTime(endStates[rotationIndex][dayIndex].value)
                            if (isEnabled && (start == null || end == null)) {
                                invalidTimeMessage = "Invalid time in Shift ${rotationIndex + 1} (${shiftDayLabel(day)})"
                                break
                            }
                            rules += ShiftDayRule(
                                dayOfWeek = day,
                                startHour = start?.first ?: 6,
                                startMinute = start?.second ?: 0,
                                endHour = end?.first ?: 14,
                                endMinute = end?.second ?: 0,
                                enabled = isEnabled
                            )
                        }
                        if (invalidTimeMessage == null) {
                            rotations += ShiftRotationDefinition("Shift ${rotationIndex + 1}", rules)
                        }
                        rotationIndex++
                    }
                    if (invalidTimeMessage != null) {
                        Toast.makeText(context, invalidTimeMessage, Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    val existingPattern = existingConfig?.alarmPattern
                    val packageName = context.packageName
                    val glossyBellId = context.resources.getIdentifier("glassy_bell", "raw", packageName)
                    val defaultRingtoneUri = if (glossyBellId != 0) {
                        "android.resource://$packageName/$glossyBellId"
                    } else {
                        "android.resource://$packageName/raw/glassy_bell"
                    }
                    val offsetMinutes = offsetMinutesInput.toIntOrNull() ?: 120
                    val config = ShiftRotationConfig(
                        enabled = true,
                        rotations = rotations,
                        alarmPattern = ShiftAlarmPattern(
                            offsetMinutesBeforeShift = offsetMinutes,
                            ringtoneUri = existingPattern?.ringtoneUri ?: defaultRingtoneUri,
                            missionType = existingPattern?.missionType,
                            missionPassword = existingPattern?.missionPassword,
                            wakeCheckEnabled = existingPattern?.wakeCheckEnabled ?: false,
                            wakeCheckMinutes = existingPattern?.wakeCheckMinutes ?: 5,
                            protectedAlarm = existingPattern?.protectedAlarm ?: false
                        ),
                        anchorWeekStartMillis = existingConfig?.anchorWeekStartMillis ?: 0L,
                        anchorRotationIndex = existingConfig?.anchorRotationIndex ?: 0,
                        transitionLeadMinutes = 60,
                        notificationWindowStartMinuteOfDay = notificationStart,
                        notificationWindowEndMinuteOfDay = notificationEnd,
                        generationHorizonWeeks = 1,
                        runtime = existingConfig?.runtime ?: com.vaishnava.alarm.shift.ShiftRotationRuntimeState()
                    )
                    onSaveRules(config)
                }
            ) {
                Text("Save Rules")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun ShiftCalibrationDialog(
    config: ShiftRotationConfig,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var selectedIndex by remember(config) { mutableStateOf(config.anchorRotationIndex.coerceIn(0, config.rotations.lastIndex)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which shift is currently active this week?") },
        text = {
            SecureDialogWindow()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                config.rotations.forEachIndexed { index, rotation ->
                    OutlinedButton(
                        onClick = { selectedIndex = index },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (selectedIndex == index) "${rotation.name} selected" else rotation.name)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedIndex) }) {
                Text("Calibrate")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AddAlarmDialog(
    timeHour: MutableState<Int>,
    timeMinute: MutableState<Int>,
    selectedDaysState: MutableState<Set<Int>>,
    selectedRingtoneUriState: MutableState<Uri?>,
    wakeCheckEnabledState: MutableState<Boolean>,
    wakeCheckMinutesState: MutableState<Int>,
    enableGradualVolumeState: MutableState<Boolean> = mutableStateOf(false),
    enableAudioBoostState: MutableState<Boolean> = mutableStateOf(false),
    enableSnoozeState: MutableState<Boolean> = mutableStateOf(false),
    snoozeMinutesState: MutableState<Int> = mutableStateOf(5),
    protectedAlarmState: MutableState<Boolean>,
    requiresFaceAuthState: MutableState<Boolean> = mutableStateOf(false),
    preventPowerOffState: MutableState<Boolean> = mutableStateOf(false),
    alarms: List<Alarm>,
    onDismissRequest: () -> Unit,
    onConfirm: (Int, Int, Uri?, Set<Int>, String?, String?, Boolean, Int, Boolean, Boolean, Boolean, Boolean, Boolean, List<Long>, Boolean, Int) -> Unit,
    mediaPlayerState: MutableState<MediaPlayer?>,
    showRingtonePicker: MutableState<Boolean>,
    initialMissionType: String = "", // Default to no mission
    initialMissionPassword: String? = null,
    isShiftRotation: MutableState<Boolean> = mutableStateOf(false), // Shift rotation toggle
    onOpenShiftRotation: () -> Unit = {} // Callback to open shift rotation settings
) {
    val context = LocalContext.current
    // Move these calculations inside the Composable so they update correctly
    val displayHour = remember(timeHour.value) {
        if (timeHour.value == 0) 12 else if (timeHour.value > 12) timeHour.value - 12 else timeHour.value
    }
    val amPm = remember(timeHour.value) {
        if (timeHour.value >= 12) "PM" else "AM"
    }
    val isPm = remember { mutableStateOf(timeHour.value >= 12) }
    val selectedDatesState = remember { mutableStateOf<List<Long>>(emptyList()) }
    LaunchedEffect(timeHour.value) {
        isPm.value = timeHour.value >= 12
    }
    fun normalizeMissionSelection(rawValue: String?): String? {
        return when (rawValue?.trim()?.lowercase()) {
            "pwd", "password" -> "password"
            "tap", "tap challenge" -> "tap"
            "stt", "speech", "speech to text" -> SttMissionSupport.MISSION_ID
            "face", "face verify", "face authentication" -> FaceMissionSupport.MISSION_ID
            "math", "math challenge" -> MathMissionSupport.MISSION_ID
            else -> null
        }
    }

    val initialMissionSpecs = remember(initialMissionType, initialMissionPassword) {
        when {
            initialMissionType == "sequencer" -> {
                SequencerMissionCodec.parseMissionSequence(initialMissionPassword).take(2)
            }
            else -> {
                listOfNotNull(
                    normalizeMissionSelection(initialMissionType)?.let { missionId ->
                        when (missionId) {
                            MathMissionSupport.MISSION_ID -> MathMissionSupport.createMissionSpec()
                            else -> SequencerMissionCodec.parseMissionToken(missionId)
                        }
                    }
                ).take(2)
            }
        }
    }

    val initialMathConfig = remember(initialMissionType, initialMissionPassword) {
        initialMissionSpecs
            .firstOrNull { it.id == MathMissionSupport.MISSION_ID }
            ?.let { MathMissionSupport.readConfig(it) }
            ?: MathMissionConfig()
    }

    val queuedMissions = remember(initialMissionType, initialMissionPassword) {
        mutableStateListOf<String>().apply {
            addAll(initialMissionSpecs.map { it.id }.take(3))
        }
    }
    val missionSlotBeingEdited = remember(initialMissionType, initialMissionPassword) {
        mutableStateOf<Int?>(null)
    }
    var selectedMathDifficulty by remember(initialMissionType, initialMissionPassword) {
        mutableStateOf(initialMathConfig.difficulty)
    }
    var mathTimeLimitInput by remember(initialMissionType, initialMissionPassword) {
        mutableStateOf(initialMathConfig.questionTimeLimitSec.toString())
    }
    var mathRequiredAnswersInput by remember(initialMissionType, initialMissionPassword) {
        mutableStateOf(initialMathConfig.requiredCorrectAnswers.toString())
    }
    var sttTargetPhraseInput by remember {
        mutableStateOf("hello")
    }
    val showMissionFaceEnrollment = remember { mutableStateOf(false) }
    val missionFaceEnrolled = remember { mutableStateOf(false) }
    val autoSubmitAfterEnrollment = remember { mutableStateOf(false) }

    fun currentMathConfig(): MathMissionConfig {
        return MathMissionSupport.normalizeConfig(
            MathMissionConfig(
                difficulty = selectedMathDifficulty,
                questionTimeLimitSec = mathTimeLimitInput.toIntOrNull() ?: initialMathConfig.questionTimeLimitSec,
                requiredCorrectAnswers = mathRequiredAnswersInput.toIntOrNull()
                    ?: initialMathConfig.requiredCorrectAnswers
            )
        )
    }
    val mathSettingsContainerColor = MaterialTheme.colorScheme.secondaryContainer
    val mathSettingsContentColor = MaterialTheme.colorScheme.onSecondaryContainer
    val mathSettingsAccentColor = MaterialTheme.colorScheme.primary
    val mathFieldColors = TextFieldDefaults.colors(
        focusedTextColor = mathSettingsContentColor,
        unfocusedTextColor = mathSettingsContentColor,
        disabledTextColor = mathSettingsContentColor.copy(alpha = 0.38f),
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        focusedLabelColor = mathSettingsAccentColor,
        unfocusedLabelColor = mathSettingsContentColor.copy(alpha = 0.78f),
        disabledLabelColor = mathSettingsContentColor.copy(alpha = 0.38f),
        focusedIndicatorColor = mathSettingsAccentColor,
        unfocusedIndicatorColor = mathSettingsContentColor.copy(alpha = 0.42f),
        disabledIndicatorColor = mathSettingsContentColor.copy(alpha = 0.2f),
        cursorColor = mathSettingsAccentColor,
        focusedSuffixColor = mathSettingsContentColor,
        unfocusedSuffixColor = mathSettingsContentColor.copy(alpha = 0.78f)
    )

    var hourInput by remember { mutableStateOf(String.format("%02d", displayHour)) }
    var minuteInput by remember { mutableStateOf(String.format("%02d", timeMinute.value)) }
    var hourFieldFocused by remember { mutableStateOf(false) }
    var minuteFieldFocused by remember { mutableStateOf(false) }

    fun normalizeHour(value: Int): Int = ((value % 24) + 24) % 24

    fun syncHourInput() {
        if (!hourFieldFocused) {
            hourInput = String.format("%02d", displayHour)
        }
    }

    fun syncMinuteInput() {
        if (!minuteFieldFocused) {
            minuteInput = String.format("%02d", timeMinute.value)
        }
    }

    fun adjustHour(delta: Int) {
        timeHour.value = normalizeHour(timeHour.value + delta)
        syncHourInput()
    }

    fun adjustMinute(delta: Int) {
        val newMinute = timeMinute.value + delta
        if (newMinute >= 60) {
            timeMinute.value = newMinute % 60
            adjustHour(newMinute / 60)
        } else if (newMinute < 0) {
            val deficit = (kotlin.math.abs(newMinute) + 59) / 60
            adjustHour(-deficit)
            timeMinute.value = (newMinute % 60 + 60) % 60
        } else {
            timeMinute.value = newMinute
        }
        syncMinuteInput()
    }

    LaunchedEffect(displayHour, isPm.value) { syncHourInput() }
    LaunchedEffect(timeMinute.value) { syncMinuteInput() }

    val performSaveAction: () -> Unit = save@{
        // Silence No Sound only for a one-time test alarm within 10 minutes (SilenceRingtoneRule.kt).
        val silenceChosen = isSilenceRingtone(selectedRingtoneUriState.value?.toString(), R.raw.silence_no_sound)
        if (!silenceRingtoneAllowed(
                silenceChosen, timeHour.value, timeMinute.value, selectedDaysState.value,
                selectedDatesState.value, isShiftRotation.value, System.currentTimeMillis()
            )
        ) {
            Toast.makeText(context, SILENCE_NOT_ALLOWED_MESSAGE, Toast.LENGTH_LONG).show()
            return@save
        }
        if (isShiftRotation.value) {
            // 1. Determine mission type and password from selections
            val missionType: String?
            val missionPassword: String?

            if (queuedMissions.isNotEmpty()) {
                val mainActivity = context as MainActivity
                val orderedMissions = queuedMissions.take(3)

                val specs = orderedMissions.filter { it.isNotEmpty() && it != "none" }.mapNotNull { missionId ->
                    Log.d("MainActivity", "CREATING_MISSION_SPEC: missionId=$missionId")
                    when (missionId) {
                        MathMissionSupport.MISSION_ID -> {
                            MathMissionSupport.createMissionSpec(config = currentMathConfig())
                        }
                        else -> {
                            SequencerMissionCodec.parseMissionToken(missionId)
                        }
                    }
                }
                mainActivity.missionSequencer?.enqueueAll(specs)
                missionType = "sequencer"
                missionPassword = SequencerMissionCodec.serializeMissionSequence(specs)
            } else {
                missionType = null
                missionPassword = null
            }

            // 2. Read existing shift rotation config
            val shiftStore = com.vaishnava.alarm.shift.ShiftRotationStore(context)
            val config = shiftStore.read()
            if (config != null) {
                // Update alarm pattern
                val updatedConfig = config.copy(
                    alarmPattern = config.alarmPattern.copy(
                        ringtoneUri = selectedRingtoneUriState.value?.toString() ?: config.alarmPattern.ringtoneUri,
                        missionType = missionType,
                        missionPassword = missionPassword,
                        wakeCheckEnabled = wakeCheckEnabledState.value,
                        wakeCheckMinutes = wakeCheckMinutesState.value,
                        protectedAlarm = protectedAlarmState.value
                    )
                )
                shiftStore.save(updatedConfig)

                // 3. Reconcile schedule to regenerate alarms with new settings
                val coordinator = com.vaishnava.alarm.shift.ShiftRotationCoordinator(context, shiftStore)
                CoroutineScope(Dispatchers.IO).launch {
                    coordinator.reconcileSchedule(System.currentTimeMillis(), forceRegenerateCurrentWeek = true)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Shift rotation alarm pattern updated", Toast.LENGTH_SHORT).show()
                        // Send broadcast to trigger UI refresh
                        context.sendBroadcast(Intent("com.vaishnava.alarm.REFRESH_ALARMS"))
                    }
                }
            }

            onDismissRequest()
        } else if (queuedMissions.isNotEmpty()) {
            val mainActivity = context as MainActivity
            val orderedMissions = queuedMissions.take(3)

            val specs = orderedMissions.filter { it.isNotEmpty() && it != "none" }.mapNotNull { missionId ->
                Log.d("MainActivity", "CREATING_MISSION_SPEC: missionId=$missionId")
                when (missionId) {
                    MathMissionSupport.MISSION_ID -> {
                        MathMissionSupport.createMissionSpec(config = currentMathConfig())
                    }
                    else -> {
                        SequencerMissionCodec.parseMissionToken(missionId)
                    }
                }
            }
            Log.d("MainActivity", "FINAL_MISSION_SPECS: ${specs.map { it.id }.joinToString(",")}")
            Log.d("MainActivity", "CALLING_ENQUEUE_ALL: specs=${specs.size}")
            mainActivity.missionSequencer?.enqueueAll(specs)
            val missionNames = SequencerMissionCodec.serializeMissionSequence(specs)

            val targetAlarmDates = selectedDatesState.value.map { dateMillis ->
                Calendar.getInstance().apply {
                    timeInMillis = dateMillis
                    set(Calendar.HOUR_OF_DAY, timeHour.value)
                    set(Calendar.MINUTE, timeMinute.value)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }

            onConfirm(
                timeHour.value,
                timeMinute.value,
                selectedRingtoneUriState.value,
                selectedDaysState.value,
                "sequencer",
                missionNames,
                wakeCheckEnabledState.value,
                wakeCheckMinutesState.value,
                enableGradualVolumeState.value,
                enableAudioBoostState.value,
                protectedAlarmState.value,
                requiresFaceAuthState.value,
                preventPowerOffState.value,
                targetAlarmDates,
                enableSnoozeState.value,
                snoozeMinutesState.value
            )
        } else {
            val targetAlarmDates = selectedDatesState.value.map { dateMillis ->
                Calendar.getInstance().apply {
                    timeInMillis = dateMillis
                    set(Calendar.HOUR_OF_DAY, timeHour.value)
                    set(Calendar.MINUTE, timeMinute.value)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }

            onConfirm(
                timeHour.value,
                timeMinute.value,
                selectedRingtoneUriState.value,
                selectedDaysState.value,
                "",
                null,
                wakeCheckEnabledState.value,
                wakeCheckMinutesState.value,
                enableGradualVolumeState.value,
                enableAudioBoostState.value,
                protectedAlarmState.value,
                requiresFaceAuthState.value,
                preventPowerOffState.value,
                targetAlarmDates,
                enableSnoozeState.value,
                snoozeMinutesState.value
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Add New Alarm", color = MaterialTheme.colorScheme.onSurface)
                Icon(
                    imageVector = Icons.Filled.AlarmAdd,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            SecureDialogWindow()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Time selection with swipe controls (hidden when Shift Rotation is selected)
                if (!isShiftRotation.value) {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    // This row cannot wrap, so instead of hoping fixed sizes fit, measure the
                    // width we actually have and scale the whole row to it. At full width the
                    // design is unchanged; on a narrow dialog everything shrinks together
                    // rather than the last child being clipped off screen.
                    //
                    // The requirement is split because the two swipe boxes are sized in dp and
                    // do not grow with the font setting, while the colon and the AM/PM chips
                    // are text-driven and do. Ignoring that is what let "PM" lose its "M" on a
                    // device with enlarged system text: the row needed ~340dp but we only ever
                    // compared against 310dp, so the scale-down never triggered.
                    // 160dp = the two 80dp swipe boxes. 138dp = colon + paddings + the two
                    // AM/PM chips at font scale 1.0. Measured, not padded out - an inflated
                    // figure would shrink the row on devices that already fit fine.
                    val fixedPart = 160.dp
                    val textDrivenPart = 138.dp * LocalDensity.current.fontScale
                    val requiredWidth = fixedPart + textDrivenPart
                    val fit = (this.maxWidth / requiredWidth).coerceIn(0.60f, 1f)

                    val timeBoxSize = 80.dp * fit
                    val timeFontSize = 44.sp * fit
                    val colonFontSize = 36.sp * fit
                    val colonPadding = 8.dp * fit
                    val ampmFontSize = 14.sp * fit

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        // Hour selector with swipe up/down
                        Box(
                            modifier = Modifier
                                .size(timeBoxSize)
                                .pointerInput(Unit) {
                                    var hasTriggered = false
                                    var totalDrag = 0f

                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            hasTriggered = false
                                            totalDrag = 0f
                                        },
                                        onDragEnd = {
                                            hasTriggered = false
                                            totalDrag = 0f
                                        }
                                    ) { change, dragAmount ->
                                        totalDrag += dragAmount

                                        // Only trigger once per drag gesture
                                        if (!hasTriggered && abs(totalDrag) > 30f) {
                                            if (totalDrag < 0) {
                                                timeHour.value = (timeHour.value + 1) % 24
                                            } else {
                                                timeHour.value = if (timeHour.value > 0) timeHour.value - 1 else 23
                                            }
                                            hasTriggered = true
                                            totalDrag = 0f
                                        }
                                        change.consume()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            BasicTextField(
                                value = hourInput,
                                onValueChange = { newValue ->
                                    val sanitized = newValue.filter { it.isDigit() }.take(2)
                                    hourInput = sanitized
                                    if (sanitized.isNotEmpty()) {
                                        val numeric = sanitized.toInt().coerceIn(1, 12)
                                        val base = if (numeric == 12) 0 else numeric
                                        hourFieldFocused = true
                                        timeHour.value = if (isPm.value) {
                                            if (numeric == 12) 12 else base + 12
                                        } else {
                                            base
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusable()
                                    .onFocusChanged { focusState ->
                                        hourFieldFocused = focusState.isFocused
                                        if (!focusState.isFocused) {
                                            hourInput = String.format("%02d", displayHour)
                                        }
                                    },
                                textStyle = MaterialTheme.typography.displayMedium.copy(
                                    fontSize = timeFontSize,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                ),
                                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
                            )
                        }

                        // Colon separator
                        Text(
                            text = ":",
                            fontSize = colonFontSize,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .padding(horizontal = colonPadding)
                                .align(Alignment.Bottom)
                        )

                        // Minute selector with swipe up/down
                        Box(
                            modifier = Modifier
                                .size(timeBoxSize)
                                .pointerInput(Unit) {
                                    var hasTriggered = false
                                    var totalDrag = 0f

                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            hasTriggered = false
                                            totalDrag = 0f
                                        },
                                        onDragEnd = {
                                            hasTriggered = false
                                            totalDrag = 0f
                                        }
                                    ) { change, dragAmount ->
                                        totalDrag += dragAmount

                                        // Only trigger once per drag gesture
                                        if (!hasTriggered && abs(totalDrag) > 30f) {
                                            if (totalDrag < 0) {
                                                adjustMinute(1)
                                            } else {
                                                adjustMinute(-1)
                                            }
                                            hasTriggered = true
                                            totalDrag = 0f
                                        }
                                        change.consume()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            BasicTextField(
                                value = minuteInput,
                                onValueChange = { newValue ->
                                    val sanitized = newValue.filter { it.isDigit() }.take(2)
                                    minuteInput = sanitized
                                    val numeric = sanitized.toIntOrNull()
                                    if (numeric != null) {
                                        val numeric = sanitized.toInt()
                                        val clamped = numeric.coerceIn(0, 59)
                                        timeMinute.value = clamped
                                        if (numeric != clamped) {
                                            adjustMinute(numeric - clamped)
                                        } else {
                                            syncMinuteInput()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusable()
                                    .onFocusChanged { focusState ->
                                        minuteFieldFocused = focusState.isFocused
                                        if (!focusState.isFocused) {
                                            syncMinuteInput()
                                        }
                                    },
                                textStyle = MaterialTheme.typography.displayMedium.copy(
                                    fontSize = timeFontSize,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                ),
                                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number)
                            )
                        }

                        // Single segmented control: one rounded container, AM and PM as the two
                        // halves inside it. Its size is scaled by `fit`, so on a narrow screen
                        // it shrinks with the rest of the row instead of clipping a letter.
                        Row(
                            modifier = Modifier
                                .padding(start = 8.dp * fit)
                                .width(120.dp * fit)
                                .height(40.dp * fit)
                                .clip(RoundedCornerShape(12.dp * fit))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(if (!isPm.value) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .clickable {
                                        if (isPm.value) {
                                            if (timeHour.value >= 12) timeHour.value = (timeHour.value - 12).coerceIn(0, 23)
                                            isPm.value = false
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "AM",
                                    fontSize = ampmFontSize,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                    color = if (!isPm.value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(if (isPm.value) MaterialTheme.colorScheme.primary else Color.Transparent)
                                    .clickable {
                                        if (!isPm.value) {
                                            if (timeHour.value < 12) timeHour.value = (timeHour.value + 12).coerceIn(0, 23)
                                            isPm.value = true
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "PM",
                                    fontSize = ampmFontSize,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                    color = if (isPm.value) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    }
                }

                // Day selection with shift rotation option
                Text(
                    text = "Repeat",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(top = 8.dp, bottom = 4.dp)
                )

                // Shift rotation toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Shift Rotation Pattern",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        // Wraps instead of running into the switch when text is scaled up.
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )
                    Switch(
                        checked = isShiftRotation.value,
                        onCheckedChange = { isShiftRotation.value = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }

                if (!isShiftRotation.value) {
                    val showMultiDatePicker = remember { mutableStateOf(false) }

                    if (selectedDatesState.value.isEmpty()) {
                        DayToggleButtons(
                            selectedDays = selectedDaysState.value,
                            onDayToggle = { day ->
                                selectedDaysState.value = if (selectedDaysState.value.contains(day)) {
                                    selectedDaysState.value - day
                                } else {
                                    selectedDaysState.value + day
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📅 ${selectedDatesState.value.size} specific dates selected",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = { selectedDatesState.value = emptyList() }
                            ) {
                                Text("Clear Dates", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    OutlinedButton(
                        onClick = { showMultiDatePicker.value = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (selectedDatesState.value.isNotEmpty()) {
                                "📅 Edit dates"
                            } else {
                                "📅 Specific dates"
                            }
                        )
                    }

                    if (showMultiDatePicker.value) {
                        MultiDatePickerDialog(
                            initialDates = selectedDatesState.value,
                            onDismiss = { showMultiDatePicker.value = false },
                            onConfirm = { newDates ->
                                selectedDatesState.value = newDates
                                showMultiDatePicker.value = false
                            }
                        )
                    }
                } else {
                    // Shift rotation pattern selector
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Shift Rotation Active",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Configure shift patterns in Shift Rotation settings",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            OutlinedButton(
                                onClick = onOpenShiftRotation,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Configure Shift Rotation")
                            }
                        }
                    }
                }

                // Mission selection is always sequencer-backed: pick one or two missions in order.
                val previewContext = LocalContext.current
                val currentMissionForPreview = queuedMissions.firstOrNull()

                fun missionLabel(mission: String): String = when (mission) {
                    "password" -> "Password Gate"
                    "tap" -> "Tap Challenge"
                    "stt" -> "Speech-to-Text"
                    "face" -> "Face Verify"
                    MathMissionSupport.MISSION_ID -> "Math Challenge"
                    else -> mission.replaceFirstChar { it.uppercase() }
                }

                fun missionSubtitle(mission: String): String = when (mission) {
                    "password" -> "Require the default alarm password before dismissing."
                    "tap" -> "Complete the full tap sequence before dismissing."
                    "stt" -> "Speak the required word or phrase to complete the challenge."
                    "face" -> "Verify your enrolled face before dismissing."
                    MathMissionSupport.MISSION_ID -> "Solve timed questions until the target score is reached."
                    else -> "Custom mission"
                }

                fun missionTag(mission: String): String = when (mission) {
                    "password" -> "SECURE"
                    "tap" -> "ACTION"
                    "stt" -> "VOICE"
                    "face" -> "BIOMETRIC"
                    MathMissionSupport.MISSION_ID -> "BRAIN"
                    else -> "CUSTOM"
                }

                fun missionIcon(mission: String): ImageVector = when (mission) {
                    "password" -> Icons.Filled.Lock
                    "tap" -> Icons.Filled.TouchApp
                    "stt" -> Icons.Filled.Audiotrack
                    "face" -> Icons.Filled.Face
                    MathMissionSupport.MISSION_ID -> Icons.Filled.CheckCircle
                    else -> Icons.Filled.CheckCircle
                }

                fun missionAccent(mission: String): Color = when (mission) {
                    "password" -> Color(0xFF9C6B2F)
                    "tap" -> Color(0xFF1D7D74)
                    "stt" -> Color(0xFF8B2F7D)
                    "face" -> Color(0xFF2F7D5A)
                    MathMissionSupport.MISSION_ID -> Color(0xFF3B64C7)
                    else -> Color(0xFF5F6B7A)
                }

                fun toggleMissionSelection(mission: String) {
                    if (queuedMissions.contains(mission)) {
                        queuedMissions.remove(mission)
                    } else if (queuedMissions.size < 3) {
                        queuedMissions.add(mission)
                    }
                }

                val previewEnabled = queuedMissions.isNotEmpty()

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    ),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Mission",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            )

                            OutlinedButton(
                                onClick = {
                                    val pm = currentMissionForPreview ?: queuedMissions.firstOrNull() ?: return@OutlinedButton
                                    try {
                                        val intent = Intent(previewContext, AlarmActivity::class.java).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                            putExtra("preview", true)
                                            putExtra("preview_mission", pm)
                                            putExtra("preview_password", "IfYouWantYouCanSleep")
                                            putExtra("preview_math_difficulty", selectedMathDifficulty.name)
                                            putExtra("preview_math_time_limit", mathTimeLimitInput)
                                            putExtra("preview_math_required_answers", mathRequiredAnswersInput)
                                            putExtra("preview_stt_phrase", sttTargetPhraseInput)
                                            putExtra(AlarmReceiver.ALARM_ID, 9100)
                                        }
                                        previewContext.startActivity(intent)
                                    } catch (_: Exception) {}
                                },
                                enabled = previewEnabled,
                                shape = RoundedCornerShape(999.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Preview")
                            }
                        }

                        // Full width on its own line - beside the Preview button it was
                        // squeezed into a 3-line wrap on narrow screens.
                        Text(
                            text = "Tap a box to add up to 3",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            (0..2).forEach { slot ->
                                val slotMission = queuedMissions.getOrNull(slot)
                                val isNextEmpty = slotMission == null && slot == queuedMissions.size
                                val accent = if (slotMission != null) missionAccent(slotMission) else MaterialTheme.colorScheme.outline
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(104.dp)
                                        .clickable(enabled = slotMission != null || isNextEmpty) {
                                            missionSlotBeingEdited.value = slot
                                        },
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (slotMission != null) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(
                                        1.dp,
                                        if (slotMission != null) accent.copy(alpha = 0.55f)
                                        else MaterialTheme.colorScheme.outline.copy(alpha = if (isNextEmpty) 0.5f else 0.2f)
                                    ),
                                    tonalElevation = if (slotMission != null) 2.dp else 0.dp
                                ) {
                                    Column(
                                        // Tight horizontal padding: this box is a third of the
                                        // dialog width, so every dp of inner padding is a dp the
                                        // label loses, which is what broke "Empty" mid-word.
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 4.dp, vertical = 10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = if (slotMission != null) missionIcon(slotMission) else Icons.Filled.Add,
                                            contentDescription = if (slotMission != null) null else "Add mission",
                                            tint = if (slotMission != null) accent
                                            else if (isNextEmpty) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                            modifier = Modifier.size(26.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = if (slotMission != null) missionLabel(slotMission) else if (isNextEmpty) "Add" else "Empty",
                                            fontSize = 12.sp,
                                            lineHeight = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.Center,
                                            maxLines = 2,
                                            // Ellipsis rather than a mid-word break: "Speech-to-Text"
                                            // degrades to "Speech-to…" instead of a stray "t".
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (slotMission != null) MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = if (isNextEmpty) 0.7f else 0.4f)
                                        )
                                    }
                                }
                            }
                        }

                        missionSlotBeingEdited.value?.let { slot ->
                            val currentSlotMission = queuedMissions.getOrNull(slot)
                            AlertDialog(
                                onDismissRequest = { missionSlotBeingEdited.value = null },
                                containerColor = MaterialTheme.colorScheme.surface,
                                title = { Text("Choose mission", color = MaterialTheme.colorScheme.onSurface) },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("password", "tap", "stt", "face", MathMissionSupport.MISSION_ID).forEach { m ->
                                            val usedElsewhere = queuedMissions.contains(m) && queuedMissions.indexOf(m) != slot
                                            val accent = missionAccent(m)
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable(enabled = !usedElsewhere) {
                                                        if (slot < queuedMissions.size) {
                                                            queuedMissions[slot] = m
                                                        } else {
                                                            queuedMissions.add(m)
                                                        }
                                                        missionSlotBeingEdited.value = null
                                                    },
                                                shape = RoundedCornerShape(14.dp),
                                                color = if (currentSlotMission == m) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (currentSlotMission == m) accent.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                                )
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = missionIcon(m),
                                                        contentDescription = null,
                                                        tint = if (usedElsewhere) MaterialTheme.colorScheme.outline else accent,
                                                        modifier = Modifier.size(22.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = missionLabel(m),
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = if (usedElsewhere) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface
                                                        )
                                                        Text(
                                                            text = if (usedElsewhere) "Already in another slot" else missionSubtitle(m),
                                                            fontSize = 12.sp,
                                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                        )
                                                    }
                                                    if (currentSlotMission == m) {
                                                        Icon(
                                                            imageVector = Icons.Filled.CheckCircle,
                                                            contentDescription = null,
                                                            tint = accent,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    if (currentSlotMission != null) {
                                        TextButton(onClick = {
                                            queuedMissions.removeAt(slot)
                                            missionSlotBeingEdited.value = null
                                        }) {
                                            Text("Remove", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { missionSlotBeingEdited.value = null }) {
                                        Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            )
                        }

                        if (queuedMissions.contains("password")) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFFF7E8D1)
                            ) {
                                Text(
                                    text = "Default password: IfYouWantYouCanSleep",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    fontSize = 12.sp,
                                    color = Color(0xFF6B4A1F),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        if (queuedMissions.contains(MathMissionSupport.MISSION_ID)) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = mathSettingsContainerColor
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "Math mission settings",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = mathSettingsContentColor
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(
                                            MathDifficulty.NORMAL,
                                            MathDifficulty.EASY,
                                            MathDifficulty.HARD,
                                            MathDifficulty.EXTREME_HARD
                                        ).forEach { difficulty ->
                                            val selected = selectedMathDifficulty == difficulty
                                            OutlinedButton(
                                                onClick = { selectedMathDifficulty = difficulty },
                                                colors = ButtonDefaults.outlinedButtonColors(
                                                    containerColor = if (selected) {
                                                        mathSettingsAccentColor
                                                    } else {
                                                        Color.Transparent
                                                    },
                                                    contentColor = if (selected) {
                                                        MaterialTheme.colorScheme.onPrimary
                                                    } else {
                                                        mathSettingsContentColor
                                                    }
                                                ),
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (selected) {
                                                        mathSettingsAccentColor
                                                    } else {
                                                        mathSettingsContentColor.copy(alpha = 0.35f)
                                                    }
                                                )
                                            ) {
                                                Text(
                                                    text = difficulty.displayName,
                                                    color = if (selected) {
                                                        MaterialTheme.colorScheme.onPrimary
                                                    } else {
                                                        mathSettingsContentColor
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = mathTimeLimitInput,
                                            onValueChange = { value ->
                                                mathTimeLimitInput = value.filter { it.isDigit() }.take(3)
                                            },
                                            modifier = Modifier.weight(1f),
                                            label = { Text("Time / question") },
                                            suffix = { Text("sec") },
                                            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                                            colors = mathFieldColors,
                                            singleLine = true
                                        )
                                        OutlinedTextField(
                                            value = mathRequiredAnswersInput,
                                            onValueChange = { value ->
                                                mathRequiredAnswersInput = value.filter { it.isDigit() }.take(2)
                                            },
                                            modifier = Modifier.weight(1f),
                                            label = { Text("Correct answers") },
                                            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
                                            colors = mathFieldColors,
                                            singleLine = true
                                        )
                                    }
                                    val mathConfig = currentMathConfig()
                                    Text(
                                        text = "${mathConfig.difficulty.displayName} - ${mathConfig.questionTimeLimitSec}s per question - ${mathConfig.requiredCorrectAnswers} correct to finish",
                                        fontSize = 12.sp,
                                        color = mathSettingsContentColor.copy(alpha = 0.82f)
                                    )
                                }
                            }
                        }

                        if (queuedMissions.contains("face")) {
                            val faceCtx = LocalContext.current

                            // A staged face belongs to the dialog session that enrolled it.
                            // Abandoning the dialog leaves it behind, so clear it when this
                            // card first appears - otherwise a brand new alarm opens claiming
                            // "Re-enrol" for a face that is not attached to anything.
                            LaunchedEffect(Unit) {
                                FaceMissionSupport.clearPendingEmbedding(faceCtx)
                                missionFaceEnrolled.value = false
                            }

                            if (showMissionFaceEnrollment.value) {
                                com.vaishnava.alarm.faceauth.FaceEnrollmentDialog(
                                    alarmId = -1,
                                    onDismiss = {
                                        showMissionFaceEnrollment.value = false
                                        autoSubmitAfterEnrollment.value = false
                                    },
                                    onEnrollmentComplete = { embedding ->
                                        showMissionFaceEnrollment.value = false
                                        if (embedding != null) {
                                            // Staged here, committed to the real alarm id once
                                            // the alarm is created.
                                            FaceMissionSupport.savePendingEmbedding(faceCtx, embedding)
                                            missionFaceEnrolled.value = true
                                            if (autoSubmitAfterEnrollment.value) {
                                                autoSubmitAfterEnrollment.value = false
                                                performSaveAction()
                                            }
                                        } else {
                                            autoSubmitAfterEnrollment.value = false
                                        }
                                    }
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFF2F7D5A).copy(alpha = 0.12f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "🙂 Face Verify settings",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = if (missionFaceEnrolled.value) {
                                            "Face enrolled for this mission. It is separate from " +
                                                "the alarm protection face."
                                        } else {
                                            "Enrol the face this alarm will ask for. This is only " +
                                                "for the mission - it does not turn on alarm protection."
                                        },
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                    )
                                    OutlinedButton(
                                        onClick = { showMissionFaceEnrollment.value = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            if (missionFaceEnrolled.value) "Re-enrol face"
                                            else "Enrol face"
                                        )
                                    }
                                    if (!missionFaceEnrolled.value) {
                                        Text(
                                            text = "Required - without it the alarm has nothing to verify against.",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }

                        if (queuedMissions.contains("stt")) {
                            LaunchedEffect(Unit) {
                                SttMissionSupport.savePendingTargetPhrase(previewContext, sttTargetPhraseInput)
                            }
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFF8B2F7D).copy(alpha = 0.12f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = "🎤 Speech-to-Text settings",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    OutlinedTextField(
                                        value = sttTargetPhraseInput,
                                        onValueChange = {
                                            sttTargetPhraseInput = it
                                            SttMissionSupport.savePendingTargetPhrase(previewContext, it)
                                        },
                                        label = { Text("Target phrase") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors()
                                    )
                                    Text(
                                        text = "User must say this phrase to complete the challenge",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }

                        if (queuedMissions.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { queuedMissions.clear() }) {
                                    Text("Clear Selection")
                                }
                            }
                        }

                        // Follow-up Alarm: Wake-up Check and Snooze merged into a single,
                        // mutually-exclusive selector - tap to open a picker, same pattern
                        // as the timing dialogs below, instead of a row of toggle boxes.
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        val showFollowUpModeDialog = remember { mutableStateOf(false) }
                        val showWakeCheckDialog = remember { mutableStateOf(false) }
                        val showSnoozeDialog = remember { mutableStateOf(false) }

                        val followUpModeLabel = when {
                            wakeCheckEnabledState.value -> "Wake-up Check"
                            enableSnoozeState.value -> "Snooze"
                            else -> "Off"
                        }

                        if (showFollowUpModeDialog.value) {
                            AlertDialog(
                                onDismissRequest = { showFollowUpModeDialog.value = false },
                                title = {
                                    Text("⏰ Follow-up Alarm", color = MaterialTheme.colorScheme.onSurface)
                                },
                                text = {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        val options = listOf(
                                            Triple("Off", false, false),
                                            Triple("Wake-up Check", true, false),
                                            Triple("Snooze", false, true)
                                        )
                                        options.forEach { (label, wake, snooze) ->
                                            val isSelected = wakeCheckEnabledState.value == wake &&
                                                enableSnoozeState.value == snooze
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        wakeCheckEnabledState.value = wake
                                                        enableSnoozeState.value = snooze
                                                        showFollowUpModeDialog.value = false
                                                    }
                                                    .padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = label,
                                                    fontSize = 14.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = { showFollowUpModeDialog.value = false }) {
                                        Text("Close", color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            )
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .clickable { showFollowUpModeDialog.value = true },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = "⏰ Follow-up Alarm",
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = followUpModeLabel,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Filled.ChevronRight,
                                    contentDescription = "Select follow-up alarm mode",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        if (wakeCheckEnabledState.value) {
                            if (showWakeCheckDialog.value) {
                                WakeCheckTimingDialog(
                                    wakeCheckMinutesState = wakeCheckMinutesState,
                                    onDismiss = { showWakeCheckDialog.value = false }
                                )
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .clickable { showWakeCheckDialog.value = true },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Check after ${wakeCheckMinutesState.value} minute${if (wakeCheckMinutesState.value > 1) "s" else ""}",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Icon(
                                        imageVector = Icons.Filled.ChevronRight,
                                        contentDescription = "Configure timing",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        if (enableSnoozeState.value) {
                            if (showSnoozeDialog.value) {
                                AlertDialog(
                                    onDismissRequest = { showSnoozeDialog.value = false },
                                    title = {
                                        Text("⏰ Snooze Timer", color = MaterialTheme.colorScheme.onSurface)
                                    },
                                    text = {
                                        SecureDialogWindow()
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                text = "Select snooze duration:",
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.padding(bottom = 16.dp)
                                            )

                                            val snoozeOptions = listOf(1, 3, 5, 7, 10)
                                            snoozeOptions.forEach { minutes ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            snoozeMinutesState.value = minutes
                                                            showSnoozeDialog.value = false
                                                        }
                                                        .padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "$minutes minute${if (minutes > 1) "s" else ""}",
                                                        fontSize = 14.sp,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    if (snoozeMinutesState.value == minutes) {
                                                        Icon(
                                                            imageVector = Icons.Filled.Check,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = { showSnoozeDialog.value = false }) {
                                            Text("Done", color = MaterialTheme.colorScheme.onSurface)
                                        }
                                    }
                                )
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .clickable { showSnoozeDialog.value = true },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Wait ${snoozeMinutesState.value} minute${if (snoozeMinutesState.value > 1) "s" else ""}",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Icon(
                                        imageVector = Icons.Filled.ChevronRight,
                                        contentDescription = "Select snooze time",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                // No per-alarm password input; uses globally set password in AlarmActivity

                // (no separate preview row; link is on the Mission header)

                // Protected Alarm (only show if no existing protected alarm)
                val hasExistingProtectedAlarm = alarms.any { it.isProtected && it.isEnabled }
                if (!hasExistingProtectedAlarm) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Check if this should be enabled (only for daily alarms)
                        val isDailyAlarm = selectedDaysState.value.size == 7 // All days selected = daily
                        val canProtect = isDailyAlarm // No need to check for existing protected alarm since section is hidden

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Protect this alarm",
                                fontSize = 14.sp,
                                color = if (canProtect) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            if (!isDailyAlarm) {
                                Text(
                                    text = "Daily alarms only",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            } else {
                                Text(
                                    text = "Prevents accidental dismiss",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }

                        Switch(
                            checked = protectedAlarmState.value,
                            onCheckedChange = {
                                if (canProtect) {
                                    protectedAlarmState.value = it
                                }
                            },
                            enabled = canProtect,
                            modifier = Modifier.padding(start = 8.dp),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.primary,
                                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }

                // Require face authentication toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Require face authentication to modify",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Only your face can edit or delete this alarm",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }

                    Switch(
                        checked = requiresFaceAuthState.value,
                        onCheckedChange = { requiresFaceAuthState.value = it },
                        modifier = Modifier.padding(start = 8.dp),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }

                // Gradually Increase Volume Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🔊 Gradually Increase Volume",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Volume rises from 40% to 100%",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }

                    Switch(
                        checked = enableGradualVolumeState.value,
                        onCheckedChange = { enableGradualVolumeState.value = it },
                        modifier = Modifier.padding(start = 8.dp),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }

                // Audio Boost Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🎚️ Enable Audio Boost",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Bass boost + equalizer effects",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }

                    Switch(
                        checked = enableAudioBoostState.value,
                        onCheckedChange = { enableAudioBoostState.value = it },
                        modifier = Modifier.padding(start = 8.dp),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }

                // Prevent Power Off Toggle
                val powerOffContext = LocalContext.current
                val showAccessibilityPrompt = remember { mutableStateOf(false) }
                // Set when the user leaves for Settings, so we know to finish the turn-on
                // when they come back.
                val awaitingAccessibilityGrant = remember { mutableStateOf(false) }

                val accessibilityLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                DisposableEffect(accessibilityLifecycleOwner) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME &&
                            awaitingAccessibilityGrant.value
                        ) {
                            awaitingAccessibilityGrant.value = false
                            // Only completes the turn-on if the user actually enabled it.
                            if (PowerMenuPreventionService.isAccessibilityEnabled(powerOffContext)) {
                                preventPowerOffState.value = true
                            }
                        }
                    }
                    accessibilityLifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { accessibilityLifecycleOwner.lifecycle.removeObserver(observer) }
                }

                if (showAccessibilityPrompt.value) {
                    AlertDialog(
                        onDismissRequest = { showAccessibilityPrompt.value = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                        title = {
                            Text(
                                "Accessibility permission needed",
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        text = {
                            SecureDialogWindow()
                            Text(
                                text = "Prevent Power Off uses CHRONA's accessibility service to " +
                                    "close the power menu while an alarm is ringing.\n\n" +
                                    "Turn on \"CHRONA\" under Installed services, then come back " +
                                    "and this will switch on automatically.",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        confirmButton = {
                            Button(onClick = {
                                showAccessibilityPrompt.value = false
                                awaitingAccessibilityGrant.value = true
                                try {
                                    powerOffContext.startActivity(
                                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                } catch (_: Exception) {
                                    awaitingAccessibilityGrant.value = false
                                    Toast.makeText(
                                        powerOffContext,
                                        "Could not open Accessibility settings",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }) {
                                Text("Open Settings", color = MaterialTheme.colorScheme.onSurface)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAccessibilityPrompt.value = false }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🔒 Prevent Power Off",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Block accidental power menu during alarm",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }

                    Switch(
                        checked = preventPowerOffState.value,
                        onCheckedChange = { wantOn ->
                            // Turning OFF never needs permission. Turning ON is blocked until
                            // the accessibility service is actually enabled, so the toggle can
                            // never show "on" while the feature cannot work.
                            if (wantOn && !PowerMenuPreventionService.isAccessibilityEnabled(powerOffContext)) {
                                showAccessibilityPrompt.value = true
                            } else {
                                preventPowerOffState.value = wantOn
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }

                // Ringtone selection
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { showRingtonePicker.value = true },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Audiotrack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 12.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Select Ringtone",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            val ringtoneName = remember(selectedRingtoneUriState.value) {
                                if (selectedRingtoneUriState.value == null) {
                                    "None selected"
                                } else {
                                    try {
                                        // Handle bundled raw resources properly
                                        val uriString = selectedRingtoneUriState.value?.toString()
                                        val isGlassy = uriString?.contains("glassy_bell") == true
                                        val isPyaro = uriString?.contains("pyaro_vrindavan") == true
                                        val isHareKrishna = uriString?.contains("harekrishna_chant") == true
                                        val isSilenceNoSound = uriString?.contains("silence_no_sound") == true
                                        if (isSilenceNoSound) {
                                            "🔇 Silence No Sound"
                                        } else if (isGlassy) {
                                            "🎵 glassy_bell"
                                        } else if (isPyaro) {
                                            "🎵 pyaro_vrindavan"
                                        } else if (isHareKrishna) {
                                            "🎵 harekrishna_chant"
                                        } else if (selectedRingtoneUriState.value?.toString()?.startsWith("android.resource://") == true) {
                                            // Handle other resource URIs
                                            val uriString = selectedRingtoneUriState.value?.toString()
                                            if (uriString?.contains("/raw/") == true) {
                                                // Extract resource name from path format: android.resource://package/raw/resourceName
                                                val segments = uriString?.split("/") ?: emptyList()
                                                if (segments.size > 3) {
                                                    "🎵 ${segments[3]}"
                                                } else {
                                                    "🎵 Custom Ringtone"
                                                }
                                            } else {
                                                // Extract resource name from ID format: android.resource://package/resourceId
                                                val segments = uriString?.split("/") ?: emptyList()
                                                if (segments.size > 3) {
                                                    try {
                                                        val resourceId = segments[3].toInt()
                                                        // Try to get resource name, fallback to "🎵 Custom Ringtone" if fails
                                                        try {
                                                            context.resources.getResourceEntryName(resourceId)
                                                        } catch (e: Exception) {
                                                            "🎵 Custom Ringtone"
                                                        }
                                                    } catch (e: NumberFormatException) {
                                                        "🎵 Custom Ringtone"
                                                    }
                                                } else {
                                                    "🎵 Custom Ringtone"
                                                }
                                            }
                                        } else {
                                            // Handle system ringtones - use RingtoneHelper for mapped names
                                            try {
                                                RingtoneHelper.getRingtoneTitle(context, selectedRingtoneUriState.value ?: Settings.System.DEFAULT_ALARM_ALERT_URI)
                                            } catch (e: Exception) {
                                                "🎵 System Ringtone"
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // Fallback for any errors
                                        val uriString = selectedRingtoneUriState.value?.toString()
                                        when {
                                            uriString?.contains("silence_no_sound") == true -> "🔇 Silence No Sound"
                                            uriString?.contains("glassy_bell") == true -> "🎵 glassy_bell"
                                            uriString?.contains("pyaro_vrindavan") == true -> "🎵 pyaro_vrindavan"
                                            uriString?.contains("harekrishna_chant") == true -> "🎵 harekrishna_chant"
                                            else -> "🎵 Custom Ringtone"
                                        }
                                    }
                                }
                            }
                            Text(
                                text = ringtoneName,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (queuedMissions.contains("face")) {
                        if (!missionFaceEnrolled.value && !FaceMissionSupport.hasPendingEmbedding(context)) {
                            Toast.makeText(context, "Please enroll your face for Face Verify mission", Toast.LENGTH_LONG).show()
                            autoSubmitAfterEnrollment.value = true
                            showMissionFaceEnrollment.value = true
                            return@Button
                        }
                    }
                    performSaveAction()
                },
                enabled = isShiftRotation.value || selectedRingtoneUriState.value != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (isShiftRotation.value) "Done" else "Create Alarm",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismissRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Cancel",
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    )

    // Ringtone picker dialog
    if (showRingtonePicker.value) {
        CustomRingtonePicker(
            context = context,
            onRingtoneSelected = { uri ->
                selectedRingtoneUriState.value = uri
                showRingtonePicker.value = false
            },
            onDismiss = {
                mediaPlayerState.value?.stop()
                mediaPlayerState.value?.release()
                mediaPlayerState.value = null
                showRingtonePicker.value = false
            },
            mediaPlayerState = mediaPlayerState
        )
    }
}

@Composable
fun PermissionsCenterDialog(
    onDismiss: () -> Unit,
    isMandatoryOnboarding: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val manualPermissionPrefs = remember(context) {
        context.getSharedPreferences("permission_center_manual_ack", Context.MODE_PRIVATE)
    }
    val exactAlarmsEnabled = remember { mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.S) }
    val batteryOptimizationEnabled = remember { mutableStateOf(MainActivity.isBatteryOptimizationIgnored(context)) }
    val accessibilityEnabled = remember { mutableStateOf(MainActivity.isAccessibilityServiceEnabled(context)) }
    val notificationsEnabled = remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val cameraEnabled = remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val microphoneEnabled = remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val musicAudioEnabled = remember { mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) }
    val writeSettingsEnabled = remember { mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)) }
    val overlayEnabled = remember { mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.M || PermissionUtils.canDrawOverlays(context)) }
    val deviceAdminEnabled = remember { mutableStateOf(false) }
    val oemLockScreenEnabled = remember { mutableStateOf(false) }
    val oemAutoStartEnabled = remember { mutableStateOf(false) }
    var pendingConfirmKey by remember { mutableStateOf<String?>(null) }

    val manufacturer = Build.MANUFACTURER.lowercase()
    val isXiaomi = manufacturer.contains("xiaomi") || manufacturer.contains("poco") || manufacturer.contains("redmi")
    val isAggressiveOem = isXiaomi || manufacturer.contains("oppo") || manufacturer.contains("vivo") || manufacturer.contains("huawei") || manufacturer.contains("oneplus") || manufacturer.contains("realme")

    fun markManualPermissionEnabled(key: String, state: MutableState<Boolean>) {
        state.value = true
        runCatching {
            manualPermissionPrefs.edit().putBoolean(key, true).commit()
        }
    }

    fun refreshPermissionStatus() {
        exactAlarmsEnabled.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.canScheduleExactAlarms()
            }.getOrDefault(false)
        } else {
            true
        }

        batteryOptimizationEnabled.value = MainActivity.isBatteryOptimizationIgnored(context)
        accessibilityEnabled.value = MainActivity.isAccessibilityServiceEnabled(context)

        notificationsEnabled.value = runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false)

        cameraEnabled.value = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        microphoneEnabled.value = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        musicAudioEnabled.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        writeSettingsEnabled.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else {
            true
        }

        overlayEnabled.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PermissionUtils.canDrawOverlays(context)
        } else {
            true
        }

        deviceAdminEnabled.value = runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
            val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)
            dpm?.isAdminActive(admin) == true
        }.getOrDefault(false)

        oemLockScreenEnabled.value = manualPermissionPrefs.getBoolean("oem_lock_screen_popup_enabled", false)
        oemAutoStartEnabled.value = manualPermissionPrefs.getBoolean("oem_auto_start_enabled", false)
    }

    LaunchedEffect(Unit) {
        refreshPermissionStatus()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                refreshPermissionStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

@Composable
fun AutoSingleLineTitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    initialFontSize: androidx.compose.ui.unit.TextUnit = 15.sp,
    minFontSize: androidx.compose.ui.unit.TextUnit = 10.sp
) {
    var scaledFontSize by remember(text) { mutableStateOf(initialFontSize) }
    var readyToDraw by remember(text) { mutableStateOf(false) }

    Text(
        text = text,
        color = color,
        fontSize = scaledFontSize,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
        modifier = modifier.drawWithContent {
            if (readyToDraw) drawContent()
        },
        onTextLayout = { textLayoutResult ->
            if (textLayoutResult.didOverflowWidth && scaledFontSize.value > minFontSize.value) {
                scaledFontSize = (scaledFontSize.value * 0.92f).sp
            } else {
                readyToDraw = true
            }
        }
    )
}

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        SecureDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
                // Top Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "CHRONA Setup",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Permissions & Power Settings",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Enable required permissions to ensure alarms ring reliably.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(12.dp))

                // Scrollable List
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AutoSingleLineTitle(text = "Exact Alarms", modifier = Modifier.weight(1f).padding(end = 8.dp))
                                if (exactAlarmsEnabled.value) {
                                    PermissionEnabledBadge()
                                }
                            }
                            Text("Allow the app to schedule precise alarms.", color = MaterialTheme.colorScheme.onSurface)
                            if (!exactAlarmsEnabled.value) {
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            PermissionUtils.requestScheduleExactAlarmsPermission(context)
                                        } catch (_: Exception) {}
                                    }
                                ) {
                                    Text("Open Alarms & Reminders")
                                }
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AutoSingleLineTitle(text = "Battery Optimization", modifier = Modifier.weight(1f).padding(end = 8.dp))
                            if (batteryOptimizationEnabled.value) {
                                PermissionEnabledBadge()
                            }
                        }
                        Text("Disable optimization so alarms can ring reliably.", color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = {
                                if (!exactAlarmsEnabled.value) {
                                    Toast.makeText(
                                        context,
                                        "Enable 'Exact Alarms' (Step 1) first, then tap Don't Optimize",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    return@Button
                                }
                                MainActivity.requestIgnoreBatteryOptimization(context)
                            },
                            enabled = !batteryOptimizationEnabled.value
                        ) {
                            Text(if (batteryOptimizationEnabled.value) "Enabled" else "Don't Optimize Battery")
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AutoSingleLineTitle(text = "Notifications", modifier = Modifier.weight(1f).padding(end = 8.dp))
                            if (notificationsEnabled.value) {
                                PermissionEnabledBadge()
                            }
                        }
                        Text("Permit notifications for foreground alarm service.", color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            },
                            enabled = !notificationsEnabled.value
                        ) { Text(if (notificationsEnabled.value) "Enabled" else "Open Notification Settings") }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AutoSingleLineTitle(text = "Camera (Face & Photo Missions)", modifier = Modifier.weight(1f).padding(end = 8.dp))
                            if (cameraEnabled.value) {
                                PermissionEnabledBadge()
                            }
                        }
                        Text("Required for Face Authentication and Photo/QR mission verification.", color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                if (context is Activity) {
                                    androidx.core.app.ActivityCompat.requestPermissions(context, arrayOf(Manifest.permission.CAMERA), 1001)
                                }
                            },
                            enabled = !cameraEnabled.value
                        ) { Text(if (cameraEnabled.value) "Enabled" else "Allow Camera") }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AutoSingleLineTitle(text = "Microphone (Voice Phrase Mission)", modifier = Modifier.weight(1f).padding(end = 8.dp))
                            if (microphoneEnabled.value) {
                                PermissionEnabledBadge()
                            }
                        }
                        Text("Required for Speech-to-Text voice phrase alarm missions.", color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                if (context is Activity) {
                                    androidx.core.app.ActivityCompat.requestPermissions(context, arrayOf(Manifest.permission.RECORD_AUDIO), 1002)
                                }
                            },
                            enabled = !microphoneEnabled.value
                        ) { Text(if (microphoneEnabled.value) "Enabled" else "Allow Microphone") }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AutoSingleLineTitle(text = "Music & Audio (Custom Ringtones)", modifier = Modifier.weight(1f).padding(end = 8.dp))
                            if (musicAudioEnabled.value) {
                                PermissionEnabledBadge()
                            }
                        }
                        Text("Required to access device audio files and set custom ringtones.", color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                if (context is Activity) {
                                    val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        Manifest.permission.READ_MEDIA_AUDIO
                                    } else {
                                        Manifest.permission.READ_EXTERNAL_STORAGE
                                    }
                                    androidx.core.app.ActivityCompat.requestPermissions(context, arrayOf(perm), 1003)
                                }
                            },
                            enabled = !musicAudioEnabled.value
                        ) { Text(if (musicAudioEnabled.value) "Enabled" else "Allow Music & Audio") }
                    }
                }

                // Modify system settings (WRITE_SETTINGS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AutoSingleLineTitle(text = "Modify system settings", modifier = Modifier.weight(1f).padding(end = 8.dp))
                                if (writeSettingsEnabled.value) {
                                    PermissionEnabledBadge()
                                }
                            }
                            Text(
                                "Allow Chrona to change system settings such as alarm volume.",
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (!writeSettingsEnabled.value) {
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                            context.startActivity(intent)
                                        } catch (_: Exception) {}
                                    }
                                ) { Text("Open Modify System Settings") }
                            }
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AutoSingleLineTitle(text = "Display over other apps", modifier = Modifier.weight(1f).padding(end = 8.dp))
                                if (overlayEnabled.value) {
                                    PermissionEnabledBadge()
                                }
                            }
                            Text("Helps show the alarm screen above other apps.", color = MaterialTheme.colorScheme.onSurface)
                            if (!overlayEnabled.value) {
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            PermissionUtils.requestOverlayPermission(context)
                                        } catch (_: Exception) {}
                                    }
                                ) { Text("Open Overlay Permission") }
                            }
                        }
                    }
                }

                if (isXiaomi) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AutoSingleLineTitle(text = "Lock screen & pop-up windows", modifier = Modifier.weight(1f).padding(end = 8.dp))
                                if (oemLockScreenEnabled.value) {
                                    PermissionEnabledBadge()
                                }
                            }
                            Text(
                                "On Xiaomi, Redmi, POCO, and similar OEM devices, enable show on lock screen and background pop-up windows here.",
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (!oemLockScreenEnabled.value) {
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            PermissionUtils.requestBackgroundWindowAndLockScreenPermissions(context)
                                            pendingConfirmKey = "oem_lock_screen_popup_enabled"
                                        } catch (_: Exception) {}
                                    }
                                ) {
                                    Text("Open OEM Permission Page")
                                }
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AutoSingleLineTitle(text = "Device admin", modifier = Modifier.weight(1f).padding(end = 8.dp))
                            if (deviceAdminEnabled.value) {
                                PermissionEnabledBadge()
                            }
                        }
                        Text("Open Device admin apps section to activate device permissions.", color = MaterialTheme.colorScheme.onSurface)
                        if (!deviceAdminEnabled.value) {
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val deviceAdminComponent = android.content.ComponentName(context, MyDeviceAdminReceiver::class.java)
                                        val intent = Intent("android.app.action.ADD_DEVICE_ADMIN").apply {
                                            putExtra("android.app.extra.DEVICE_ADMIN", deviceAdminComponent)
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        try {
                                            val intent = Intent("android.settings.DEVICE_ADMIN_SETTINGS")
                                            context.startActivity(intent)
                                        } catch (e2: Exception) {
                                            try {
                                                val intent = Intent("android.app.action.DEVICE_ADMIN_SETTINGS")
                                                context.startActivity(intent)
                                            } catch (e3: Exception) {
                                                android.widget.Toast.makeText(context, "Please go to Settings > Security > Device admin apps", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                            ) {
                                Text("Open device admin settings")
                            }
                        }
                    }
                }

                if (isAggressiveOem) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AutoSingleLineTitle(text = "Auto-start & OEM power", modifier = Modifier.weight(1f).padding(end = 8.dp))
                                if (oemAutoStartEnabled.value) {
                                    PermissionEnabledBadge()
                                }
                            }
                            Text("Open OEM pages to allow background start and no restrictions.", color = MaterialTheme.colorScheme.onSurface)

                            // Special guidance for OPPO/Realme/OnePlus users
                            if (manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oplus")) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "⚠️ OPPO/Realme: Enable 'Display pop-up window while running in background' for reliable alarms",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            if (!oemAutoStartEnabled.value) {
                                Spacer(Modifier.height(6.dp))
                                Button(
                                    onClick = {
                                        try {
                                            val manufacturerStr = Build.MANUFACTURER.lowercase()
                                            fun tryStart(context: Context, cn: ComponentName): Boolean = try {
                                                val i = Intent().apply { component = cn; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                                context.startActivity(i); true
                                            } catch (_: Exception) { false }

                                            val opened = when {
                                                manufacturerStr.contains("xiaomi") || manufacturerStr.contains("poco") || manufacturerStr.contains("redmi") -> tryStart(context, ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")) ||
                                                        tryStart(context, ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")) ||
                                                        tryStart(context, ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")) ||
                                                        tryStart(context, ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.PowerHideModeActivity"))
                                                manufacturerStr.contains("oppo") || manufacturerStr.contains("realme") || manufacturerStr.contains("oplus") -> {
                                                    MainActivity.showOppoBackgroundPopupDialog(context)
                                                    true
                                                }
                                                manufacturerStr.contains("vivo") -> tryStart(context, ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")) ||
                                                        tryStart(context, ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
                                                manufacturerStr.contains("huawei") -> tryStart(context, ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                                                manufacturerStr.contains("oneplus") -> tryStart(context, ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))
                                                else -> false
                                            }
                                            if (!opened) {
                                                try {
                                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                        data = Uri.parse("package:${context.packageName}")
                                                    }
                                                    context.startActivity(intent)
                                                } catch (_: Exception) {}
                                            }
                                            pendingConfirmKey = "oem_auto_start_enabled"
                                        } catch (_: Exception) {}
                                    }
                                ) { Text("Open OEM Auto-start Settings") }
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AutoSingleLineTitle(
                                text = "Power Menu Prevention (Accessibility)",
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            if (accessibilityEnabled.value) {
                                PermissionEnabledBadge()
                            }
                        }
                        Text(
                            text = "Prevents turning off the phone while an alarm is ringing. On OPPO A6 Pro 5G, if greyed out ('Restricted setting'), tap 'App Info' below -> ⋮ (top right) -> 'Allow restricted settings'.",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (!accessibilityEnabled.value) {
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { MainActivity.openAccessibilitySettingsForChrona(context) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Enable Accessibility")
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    OutlinedButton(
                                        onClick = {
                                            runCatching {
                                                context.startActivity(
                                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                        data = Uri.parse("package:${context.packageName}")
                                                    }
                                                )
                                            }
                                        }
                                    ) {
                                        Text("App Info")
                                    }
                                }
                            }
                        }
                    }
                }
            }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Sticky Action Bar
                val missingList = buildList {
                    if (!exactAlarmsEnabled.value) add("Exact Alarms")
                    if (!notificationsEnabled.value) add("Notifications")
                    if (!cameraEnabled.value) add("Camera")
                    if (!microphoneEnabled.value) add("Microphone")
                    if (!musicAudioEnabled.value) add("Music & Audio")
                    if (!batteryOptimizationEnabled.value) add("Battery Optimization")
                    if (!writeSettingsEnabled.value) add("Modify System Settings")
                    if (!overlayEnabled.value) add("Display Over Other Apps")
                    if (!deviceAdminEnabled.value) add("Device Admin")
                    if (!accessibilityEnabled.value) add("Accessibility")
                }
                val allGranted = missingList.isEmpty()

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        onClick = {
                            refreshPermissionStatus()
                            val currentMissing = MainActivity.getMissingRequiredPermissions(context)
                            if (currentMissing.isEmpty()) {
                                onDismiss()
                            } else {
                                when {
                                    !exactAlarmsEnabled.value -> {
                                        Toast.makeText(context, "Opening Exact Alarms Settings...", Toast.LENGTH_SHORT).show()
                                        PermissionUtils.requestScheduleExactAlarmsPermission(context)
                                    }
                                    !notificationsEnabled.value -> {
                                        Toast.makeText(context, "Opening Notification Settings...", Toast.LENGTH_SHORT).show()
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            runCatching { context.startActivity(intent) }.onFailure {
                                                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                    data = Uri.fromParts("package", context.packageName, null)
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                runCatching { context.startActivity(fallback) }
                                            }
                                        }
                                    }
                                    !cameraEnabled.value -> {
                                        if (context is Activity) {
                                            androidx.core.app.ActivityCompat.requestPermissions(context, arrayOf(Manifest.permission.CAMERA), 1001)
                                        }
                                    }
                                    !microphoneEnabled.value -> {
                                        if (context is Activity) {
                                            androidx.core.app.ActivityCompat.requestPermissions(context, arrayOf(Manifest.permission.RECORD_AUDIO), 1002)
                                        }
                                    }
                                    !musicAudioEnabled.value -> {
                                        if (context is Activity) {
                                            val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                Manifest.permission.READ_MEDIA_AUDIO
                                            } else {
                                                Manifest.permission.READ_EXTERNAL_STORAGE
                                            }
                                            androidx.core.app.ActivityCompat.requestPermissions(context, arrayOf(perm), 1003)
                                        }
                                    }
                                    !batteryOptimizationEnabled.value -> {
                                        Toast.makeText(context, "Opening Battery Settings...", Toast.LENGTH_SHORT).show()
                                        MainActivity.requestIgnoreBatteryOptimization(context)
                                    }
                                    !overlayEnabled.value -> {
                                        Toast.makeText(context, "Opening Display Over Apps Settings...", Toast.LENGTH_SHORT).show()
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            runCatching { context.startActivity(intent) }
                                        }
                                    }
                                    else -> {
                                        Toast.makeText(
                                            context,
                                            "Please enable: ${currentMissing.joinToString(", ")}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }
                    ) {
                        Text(
                            text = if (allGranted) "Continue to App" else "Check & Continue (${missingList.size} missing)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (pendingConfirmKey != null) {
            val key = pendingConfirmKey
            val isLockScreen = key == "oem_lock_screen_popup_enabled"
            AlertDialog(
                onDismissRequest = { pendingConfirmKey = null },
                title = { Text(if (isLockScreen) "Confirm Lock Screen Permission" else "Confirm Auto-start Permission") },
                text = {
                    SecureDialogWindow()
                    Text(
                        if (isLockScreen)
                            "Did you enable 'Show on lock screen' and background pop-up permissions in device settings?"
                        else
                            "Did you enable 'Auto-start' / background activity for this app in device settings?"
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (isLockScreen) {
                                markManualPermissionEnabled("oem_lock_screen_popup_enabled", oemLockScreenEnabled)
                            } else {
                                markManualPermissionEnabled("oem_auto_start_enabled", oemAutoStartEnabled)
                            }
                            pendingConfirmKey = null
                        }
                    ) {
                        Text("Yes, Enabled")
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { pendingConfirmKey = null }
                    ) {
                        Text("No, Not Yet")
                    }
                }
            )
        }
    }

@Composable
fun DeviceForensicsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val snapshot = remember { ExtremeForensicLogger.captureSessionSnapshot(context) }
    val app = remember { snapshot.optJSONObject("forensic_session")?.optJSONObject("app") }
    val device = remember { snapshot.optJSONObject("forensic_session")?.optJSONObject("device") }
    val hardware = remember { snapshot.optJSONObject("forensic_session")?.optJSONObject("hardware") }
    val display = remember { snapshot.optJSONObject("forensic_session")?.optJSONObject("display") }
    val runtime = remember { snapshot.optJSONObject("forensic_session")?.optJSONObject("runtime") }
    val power = remember { snapshot.optJSONObject("forensic_session")?.optJSONObject("power") }
    val connectivity = remember { snapshot.optJSONObject("forensic_session")?.optJSONObject("connectivity") }
    val recentEvents = remember { ExtremeForensicLogger.recentEvents(100) }

    fun bytesToMb(bytes: Long): String = if (bytes < 0) "unavailable" else "${bytes / (1024 * 1024)} MB"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Device & Runtime Info") },
        text = {
            SecureDialogWindow()
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ForensicSection("App") {
                    ForensicRow("Version", "${app?.optString("version_name")} (${app?.optLong("version_code")})")
                    ForensicRow("Build type", app?.optString("build_type") ?: "unavailable")
                    ForensicRow("Package", app?.optString("package") ?: "unavailable")

                    val currentDownloadState by AppUpdateManager.downloadState.collectAsState()
                    if (currentDownloadState is AppUpdateManager.DownloadState.Downloading) {
                        val state = currentDownloadState as AppUpdateManager.DownloadState.Downloading
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Downloading Update...",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = if (state.progressPercent >= 0) "${state.progressPercent}%" else "...",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            if (state.progressPercent >= 0) {
                                LinearProgressIndicator(
                                    progress = state.progressPercent / 100f,
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }

                            val downloadedMb = String.format("%.1f", state.downloadedBytes / (1024f * 1024f))
                            val totalMb = if (state.totalBytes > 0) String.format("%.1f MB", state.totalBytes / (1024f * 1024f)) else "MB"
                            Text(
                                text = "$downloadedMb MB / $totalMb",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    when (val shown = currentDownloadState) {
                        AppUpdateManager.DownloadState.Checking -> Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                text = "Checking for updates...",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        AppUpdateManager.DownloadState.UpToDate -> Text(
                            text = "You are using the latest version.",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        is AppUpdateManager.DownloadState.Error -> Text(
                            text = shown.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        else -> Unit
                    }

                    val pendingUpdate = remember { AppUpdateManager.getDownloadedUpdateFile(context) }
                    if (pendingUpdate != null) {
                        Button(
                            onClick = { AppUpdateManager.installDownloadedUpdate(context) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Install Downloaded Update Now")
                        }
                    }

                    val activity = context as? Activity
                    val updateBusy = currentDownloadState is AppUpdateManager.DownloadState.Checking ||
                        currentDownloadState is AppUpdateManager.DownloadState.Downloading
                    Button(
                        onClick = { activity?.let { AppUpdateManager.checkForUpdate(it, isManual = true) } },
                        enabled = !updateBusy && activity != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (currentDownloadState is AppUpdateManager.DownloadState.Checking) "Checking..." else "Check for Updates")
                    }
                }
                ForensicSection("Remote Logs") {
                    var tokenInput by remember { mutableStateOf(RemoteLogUploader.token(context)) }
                    val savedStatus = remember { RemoteLogUploader.lastStatus(context) }
                    val uploadStatus by RemoteLogUploader.status.collectAsState()
                    Text(
                        text = "Logs go to the private GitHub repo ${RemoteLogUploader.REPOSITORY} every 15 min and after each alarm, passwords hidden.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text("GitHub token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                RemoteLogUploader.saveToken(context, tokenInput)
                                Toast.makeText(context, "Token saved", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Save Token") }
                        OutlinedButton(
                            onClick = { RemoteLogUploader.uploadSoon(context, "manual") },
                            enabled = tokenInput.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) { Text("Upload Now") }
                    }
                    uploadStatus.ifEmpty { savedStatus }.takeIf { it.isNotEmpty() }?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                ForensicSection("Device") {
                    ForensicRow("Manufacturer", device?.optString("manufacturer") ?: "unavailable")
                    ForensicRow("Model", device?.optString("model") ?: "unavailable")
                    ForensicRow("Android", "${device?.optString("android_version")} (SDK ${device?.optInt("sdk")})")
                    ForensicRow("Build ID", device?.optString("build_id") ?: "unavailable")
                    ForensicRow("Security patch", device?.optString("security_patch") ?: "unavailable")
                    ForensicRow("ABI", device?.optString("abi") ?: "unavailable")
                }
                ForensicSection("Hardware") {
                    ForensicRow("CPU cores", hardware?.optInt("cpu_cores")?.toString() ?: "unavailable")
                    ForensicRow("RAM available", bytesToMb(hardware?.optLong("ram_available_bytes") ?: -1L))
                    ForensicRow("RAM total", bytesToMb(hardware?.optLong("ram_total_bytes") ?: -1L))
                    ForensicRow("Storage available", bytesToMb(hardware?.optLong("storage_available_bytes") ?: -1L))
                    ForensicRow("Storage total", bytesToMb(hardware?.optLong("storage_total_bytes") ?: -1L))
                }
                ForensicSection("Display") {
                    ForensicRow("Resolution", display?.optString("resolution") ?: "unavailable")
                    ForensicRow("Density", "${display?.optInt("density_dpi")} dpi")
                    ForensicRow("Refresh rate", display?.optString("refresh_rate") ?: "unavailable")
                    ForensicRow("Orientation", display?.optString("orientation") ?: "unavailable")
                }
                ForensicSection("Runtime") {
                    ForensicRow("Locale", runtime?.optString("locale") ?: "unavailable")
                    ForensicRow("Timezone", runtime?.optString("timezone") ?: "unavailable")
                    ForensicRow("Session ID", runtime?.optString("session_id")?.take(8) ?: "unavailable")
                }
                ForensicSection("Power") {
                    ForensicRow("Battery", "${power?.optInt("battery_percent")}%")
                    ForensicRow("Charging", power?.optString("charging") ?: "unavailable")
                    ForensicRow("Power save mode", power?.optString("power_save_mode") ?: "unavailable")
                }
                ForensicSection("Connectivity") {
                    ForensicRow("Network", connectivity?.optString("transport") ?: "unavailable")
                    ForensicRow("Connected", connectivity?.optBoolean("connected")?.toString() ?: "unavailable")
                }
                ForensicSection("Recent Activity (${recentEvents.size})") {
                    if (recentEvents.isEmpty()) {
                        Text(
                            text = "No events captured yet this session.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    } else {
                        recentEvents.asReversed().forEach { line ->
                            Text(
                                text = line,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                try {
                    val dir = File(context.getExternalFilesDir(null), "forensics")
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, "device_forensic_report_${System.currentTimeMillis()}.txt")
                    val report = buildString {
                        append("=== DEVICE / SESSION SNAPSHOT ===\n")
                        append(snapshot.toString(2))
                        append("\n\n=== RECENT ACTIVITY (")
                        append(recentEvents.size)
                        append(" events) ===\n")
                        if (recentEvents.isEmpty()) {
                            append("No events captured yet this session.\n")
                        } else {
                            recentEvents.forEach { append(it); append('\n') }
                        }
                    }
                    file.writeText(report)

                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share forensic report"))
                } catch (e: Exception) {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }) {
                Text("Export & Share")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ForensicSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(
            text = title.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        content()
    }
}

@Composable
private fun ForensicRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            fontSize = 13.sp,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun PermissionEnabledBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF2E7D32),
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = "Enabled",
            color = Color(0xFF2E7D32),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun DayToggleButton(
    day: Int,
    dayString: String,
    selectedDays: Set<Int>,
    onDaySelected: (Int, Boolean) -> Unit
) {
    val isSelected = selectedDays.contains(day)
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp)
            )
            .border(
                width = 1.5.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable {
                onDaySelected(day, !isSelected)
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = dayString,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

// ───────────────────────── RINGTONES FROM LOCAL FOLDER ─────────────────────────

private fun Context.getLocalChronaRingtones(): MutableList<Pair<String, Uri>> {
    val list = mutableListOf<Pair<String, Uri>>()

    try {
        // Try common paths for file manager's ringtone folder
        val possiblePaths = listOf(
            File("/storage/emulated/0/Ringtones/chrona"),
            File("/storage/emulated/0/Download/Ringtones/chrona"),
            File("/storage/emulated/0/Music/Ringtones/chrona"),
            File("/storage/emulated/0/chrona"),
            File("/sdcard/Ringtones/chrona"),
            File("/sdcard/Download/Ringtones/chrona"),
            File("/sdcard/Music/Ringtones/chrona"),
            File("/sdcard/chrona")
        )

        var chronaFolder: File? = null

        // Find the first existing chrona folder
        for (path in possiblePaths) {
            if (path.exists() && path.isDirectory) {
                chronaFolder = path
                Log.d("LocalRingtones", "Found chrona folder: ${path.absolutePath}")
                break
            }
        }

        if (chronaFolder != null) {
            val audioFiles = chronaFolder.listFiles { file ->
                file.isFile && (file.extension.lowercase() in listOf("mp3", "wav", "ogg", "m4a", "aac", "flac"))
            }

            audioFiles?.forEach { file: File ->
                val displayName = file.nameWithoutExtension
                val icon = when {
                    displayName.contains("Silence", ignoreCase = true) -> "🔇"
                    displayName.contains("Glassy", ignoreCase = true) -> "🎵"
                    displayName.contains("Krishna", ignoreCase = true) -> "🎵"
                    displayName.contains("Hare", ignoreCase = true) -> "🎵"
                    displayName.contains("Vrindavan", ignoreCase = true) -> "🎵"
                    displayName.contains("Flute", ignoreCase = true) -> "🎵"
                    displayName.contains("Kaalbhairav", ignoreCase = true) -> "🎵"
                    displayName.contains("Alaipayuthe", ignoreCase = true) -> "🎵"
                    else -> "🎵"
                }

                // Create URI from file path
                val uri = Uri.fromFile(file)
                list.add("$icon $displayName" to uri)
                Log.d("LocalRingtones", "✅ Added local ringtone: $displayName -> $uri")
            }

            if (audioFiles.isNullOrEmpty()) {
                Log.w("LocalRingtones", "No audio files found in chrona folder")
            }
        } else {
            Log.w("LocalRingtones", "chrona folder not found in any of the expected paths")
        }

    } catch (e: SecurityException) {
        Log.e("LocalRingtones", "❌ SecurityException - no permission to access local files", e)
    } catch (e: Exception) {
        Log.e("LocalRingtones", "❌ Error reading local ringtones", e)
    }

    return list
}

@Composable
fun MultiDatePickerDialog(
    initialDates: List<Long>,
    onDismiss: () -> Unit,
    onConfirm: (List<Long>) -> Unit
) {
    val selectedMillisSet = remember { mutableStateOf(initialDates.toSet()) }
    val currentCalendar = remember { Calendar.getInstance() }
    val displayYear = remember { mutableStateOf(currentCalendar.get(Calendar.YEAR)) }
    val displayMonth = remember { mutableStateOf(currentCalendar.get(Calendar.MONTH)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            Text("Select Dates", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        },
        text = {
            SecureDialogWindow()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Month navigation row spanning full dialog width
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val todayCalendar = remember { Calendar.getInstance() }
                    val currentYear = todayCalendar.get(Calendar.YEAR)
                    val currentMonth = todayCalendar.get(Calendar.MONTH)
                    val canGoBack = displayYear.value > currentYear || (displayYear.value == currentYear && displayMonth.value > currentMonth)

                    IconButton(
                        onClick = {
                            if (canGoBack) {
                                if (displayMonth.value == 0) {
                                    displayMonth.value = 11
                                    displayYear.value--
                                } else {
                                    displayMonth.value--
                                }
                            }
                        },
                        enabled = canGoBack
                    ) {
                        Text(
                            "◀",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (canGoBack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        )
                    }

                    Text(
                        text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(
                            Calendar.getInstance().apply { set(displayYear.value, displayMonth.value, 1) }.time
                        ),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    IconButton(
                        onClick = {
                            if (displayMonth.value == 11) {
                                displayMonth.value = 0
                                displayYear.value++
                            } else {
                                displayMonth.value++
                            }
                        }
                    ) {
                        Text(
                            "▶",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                // Day of week headers
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa").forEach { day ->
                        Text(day, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, modifier = Modifier.width(32.dp))
                    }
                }

                // Days grid
                val daysInMonthList = remember(displayYear.value, displayMonth.value) {
                    val cal = Calendar.getInstance().apply {
                        set(displayYear.value, displayMonth.value, 1)
                    }
                    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                    val startDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0-indexed Sunday
                    val list = mutableListOf<Long?>()
                    repeat(startDayOfWeek) { list.add(null) }
                    for (day in 1..daysInMonth) {
                        val dayCal = Calendar.getInstance().apply {
                            set(displayYear.value, displayMonth.value, day, 0, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        list.add(dayCal.timeInMillis)
                    }
                    list
                }

                val todayStartMillis = remember {
                    Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                }

                // Chunk into weeks (7 days each)
                val weeks = daysInMonthList.chunked(7)
                weeks.forEach { week ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        week.forEach { dateMillis ->
                            if (dateMillis == null) {
                                Spacer(modifier = Modifier.size(36.dp))
                            } else {
                                val dayCal = Calendar.getInstance().apply { timeInMillis = dateMillis }
                                val dayNum = dayCal.get(Calendar.DAY_OF_MONTH)
                                val isPast = dateMillis < todayStartMillis
                                val isSelected = selectedMillisSet.value.contains(dateMillis)

                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                        .clickable(enabled = !isPast) {
                                            if (isSelected) {
                                                selectedMillisSet.value -= dateMillis
                                            } else {
                                                selectedMillisSet.value += dateMillis
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = dayNum.toString(),
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = when {
                                            isPast -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                            isSelected -> MaterialTheme.colorScheme.onPrimary
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                            }
                        }
                        repeat(7 - week.size) {
                            Spacer(modifier = Modifier.size(36.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "${selectedMillisSet.value.size} dates selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedMillisSet.value.sorted()) }
            ) {
                Text("OK", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
            }
        }
    )
}

// System ringtone name mapping (maps raw titles like "ringtone_001" to friendly names)
private val ringtoneNameMap = mapOf(
    "ringtone_001" to "Ambient",
    "ringtone_002" to "Aurora",
    "ringtone_003" to "Bells",
    "ringtone_004" to "Bright",
    "ringtone_005" to "Bubbles",
    "ringtone_006" to "Cascade",
    "ringtone_007" to "Celestial",
    "ringtone_008" to "Chimes",
    "ringtone_009" to "Classical",
    "ringtone_010" to "Cosmic",
    "ringtone_011" to "Crystal",
    "ringtone_012" to "Dewdrop",
    "ringtone_013" to "Digital",
    "ringtone_014" to "Dream",
    "ringtone_015" to "Echoes",
    "ringtone_016" to "Electronic",
    "ringtone_017" to "Elegant",
    "ringtone_018" to "Ethereal",
    "ringtone_019" to "Forest",
    "ringtone_020" to "Garden",
    "ringtone_021" to "Gong",
    "ringtone_022" to "Harmony",
    "ringtone_023" to "Heavenly",
    "ringtone_024" to "Holiday",
    "ringtone_025" to "Humming",
    "ringtone_026" to "Iconic",
    "ringtone_027" to "Inspiration",
    "ringtone_028" to "Jade",
    "ringtone_029" to "Jazz",
    "ringtone_030" to "Jingle",
    "ringtone_031" to "Kalimba",
    "ringtone_032" to "Karma",
    "ringtone_033" to "Lullaby",
    "ringtone_034" to "Lotus",
    "ringtone_035" to "Holiday",
    "ringtone_036" to "Metallic",
    "ringtone_037" to "Music Box",
    "ringtone_038" to "Mystic",
    "ringtone_039" to "Nature",
    "ringtone_040" to "Nostalgia",
    "ringtone_041" to "Notification",
    "ringtone_042" to "Oasis",
    "ringtone_043" to "Paradise",
    "ringtone_044" to "Peace",
    "ringtone_045" to "Playful",
    "ringtone_046" to "Pop",
    "ringtone_047" to "Pulse",
    "ringtone_048" to "Quiet",
    "ringtone_049" to "Rainbow",
    "ringtone_050" to "Retro",
    "ringtone_051" to "Rhythmic",
    "ringtone_052" to "Sensual",
    "ringtone_053" to "Serenity",
    "ringtone_054" to "Silky",
    "ringtone_055" to "Simplicity",
    "ringtone_056" to "Stellar",
    "ringtone_057" to "Sunshine",
    "ringtone_058" to "Synth",
    "ringtone_059" to "Symphony",
    "ringtone_060" to "Dynamic Weather",
    "ringtone_061" to "Rise"
)

@Composable
fun CustomRingtonePicker(
    context: Context,
    onRingtoneSelected: (Uri) -> Unit,
    onDismiss: () -> Unit,
    mediaPlayerState: MutableState<MediaPlayer?>
) {
    // 1. Fetch, separate, and sort App Ringtones vs System Ringtones in memory
    val (appRingtones, systemRingtones) = remember(context) {
        // --- A. APP RINGTONES (Bundled Raw Ringtones + Local Chrona Folder) ---
        val appList = mutableListOf<Pair<String, Uri>>()

        val rawRingtones = listOf(
            "silence_no_sound" to "🔇 Silence No Sound",
            "glassy_bell" to "🎵 Glossy Bell",
            "pyaro_vrindavan" to "🎵 Pyaro Vrindavan",
            "agam_kaalbhairav_ashtakam" to "🎵 Kaalbhairav Ashtakam",
            "alaipayuthe" to "🎵 Alaipayuthe",
            "gentle_ocean_and_birdsong_24068" to "🎵 Gentle Ocean & Birdsong",
            "harekrishna_chant" to "🎵 Hare Krishna Chant",
            "krishna_flute" to "🎵 Krishna Flute",
            "radha_krishna_flute" to "🎵 Radha Krishna Flute"
        )
        rawRingtones.forEach { (resName, displayLabel) ->
            val resId = context.resources.getIdentifier(resName, "raw", context.packageName)
            if (resId != 0) {
                appList.add(displayLabel to Uri.parse("android.resource://${context.packageName}/$resId"))
            }
        }

        appList.addAll(context.getLocalChronaRingtones())

        // Deduplicate app ringtones by normalized name
        val appSeen = mutableMapOf<String, Pair<String, Uri>>()
        appList.forEach { item ->
            val norm = item.first.replace(Regex("^[🎵⏰🔔🔇]\\s*"), "").replace("_", " ").trim().lowercase(Locale.getDefault())
            appSeen.putIfAbsent(norm, item)
        }
        val sortedAppRingtones = appSeen.values.sortedBy { item ->
            item.first.replace(Regex("^[🎵⏰🔔🔇]\\s*"), "").replace("_", " ").trim().lowercase(Locale.getDefault())
        }

        // Collect all app ringtone names to exclude them from the System Ringtones list
        val appRingtoneNames = sortedAppRingtones.map { item ->
            item.first.replace(Regex("^[🎵⏰🔔🔇]\\s*"), "").replace("_", " ").trim().lowercase(Locale.getDefault())
        }.toSet()

        // App ringtone keywords to filter out from System Ringtones
        val appKeywords = listOf(
            "radha", "krishna", "flute", "chant", "vrindavan", "kaalbhairav",
            "ashtakam", "alaipayuthe", "ocean", "birdsong", "glassy", "silence",
            "pyaro", "hare", "bhajan", "mantra", "chrona"
        )

        // --- B. SYSTEM RINGTONES (Alarm + Standard System Ringtones only) ---
        val systemList = mutableListOf<Pair<String, Uri>>()
        val actualDefaultUri = try {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        } catch (_: Exception) { null }

        listOf(
            RingtoneManager.TYPE_ALARM to "🔔",
            RingtoneManager.TYPE_RINGTONE to "🎵"
        ).forEach { (type, emoji) ->
            try {
                val manager = RingtoneManager(context).apply { setType(type) }
                manager.cursor?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val rawTitle = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)?.trim() ?: "Unknown"
                        val idStr = cursor.getString(RingtoneManager.ID_COLUMN_INDEX)
                        val uriBase = cursor.getString(RingtoneManager.URI_COLUMN_INDEX)
                        val uriStr = "$uriBase/$idStr"
                        val uri = Uri.parse(uriStr)

                        val rawLower = rawTitle.lowercase(Locale.getDefault())

                        // Exclude app package resources, chrona folder files, or app resource URIs
                        if (uriStr.contains(context.packageName, ignoreCase = true) ||
                            uriStr.contains("chrona", ignoreCase = true) ||
                            uri.scheme == "android.resource"
                        ) {
                            continue
                        }

                        val cleanTitle = rawTitle.substringBeforeLast(".")
                        val key = cleanTitle.lowercase(Locale.getDefault())

                        var resolvedTitle = ringtoneNameMap[key]
                            ?: ringtoneNameMap[rawLower]

                        if (resolvedTitle == null) {
                            if (cleanTitle.matches(Regex("^ringtone[_-].*", RegexOption.IGNORE_CASE))) {
                                val numPart = cleanTitle.removePrefix("ringtone").removePrefix("_").removePrefix("-")
                                resolvedTitle = if (numPart.isNotBlank()) "Ringtone ${numPart.replace("_", " ")}" else rawTitle
                            } else {
                                resolvedTitle = cleanTitle.replace("_", " ")
                                    .split(" ")
                                    .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
                            }
                        }

                        val normTitle = resolvedTitle.replace(Regex("^[🎵⏰🔔🔇]\\s*"), "").replace("_", " ").trim().lowercase(Locale.getDefault())

                        // Exclude items matching app ringtone titles or app keywords or fuzzy title match
                        val isAppRingtone = appRingtoneNames.any { appName ->
                            normTitle.contains(appName) || appName.contains(normTitle) || key.contains(appName)
                        } || appKeywords.any { kw ->
                            normTitle.contains(kw) || rawLower.contains(kw)
                        }

                        if (isAppRingtone) {
                            continue
                        }

                        val isDefault = uri == actualDefaultUri || uri.toString() == actualDefaultUri?.toString()
                        val prefix = if (isDefault) "⏰ Default - " else "$emoji "

                        systemList.add("$prefix$resolvedTitle" to uri)
                    }
                }
            } catch (e: Exception) {
                Log.e("CustomRingtonePicker", "Error loading system ringtones", e)
            }
        }

        // Deduplicate system ringtones by normalized name
        val systemSeen = mutableMapOf<String, Pair<String, Uri>>()
        systemList.forEach { item ->
            val norm = item.first.replace(Regex("^[🎵⏰🔔🔇]\\s*"), "").replace(Regex("^Default\\s*-\\s*"), "").replace("_", " ").trim().lowercase(Locale.getDefault())
            val existing = systemSeen[norm]
            if (existing == null) {
                systemSeen[norm] = item
            } else if (item.first.contains("Default -") && !existing.first.contains("Default -")) {
                systemSeen[norm] = item
            }
        }
        val sortedSystemRingtones = systemSeen.values.sortedBy { item ->
            item.first.replace(Regex("^[🎵⏰🔔🔇]\\s*"), "").replace(Regex("^Default\\s*-\\s*"), "").replace("_", " ").trim().lowercase(Locale.getDefault())
        }

        Pair(sortedAppRingtones, sortedSystemRingtones)
    }

    var selectedTab by remember { mutableStateOf(0) } // 0 = System Ringtones, 1 = App Ringtones
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var playbackPosition by remember { mutableStateOf(0) }

    fun playRingtone(uri: Uri, startPosition: Int = 0) {
        try {
            mediaPlayerState.value?.stop()
            mediaPlayerState.value?.release()
        } catch (_: Exception) {}
        mediaPlayerState.value = null
        try {
            mediaPlayerState.value = MediaPlayer().apply {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                setAudioAttributes(audioAttributes)
                setDataSource(context, uri)
                prepare()
                if (startPosition > 0 && startPosition < duration) {
                    seekTo(startPosition)
                }
                start()
            }
        } catch (e: Exception) {
            Log.e("CustomRingtonePicker", "Error playing ringtone", e)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    // Pause audio on background pause; RESUME from exact paused position when app returns to foreground
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_PAUSE -> {
                    val mp = mediaPlayerState.value
                    if (mp != null) {
                        try {
                            if (mp.isPlaying) {
                                playbackPosition = mp.currentPosition
                                mp.pause()
                            }
                        } catch (e: Exception) {
                            Log.e("CustomRingtonePicker", "Error pausing player on lifecycle event", e)
                        }
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    selectedUri?.let { uri ->
                        val mp = mediaPlayerState.value
                        if (mp != null) {
                            try {
                                if (playbackPosition > 0 && playbackPosition < mp.duration) {
                                    mp.seekTo(playbackPosition)
                                }
                                mp.start()
                            } catch (_: Exception) {
                                playRingtone(uri, playbackPosition)
                            }
                        } else {
                            playRingtone(uri, playbackPosition)
                        }
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                mediaPlayerState.value?.stop()
                mediaPlayerState.value?.release()
            } catch (e: Exception) {
                Log.e("CustomRingtonePicker", "Error releasing player on dispose", e)
            } finally {
                mediaPlayerState.value = null
            }
        }
    }

    val displayedRingtones = if (selectedTab == 0) systemRingtones else appRingtones

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Select Ringtone", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            SecureDialogWindow()
            Column(modifier = Modifier.fillMaxWidth()) {
                // Tab Selection Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { selectedTab = 0 },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selectedTab == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("System Ringtones", fontSize = 12.sp, maxLines = 1)
                    }
                    Button(
                        onClick = { selectedTab = 1 },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selectedTab == 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("App Ringtones", fontSize = 12.sp, maxLines = 1)
                    }
                }

                // Ringtone List
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                    items(displayedRingtones.size) { index ->
                        val item = displayedRingtones[index]
                        val name = item.first
                        val uri = item.second
                        val isSelected = selectedUri == uri

                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        selectedUri = uri
                                        playbackPosition = 0
                                        playRingtone(uri, 0)
                                    }
                                    .padding(vertical = 12.dp, horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = name,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            if (index < displayedRingtones.size - 1) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                    thickness = 1.dp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    mediaPlayerState.value?.stop()
                    mediaPlayerState.value?.release()
                    mediaPlayerState.value = null
                    selectedUri?.let { onRingtoneSelected(it) }
                    onDismiss()
                },
                enabled = selectedUri != null
            ) {
                Text("Select")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    mediaPlayerState.value?.stop()
                    mediaPlayerState.value?.release()
                    mediaPlayerState.value = null
                    onDismiss()
                }
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun QueueMissionsDialog(
    onDismiss: () -> Unit,
    onEnqueue: (String) -> Unit
) {
    val missionIds = remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Queue Missions", color = MaterialTheme.colorScheme.onSurface)
        },
        text = {
            SecureDialogWindow()
            Column {
                Text(
                    text = "Enter mission IDs (comma-separated):",
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = missionIds.value,
                    onValueChange = { missionIds.value = it },
                    label = { Text("Mission IDs", color = MaterialTheme.colorScheme.onSurface) },
                    placeholder = { Text("mission1,mission2,mission3", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Example: math_challenge,meditation_timer,exercise_routine",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (missionIds.value.isNotBlank()) {
                        onEnqueue(missionIds.value)
                        onDismiss()
                    }
                },
                enabled = missionIds.value.isNotBlank()
            ) {
                Text("Queue Missions", color = MaterialTheme.colorScheme.onSurface)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
            }
        }
    )
}

@Composable
fun WakeCheckTimingDialog(
    wakeCheckMinutesState: MutableState<Int>,
    onDismiss: () -> Unit
) {
    val options = listOf(1, 3, 5, 7, 10)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("⏰ Wake-Up Check Timing", color = MaterialTheme.colorScheme.onSurface)
        },
        text = {
            SecureDialogWindow()
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Check if you're awake after:",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                options.forEach { minutes ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                wakeCheckMinutesState.value = minutes
                                onDismiss()
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "$minutes minute${if (minutes > 1) "s" else ""}",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (wakeCheckMinutesState.value == minutes) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = MaterialTheme.colorScheme.onSurface)
            }
        }
    )
}

/**
 * Export ringtones to MediaStore for cross-app access.
 * This is called during app startup to ensure ringtones are available to other apps.
 */
private fun MainActivity.exportRingtonesToMediaStore() {
    val exportPrefs = getSharedPreferences("ringtone_export_prefs", Context.MODE_PRIVATE)
    if (exportPrefs.getBoolean("ringtones_fully_exported", false)) {
        Log.d("MainActivity", "Ringtone export already completed previously — skipping")
        return
    }

    val exporter = MediaStoreExporter(this)
    val mainActivityContext = this // Store context for use in coroutine

    // Run export in background thread
    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val result = exporter.exportRingtonesToMediaStore()

            runOnUiThread {
                if (result.success) {
                    Log.d("MainActivity", "✅ MediaStore export completed: ${result.exportedCount} exported, ${result.skippedCount} skipped")

                    if (result.exportedCount > 0) {
                        Toast.makeText(
                            mainActivityContext,
                            "Ringtones exported: ${result.exportedCount} new, ${result.skippedCount} already exist",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    exportPrefs.edit().putBoolean("ringtones_fully_exported", true).apply()
                } else {
                    Log.e("MainActivity", "❌ MediaStore export failed: ${result.error}")
                    Toast.makeText(
                        mainActivityContext,
                        "Ringtone export failed: ${result.error}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error during MediaStore export", e)
            runOnUiThread {
                Toast.makeText(
                    mainActivityContext,
                    "Error exporting ringtones: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

/**
 * True if [alarm] should be locked from last-minute delete / toggle-off right now.
 *
 * Locks only alarms that were set well in advance: the alarm must be enabled, its next ring must
 * be within [windowMinutes] minutes, AND the gap from when it was created to that ring must exceed
 * [windowMinutes]. So a short-horizon alarm (e.g. a 1-minute test alarm, or "wake me in 5 min")
 * is never locked and stays freely deletable, while an alarm committed to well ahead of time locks
 * as it approaches. Fails open (returns false) on any error, so a failure never blocks the user.
 */
private fun isAlarmWithinLockWindow(
    context: Context,
    alarm: Alarm,
    windowMinutes: Int = 10
): Boolean {
    if (!alarm.isEnabled) return false
    return runCatching {
        val now = System.currentTimeMillis()
        val windowMs = windowMinutes * 60_000L
        val next = AndroidAlarmScheduler(context).nextTriggerMillis(alarm, now)
        val untilRing = next - now
        val setMoreThanWindowInAdvance = (next - alarm.createdTime) > windowMs
        untilRing in 0..windowMs && setMoreThanWindowInAdvance
    }.getOrDefault(false)
}
