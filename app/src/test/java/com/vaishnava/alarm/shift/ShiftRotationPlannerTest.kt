package com.vaishnava.alarm.shift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random

class ShiftRotationPlannerTest {
    private val tz: TimeZone = TimeZone.getTimeZone("UTC")

    @Test
    fun normalRotationCyclesAcrossMultipleWeeksWithoutDrift() {
        val anchor = utc(2025, 9, 1, 0, 0)
        val config = testConfig(anchor, anchorRotationIndex = 0)
        val indices = (0..5).map { week ->
            ShiftRotationPlanner.rotationIndexForWeek(config, anchor + week * WEEK)
        }
        assertEquals(listOf(0, 1, 2, 0, 1, 2), indices)
    }

    @Test
    fun midweekSetupCountsCurrentWeekAndNextMondayAdvances() {
        val friday = utc(2025, 9, 5, 10, 0)
        val anchor = ShiftRotationPlanner.mondayWeekStartMillis(friday, tz)
        val config = testConfig(anchor, anchorRotationIndex = 1)

        assertEquals(1, ShiftRotationPlanner.rotationIndexForWeek(config, anchor))
        assertEquals(2, ShiftRotationPlanner.rotationIndexForWeek(config, anchor + WEEK))
    }

    @Test
    fun perRotationDaysSkipAlarmsWithoutChangingSequence() {
        val anchor = utc(2025, 9, 1, 0, 0)
        val config = testConfig(anchor, anchorRotationIndex = 0)
        val rotationThreeWeek = anchor + 2 * WEEK
        val planned = ShiftRotationPlanner.plannedAlarmsForWeek(
            config = config,
            weekStartMillis = rotationThreeWeek,
            nowMillis = rotationThreeWeek - 1,
            timeZone = tz
        )

        assertEquals(2, ShiftRotationPlanner.rotationIndexForWeek(config, rotationThreeWeek))
        assertEquals(5, planned.size)
        assertTrue(planned.none { it.dayRule.dayOfWeek == Calendar.SATURDAY })
        assertEquals(0, ShiftRotationPlanner.rotationIndexForWeek(config, rotationThreeWeek + WEEK))
    }

    @Test
    fun crossMidnightShiftBelongsToConfiguredStartDay() {
        val anchor = utc(2025, 9, 1, 0, 0)
        val config = ShiftRotationConfig(
            enabled = true,
            rotations = listOf(
                ShiftRotationDefinition(
                    "Night",
                    listOf(
                        ShiftDayRule(
                            dayOfWeek = Calendar.TUESDAY,
                            startHour = 22,
                            startMinute = 0,
                            endHour = 6,
                            endMinute = 0
                        )
                    )
                )
            ),
            alarmPattern = ShiftAlarmPattern(offsetMinutesBeforeShift = 120),
            anchorWeekStartMillis = anchor,
            anchorRotationIndex = 0
        )

        val planned = ShiftRotationPlanner.plannedAlarmsForWeek(config, anchor, anchor - 1, tz)
        assertEquals(1, planned.size)
        assertEquals(utc(2025, 9, 2, 22, 0), planned.single().shiftStartAtMillis)
        assertEquals(utc(2025, 9, 2, 20, 0), planned.single().alarmAtMillis)
    }

    @Test
    fun transitionUsesFirstConfiguredAlarmNotMondayMidnight() {
        val anchor = utc(2025, 9, 1, 0, 0)
        val config = ShiftRotationConfig(
            enabled = true,
            rotations = listOf(
                ShiftRotationDefinition(
                    "Early",
                    listOf(ShiftDayRule(Calendar.MONDAY, 6, 0, 14, 0))
                )
            ),
            alarmPattern = ShiftAlarmPattern(offsetMinutesBeforeShift = 120),
            anchorWeekStartMillis = anchor,
            anchorRotationIndex = 0,
            transitionLeadMinutes = 60
        )

        val transitionAt = ShiftRotationPlanner.transitionAtForWeek(config, anchor + WEEK, 0, tz)
        assertEquals(utc(2025, 9, 8, 3, 0), transitionAt)
        assertNotEquals(utc(2025, 9, 8, 0, 0), transitionAt)
    }

    @Test
    fun pendingReassignmentDoesNotChangeCurrentWeekUntilEffectiveTransition() {
        val anchor = utc(2025, 9, 1, 0, 0)
        val friday = utc(2025, 9, 5, 12, 0)
        val config = testConfig(anchor, anchorRotationIndex = 0)
        val pending = ShiftRotationPlanner.createPendingReassignment(
            config = config,
            requestedRotationIndex = 2,
            nowMillis = friday,
            timeZone = tz,
            random = Random(3)
        )
        val withPending = config.copy(pendingReassignment = pending)

        assertEquals(0, ShiftRotationPlanner.rotationIndexForWeek(withPending, anchor))
        assertEquals(0, ShiftRotationPlanner.applyPendingReassignmentIfDue(withPending, pending.effectiveAtMillis - 1).anchorRotationIndex)
        val applied = ShiftRotationPlanner.applyPendingReassignmentIfDue(withPending, pending.effectiveAtMillis)
        assertEquals(anchor + WEEK, applied.anchorWeekStartMillis)
        assertEquals(2, applied.anchorRotationIndex)
        assertEquals(null, applied.pendingReassignment)
    }

    @Test
    fun dailyReminderAvoidsRepeatingSameMinuteAndNeverSchedulesPast() {
        val anchor = utc(2025, 9, 1, 0, 0)
        val config = testConfig(anchor, anchorRotationIndex = 0)
            .copy(notificationWindowStartMinuteOfDay = 9 * 60, notificationWindowEndMinuteOfDay = 10 * 60)
        val now = utc(2025, 9, 5, 9, 30)
        val first = ShiftRotationPlanner.nextRandomReminderAt(config, now, -1, tz, Random(1))
        val second = ShiftRotationPlanner.nextRandomReminderAt(config, first.first, first.second, tz, Random(1))

        assertTrue(first.first > now)
        assertTrue(second.first > first.first)
        assertNotEquals(first.second, second.second)
    }

    @Test
    fun reminderWindowMustAllowMoreThanOneRandomMinute() {
        val anchor = utc(2025, 9, 1, 0, 0)
        val config = testConfig(anchor, anchorRotationIndex = 0)
            .copy(notificationWindowStartMinuteOfDay = 9 * 60, notificationWindowEndMinuteOfDay = 9 * 60 + 1)

        assertTrue(ShiftRotationPlanner.validate(config)
            .any { it.contains("at least two minutes") })
    }

    @Test
    fun pendingReassignmentTokenChangesForSeparateRequests() {
        val anchor = utc(2025, 9, 1, 0, 0)
        val config = testConfig(anchor, anchorRotationIndex = 0)
        val now = utc(2025, 9, 5, 12, 0)
        val first = ShiftRotationPlanner.createPendingReassignment(
            config, requestedRotationIndex = 1, nowMillis = now, timeZone = tz, random = Random(1)
        )
        val second = ShiftRotationPlanner.createPendingReassignment(
            config, requestedRotationIndex = 1, nowMillis = now, timeZone = tz, random = Random(2)
        )

        assertNotEquals(first.token, second.token)
    }

    private fun testConfig(anchor: Long, anchorRotationIndex: Int): ShiftRotationConfig {
        fun rule(day: Int, enabled: Boolean = true) = ShiftDayRule(day, 6, 0, 14, 0, enabled = enabled)
        return ShiftRotationConfig(
            enabled = true,
            rotations = listOf(
                ShiftRotationDefinition("Shift 1", listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY).map(::rule)),
                ShiftRotationDefinition("Shift 2", listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY).map(::rule)),
                ShiftRotationDefinition("Shift 3", listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY).map(::rule))
            ),
            alarmPattern = ShiftAlarmPattern(offsetMinutesBeforeShift = 120),
            anchorWeekStartMillis = anchor,
            anchorRotationIndex = anchorRotationIndex,
            transitionLeadMinutes = 60
        )
    }

    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return Calendar.getInstance(tz).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private companion object {
        const val WEEK = 7L * 24L * 60L * 60L * 1000L
    }
}
