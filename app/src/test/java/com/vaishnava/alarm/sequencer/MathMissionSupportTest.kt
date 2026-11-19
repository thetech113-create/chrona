package com.vaishnava.alarm.sequencer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathMissionSupportTest {

    @Test
    fun ensureActiveQuestion_keepsExistingQuestion_untilDeadlineExpires() {
        val config = MathMissionConfig(
            difficulty = MathDifficulty.HARD,
            questionTimeLimitSec = 12,
            requiredCorrectAnswers = 2
        )
        val mission = MathMissionSupport.createMissionSpec(config = config)
        val initialized = MathMissionSupport.ensureActiveQuestion(mission, nowElapsedMs = 1_000L) {
            question("q1", "8 x 7", 56)
        }

        val restored = MathMissionSupport.ensureActiveQuestion(initialized, nowElapsedMs = 5_000L) {
            question("q2", "9 x 9", 81)
        }

        assertEquals("q1", MathMissionSupport.readRuntimeState(restored)?.currentQuestion?.id)
        assertEquals(0, MathMissionSupport.readRuntimeState(restored)?.solvedCount)
    }

    @Test
    fun ensureActiveQuestion_rotatesExpiredQuestion_andPreservesSolvedCount() {
        val config = MathMissionConfig(
            difficulty = MathDifficulty.NORMAL,
            questionTimeLimitSec = 10,
            requiredCorrectAnswers = 3
        )
        val mission = MathMissionSupport.createMissionSpec(config = config)
        val initialized = MathMissionSupport.ensureActiveQuestion(mission, nowElapsedMs = 0L) {
            question("q1", "3 + 5", 8)
        }
        val answered = MathMissionSupport.submitAnswer(
            mission = initialized,
            answerText = "8",
            nowElapsedMs = 2_000L
        ) {
            question("q2", "7 + 4", 11)
        }.updatedMission

        val rotated = MathMissionSupport.ensureActiveQuestion(answered, nowElapsedMs = 13_500L) {
            question("q3", "9 + 6", 15)
        }

        val runtime = MathMissionSupport.readRuntimeState(rotated)
        assertEquals("q3", runtime?.currentQuestion?.id)
        assertEquals(1, runtime?.solvedCount)
    }

    @Test
    fun submitAnswer_wrongAnswer_keepsCurrentQuestion() {
        val mission = MathMissionSupport.ensureActiveQuestion(
            mission = MathMissionSupport.createMissionSpec(),
            nowElapsedMs = 500L
        ) {
            question("q1", "10 - 3", 7)
        }

        val result = MathMissionSupport.submitAnswer(
            mission = mission,
            answerText = "6",
            nowElapsedMs = 1_000L
        ) {
            question("q2", "5 + 5", 10)
        }

        assertFalse(result.wasCorrect)
        assertFalse(result.missionCompleted)
        assertFalse(result.questionChanged)
        assertEquals("q1", MathMissionSupport.readRuntimeState(result.updatedMission)?.currentQuestion?.id)
        assertEquals(0, MathMissionSupport.readRuntimeState(result.updatedMission)?.solvedCount)
    }

    @Test
    fun submitAnswer_finalCorrect_completesMissionThroughSharedState() {
        val mission = MathMissionSupport.ensureActiveQuestion(
            mission = MathMissionSupport.createMissionSpec(
                config = MathMissionConfig(
                    difficulty = MathDifficulty.EASY,
                    questionTimeLimitSec = 15,
                    requiredCorrectAnswers = 1
                )
            ),
            nowElapsedMs = 100L
        ) {
            question("q1", "4 + 4", 8)
        }

        val result = MathMissionSupport.submitAnswer(
            mission = mission,
            answerText = "8",
            nowElapsedMs = 1_000L
        ) {
            question("q2", "6 + 6", 12)
        }

        assertTrue(result.wasCorrect)
        assertTrue(result.missionCompleted)
        assertFalse(result.questionChanged)
        assertEquals(1, MathMissionSupport.readRuntimeState(result.updatedMission)?.solvedCount)
        assertEquals("q1", MathMissionSupport.readRuntimeState(result.updatedMission)?.currentQuestion?.id)
    }

    @Test
    fun sequenceCodec_roundTripsMathConfiguration() {
        val serialized = SequencerMissionCodec.serializeMissionSequence(
            listOf(
                MathMissionSupport.createMissionSpec(
                    config = MathMissionConfig(
                        difficulty = MathDifficulty.EXTREME_HARD,
                        questionTimeLimitSec = 25,
                        requiredCorrectAnswers = 6
                    )
                ),
                SequencerMissionCodec.parseMissionToken("tap")!!
            )
        )

        val parsed = SequencerMissionCodec.parseMissionSequence(serialized)

        assertEquals(MathMissionSupport.MISSION_ID, parsed.first().id)
        assertEquals(MathDifficulty.EXTREME_HARD, MathMissionSupport.readConfig(parsed.first())?.difficulty)
        assertEquals(25, MathMissionSupport.readConfig(parsed.first())?.questionTimeLimitSec)
        assertEquals(6, MathMissionSupport.readConfig(parsed.first())?.requiredCorrectAnswers)
        assertEquals("tap", parsed[1].id)
    }

    private fun question(id: String, expression: String, answer: Int): MathQuestion {
        return MathQuestion(
            id = id,
            expressionText = expression,
            correctAnswer = answer,
            leftOperand = answer,
            rightOperand = 1,
            operatorSymbol = "+"
        )
    }
}
