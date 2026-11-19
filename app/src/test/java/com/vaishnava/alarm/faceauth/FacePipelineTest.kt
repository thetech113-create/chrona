package com.vaishnava.alarm.faceauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class FacePipelineTest {

    private fun unit(seed: Int): FloatArray {
        val r = Random(seed)
        val v = FloatArray(FaceTemplate.EMBEDDING_SIZE) { r.nextFloat() - 0.5f }
        val n = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    // ---- template format ----

    @Test
    fun oldFacesAreRecognisedAndNewOnesRoundTrip() {
        val old = unit(1)
        assertTrue(FaceTemplate.isLegacy(old))
        assertTrue(FaceTemplate.gallery(old).isEmpty())

        val poses = listOf(unit(2), unit(3), unit(4))
        val encoded = FaceTemplate.encode(poses)
        assertFalse(FaceTemplate.isLegacy(encoded))
        val back = FaceTemplate.gallery(encoded)
        assertEquals(3, back.size)
        poses.indices.forEach { assertTrue(poses[it].contentEquals(back[it])) }
    }

    @Test
    fun bestPoseCounts() {
        val me = unit(10)
        val gallery = listOf(unit(11), me, unit(12))
        assertEquals(1f, FaceTemplate.bestScore(gallery, me), 1e-4f)
    }

    @Test
    fun damagedTemplateGivesNoGallery() {
        val encoded = FaceTemplate.encode(listOf(unit(5), unit(6)))
        assertTrue(FaceTemplate.gallery(encoded.copyOf(encoded.size - 1)).isEmpty())
    }

    // ---- alignment ----

    @Test
    fun similarityTransformRecoversRotationScaleAndShift() {
        val angle = 0.3
        val scale = 1.7
        val a = (scale * cos(angle)).toFloat()
        val b = (scale * sin(angle)).toFloat()
        val src = floatArrayOf(10f, 20f, 50f, 22f, 30f, 40f, 15f, 60f, 45f, 61f)
        val dst = FloatArray(src.size)
        for (i in 0 until 5) {
            val x = src[2 * i]; val y = src[2 * i + 1]
            dst[2 * i] = a * x - b * y + 7f
            dst[2 * i + 1] = b * x + a * y - 3f
        }
        val t = similarityTransform(src, dst)
        assertEquals(a, t[0], 1e-3f)
        assertEquals(b, t[1], 1e-3f)
        assertEquals(7f, t[2], 1e-2f)
        assertEquals(-3f, t[3], 1e-2f)
    }

    @Test
    fun alignedReferencePointsMapOntoThemselves() {
        val t = similarityTransform(FaceAlignment.REFERENCE, FaceAlignment.REFERENCE)
        assertEquals(1f, t[0], 1e-4f)
        assertEquals(0f, t[1], 1e-4f)
    }

    // ---- quality ----

    @Test
    fun brightnessAndSharpnessAreMeasured() {
        val size = 112
        val flatGray = IntArray(size * size) { 0xFF808080.toInt() }
        assertEquals(128f, meanLuma(flatGray), 1f)
        assertEquals(0f, laplacianVariance(flatGray, size), 1e-3f)
        assertEquals("Hold the phone still.", FaceQuality.check(flatGray, size).problem)

        val checker = IntArray(size * size) { i -> if (((i % size) + i / size) % 2 == 0) 0xFF202020.toInt() else 0xFFE0E0E0.toInt() }
        assertTrue(laplacianVariance(checker, size) > FaceQuality.MIN_SHARPNESS)
        assertTrue(FaceQuality.check(checker, size).ok)

        val dark = IntArray(size * size) { 0xFF101010.toInt() }
        assertEquals("Too dark. Move to better light.", FaceQuality.check(dark, size).problem)
    }

    @Test
    fun onlyFrontalOpenEyedFramesAreUsed() {
        assertNull(FaceQuality.poseProblem(3f, -4f, 2f, 0.9f, 0.9f))
        assertEquals("Look straight at the camera.", FaceQuality.poseProblem(20f, 0f, 0f, 0.9f, 0.9f))
        assertEquals("Hold your head straight.", FaceQuality.poseProblem(0f, 0f, 25f, 0.9f, 0.9f))
        assertEquals("Keep your eyes open.", FaceQuality.poseProblem(0f, 0f, 0f, 0.1f, 0.9f))
    }

    // ---- multi-frame decision ----

    @Test
    fun decisionNeedsSeveralFrames() {
        assertNull(FaceMatcher.decide(listOf(0.9f, 0.9f), MatchAction.CREATE_OR_EDIT))
        assertEquals(MatchDecision.ALLOWED, FaceMatcher.decide(listOf(0.3f, 0.6f, 0.62f), MatchAction.CREATE_OR_EDIT))
        // One lucky frame is not enough.
        assertNull(FaceMatcher.decide(listOf(0.3f, 0.6f, 0.45f), MatchAction.CREATE_OR_EDIT))
        assertEquals(MatchDecision.DENIED, FaceMatcher.decide(listOf(0.1f, 0.2f, 0.3f), MatchAction.CREATE_OR_EDIT))
        assertEquals(MatchDecision.UNCERTAIN, FaceMatcher.decide(listOf(0.5f, 0.45f, 0.5f, 0.48f, 0.44f), MatchAction.CREATE_OR_EDIT))
        // Delete is stricter.
        assertEquals(null, FaceMatcher.decide(listOf(0.6f, 0.6f, 0.6f), MatchAction.DELETE))
        assertEquals(MatchDecision.ALLOWED, FaceMatcher.decide(listOf(0.7f, 0.66f, 0.5f), MatchAction.DELETE))
    }

    // ---- liveness ----

    private fun LivenessDetector.frame(yaw: Float = 0f, pitch: Float = 0f, eyes: Float = 0.9f, smile: Float = 0.1f) =
        processSignals(yaw, pitch, eyes, eyes, smile)

    private fun LivenessDetector.perform(step: LivenessStep) {
        frame()
        when (step) {
            LivenessStep.BLINK -> { frame(eyes = 0.1f); frame(eyes = 0.9f) }
            LivenessStep.TURN -> frame(yaw = -18f)
            LivenessStep.NOD -> frame(pitch = 12f)
            LivenessStep.SMILE -> frame(smile = 0.9f)
        }
    }

    @Test
    fun challengeIsBlinkPlusOneRandomOtherStep() {
        repeat(30) { seed ->
            val steps = LivenessDetector(Random(seed)).steps
            assertEquals(2, steps.size)
            assertTrue(LivenessStep.BLINK in steps)
            assertEquals(2, steps.toSet().size)
        }
    }

    @Test
    fun aLivePersonPassesEveryChallenge() {
        repeat(30) { seed ->
            val liveness = LivenessDetector(Random(seed))
            liveness.steps.forEach { liveness.perform(it) }
            assertTrue("seed $seed ${liveness.steps}", liveness.isPassed)
        }
    }

    @Test
    fun aStillPhotoNeverPasses() {
        repeat(30) { seed ->
            val liveness = LivenessDetector(Random(seed))
            repeat(100) { liveness.frame(yaw = 2f, pitch = 1f, eyes = 0.95f, smile = 0.95f) }
            assertFalse(liveness.isPassed)
        }
    }

    @Test
    fun blinkMustBeSeenClosingThenOpening() {
        val liveness = LivenessDetector(Random(0))
        while (liveness.steps.first() != LivenessStep.BLINK) liveness.reset()
        liveness.frame(eyes = 0.9f)
        assertEquals("Step 1 of 2: Blink your eyes", liveness.prompt())
        liveness.frame(eyes = 0.1f)
        assertEquals("Step 1 of 2: Blink your eyes", liveness.prompt()) // closed is not yet a blink
        liveness.frame(eyes = 0.9f)
        assertTrue(liveness.prompt()!!.startsWith("Step 2 of 2"))
    }

    @Test
    fun resetStartsANewChallenge() {
        val liveness = LivenessDetector(Random(3))
        liveness.steps.forEach { liveness.perform(it) }
        assertTrue(liveness.isPassed)
        liveness.reset()
        assertFalse(liveness.isPassed)
    }
}
