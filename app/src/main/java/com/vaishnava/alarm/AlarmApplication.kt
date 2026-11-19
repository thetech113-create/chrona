package com.vaishnava.alarm

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.vaishnava.alarm.data.AlarmRepository
import com.vaishnava.alarm.sequencer.MissionQueueStore
import java.io.File

class AlarmApplication : Application() {
    lateinit var repository: AlarmRepository
        private set

    companion object {
        private const val TAG = "AlarmApp"
        private const val CURRENT_STATE_SCHEMA_VERSION = 2
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyWindowSecurity(activity)
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                applyWindowSecurity(activity)
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}

            private fun applyWindowSecurity(activity: Activity) {
                try {
                    // Skipped in debug builds so screenshots/screen recording work for
                    // debugging; release builds keep FLAG_SECURE enforced everywhere.
                    if (BuildConfig.DEBUG) {
                        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else if (activity !is AlarmActivity) {
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply FLAG_SECURE on ${activity.javaClass.simpleName}: ${e.message}")
                }
            }
        })
        repository = AlarmRepository(this)
        enforceStateSchemaIntegrity(this)
        setForceRestartFlag()
        createDummyIntegrityCheckFile()
    }

    private fun enforceStateSchemaIntegrity(context: Context) {
        val appContext = context.applicationContext
        val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appContext.createDeviceProtectedStorageContext()
        } else {
            appContext
        }
        try {
            val alarmDpsPrefs = dpsContext.getSharedPreferences("alarm_dps", MODE_PRIVATE)
            val diskSchemaVersion = alarmDpsPrefs.getInt("app_state_schema_version", 0)
            if (diskSchemaVersion >= CURRENT_STATE_SCHEMA_VERSION) return

            val volatileAlarmKeys = alarmDpsPrefs.all.keys.filter { key ->
                key != "app_state_schema_version" &&
                    (key.startsWith("sequencer_") || key.startsWith("sequence_") || key.startsWith("wakecheck_"))
            }
            val dpsEditor = alarmDpsPrefs.edit()
            volatileAlarmKeys.forEach(dpsEditor::remove)
            val alarmDpsCommitted = dpsEditor
                .putInt("app_state_schema_version", CURRENT_STATE_SCHEMA_VERSION)
                .commit()

            val completionSyncCleared = clearPrefsMatching(dpsContext, "dps_completion_sync") { key ->
                key.startsWith("alarm_completed_in_dps_")
            }
            val pointerCleared = clearPrefsMatching(dpsContext, "mission_sequencer_pointer") { key ->
                key.startsWith("active_mission_") || key.startsWith("queue_ids_") || key.startsWith("pointer_timestamp_")
            }
            val directBootRuntimeCleared = clearPrefsMatching(dpsContext, "direct_boot_alarm_prefs") { key ->
                key == "current_alarm_id" || key == "was_ringing" || key.startsWith("wakecheck_")
            }
            val pendingClearedApp = clearPrefsMatching(appContext, "pending_completion_store") { true }
            val pendingClearedDps = if (dpsContext !== appContext) {
                clearPrefsMatching(dpsContext, "pending_completion_store") { true }
            } else 0

            val queueFilesPurged = MissionQueueStore.purgeLegacyStateFiles(appContext)
            val snapshotFilesPurged = purgeLegacySequencerSnapshots(appContext, dpsContext)
            val legacyPrefKeysPurged = purgeLegacyAlarmPrefs(appContext, dpsContext)
            val legacyFilesPurged = purgeLegacyAlarmFiles(appContext, dpsContext)

            Log.d("STATE_INTEGRITY", "Schema purge complete: alarmDpsCommitted=$alarmDpsCommitted " +
                "alarmKeys=${volatileAlarmKeys.size} completionSyncKeys=$completionSyncCleared " +
                "pointerKeys=$pointerCleared directBootRuntimeKeys=$directBootRuntimeCleared " +
                "pendingKeys=${pendingClearedApp + pendingClearedDps} " +
                "queueFilesPurged=$queueFilesPurged snapshotFilesPurged=$snapshotFilesPurged " +
                "legacyPrefKeysPurged=$legacyPrefKeysPurged legacyFilesPurged=$legacyFilesPurged")
        } catch (e: Exception) {
            Log.e("STATE_INTEGRITY", "Failed to enforce schema: ${e.message}", e)
        }
    }

    private fun clearPrefsMatching(context: Context, prefsName: String, predicate: (String) -> Boolean): Int {
        val prefs = context.getSharedPreferences(prefsName, MODE_PRIVATE)
        val keysToRemove = prefs.all.keys.filter(predicate)
        if (keysToRemove.isEmpty()) return 0
        val editor = prefs.edit()
        keysToRemove.forEach(editor::remove)
        editor.apply()
        return keysToRemove.size
    }

    private fun purgeLegacySequencerSnapshots(vararg contexts: Context): Int {
        val candidateDirs = linkedSetOf<String>()
        contexts.forEach { ctx -> runCatching { candidateDirs.add(ctx.filesDir.absolutePath) } }
        var deletedCount = 0
        candidateDirs.forEach { dirPath ->
            val dir = File(dirPath)
            val files = dir.listFiles() ?: return@forEach
            files.filter { file ->
                file.name.startsWith("dps_sequence_") &&
                    (file.name.endsWith(".json") || file.name.endsWith(".json.tmp"))
            }.forEach { file ->
                if (file.delete()) deletedCount++
            }
        }
        return deletedCount
    }

    private fun purgeLegacyAlarmPrefs(vararg contexts: Context): Int {
        var deletedCount = 0
        val uniqueContexts = contexts.toSet()
        uniqueContexts.forEach { ctx ->
            deletedCount += clearPrefsMatching(ctx, "direct_boot_alarms") { true }
            deletedCount += clearPrefsMatching(ctx, "alarm_data_cache") { true }
        }
        return deletedCount
    }

    private fun purgeLegacyAlarmFiles(vararg contexts: Context): Int {
        val candidateDirs = linkedSetOf<String>()
        contexts.forEach { ctx -> runCatching { candidateDirs.add(ctx.filesDir.absolutePath) } }
        var deletedCount = 0
        val legacyNames = setOf("active_alarms.txt", "direct_boot_alarms.json")
        candidateDirs.forEach { dirPath ->
            val dir = File(dirPath)
            val files = dir.listFiles() ?: return@forEach
            files.filter { it.name in legacyNames }.forEach { file ->
                if (file.delete()) deletedCount++
            }
        }
        return deletedCount
    }

    private fun setForceRestartFlag() {
        try {
            getSharedPreferences("alarm_system", MODE_PRIVATE).edit().putBoolean("is_force_restart", true).apply()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                createDeviceProtectedStorageContext().getSharedPreferences("alarm_system",
                    MODE_PRIVATE
                ).edit().putBoolean("is_force_restart", true).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set force restart flag: ${e.message}")
        }
    }

    private fun createDummyIntegrityCheckFile() {
        try {
            val dpsContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) createDeviceProtectedStorageContext() else this
            val file = dpsContext.getFileStreamPath("dummy_integrity_check.txt")
            if (!file.exists()) {
                dpsContext.openFileOutput("dummy_integrity_check.txt", MODE_PRIVATE).use {
                    it.write("integrity_check_data".toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create dummy_integrity_check.txt: ${e.message}", e)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        try {
            getSharedPreferences("alarm_system", MODE_PRIVATE).edit().putBoolean("is_force_restart", false).apply()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                createDeviceProtectedStorageContext().getSharedPreferences("alarm_system",
                    MODE_PRIVATE
                ).edit().putBoolean("is_force_restart", false).apply()
            }
        } catch (_: Exception) {}
    }
}
