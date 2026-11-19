package com.vaishnava.alarm.shift

data class ShiftRotationConfig(
    val enabled: Boolean = false,
    val rotations: List<ShiftRotationDefinition> = emptyList(),
    val alarmPattern: ShiftAlarmPattern = ShiftAlarmPattern(),
    val anchorWeekStartMillis: Long = 0L,
    val anchorRotationIndex: Int = 0,
    val transitionLeadMinutes: Int = 60,
    val notificationWindowStartMinuteOfDay: Int = 9 * 60,
    val notificationWindowEndMinuteOfDay: Int = 20 * 60,
    val generationHorizonWeeks: Int = 1,
    val pendingReassignment: PendingShiftReassignment? = null,
    val runtime: ShiftRotationRuntimeState = ShiftRotationRuntimeState()
)

data class ShiftRotationDefinition(
    val name: String,
    val dayRules: List<ShiftDayRule>
)

data class ShiftDayRule(
    val dayOfWeek: Int,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val enabled: Boolean = true
)

data class ShiftAlarmPattern(
    val offsetMinutesBeforeShift: Int = 0,
    val ringtoneUri: String? = null,
    val missionType: String? = null,
    val missionPassword: String? = null,
    val wakeCheckEnabled: Boolean = false,
    val wakeCheckMinutes: Int = 5,
    val protectedAlarm: Boolean = false
)

data class PendingShiftReassignment(
    val token: Long,
    val requestedRotationIndex: Int,
    val originalAnchorWeekStartMillis: Long,
    val originalAnchorRotationIndex: Int,
    val requestedAtMillis: Long,
    val effectiveAtMillis: Long,
    val effectiveWeekStartMillis: Long,
    val scheduledReminderAtMillis: Long,
    val previousReminderMinuteOfDay: Int
)

data class ShiftRotationRuntimeState(
    val generatedAlarmIds: List<Int> = emptyList(),
    val generatedForWeekStartMillis: Long = 0L,
    val generatedRotationIndex: Int = -1,
    val lastUpdatedAtMillis: Long = 0L
)

data class PlannedShiftAlarm(
    val rotationIndex: Int,
    val rotationName: String,
    val dayRule: ShiftDayRule,
    val shiftStartAtMillis: Long,
    val alarmAtMillis: Long,
    val hour: Int,
    val minute: Int
)
