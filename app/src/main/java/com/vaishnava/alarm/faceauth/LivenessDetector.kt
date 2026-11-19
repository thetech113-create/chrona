package com.vaishnava.alarm.faceauth

import android.util.Log
import com.google.mlkit.vision.face.Face
import kotlin.math.abs
import kotlin.random.Random

enum class LivenessStep(val prompt: String) {
    BLINK("Blink your eyes"),
    TURN("Turn your head slowly to one side"),
    NOD("Nod your head up or down"),
    SMILE("Smile")
}

data class FrameQualityMetrics(
    val faceSizeOk: Boolean,
    val faceCentered: Boolean
)

/**
 * Random liveness challenge: always BLINK plus one of TURN / NOD / SMILE, in random order, done one after the other.
 * Blink and smile count only as a change seen live (eyes open -> closed -> open, no smile -> smile), so a printed or
 * on-screen photo cannot pass; the random order makes a recorded video harder to replay. Directions are not asked
 * ("turn left"): a wrong sign would lock the user out of their own alarm, and either side proves the same.
 */
class LivenessDetector(private val random: Random = Random.Default) {
    private companion object {
        const val TAG = "LivenessDetector"
        const val TURN_DEGREES = 15f
        const val NOD_DEGREES = 10f
        const val EYES_CLOSED = 0.35f
        const val EYES_OPEN = 0.6f
        const val NO_SMILE = 0.3f
        const val SMILE = 0.7f
    }

    var steps: List<LivenessStep> = newSteps()
        private set
    private var stepIndex = 0
    private var baselineYaw: Float? = null
    private var baselinePitch: Float? = null
    private var sawEyesClosed = false
    private var sawNoSmile = false

    val isPassed: Boolean get() = stepIndex >= steps.size

    private fun newSteps(): List<LivenessStep> =
        (listOf(LivenessStep.BLINK) + listOf(LivenessStep.TURN, LivenessStep.NOD, LivenessStep.SMILE).random(random))
            .shuffled(random)

    /** A new random challenge (after a failed match, more than one face, or a face that left the frame). */
    fun reset() {
        steps = newSteps()
        stepIndex = 0
        startStep()
    }

    private fun startStep() {
        baselineYaw = null
        baselinePitch = null
        sawEyesClosed = false
        sawNoSmile = false
    }

    /** "Step 1 of 2: Blink your eyes", or null once passed. */
    fun prompt(): String? =
        steps.getOrNull(stepIndex)?.let { "Step ${stepIndex + 1} of ${steps.size}: ${it.prompt}" }

    fun processFrame(face: Face): Boolean {
        val before = stepIndex
        val passed = processSignals(
            yaw = face.headEulerAngleY,
            pitch = face.headEulerAngleX,
            leftEyeOpen = face.leftEyeOpenProbability,
            rightEyeOpen = face.rightEyeOpenProbability,
            smile = face.smilingProbability
        )
        if (stepIndex != before) {
            Log.d(TAG, "liveness step done: ${steps[before]} ($stepIndex/${steps.size}) yaw=${face.headEulerAngleY} pitch=${face.headEulerAngleX}")
        }
        return passed
    }

    /** One frame's head angles and probabilities (null = ML Kit did not report it); true once every step is done. */
    fun processSignals(yaw: Float, pitch: Float, leftEyeOpen: Float?, rightEyeOpen: Float?, smile: Float?): Boolean {
        val step = steps.getOrNull(stepIndex) ?: return true
        val baseYaw = baselineYaw ?: yaw.also { baselineYaw = it }
        val basePitch = baselinePitch ?: pitch.also { baselinePitch = it }
        val done = when (step) {
            LivenessStep.BLINK -> {
                if (leftEyeOpen == null || rightEyeOpen == null) false
                else {
                    if (leftEyeOpen < EYES_CLOSED && rightEyeOpen < EYES_CLOSED) sawEyesClosed = true
                    sawEyesClosed && leftEyeOpen > EYES_OPEN && rightEyeOpen > EYES_OPEN
                }
            }
            LivenessStep.TURN -> abs(yaw - baseYaw) >= TURN_DEGREES
            LivenessStep.NOD -> abs(pitch - basePitch) >= NOD_DEGREES
            LivenessStep.SMILE -> {
                if (smile == null) false
                else {
                    if (smile < NO_SMILE) sawNoSmile = true
                    sawNoSmile && smile >= SMILE
                }
            }
        }
        if (done) {
            stepIndex++
            startStep()
        }
        return isPassed
    }

    /**
     * Whether the face is a usable size and near the middle of the UPRIGHT frame. [imageWidth]/[imageHeight] must be
     * the upright size (the sensor size swapped for 90/270 degree rotation), as ML Kit's box is upright.
     */
    fun evaluateQuality(face: Face, imageWidth: Int, imageHeight: Int): FrameQualityMetrics {
        val bbox = face.boundingBox
        val faceSizeRatio = (bbox.width() * bbox.height()).toFloat() / (imageWidth * imageHeight).toFloat()
        val centerX = imageWidth / 2f
        val centerY = imageHeight / 2f
        val distFromCenter = maxOf(abs(bbox.centerX() - centerX) / centerX, abs(bbox.centerY() - centerY) / centerY)
        return FrameQualityMetrics(faceSizeOk = faceSizeRatio in 0.1f..0.7f, faceCentered = distFromCenter < 0.3f)
    }
}
