package com.vaishnava.alarm.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The log line formats are the ones Chrona really writes (MainActivity, AlarmActivity, AlarmReceiver, AlarmForegroundService). */
class MaskSecretsTest {

    private fun assertHidden(line: String, secret: String) {
        val masked = maskSecrets(line)
        assertFalse("'$secret' still visible in: $masked", masked.contains(secret))
    }

    @Test
    fun passwordValuesAreHidden() {
        assertEquals("ID=5, Type=password, Password=***", maskSecrets("ID=5, Type=password, Password=open123"))
        assertHidden("Alarm 5: DPS Type=sequencer, DPS Password=open123", "open123")
        assertHidden("INTENT_ONLY: alarmId=5 missionType=password missionPassword=open123", "open123")
        assertHidden("hasPassword=true requiredPassword=open123 alarmMissionType=password alarmMissionPassword=open123", "open123")
        assertHidden("Raw missionPassword: 'tap+math+open123'", "open123")
        assertHidden("Auto-setting missionType to 'sequencer' for updated sequence password: open123", "open123")
        assertHidden("Correcting mission type (password contains sequence: open123)", "open123")
        assertHidden("{\"missionPassword\":\"open123\",\"id\":5}", "open123")
    }

    @Test
    fun shortsAndTypedInputAreHidden() {
        assertHidden("Mission Password: open123 (from Shorts: open456)", "open123")
        assertHidden("Mission Password: open123 (from Shorts: open456)", "open456")
        assertHidden("Password UI rendering: visible=true, input='open1', timerState=RUNNING", "open1")
        assertHidden("Keystroke: 'x' length=4", "'x'")
    }

    @Test
    fun defaultPasswordIsHidden() {
        assertHidden("Using IfYouWantYouCanSleep as the global password", "IfYouWantYouCanSleep")
    }

    @Test
    fun yesNoValuesAndOtherLinesStay() {
        val kept = "MISSION_STATE_SAVED: alarmId=5 missionId=tap tapEnabled=true hasPassword=false"
        assertEquals(kept, maskSecrets(kept))
        assertEquals("requiredPassword=null", maskSecrets("requiredPassword=null"))
        val normal = "11-19 17:10:02.123  1234  1250 D AlarmForegroundService: Alarm 5 started, volume=12"
        assertEquals(normal, maskSecrets(normal))
    }
}
