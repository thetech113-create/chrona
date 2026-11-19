package com.vaishnava.alarm

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

object RingtoneHelper {
    private const val TAG = "RingtoneHelper"

    // Ringtone display name mapping
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
        "ringtone_058" to "Symphony",
        "ringtone_059" to "Techno",
        "ringtone_060" to "Dynamic Weather",
        "ringtone_061" to "Rise"
    )

    // Reverse map: normalized title → display name, for matching system-returned titles
    private val displayNameMap = mapOf(
        "holiday" to "Holiday",
        "metallic" to "Metallic",
        "dynamicweather" to "Dynamic Weather",
        "rise" to "Rise",
        "alarmAlert" to "Holiday",
        "alarmalert" to "Holiday",
        "alarm" to "Holiday"
    )

    fun getDefaultAlarmUri(context: Context): Uri {
        return try {
            val alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            alarm ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        } catch (e: Exception) {
            Log.w(TAG, "getDefaultAlarmUri failed: ${e.message}")
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }
    }

    fun parseUriSafe(uriString: String?): Uri? {
        return try {
            if (uriString.isNullOrBlank()) null else Uri.parse(uriString)
        } catch (t: Throwable) {
            Log.w(TAG, "parseUriSafe: bad uri $uriString", t)
            null
        }
    }

    private fun String.titleCase(): String {
        return this.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun queryUriForTitle(context: Context, targetUri: Uri): String? {
        val projection = arrayOf(
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA
        )
        return try {
            Log.d(TAG, "[02_MEDIASTORE_QUERY] uri=$targetUri")
            context.contentResolver.query(targetUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val titleIndex = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                    val displayIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                    val idIndex = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                    val dataIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

                    val title = if (titleIndex >= 0) cursor.getString(titleIndex) else null
                    val displayName = if (displayIndex >= 0) cursor.getString(displayIndex) else null
                    val id = if (idIndex >= 0) cursor.getString(idIndex) else null
                    val data = if (dataIndex >= 0) cursor.getString(dataIndex) else null

                    Log.d(TAG, "[02_MEDIASTORE_DATA] id=$id title=$title displayName=$displayName data=$data")

                    (title ?: displayName)?.let { name ->
                        val cleanName = name.substringBeforeLast(".")
                        val result = if (cleanName.matches(Regex("^ringtone[_-].*", RegexOption.IGNORE_CASE))) {
                            // Try to find mapped name first
                            val mappedName = ringtoneNameMap[cleanName.lowercase()]
                            if (mappedName != null) {
                                mappedName
                            } else {
                                cleanName.removePrefix("ringtone").removePrefix("_").removePrefix("-")
                                    .replace("_", " ").replace("-", " ")
                                    .titleCase()
                                    .let { formatted -> "Ringtone ${formatted.trim()}" }
                            }
                        } else {
                            cleanName.replace("_", " ").titleCase()
                        }
                        Log.d(TAG, "[02_MEDIASTORE_OUTPUT] input=$name → result=$result")
                        result
                    }
                } else {
                    Log.d(TAG, "[02_MEDIASTORE_EMPTY] uri=$targetUri")
                    null
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "[02_MEDIASTORE_EXCEPTION] uri=$targetUri error=${t.message}", t)
            null
        }
    }

    private fun queryMediaStoreForRingtoneTitle(context: Context, uri: Uri): String? {
        return try {
            val uriString = uri.toString()
            Log.d(TAG, "queryMediaStore: originalUri=$uriString")

            if (uriString.contains("://")) {
                queryUriForTitle(context, uri)
            } else {
                val rawId = when {
                    uriString.matches(Regex("^[0-9]+$")) -> uriString
                    uriString.matches(Regex("^RINGTONE_\\d+$")) -> uriString.substringAfterLast("_")
                    else -> null
                }

                if (rawId != null) {
                    val internalUri = Uri.withAppendedPath(MediaStore.Audio.Media.INTERNAL_CONTENT_URI, rawId)
                    val internalTitle = queryUriForTitle(context, internalUri)
                    if (internalTitle != null) return internalTitle

                    val externalUri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, rawId)
                    val externalTitle = queryUriForTitle(context, externalUri)
                    if (externalTitle != null) return externalTitle
                }

                if (uriString.startsWith("/") || uriString.contains(".")) {
                    val fileName = uriString.substringAfterLast("/").substringBeforeLast(".")
                    if (fileName.isNotBlank()) {
                        val formatted = fileName.replace("_", " ").titleCase()
                        Log.d(TAG, "queryMediaStore: extracted title '$formatted' from file path $uriString")
                        return formatted
                    }
                }

                queryUriForTitle(context, uri)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "queryMediaStoreForRingtoneTitle failed: ${t.message}")
            null
        }
    }

    fun getRingtoneTitle(context: Context, uri: Uri?): String {
        if (uri == null) return "Alarm"

        // Strip polluted query parameters (e.g., ?title=...&canonical=1)
        val cleanUri = try {
            uri.buildUpon().clearQuery().build()
        } catch (t: Throwable) {
            uri
        }

        val uriString = cleanUri.toString()
        Log.d(TAG, "[03_HELPER_ENTRY] rawUri=$uri cleanUri=$cleanUri scheme=${cleanUri.scheme}")

        // Only replace with "Default Ringtone" if it's the ACTUAL device default
        val actualDefault = try {
            RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
        } catch (_: Exception) { null }

        if (actualDefault != null && (cleanUri == actualDefault || uriString == actualDefault.toString())) {
            Log.d(TAG, "[03_HELPER_EXIT] uri=$uriString → DEFAULT_RINGTONE")
            return "Default Ringtone"
        }

        // For other URIs, don't replace just because they contain "default"

        val result = try {
            // Special handling for bundled raw resources
            val rawResourceNames = mapOf(
                "glassy_bell" to "Glossy Bell",
                "pyaro_vrindavan" to "Pyaro Vrindavan",
                "agam_kaalbhairav_ashtakam" to "Kaalbhairav Ashtakam",
                "alaipayuthe" to "Alaipayuthe",
                "gentle_ocean_and_birdsong_24068" to "Gentle Ocean & Birdsong",
                "harekrishna_chant" to "Hare Krishna Chant",
                "krishna_flute" to "Krishna Flute",
                "radha_krishna_flute" to "Radha Krishna Flute",
                "silence_no_sound" to "Silence No Sound"
            )

            val packageName = context.packageName
            val matchedRawName = rawResourceNames.entries.firstOrNull { (key, _) ->
                val resId = context.resources.getIdentifier(key, "raw", packageName)
                val rawUri = if (resId != 0) {
                    Uri.parse("android.resource://$packageName/$resId")
                } else {
                    Uri.parse("android.resource://$packageName/raw/$key")
                }
                cleanUri == rawUri || uriString.contains(key)
            }?.value

            if (matchedRawName != null) {
                matchedRawName.trim()
            } else {
                // Fast, non-blocking MediaStore query (avoids RingtoneManager/MediaPlayer instantiation ANR)
                val mediaStoreTitle = queryUriForTitle(context, cleanUri)
                if (!mediaStoreTitle.isNullOrBlank()) {
                    mediaStoreTitle.trim()
                } else {
                    val fallback = queryMediaStoreForRingtoneTitle(context, cleanUri)
                    if (!fallback.isNullOrBlank()) {
                        fallback.trim()
                    } else {
                        cleanUri.path?.substringAfterLast("/")?.substringBeforeLast(".")?.replace("_", " ")?.titleCase()?.trim() ?: "Alarm"
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "[03_HELPER_EXCEPTION] uri=$uriString error=${t.message}", t)
            queryMediaStoreForRingtoneTitle(context, cleanUri)?.trim() ?: "Alarm"
        }

        // CRITICAL FIX: Map system-returned generic names to actual ringtone names
        val normalizedResult = result.lowercase(java.util.Locale.getDefault()).replace(" ", "")
        val mappedResult = displayNameMap[normalizedResult]
        if (mappedResult != null) {
            Log.d(TAG, "[03_SYSTEM_NAME_MAPPED] '$result' → '$mappedResult'")
            return mappedResult
        }

        Log.d(TAG, "[03_FINAL_RESULT] uri=$uriString → result=$result")
        return result
    }
}
