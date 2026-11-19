package com.vaishnava.alarm

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.FileNotFoundException

/**
 * ContentProvider for cross-app ringtone access.
 * Allows other apps (like Shorts) to access alarm ringtones exported to MediaStore.
 */
class RingtoneContentProvider : ContentProvider() {
    
    companion object {
        private const val TAG = "RingtoneContentProvider"
        private const val AUTHORITY = "com.vaishnava.alarm.ringtone"
        
        // URI matcher codes
        private const val RINGTONES = 1
        private const val RINGTONE_ID = 2
        
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "ringtones", RINGTONES)
            addURI(AUTHORITY, "ringtones/#", RINGTONE_ID)
        }
    }
    
    override fun onCreate(): Boolean {
        Log.d(TAG, "RingtoneContentProvider created")
        return true
    }
    
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        Log.d(TAG, "Query: $uri")
        
        return when (uriMatcher.match(uri)) {
            RINGTONES -> {
                // Query all alarm ringtones from MediaStore
                queryAlarmRingtones(projection, selection, selectionArgs, sortOrder)
            }
            RINGTONE_ID -> {
                // Query specific ringtone by ID
                val ringtoneId = uri.lastPathSegment
                queryAlarmRingtoneById(ringtoneId, projection)
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }
    
    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            RINGTONES -> "vnd.android.cursor.dir/vnd.vaishnava.alarm.ringtone"
            RINGTONE_ID -> "vnd.android.cursor.item/vnd.vaishnava.alarm.ringtone"
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }
    
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        // Read-only provider - no insert support
        throw UnsupportedOperationException("Insert not supported")
    }
    
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        // Read-only provider - no update support
        throw UnsupportedOperationException("Update not supported")
    }
    
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        // Read-only provider - no delete support
        throw UnsupportedOperationException("Delete not supported")
    }
    
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        Log.d(TAG, "Open file: $uri, mode: $mode")
        
        if (mode != "r") {
            throw IllegalArgumentException("Only read mode supported")
        }
        
        return when (uriMatcher.match(uri)) {
            RINGTONE_ID -> {
                val ringtoneId = uri.lastPathSegment
                openRingtoneFile(ringtoneId)
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }
    
    private fun queryAlarmRingtones(
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val context = context ?: throw IllegalStateException("Context not available")
        
        // Default projection if none provided
        val finalProjection = projection ?: arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE
        )
        
        // Query MediaStore WITHOUT sort - we'll sort in code
        val rawCursor = context.contentResolver.query(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            finalProjection,
            "${MediaStore.Audio.Media.IS_ALARM} = ?",
            arrayOf("1"),
            null // No sort - we sort after fetching
        ) ?: throw IllegalStateException("Failed to query MediaStore")

        // THE FIX: Fetch all rows, sort them, then populate MatrixCursor
        val ringtones = mutableListOf<Map<String, Any?>>()
        rawCursor.use { cursor ->
            while (cursor.moveToNext()) {
                val row = mutableMapOf<String, Any?>()
                for (i in cursor.columnNames.indices) {
                    row[cursor.columnNames[i]] = cursor.getString(i)
                }
                ringtones.add(row)
            }
        }

        // Sort alphabetically by TITLE, ignoring case and spaces
        val sortedRingtones = ringtones.sortedBy { row ->
            (row[MediaStore.Audio.Media.TITLE] as? String)?.trim()?.lowercase(java.util.Locale.getDefault()) ?: ""
        }

        // Populate MatrixCursor with sorted data
        val matrixCursor = android.database.MatrixCursor(finalProjection)
        for (row in sortedRingtones) {
            val values = finalProjection.map { column ->
                row[column]
            }.toTypedArray()
            matrixCursor.addRow(values)
        }

        return matrixCursor
    }
    
    private fun queryAlarmRingtoneById(
        ringtoneId: String?,
        projection: Array<out String>?
    ): Cursor {
        val context = context ?: throw IllegalStateException("Context not available")
        
        val finalProjection = projection ?: arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE
        )
        
        val cursor = context.contentResolver.query(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            finalProjection,
            "${MediaStore.Audio.Media._ID} = ? AND ${MediaStore.Audio.Media.IS_ALARM} = ?",
            arrayOf(ringtoneId, "1"),
            null
        )
        
        return cursor ?: throw IllegalStateException("Failed to query specific ringtone")
    }
    
    private fun openRingtoneFile(ringtoneId: String?): ParcelFileDescriptor {
        val context = context ?: throw IllegalStateException("Context not available")
        
        val ringtoneUri = Uri.withAppendedPath(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ringtoneId
        )
        
        try {
            return context.contentResolver.openFileDescriptor(ringtoneUri, "r")
                ?: throw FileNotFoundException("Cannot open ringtone file: $ringtoneUri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open ringtone file: $ringtoneUri", e)
            throw FileNotFoundException("Ringtone file not found: $ringtoneId")
        }
    }
}
