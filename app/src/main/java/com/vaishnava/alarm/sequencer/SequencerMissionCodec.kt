package com.vaishnava.alarm.sequencer

object SequencerMissionCodec {
    fun parseMissionSequence(serialized: String?, alarmId: Int? = null): List<MissionSpec> {
        if (serialized.isNullOrBlank()) return emptyList()

        return serialized
            .split("+")
            .mapNotNull { parseMissionToken(it, alarmId) }
    }

    fun parseMissionToken(token: String, alarmId: Int? = null): MissionSpec? {
        val normalized = token.trim()
        val lower = normalized.lowercase()

        return when (lower) {
            "tap" -> baseMissionSpec("tap", 105000L, alarmId)
            "pwd", "password", "pswd" -> baseMissionSpec("password", 30000L, alarmId)
            "stt" -> baseMissionSpec(SttMissionSupport.MISSION_ID, 120000L, alarmId)
            "face" -> baseMissionSpec(FaceMissionSupport.MISSION_ID, 120000L, alarmId)
            "math" -> MathMissionSupport.createMissionSpec(alarmId)
            else -> MathMissionSupport.parseSequenceToken(normalized, alarmId)
        }
    }

    fun serializeMissionSequence(missions: List<MissionSpec>): String {
        return missions.joinToString("+") { serializeMissionToken(it) }
    }

    fun serializeMissionToken(mission: MissionSpec): String {
        return when (mission.id) {
            "tap" -> "Tap"
            "password" -> "Pwd"
            SttMissionSupport.MISSION_ID -> "Stt"
            FaceMissionSupport.MISSION_ID -> "Face"
            MathMissionSupport.MISSION_ID -> {
                val config = MathMissionSupport.readConfig(mission) ?: MathMissionConfig()
                MathMissionSupport.toSequenceToken(config)
            }
            else -> mission.id
        }
    }

    fun describeMissionSequence(serialized: String?): String? {
        val missions = parseMissionSequence(serialized)
        if (missions.isEmpty()) return null
        return missions.joinToString(" + ") { displayName(it) }
    }

    fun firstMissionId(serialized: String?): String? {
        return parseMissionSequence(serialized).firstOrNull()?.id
    }

    fun isSequencerConfig(serialized: String?): Boolean {
        return parseMissionSequence(serialized).isNotEmpty()
    }

    fun displayName(mission: MissionSpec): String {
        return when (mission.id) {
            "tap" -> "Tap"
            "password" -> "Password"
            SttMissionSupport.MISSION_ID -> "Speech"
            FaceMissionSupport.MISSION_ID -> "Face"
            MathMissionSupport.MISSION_ID -> "Math"
            else -> mission.id.replaceFirstChar { it.uppercase() }
        }
    }

    private fun baseMissionSpec(missionId: String, timeoutMs: Long, alarmId: Int?): MissionSpec {
        val params = mutableMapOf("mission_type" to missionId)
        if (alarmId != null && alarmId != -1) {
            params["alarm_id"] = alarmId.toString()
        }
        return MissionSpec(
            id = missionId,
            params = params,
            timeoutMs = timeoutMs,
            retryCount = 3,
            sticky = true,
            retryDelayMs = 1000L
        )
    }
}
