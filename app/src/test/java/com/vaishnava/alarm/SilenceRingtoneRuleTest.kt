package com.vaishnava.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SilenceRingtoneRuleTest {
    private val silenceId = 2131755012

    /** 19 Nov 2026 at [hour]:[minute]:[second] local time. */
    private fun at(hour: Int, minute: Int, second: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 19, hour, minute, second)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun allowed(hour: Int, minute: Int, now: Long, days: Set<Int> = emptySet(), dates: List<Long> = emptyList(), shift: Boolean = false) =
        silenceRingtoneAllowed(true, hour, minute, days, dates, shift, now)

    @Test
    fun silenceIsRecognised() {
        assertTrue(isSilenceRingtone("android.resource://com.vaishnava.alarm/$silenceId", silenceId))
        assertTrue(isSilenceRingtone("android.resource://com.vaishnava.alarm/raw/silence_no_sound", silenceId))
        assertFalse(isSilenceRingtone("android.resource://com.vaishnava.alarm/2131755013", silenceId))
        assertFalse(isSilenceRingtone("content://media/internal/audio/media/42", silenceId))
        assertFalse(isSilenceRingtone(null, silenceId))
    }

    @Test
    fun oneTimeTestAlarmWithinTenMinutesIsAllowed() {
        assertTrue(allowed(17, 52, now = at(17, 50, 30)))
        assertTrue(allowed(18, 0, now = at(17, 50)))   // exactly 10 minutes
        assertTrue(allowed(0, 5, now = at(23, 58)))    // just after midnight
    }

    @Test
    fun realAlarmsCannotBeSilent() {
        assertFalse(allowed(5, 0, now = at(22, 0)))    // 5 AM set the night before
        assertFalse(allowed(18, 1, now = at(17, 50)))  // 11 minutes ahead
        assertFalse(allowed(17, 50, now = at(17, 50, 30))) // this minute has passed: rings tomorrow
        assertFalse(allowed(17, 52, now = at(17, 50), days = setOf(Calendar.MONDAY)))
        assertFalse(allowed(17, 52, now = at(17, 50), dates = listOf(at(17, 52))))
        assertFalse(allowed(17, 52, now = at(17, 50), shift = true))
    }

    @Test
    fun otherRingtonesAreNeverBlocked() {
        assertEquals(true, silenceRingtoneAllowed(false, 5, 0, setOf(1, 2, 3), emptyList(), true, at(22, 0)))
    }
}
