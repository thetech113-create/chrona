package com.vaishnava.alarm.sequencer

import kotlin.random.Random

enum class MathDifficulty(val wireValue: String, val displayName: String) {
    NORMAL("normal", "Normal"),
    EASY("easy", "Easy"),
    HARD("hard", "Hard"),
    EXTREME_HARD("extreme_hard", "Extreme Hard");

    companion object {
        fun fromWireValue(rawValue: String?): MathDifficulty {
            val normalized = rawValue
                ?.trim()
                ?.lowercase()
                ?.replace(' ', '_')
                ?.replace('-', '_')
                ?: return NORMAL

            return values().firstOrNull { it.wireValue == normalized } ?: NORMAL
        }
    }
}

data class MathMissionConfig(
    val difficulty: MathDifficulty = MathDifficulty.NORMAL,
    val questionTimeLimitSec: Int = 15,
    val requiredCorrectAnswers: Int = 3
)

data class MathQuestion(
    val id: String,
    val expressionText: String,
    val correctAnswer: Int,
    val leftOperand: Int,
    val rightOperand: Int,
    val operatorSymbol: String
)

data class MathMissionRuntimeState(
    val solvedCount: Int,
    val currentQuestion: MathQuestion,
    val questionStartedAtElapsedMs: Long,
    val questionDeadlineElapsedMs: Long
)

data class MathMissionAnswerResult(
    val updatedMission: MissionSpec,
    val wasCorrect: Boolean,
    val missionCompleted: Boolean,
    val questionChanged: Boolean
)

object MathQuestionGenerator {
    fun generate(config: MathMissionConfig, random: Random = Random(System.nanoTime().toInt())): MathQuestion {
        return when (config.difficulty) {
            MathDifficulty.EASY -> buildEasyQuestion(random)
            MathDifficulty.NORMAL -> buildNormalQuestion(random)
            MathDifficulty.HARD -> buildHardQuestion(random)
            MathDifficulty.EXTREME_HARD -> buildExtremeQuestion(random)
        }
    }

    private fun buildEasyQuestion(random: Random): MathQuestion {
        val useAddition = random.nextBoolean()
        val left = random.nextInt(1, 11)
        val right = random.nextInt(1, 11)
        return if (useAddition) {
            question(left, right, "+", left + right, random)
        } else {
            val top = maxOf(left, right)
            val bottom = minOf(left, right)
            question(top, bottom, "-", top - bottom, random)
        }
    }

    private fun buildNormalQuestion(random: Random): MathQuestion {
        return when (random.nextInt(3)) {
            0 -> {
                val left = random.nextInt(5, 26)
                val right = random.nextInt(5, 26)
                question(left, right, "+", left + right, random)
            }
            1 -> {
                val left = random.nextInt(10, 31)
                val right = random.nextInt(1, left + 1)
                question(left, right, "-", left - right, random)
            }
            else -> {
                val left = random.nextInt(2, 11)
                val right = random.nextInt(2, 11)
                question(left, right, "x", left * right, random)
            }
        }
    }

    private fun buildHardQuestion(random: Random): MathQuestion {
        return when (random.nextInt(4)) {
            0 -> {
                val left = random.nextInt(20, 81)
                val right = random.nextInt(15, 61)
                question(left, right, "+", left + right, random)
            }
            1 -> {
                val left = random.nextInt(25, 101)
                val right = random.nextInt(5, left + 1)
                question(left, right, "-", left - right, random)
            }
            2 -> {
                val left = random.nextInt(4, 16)
                val right = random.nextInt(4, 16)
                question(left, right, "x", left * right, random)
            }
            else -> {
                val divisor = random.nextInt(2, 13)
                val quotient = random.nextInt(2, 13)
                question(divisor * quotient, divisor, "÷", quotient, random)
            }
        }
    }

    private fun buildExtremeQuestion(random: Random): MathQuestion {
        return when (random.nextInt(4)) {
            0 -> {
                val left = random.nextInt(75, 201)
                val right = random.nextInt(50, 151)
                question(left, right, "+", left + right, random)
            }
            1 -> {
                val left = random.nextInt(80, 251)
                val right = random.nextInt(25, left + 1)
                question(left, right, "-", left - right, random)
            }
            2 -> {
                val left = random.nextInt(8, 26)
                val right = random.nextInt(6, 19)
                question(left, right, "x", left * right, random)
            }
            else -> {
                val divisor = random.nextInt(3, 16)
                val quotient = random.nextInt(6, 26)
                question(divisor * quotient, divisor, "÷", quotient, random)
            }
        }
    }

    private fun question(
        left: Int,
        right: Int,
        operatorSymbol: String,
        answer: Int,
        random: Random
    ): MathQuestion {
        val suffix = random.nextInt(100000, 999999)
        return MathQuestion(
            id = "${operatorSymbol}_${left}_${right}_$suffix",
            expressionText = "$left $operatorSymbol $right",
            correctAnswer = answer,
            leftOperand = left,
            rightOperand = right,
            operatorSymbol = operatorSymbol
        )
    }
}

object MathMissionSupport {
    const val MISSION_ID = "math"

    const val KEY_DIFFICULTY = "math_difficulty"
    const val KEY_QUESTION_TIME_LIMIT_SEC = "math_question_time_limit_sec"
    const val KEY_REQUIRED_CORRECT_ANSWERS = "math_required_correct_answers"
    const val KEY_SOLVED_COUNT = "math_solved_count"
    const val KEY_QUESTION_ID = "math_question_id"
    const val KEY_QUESTION_EXPRESSION = "math_question_expression"
    const val KEY_QUESTION_ANSWER = "math_question_answer"
    const val KEY_QUESTION_LEFT = "math_question_left"
    const val KEY_QUESTION_RIGHT = "math_question_right"
    const val KEY_QUESTION_OPERATOR = "math_question_operator"
    const val KEY_QUESTION_STARTED_AT_ELAPSED_MS = "math_question_started_at_elapsed_ms"
    const val KEY_QUESTION_DEADLINE_ELAPSED_MS = "math_question_deadline_elapsed_ms"

    private val runtimeKeys = setOf(
        KEY_SOLVED_COUNT,
        KEY_QUESTION_ID,
        KEY_QUESTION_EXPRESSION,
        KEY_QUESTION_ANSWER,
        KEY_QUESTION_LEFT,
        KEY_QUESTION_RIGHT,
        KEY_QUESTION_OPERATOR,
        KEY_QUESTION_STARTED_AT_ELAPSED_MS,
        KEY_QUESTION_DEADLINE_ELAPSED_MS
    )

    fun isMathMission(mission: MissionSpec?): Boolean = mission?.id == MISSION_ID

    fun createMissionSpec(alarmId: Int? = null, config: MathMissionConfig = MathMissionConfig()): MissionSpec {
        val normalized = normalizeConfig(config)
        val params = mutableMapOf(
            "mission_type" to MISSION_ID,
            KEY_DIFFICULTY to normalized.difficulty.wireValue,
            KEY_QUESTION_TIME_LIMIT_SEC to normalized.questionTimeLimitSec.toString(),
            KEY_REQUIRED_CORRECT_ANSWERS to normalized.requiredCorrectAnswers.toString()
        )
        if (alarmId != null && alarmId != -1) {
            params["alarm_id"] = alarmId.toString()
        }
        return MissionSpec(
            id = MISSION_ID,
            params = params,
            timeoutMs = normalized.questionTimeLimitSec * 1000L,
            retryCount = 3,
            sticky = true,
            retryDelayMs = 1000L
        )
    }

    fun normalizeConfig(config: MathMissionConfig): MathMissionConfig {
        return config.copy(
            questionTimeLimitSec = config.questionTimeLimitSec.coerceIn(5, 300),
            requiredCorrectAnswers = config.requiredCorrectAnswers.coerceIn(1, 99)
        )
    }

    fun readConfig(mission: MissionSpec?): MathMissionConfig? {
        if (!isMathMission(mission)) return null
        return readConfig(mission?.params.orEmpty())
    }

    fun readConfig(params: Map<String, String>): MathMissionConfig {
        return normalizeConfig(
            MathMissionConfig(
                difficulty = MathDifficulty.fromWireValue(params[KEY_DIFFICULTY]),
                questionTimeLimitSec = params[KEY_QUESTION_TIME_LIMIT_SEC]?.toIntOrNull() ?: 15,
                requiredCorrectAnswers = params[KEY_REQUIRED_CORRECT_ANSWERS]?.toIntOrNull() ?: 3
            )
        )
    }

    fun readRuntimeState(mission: MissionSpec?): MathMissionRuntimeState? = readRuntimeState(mission?.params.orEmpty())

    fun readRuntimeState(params: Map<String, String>): MathMissionRuntimeState? {
        val questionId = params[KEY_QUESTION_ID] ?: return null
        val expression = params[KEY_QUESTION_EXPRESSION] ?: return null
        val answer = params[KEY_QUESTION_ANSWER]?.toIntOrNull() ?: return null
        val left = params[KEY_QUESTION_LEFT]?.toIntOrNull() ?: return null
        val right = params[KEY_QUESTION_RIGHT]?.toIntOrNull() ?: return null
        val operator = params[KEY_QUESTION_OPERATOR] ?: return null
        val startedAt = params[KEY_QUESTION_STARTED_AT_ELAPSED_MS]?.toLongOrNull() ?: return null
        val deadline = params[KEY_QUESTION_DEADLINE_ELAPSED_MS]?.toLongOrNull() ?: return null

        return MathMissionRuntimeState(
            solvedCount = params[KEY_SOLVED_COUNT]?.toIntOrNull() ?: 0,
            currentQuestion = MathQuestion(
                id = questionId,
                expressionText = expression,
                correctAnswer = answer,
                leftOperand = left,
                rightOperand = right,
                operatorSymbol = operator
            ),
            questionStartedAtElapsedMs = startedAt,
            questionDeadlineElapsedMs = deadline
        )
    }

    fun ensureActiveQuestion(
        mission: MissionSpec,
        nowElapsedMs: Long,
        questionFactory: (MathMissionConfig) -> MathQuestion = { config ->
            MathQuestionGenerator.generate(config)
        }
    ): MissionSpec {
        if (!isMathMission(mission)) return mission

        val config = readConfig(mission.params)
        val runtime = readRuntimeState(mission)
        if (runtime == null) {
            return startQuestion(mission, config, 0, nowElapsedMs, questionFactory)
        }

        if (runtime.solvedCount >= config.requiredCorrectAnswers) {
            return withRuntimeState(mission, config, runtime)
        }

        return if (runtime.questionDeadlineElapsedMs > nowElapsedMs) {
            withRuntimeState(mission, config, runtime)
        } else {
            startQuestion(mission, config, runtime.solvedCount, nowElapsedMs, questionFactory)
        }
    }

    fun rotateQuestion(
        mission: MissionSpec,
        nowElapsedMs: Long,
        questionFactory: (MathMissionConfig) -> MathQuestion = { config ->
            MathQuestionGenerator.generate(config)
        }
    ): MissionSpec {
        if (!isMathMission(mission)) return mission
        val config = readConfig(mission.params)
        val solvedCount = readRuntimeState(mission)?.solvedCount ?: 0
        return if (solvedCount >= config.requiredCorrectAnswers) {
            mission
        } else {
            startQuestion(mission, config, solvedCount, nowElapsedMs, questionFactory)
        }
    }

    fun submitAnswer(
        mission: MissionSpec,
        answerText: String,
        nowElapsedMs: Long,
        questionFactory: (MathMissionConfig) -> MathQuestion = { config ->
            MathQuestionGenerator.generate(config)
        }
    ): MathMissionAnswerResult {
        if (!isMathMission(mission)) {
            return MathMissionAnswerResult(mission, false, false, false)
        }

        val ensuredMission = ensureActiveQuestion(mission, nowElapsedMs, questionFactory)
        val config = readConfig(ensuredMission.params)
        val runtime = readRuntimeState(ensuredMission)
            ?: return MathMissionAnswerResult(ensuredMission, false, false, false)

        if (runtime.solvedCount >= config.requiredCorrectAnswers) {
            return MathMissionAnswerResult(ensuredMission, true, true, false)
        }

        if (runtime.questionDeadlineElapsedMs <= nowElapsedMs) {
            val rotated = startQuestion(ensuredMission, config, runtime.solvedCount, nowElapsedMs, questionFactory)
            return MathMissionAnswerResult(rotated, false, false, true)
        }

        val submittedAnswer = answerText.trim().toIntOrNull()
        if (submittedAnswer == null || submittedAnswer != runtime.currentQuestion.correctAnswer) {
            return MathMissionAnswerResult(ensuredMission, false, false, false)
        }

        val solvedCount = runtime.solvedCount + 1
        if (solvedCount >= config.requiredCorrectAnswers) {
            val completedMission = withRuntimeState(
                ensuredMission,
                config,
                runtime.copy(solvedCount = solvedCount)
            )
            return MathMissionAnswerResult(completedMission, true, true, false)
        }

        val advancedMission = startQuestion(ensuredMission, config, solvedCount, nowElapsedMs, questionFactory)
        return MathMissionAnswerResult(advancedMission, true, false, true)
    }

    fun toSequenceToken(config: MathMissionConfig): String {
        val normalized = normalizeConfig(config)
        return "Math(${normalized.difficulty.wireValue},${normalized.questionTimeLimitSec},${normalized.requiredCorrectAnswers})"
    }

    fun parseSequenceToken(token: String, alarmId: Int? = null): MissionSpec? {
        val match = Regex(
            pattern = """^math\(([^,]+),(\d+),(\d+)\)$""",
            option = RegexOption.IGNORE_CASE
        ).matchEntire(token.trim()) ?: return null

        val difficulty = MathDifficulty.fromWireValue(match.groupValues[1])
        val timeLimitSec = match.groupValues[2].toIntOrNull() ?: return null
        val requiredCorrectAnswers = match.groupValues[3].toIntOrNull() ?: return null

        return createMissionSpec(
            alarmId = alarmId,
            config = MathMissionConfig(
                difficulty = difficulty,
                questionTimeLimitSec = timeLimitSec,
                requiredCorrectAnswers = requiredCorrectAnswers
            )
        )
    }

    private fun startQuestion(
        mission: MissionSpec,
        config: MathMissionConfig,
        solvedCount: Int,
        nowElapsedMs: Long,
        questionFactory: (MathMissionConfig) -> MathQuestion
    ): MissionSpec {
        val question = questionFactory(config)
        val runtime = MathMissionRuntimeState(
            solvedCount = solvedCount,
            currentQuestion = question,
            questionStartedAtElapsedMs = nowElapsedMs,
            questionDeadlineElapsedMs = nowElapsedMs + (config.questionTimeLimitSec * 1000L)
        )
        return withRuntimeState(mission, config, runtime)
    }

    private fun withRuntimeState(
        mission: MissionSpec,
        config: MathMissionConfig,
        runtime: MathMissionRuntimeState
    ): MissionSpec {
        val params = mission.params
            .toMutableMap()
            .filterKeys { it !in runtimeKeys }
            .toMutableMap()

        params["mission_type"] = MISSION_ID
        if (!params.containsKey("alarm_id")) {
            mission.params["alarm_id"]?.let { params["alarm_id"] = it }
        }
        params[KEY_DIFFICULTY] = config.difficulty.wireValue
        params[KEY_QUESTION_TIME_LIMIT_SEC] = config.questionTimeLimitSec.toString()
        params[KEY_REQUIRED_CORRECT_ANSWERS] = config.requiredCorrectAnswers.toString()
        params[KEY_SOLVED_COUNT] = runtime.solvedCount.toString()
        params[KEY_QUESTION_ID] = runtime.currentQuestion.id
        params[KEY_QUESTION_EXPRESSION] = runtime.currentQuestion.expressionText
        params[KEY_QUESTION_ANSWER] = runtime.currentQuestion.correctAnswer.toString()
        params[KEY_QUESTION_LEFT] = runtime.currentQuestion.leftOperand.toString()
        params[KEY_QUESTION_RIGHT] = runtime.currentQuestion.rightOperand.toString()
        params[KEY_QUESTION_OPERATOR] = runtime.currentQuestion.operatorSymbol
        params[KEY_QUESTION_STARTED_AT_ELAPSED_MS] = runtime.questionStartedAtElapsedMs.toString()
        params[KEY_QUESTION_DEADLINE_ELAPSED_MS] = runtime.questionDeadlineElapsedMs.toString()

        return mission.copy(
            id = MISSION_ID,
            params = params,
            timeoutMs = config.questionTimeLimitSec * 1000L
        )
    }
}
