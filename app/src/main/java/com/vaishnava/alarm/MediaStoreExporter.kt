package com.vaishnava.alarm

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Publishes bundled alarm sounds into shared MediaStore storage so they can be reused
 * through content URIs instead of direct file paths.
 */
class MediaStoreExporter(private val context: Context) {

    companion object {
        private const val TAG = "MediaStoreExporter"
        private const val RINGTONE_ARTIST = "Alarm121"
        // FIX: Removed the "/chrona/" subfolder to prevent EACCES (Permission Denied) on Xiaomi/Redmi devices
        private val ALARM_RINGTONE_PATH = Environment.DIRECTORY_RINGTONES

        private val RINGTONE_EXPORTS = listOf(
            RingtoneExport("silence_no_sound", "Silence No Sound", "audio/wav"),
            RingtoneExport("glassy_bell", "Glossy Bell", "audio/wav"),
            RingtoneExport("pyaro_vrindavan", "Pyaro Vrindavan", "audio/wav"),
            RingtoneExport("agam_kaalbhairav_ashtakam", "Kaalbhairav Ashtakam", "audio/wav"),
            RingtoneExport("alaipayuthe", "Alaipayuthe", "audio/wav"),
            RingtoneExport("gentle_ocean_and_birdsong_24068", "Gentle Ocean & Birdsong", "audio/wav"),
            RingtoneExport("harekrishna_chant", "Hare Krishna Chant", "audio/wav"),
            RingtoneExport("krishna_flute", "Krishna Flute", "audio/wav"),
            RingtoneExport("radha_krishna_flute", "Radha Krishna Flute", "audio/wav")
        )
    }

    data class RingtoneExport(
        val resName: String,
        val displayName: String,
        val mimeType: String
    )

    suspend fun exportRingtonesToMediaStore(): ExportResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting MediaStore export for ${RINGTONE_EXPORTS.size} ringtones")

            val exportedUris = mutableListOf<Uri>()
            val skipped = mutableListOf<String>()

            for (ringtone in RINGTONE_EXPORTS) {
                val existingUri = findExistingRingtone(ringtone.displayName)
                if (existingUri != null) {
                    skipped.add(ringtone.displayName)
                    continue
                }

                val uri = exportRingtone(ringtone)
                if (uri != null) {
                    exportedUris.add(uri)
                    Log.d(TAG, "Exported ringtone ${ringtone.displayName} -> $uri")
                } else {
                    Log.w(TAG, "Failed to export ringtone ${ringtone.displayName}")
                }
            }

            ExportResult(
                success = true,
                exportedCount = exportedUris.size,
                skippedCount = skipped.size,
                exportedUris = exportedUris,
                error = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore export failed", e)
            ExportResult(
                success = false,
                exportedCount = 0,
                skippedCount = 0,
                exportedUris = emptyList(),
                error = e.message
            )
        }
    }

    private fun findExistingRingtone(displayName: String): Uri? {
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        // FIX: The Android MediaScanner often removes the ARTIST tag if it isn't hardcoded into the audio file metadata.
        // We now search exclusively by TITLE or DISPLAY_NAME to prevent duplicates from being created.
        val selection = "${MediaStore.Audio.Media.TITLE} = ? OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf(displayName, "$displayName%")

        return try {
            context.contentResolver.query(
                audioCollectionUri(),
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                    Uri.withAppendedPath(audioCollectionUri(), id.toString())
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking existing ringtone: $displayName", e)
            null
        }
    }

    private fun exportRingtone(ringtone: RingtoneExport): Uri? {
        val resourceId = context.resources.getIdentifier(ringtone.resName, "raw", context.packageName)
        if (resourceId == 0) {
            Log.e(TAG, "Resource not found: ${ringtone.resName}")
            return null
        }

        var createdUri: Uri? = null
        try {
            val bytesCopied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                createdUri = createScopedAudioEntry(ringtone)
                context.contentResolver.openOutputStream(createdUri, "w")?.use { output ->
                    context.resources.openRawResource(resourceId).use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IOException("Failed to open output stream for $createdUri")
            } else {
                createdUri = createLegacyAudioEntry(ringtone, resourceId)
                context.contentResolver.openFileDescriptor(createdUri, "w")?.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { output ->
                        context.resources.openRawResource(resourceId).use { input ->
                            input.copyTo(output)
                        }
                    }
                } ?: throw IOException("Failed to open file descriptor for $createdUri")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.update(
                    createdUri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
            }

            Log.d(TAG, "Exported alarm ringtone ${ringtone.displayName} ($bytesCopied bytes)")
            return createdUri
        } catch (e: Exception) {
            createdUri?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
            Log.e(TAG, "Error exporting ringtone: ${ringtone.displayName}", e)
            return null
        }
    }

    private fun createScopedAudioEntry(ringtone: RingtoneExport): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "${ringtone.displayName}.wav")
            put(MediaStore.Audio.Media.TITLE, ringtone.displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, ringtone.mimeType)
            put(MediaStore.Audio.Media.IS_ALARM, 1)
            put(MediaStore.Audio.Media.IS_RINGTONE, 0)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, 0)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
            put(MediaStore.Audio.Media.ARTIST, RINGTONE_ARTIST)
            put(MediaStore.MediaColumns.RELATIVE_PATH, ALARM_RINGTONE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        return context.contentResolver.insert(audioCollectionUri(), values)
            ?: throw IOException("Failed to create MediaStore entry for ${ringtone.displayName}")
    }

    private fun createLegacyAudioEntry(ringtone: RingtoneExport, resourceId: Int): Uri {
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES)
        // FIX: Remove chrona subfolder to avoid EACCES permission denied
        if (!publicDir.exists()) {
            publicDir.mkdirs()
        }

        val targetFile = File(publicDir, "${ringtone.displayName}.wav")
        val sizeBytes = FileOutputStream(targetFile).use { output ->
            context.resources.openRawResource(resourceId).use { input ->
                input.copyTo(output).also { output.flush() }
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "${ringtone.displayName}.wav")
            put(MediaStore.Audio.Media.TITLE, ringtone.displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, ringtone.mimeType)
            put(MediaStore.Audio.Media.DATA, targetFile.absolutePath)
            put(MediaStore.Audio.Media.IS_ALARM, 1)
            put(MediaStore.Audio.Media.IS_RINGTONE, 0)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, 0)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
            put(MediaStore.Audio.Media.ARTIST, RINGTONE_ARTIST)
            put(MediaStore.Audio.Media.SIZE, sizeBytes)
        }

        val uri = context.contentResolver.insert(audioCollectionUri(), values)
            ?: throw IOException("Failed to create legacy MediaStore row for ${ringtone.displayName}")

        runCatching {
            val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = Uri.fromFile(targetFile)
            }
            context.sendBroadcast(scanIntent)
        }

        return uri
    }

    suspend fun getPublishedRingtoneUris(): List<Uri> = withContext(Dispatchers.IO) {
        val uris = mutableListOf<Uri>()
        // FIX: Instead of relying on the ARTIST tag (which gets wiped by Android), 
        // we manually fetch the files by their exact titles to ensure they load properly in the UI list.
        for (ringtone in RINGTONE_EXPORTS) {
            findExistingRingtone(ringtone.displayName)?.let { uris.add(it) }
        }
        uris
    }

    suspend fun cleanupOldRingtones(): Int = withContext(Dispatchers.IO) {
        // Disabled cleanup relying on ARTIST tag to prevent accidental deletion
        0   
    }

    private fun audioCollectionUri(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
    }

    data class ExportResult(
        val success: Boolean,
        val exportedCount: Int,
        val skippedCount: Int,
        val exportedUris: List<Uri>,
        val error: String?
    )
}
