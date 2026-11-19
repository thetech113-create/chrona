package com.vaishnava.alarm.shift

import java.util.Calendar
import java.util.TimeZone
import kotlin.random.Random

object ShiftRotationPlanner {
    private const val MILLIS_PER_WEEK = 7L * 24L * 60L * 60L * 1000L

    fun validate(config: ShiftRotationConfig): List<String> {
        val errors = mutableListOf<String>()
        if (!config.enabled) return errors
        if (config.rotations.isEmpty()) {
            errors += "At least one rotation is required"
        }
        if (config.anchorRotationIndex !in config.rotations.indices) {
            errors += "Anchor rotation index is out of range"
        }
        if (config.alarmPattern.offsetMinutesBeforeShift < 0) {
            errors += "Alarm offset must not be negative"
        }
        if (config.transitionLeadMinutes < 0) {
            errors += "Transition lead must not be negative"
        }
        if (config.notificationWindowStartMinuteOfDay !in 0..1439 ||
            config.notificationWindowEndMinuteOfDay !in 0..1439 ||
            config.notificationWindowStartMinuteOfDay >= config.notificationWindowEndMinuteOfDay
        ) {
            errors += "Notification window must be within one day and start before end"
        } else if (config.notificationWindowEndMinuteOfDay - config.notificationWindowStartMinuteOfDay < 2) {
            errors += "Notification window must span at least two minutes for randomized reminders"
        }
        config.rotations.forEachIndexed { rotationIndex, rotation ->
            rotation.dayRules.forEach { rule ->
                if (rule.dayOfWeek !in Calendar.SUNDAY..Calendar.SATURDAY) {
                    errors += "Rotation ${rotationIndex + 1} has an invalid day"
                }
                if (rule.startHour !in 0..23 || rule.endHour !in 0..23 ||
                    rule.startMinute !in 0..59 || rule.endMinute !in 0..59
                ) {
                    errors += "Rotation ${rotationIndex + 1} has an invalid time"
                }
            }
        }
        return errors
    }

    fun mondayWeekStartMillis(
        atMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = atMillis
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val delta = Math.floorMod(cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY, 7)
        cal.add(Calendar.DAY_OF_YEAR, -delta)
        return cal.timeInMillis
    }

    fun rotationIndexForWeek(
        config: ShiftRotationConfig,
        weekStartMillis: Long
    ): Int {
        if (config.rotations.isEmpty()) return -1
        val anchor = config.anchorWeekStartMillis
        val deltaMillis = weekStartMillis - anchor
        val weeks = Math.round(deltaMillis.toDouble() / MILLIS_PER_WEEK).toInt()
        return Math.floorMod(config.anchorRotationIndex + weeks, config.rotations.size)
    }

    fun plannedAlarmsForWeek(
        config: ShiftRotationConfig,
        weekStartMillis: Long,
        nowMillis: Long = Long.MIN_VALUE,
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<PlannedShiftAlarm> {
        if (!config.enabled || config.rotations.isEmpty()) return emptyList()
        val rotationIndex = rotationIndexForWeek(config, weekStartMillis)
        if (rotationIndex !in config.rotations.indices) return emptyList()
        return plannedAlarmsForRotationWeek(
            config = config,
            rotationIndex = rotationIndex,
            weekStartMillis = weekStartMillis,
            nowMillis = nowMillis,
            timeZone = timeZone
        )
    }

    fun plannedAlarmsForRotationWeek(
        config: ShiftRotationConfig,
        rotationIndex: Int,
        weekStartMillis: Long,
        nowMillis: Long = Long.MIN_VALUE,
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<PlannedShiftAlarm> {
        if (rotationIndex !in config.rotations.indices) return emptyList()
        val rotation = config.rotations[rotationIndex]
        return rotation.dayRules
            .filter { it.enabled }
            .map { rule ->
                val shiftStart = millisForDayAndTime(
                    weekStartMillis = weekStartMillis,
                    dayOfWeek = rule.dayOfWeek,
                    hour = rule.startHour,
                    minute = rule.startMinute,
                    timeZone = timeZone
                )
                val alarmAt = shiftStart - config.alarmPattern.offsetMinutesBeforeShift * 60_000L
                val alarmCal = Calendar.getInstance(timeZone).apply { timeInMillis = alarmAt }
                PlannedShiftAlarm(
                    rotationIndex = rotationIndex,
                    rotationName = rotation.name,
                    dayRule = rule,
                    shiftStartAtMillis = shiftStart,
                    alarmAtMillis = alarmAt,
                    hour = alarmCal.get(Calendar.HOUR_OF_DAY),
                    minute = alarmCal.get(Calendar.MINUTE)
                )
            }
            .filter { it.alarmAtMillis > nowMillis }
            .sortedBy { it.alarmAtMillis }
    }

    fun nextRotationTransitionAt(
        config: ShiftRotationConfig,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Pair<Long, Long>? {
        if (!config.enabled || config.rotations.isEmpty()) return null
        var candidateWeek = mondayWeekStartMillis(nowMillis, timeZone) + MILLIS_PER_WEEK
        repeat(16) {
            val nextIndex = rotationIndexForWeek(config, candidateWeek)
            val transitionAt = transitionAtForWeek(config, candidateWeek, nextIndex, timeZone)
            if (transitionAt > nowMillis) return transitionAt to candidateWeek
            candidateWeek += MILLIS_PER_WEEK
        }
        return null
    }

    fun transitionAtForWeek(
        config: ShiftRotationConfig,
        weekStartMillis: Long,
        rotationIndex: Int,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long {
        val firstAlarm = plannedAlarmsForRotationWeek(
            config = config,
            rotationIndex = rotationIndex,
            weekStartMillis = weekStartMillis,
            nowMillis = Long.MIN_VALUE,
            timeZone = timeZone
        ).minByOrNull { it.alarmAtMillis }
        return if (firstAlarm != null) {
            firstAlarm.alarmAtMillis - config.transitionLeadMinutes * 60_000L
        } else {
            val fallback = Calendar.getInstance(timeZone).apply {
                timeInMillis = weekStartMillis
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                set(Calendar.HOUR_OF_DAY, config.notificationWindowStartMinuteOfDay / 60)
                set(Calendar.MINUTE, config.notificationWindowStartMinuteOfDay % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            fallback.timeInMillis
        }
    }

    fun createPendingReassignment(
        config: ShiftRotationConfig,
        requestedRotationIndex: Int,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
        random: Random = Random.Default
    ): PendingShiftReassignment {
        require(requestedRotationIndex in config.rotations.indices) { "requestedRotationIndex out of range" }
        val (effectiveAt, effectiveWeekStart) = nextRotationTransitionAt(config, nowMillis, timeZone)
            ?: error("Unable to calculate next rotation transition")
        val reminder = nextRandomReminderAt(
            config = config,
            afterMillis = nowMillis,
            previousMinuteOfDay = -1,
            timeZone = timeZone,
            random = random
        )
        val currentWeekStart = mondayWeekStartMillis(nowMillis, timeZone)
        return PendingShiftReassignment(
            token = (nowMillis xor requestedRotationIndex.toLong() xor effectiveAt xor random.nextLong())
                .takeIf { it != 0L } ?: 1L,
            requestedRotationIndex = requestedRotationIndex,
            originalAnchorWeekStartMillis = currentWeekStart,
            originalAnchorRotationIndex = rotationIndexForWeek(config, currentWeekStart),
            requestedAtMillis = nowMillis,
            effectiveAtMillis = effectiveAt,
            effectiveWeekStartMillis = effectiveWeekStart,
            scheduledReminderAtMillis = reminder.first,
            previousReminderMinuteOfDay = reminder.second
        )
    }

    fun applyPendingReassignmentIfDue(
        config: ShiftRotationConfig,
        nowMillis: Long
    ): ShiftRotationConfig {
        val pending = config.pendingReassignment ?: return config
        if (nowMillis < pending.effectiveAtMillis) return config
        return config.copy(
            anchorWeekStartMillis = pending.effectiveWeekStartMillis,
            anchorRotationIndex = pending.requestedRotationIndex,
            pendingReassignment = null,
            runtime = config.runtime.copy(lastUpdatedAtMillis = nowMillis)
        )
    }

    fun cancelPendingReassignment(config: ShiftRotationConfig): ShiftRotationConfig {
        return config.copy(pendingReassignment = null)
    }

    fun nextRandomReminderAt(
        config: ShiftRotationConfig,
        afterMillis: Long,
        previousMinuteOfDay: Int,
        timeZone: TimeZone = TimeZone.getDefault(),
        random: Random = Random.Default
    ): Pair<Long, Int> {
        val start = config.notificationWindowStartMinuteOfDay
        val end = config.notificationWindowEndMinuteOfDay
        val width = (end - start).coerceAtLeast(1)
        var minuteOfDay = start + random.nextInt(width)
        if (width > 1 && minuteOfDay == previousMinuteOfDay) {
            minuteOfDay = start + Math.floorMod(minuteOfDay - start + 37, width)
        }
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = afterMillis
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= afterMillis) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis to minuteOfDay
    }

    private fun millisForDayAndTime(
        weekStartMillis: Long,
        dayOfWeek: Int,
        hour: Int,
        minute: Int,
        timeZone: TimeZone
    ): Long {
        val offset = Math.floorMod(dayOfWeek - Calendar.MONDAY, 7)
        return Calendar.getInstance(timeZone).apply {
            timeInMillis = weekStartMillis
            add(Calendar.DAY_OF_YEAR, offset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
