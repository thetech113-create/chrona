@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.vaishnava.alarm

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ExperimentalMaterial3Api
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.tasks.Tasks
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.FileList
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.vaishnava.alarm.data.Alarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class CloudBackupResult(
    val success: Boolean,
    val count: Int = 0,
    val message: String
)

data class CloudRestoreResult(
    val success: Boolean,
    val count: Int = 0,
    val message: String,
    val status: CloudRestoreStatus = if (success) CloudRestoreStatus.SUCCESS else CloudRestoreStatus.OTHER_ERROR
)

enum class CloudRestoreStatus {
    SUCCESS,
    SIGN_IN_REQUIRED,
    NO_BACKUP_FOUND,
    LOCAL_DATA_PRESENT,
    CLEAR_DATA_NO_CACHE,
    OFFLINE_NO_CACHE,
    BACKEND_NOT_READY,
    PERMISSION_DENIED,
    OTHER_ERROR
}

class CloudAlarmStorage(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val backupPrefs = appContext.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
    private val gson = GsonBuilder()
        .registerTypeAdapter(Uri::class.java, UriAdapter())
        .create()

    private fun buildDriveService(): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(appContext) ?: run {
            Log.w(TAG, "No signed-in Google account for Drive")
            return null
        }
        val email = account.email
        if (email.isNullOrBlank()) {
            Log.w(TAG, "Signed-in account has no email for Drive")
            return null
        }
        val credential = GoogleAccountCredential.usingOAuth2(
            appContext,
            listOf(
                DriveScopes.DRIVE_FILE,
                DriveScopes.DRIVE_APPDATA
            )
        )
        credential.selectedAccountName = email
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("Chrona Alarm")
            .build()
    }

    private fun getOrCreateDriveFolder(drive: Drive): String? {
        val folderName = DEDICATED_FOLDER_NAME
        return try {
            val q = "mimeType = 'application/vnd.google-apps.folder' and name = '${folderName.replace("'", "\\'")}' and trashed = false"
            val result = drive.files().list()
                .setQ(q)
                .setSpaces("drive")
                .setFields("files(id, name)")
                .execute()
            val existing = result.files?.firstOrNull()
            if (existing != null) {
                return existing.id
            }

            val folderMetadata = File().apply {
                name = folderName
                mimeType = "application/vnd.google-apps.folder"
            }
            val createdFolder = drive.files().create(folderMetadata)
                .setFields("id")
                .execute()
            Log.d(TAG, "Created dedicated Google Drive folder: ${createdFolder.id}")
            createdFolder.id
        } catch (e: Exception) {
            Log.w(TAG, "Failed creating or finding dedicated Drive folder: ${e.message}")
            null
        }
    }

    private fun findBackupFileInDrive(drive: Drive): File? {
        // 1. Check dedicated "Chrona Alarm Backups" folder
        try {
            val folderId = getOrCreateDriveFolder(drive)
            if (folderId != null) {
                val q = "name = '$APPDATA_FILENAME' and '$folderId' in parents and trashed = false"
                val result = drive.files().list()
                    .setQ(q)
                    .setSpaces("drive")
                    .setFields("files(id, name)")
                    .execute()
                val file = result.files?.firstOrNull()
                if (file != null) return file
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed searching in dedicated folder: ${e.message}")
            if (e.message?.contains("403") == true || e.message?.contains("401") == true) {
                return null // User denied permission; fail fast
            }
        }

        // 2. Fallback to appDataFolder if present
        return findAppDataFile(drive, APPDATA_FILENAME)
    }

    private fun findAppDataFile(drive: Drive, name: String): File? {
        return try {
            val q = "name = '${name.replace("'", "\\'")}' and 'appDataFolder' in parents and trashed = false"
            val result: FileList = drive.files().list()
                .setSpaces("appDataFolder")
                .setQ(q)
                .setFields("files(id,name)")
                .execute()
            result.files?.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed finding appData file in Google Drive: ${e.message}")
            null
        }
    }

    fun triggerAsyncSync() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                withTimeoutOrNull(7000) {
                    val driveRes = restoreAlarmsFromDrive()
                    if (!driveRes.success) {
                        restoreAlarmsFromFirebase()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Async sync error: ${e.message}")
            }
        }
    }

    suspend fun backupAlarmsToDrive(): CloudBackupResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val drive = buildDriveService()
                ?: return@withContext CloudBackupResult(
                    success = false,
                    message = "Google Drive account not connected"
                )
            val alarms = AlarmStorage(appContext).getAlarms()
            val json = gson.toJson(alarms)

            val existingFile = findBackupFileInDrive(drive)
            val mediaContent = ByteArrayContent.fromString("application/json", json)

            if (existingFile != null) {
                drive.files().update(existingFile.id, null, mediaContent).execute()
                Log.d(TAG, "Updated Google Drive backup file: ${existingFile.id}")
            } else {
                val folderId = getOrCreateDriveFolder(drive)
                val metadata = File().apply {
                    name = APPDATA_FILENAME
                    if (folderId != null) {
                        parents = listOf(folderId)
                    } else {
                        parents = listOf("appDataFolder")
                    }
                    mimeType = "application/json"
                }
                drive.files().create(metadata, mediaContent).setFields("id, name").execute()
                Log.d(TAG, "Created new Google Drive backup in folder: $folderId")
            }
            cacheBackupLocally(json)
            CloudBackupResult(
                success = true,
                count = alarms.size,
                message = "Backed up ${alarms.size} alarms to Google Drive"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Drive backup failed", e)
            CloudBackupResult(
                success = false,
                message = "Drive backup error: ${e.localizedMessage ?: "Unknown error"}"
            )
        }
    }

    suspend fun restoreAlarmsFromDrive(): CloudRestoreResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val drive = buildDriveService()
                ?: return@withContext CloudRestoreResult(
                    success = false,
                    message = "Sign in to Google Drive first",
                    status = CloudRestoreStatus.SIGN_IN_REQUIRED
                )
            val file = findBackupFileInDrive(drive)
                ?: return@withContext CloudRestoreResult(
                    success = false,
                    message = "No backup found in Google Drive",
                    status = CloudRestoreStatus.NO_BACKUP_FOUND
                )

            val text = drive.files().get(file.id).executeMediaAsInputStream().use { input ->
                input.bufferedReader().use { reader -> reader.readText() }
            }

            if (text.isBlank()) {
                return@withContext CloudRestoreResult(
                    success = false,
                    message = "Drive backup file is empty",
                    status = CloudRestoreStatus.NO_BACKUP_FOUND
                )
            }

            val listType = object : TypeToken<List<Alarm>>() {}.type
            val restoredAlarms: List<Alarm> = gson.fromJson(text, listType) ?: emptyList()

            if (restoredAlarms.isEmpty()) {
                return@withContext CloudRestoreResult(
                    success = false,
                    message = "No alarms found in Google Drive backup",
                    status = CloudRestoreStatus.NO_BACKUP_FOUND
                )
            }

            val alarmStorage = AlarmStorage(appContext)
            val scheduler = AndroidAlarmScheduler(appContext)
            val currentAlarms = alarmStorage.getAlarms()

            val finalAlarms = mergeAlarms(currentAlarms, restoredAlarms)
            alarmStorage.saveAlarms(finalAlarms, synchronous = true)

            finalAlarms
                .filter { it.isEnabled }
                .forEach { alarm ->
                    runCatching { scheduler.schedule(alarm) }
                }

            cacheBackupLocally(text)
            notifyRestore(finalAlarms.size)

            CloudRestoreResult(
                success = true,
                count = finalAlarms.size,
                message = "Restored ${finalAlarms.size} alarms from Google Drive",
                status = CloudRestoreStatus.SUCCESS
            )
        } catch (e: Exception) {
            Log.e(TAG, "Drive restore failed", e)
            CloudRestoreResult(
                success = false,
                message = "Drive restore error: ${e.localizedMessage ?: "Unknown error"}",
                status = CloudRestoreStatus.OTHER_ERROR
            )
        }
    }

    suspend fun backupAlarmsToFirebase(): CloudBackupResult = withContext(Dispatchers.IO) {
        runCatching { backupAlarmsToDrive() }

        val user = auth.currentUser
            ?: return@withContext CloudBackupResult(
                success = false,
                message = "Sign in first to back up alarms"
            )

        return@withContext try {
            val alarms = AlarmStorage(appContext).getAlarms()
            val alarmsJson = gson.toJson(alarms)
            val payload = hashMapOf(
                "alarms_json" to alarmsJson,
                "last_backup_time" to System.currentTimeMillis()
            )

            Tasks.await(
                db.collection("users")
                    .document(user.uid)
                    .collection("backups")
                    .document("alarm_data")
                    .set(payload)
            )
            cacheBackupLocally(alarmsJson)

            Log.d(TAG, "Backed up ${alarms.size} alarms for uid=${user.uid}")
            CloudBackupResult(
                success = true,
                count = alarms.size,
                message = "Backed up ${alarms.size} alarms"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            CloudBackupResult(
                success = false,
                message = cloudErrorMessage(e, operation = "backup")
            )
        }
    }

    suspend fun restoreAlarmsFromFirebase(): CloudRestoreResult = withContext(Dispatchers.IO) {
        val driveResult = runCatching { restoreAlarmsFromDrive() }.getOrNull()
        if (driveResult?.success == true) {
            return@withContext driveResult
        }

        val user = auth.currentUser
            ?: return@withContext CloudRestoreResult(
                success = false,
                message = "Sign in first to restore alarms",
                status = CloudRestoreStatus.SIGN_IN_REQUIRED
            )

        return@withContext try {
            val networkAvailable = isNetworkAvailable()
            val localCacheExists = !readLocalBackupJson().isNullOrBlank()
            Log.d(
                TAG,
                "RESTORE_START uid=${user.uid} email=${user.email} network=$networkAvailable localCacheExists=$localCacheExists"
            )
            val backupPayload = loadBackupPayload(user.uid)
            if (backupPayload == null) {
                val result = when {
                    !localCacheExists && !networkAvailable -> CloudRestoreResult(
                        success = false,
                        message = "After Clear data, this device has no cached backup. Sign in and go online once to restore from Firebase.",
                        status = CloudRestoreStatus.CLEAR_DATA_NO_CACHE
                    )
                    !networkAvailable -> CloudRestoreResult(
                        success = false,
                        message = "Device is offline and no cached backup is available on this device yet.",
                        status = CloudRestoreStatus.OFFLINE_NO_CACHE
                    )
                    else -> CloudRestoreResult(
                        success = false,
                        message = "No cloud backup found for this account",
                        status = CloudRestoreStatus.NO_BACKUP_FOUND
                    )
                }
                Log.d(
                    TAG,
                    "RESTORE_EMPTY uid=${user.uid} email=${user.email} status=${result.status} network=$networkAvailable localCacheExists=$localCacheExists"
                )
                return@withContext result
            }

            Log.d(
                TAG,
                "RESTORE_PAYLOAD uid=${user.uid} email=${user.email} source=${backupPayload.source}"
            )
            val alarmsJson = backupPayload.alarmsJson
            val listType = object : TypeToken<List<Alarm>>() {}.type
            val restoredAlarms: List<Alarm> = gson.fromJson(alarmsJson, listType) ?: emptyList()

            val alarmStorage = AlarmStorage(appContext)
            val scheduler = AndroidAlarmScheduler(appContext)
            val currentAlarms = alarmStorage.getAlarms()

            val finalAlarms = mergeAlarms(currentAlarms, restoredAlarms)
            alarmStorage.saveAlarms(finalAlarms, synchronous = true)

            finalAlarms
                .filter { it.isEnabled }
                .forEach { alarm ->
                    runCatching { scheduler.schedule(alarm) }
                        .onFailure { Log.w(TAG, "Failed scheduling restored alarm ${alarm.id}", it) }
                }

            cacheBackupLocally(alarmsJson)
            notifyRestore(finalAlarms.size)
            Log.d(TAG, "Restored ${finalAlarms.size} alarms for uid=${user.uid} source=${backupPayload.source}")
            CloudRestoreResult(
                success = true,
                count = finalAlarms.size,
                message = "Restored ${finalAlarms.size} alarms",
                status = CloudRestoreStatus.SUCCESS
            )
        } catch (e: Exception) {
            val message = cloudErrorMessage(e, operation = "restore")
            val status = cloudErrorStatus(e)
            Log.e(
                TAG,
                "RESTORE_FAILED uid=${user.uid} email=${user.email} status=$status message=$message",
                e
            )
            CloudRestoreResult(
                success = false,
                message = message,
                status = status
            )
        }
    }

    private fun mergeAlarms(localAlarms: List<Alarm>, cloudAlarms: List<Alarm>): List<Alarm> {
        if (cloudAlarms.isEmpty()) return localAlarms
        if (localAlarms.isEmpty()) return cloudAlarms

        val mergedMap = LinkedHashMap<Int, Alarm>()
        cloudAlarms.forEach { alarm ->
            mergedMap[alarm.id] = alarm
        }
        localAlarms.forEach { alarm ->
            if (!mergedMap.containsKey(alarm.id)) {
                mergedMap[alarm.id] = alarm
            }
        }
        return mergedMap.values.toList()
    }

    private fun loadBackupPayload(uid: String): BackupPayload? {
        val docRef = db.collection("users")
            .document(uid)
            .collection("backups")
            .document("alarm_data")
        Log.d(TAG, "RESTORE_QUERY path=users/$uid/backups/alarm_data")

        val serverSnapshot = try {
            Tasks.await(docRef.get())
        } catch (e: Exception) {
            val firestoreError = e as? FirebaseFirestoreException
            if (
                firestoreError?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ||
                firestoreError?.code == FirebaseFirestoreException.Code.NOT_FOUND
            ) {
                throw e
            }
            null
        }
        serverSnapshot
            ?.getString("alarms_json")
            ?.takeIf { it.isNotBlank() }
            ?.let { alarmsJson ->
                val source = if (serverSnapshot.metadata.isFromCache) "cache" else "server"
                val lastBackupTime = serverSnapshot.getLong("last_backup_time")
                return BackupPayload(alarmsJson, source, lastBackupTime)
            }

        runCatching {
            Tasks.await(docRef.get(Source.CACHE))
        }.getOrNull()
            ?.getString("alarms_json")
            ?.takeIf { it.isNotBlank() }
            ?.let { cacheJson ->
                val lastBackupTime = backupPrefs.getLong(KEY_LAST_BACKUP_TIME, 0L)
                return BackupPayload(cacheJson, "cache", lastBackupTime)
            }

        readLocalBackupJson()
            ?.takeIf { it.isNotBlank() }
            ?.let { 
                val lastBackupTime = backupPrefs.getLong(KEY_LAST_BACKUP_TIME, 0L)
                return BackupPayload(it, "local cache", lastBackupTime)
            }

        return null
    }

    private fun cacheBackupLocally(alarmsJson: String) {
        backupPrefs.edit()
            .putString(KEY_ALARMS_JSON, alarmsJson)
            .putLong(KEY_LAST_BACKUP_TIME, System.currentTimeMillis())
            .commit()
    }

    private fun readLocalBackupJson(): String? {
        return backupPrefs.getString(KEY_ALARMS_JSON, null)
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            false
        }
    }

    private fun cloudErrorMessage(error: Exception, operation: String): String {
        val firestoreError = error as? FirebaseFirestoreException
        return when (firestoreError?.code) {
            FirebaseFirestoreException.Code.NOT_FOUND -> {
                "Cloud Firestore database is not created for this Firebase project yet. Create Firestore in Firebase Console, then retry $operation."
            }
            FirebaseFirestoreException.Code.PERMISSION_DENIED -> {
                "Firebase blocked the $operation. Update Firestore rules to allow this signed-in user to access users/{uid}/backups."
            }
            FirebaseFirestoreException.Code.UNAVAILABLE -> {
                "Device is offline. Connect to the internet to $operation the cloud backup, or retry after this device has a cached copy."
            }
            else -> error.localizedMessage ?: "${operation.replaceFirstChar { it.uppercase() }} failed"
        }
    }

    private fun cloudErrorStatus(error: Exception): CloudRestoreStatus {
        val firestoreError = error as? FirebaseFirestoreException
        return when (firestoreError?.code) {
            FirebaseFirestoreException.Code.NOT_FOUND -> CloudRestoreStatus.BACKEND_NOT_READY
            FirebaseFirestoreException.Code.PERMISSION_DENIED -> CloudRestoreStatus.PERMISSION_DENIED
            else -> CloudRestoreStatus.OTHER_ERROR
            }
    }

    private fun notifyRestore(restoredCount: Int) {
        val refreshIntent = Intent("com.vaishnava.alarm.REFRESH_ALARMS").apply {
            setPackage(appContext.packageName)
            putExtra("alarms_restored", true)
            putExtra("alarms_count", restoredCount)
            putExtra("restored_count", restoredCount)
        }
        appContext.sendBroadcast(refreshIntent)
    }

    companion object {
        private const val TAG = "CloudAlarmStorage"
        private const val BACKUP_PREFS = "cloud_alarm_backup"
        private const val KEY_ALARMS_JSON = "alarms_json"
        private const val KEY_LAST_BACKUP_TIME = "last_backup_time"
        private const val DEDICATED_FOLDER_NAME = "Chrona Alarm Backups"
        private const val APPDATA_FILENAME = "alarms.json"
    }
}

private data class BackupPayload(
    val alarmsJson: String,
    val source: String,
    val lastBackupTime: Long? = null
)
