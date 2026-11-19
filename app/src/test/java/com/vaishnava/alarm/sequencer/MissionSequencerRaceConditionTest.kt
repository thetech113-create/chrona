package com.vaishnava.alarm.sequencer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionSequencerRaceConditionTest {

    @Test
    fun missionSpec_defaultsRemainStable() {
        val mission = MissionSpec(id = "password")

        assertEquals("password", mission.id)
        assertTrue(mission.params.isEmpty())
        assertEquals(30000L, mission.timeoutMs)
        assertEquals(3, mission.retryCount)
        assertEquals(false, mission.sticky)
        assertEquals(1000L, mission.retryDelayMs)
    }

    @Test
    fun missionSpec_preservesExplicitParameters() {
        val mission = MissionSpec(
            id = "tap",
            params = mapOf("alarm_id" to "42", "mission_type" to "tap"),
            timeoutMs = 105000L,
            retryCount = 5,
            sticky = true,
            retryDelayMs = 2500L
        )

        assertEquals("tap", mission.id)
        assertEquals("42", mission.params["alarm_id"])
        assertEquals("tap", mission.params["mission_type"])
        assertEquals(105000L, mission.timeoutMs)
        assertEquals(5, mission.retryCount)
        assertEquals(true, mission.sticky)
        assertEquals(2500L, mission.retryDelayMs)
    }
}
