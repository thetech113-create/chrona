package com.vaishnava.alarm

import java.util.Calendar

/**
 * "Silence No Sound" is only for test alarms: a one-time alarm that rings within [SILENCE_MAX_AHEAD_MS] of saving.
 * A real alarm (repeating days, dates, shift rotation, or further ahead, like 5 AM set the night before) must never be
 * saved silent by mistake. Checked only when the alarm is saved; the ringing code is unchanged.
 */
internal const val SILENCE_MAX_AHEAD_MS = 10 * 60 * 1000L
internal const val SILENCE_NOT_ALLOWED_MESSAGE =
    "Silence No Sound is only for test alarms: one-time, ringing within 10 minutes. Pick a real sound for this alarm."

/** True when [ringtoneUri] is Chrona's silent ringtone (android.resource://<pkg>/<id> or .../raw/silence_no_sound). */
internal fun isSilenceRingtone(ringtoneUri: String?, silenceResourceId: Int): Boolean {
    if (ringtoneUri == null) return false
    if (ringtoneUri.contains("silence_no_sound")) return true
    if (!ringtoneUri.startsWith("android.resource://")) return false
    return ringtoneUri.substringAfterLast('/').toIntOrNull() == silenceResourceId
}

/** False when the silent ringtone is chosen for anything other than a one-time alarm ringing within 10 minutes. */
internal fun silenceRingtoneAllowed(
    isSilence: Boolean,
    hour: Int,
    minute: Int,
    days: Set<Int>,
    dates: List<Long>,
    isShift: Boolean,
    nowMillis: Long
): Boolean {
    if (!isSilence) return true
    if (isShift || days.isNotEmpty() || dates.isNotEmpty()) return false
    val next = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= nowMillis) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
    return next - nowMillis <= SILENCE_MAX_AHEAD_MS
}
