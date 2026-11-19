package com.vaishnava.alarm.faceauth

import android.util.Log
import com.google.mlkit.vision.face.Face
import kotlin.math.abs

enum class LivenessChallenge {
    ANY_MOVEMENT,
    PASSED
}

data class FrameQualityMetrics(
    val isBright: Boolean,
    val isBlurry: Boolean,
    val faceSizeOk: Boolean,
    val faceCentered: Boolean,
    val quality: Float // 0.0-1.0 overall quality score
)

data class LivenessFrameData(
    val yaw: Float,
    val pitch: Float,
    val leftEyeOpen: Float,
    val rightEyeOpen: Float,
    val smile: Float,
    val quality: FrameQualityMetrics
)

class LivenessDetector {
    private companion object {
        const val TAG = "LivenessDetector"
        const val MAX_FRAMES_TO_COLLECT = 30 // Collect up to 30 frames for aggregation
        const val REQUIRED_QUALITY_FRAMES = 3 // Need at least 3 good-quality frames (was 5)
        const val YAWDIFF_THRESHOLD = 3.5f // Easier head turn detection (was 6.0f)
        const val PITCHDIFF_THRESHOLD = 3.5f // Easier head tilt detection (was 6.0f)
        const val SMILE_THRESHOLD = 0.35f // Easier smile detection (was 0.5f)
        const val EYE_CLOSED_THRESHOLD = 0.5f // Easier blink detection (was 0.4f)
    }

    private var isPassed = false
    private val frameHistory = mutableListOf<LivenessFrameData>()
    private var initialYaw: Float? = null
    private var initialPitch: Float? = null

    var currentChallenge: LivenessChallenge = LivenessChallenge.ANY_MOVEMENT
        private set

    fun reset() {
        Log.d(TAG, "reset() called - clearing isPassed=$isPassed, frameHistory.size=${frameHistory.size}")
        initialYaw = null
        initialPitch = null
        isPassed = false
        frameHistory.clear()
        currentChallenge = LivenessChallenge.ANY_MOVEMENT
    }

    // Evaluate image quality from face detection results. Public so callers can gate
    // capture on faceCentered/faceSizeOk directly - the round preview frame is a visual
    // guide only (the analyzed frame is the full uncropped sensor image), so without this
    // check a face anywhere in the wider camera view - not just inside the round frame -
    // would be accepted for capture.
    fun evaluateQuality(face: Face, imageWidth: Int, imageHeight: Int): FrameQualityMetrics {
        val bbox = face.boundingBox
        val faceWidth = bbox.width()
        val faceHeight = bbox.height()
        val faceArea = faceWidth * faceHeight
        val imageArea = imageWidth * imageHeight

        // Face should be 10%-70% of image area
        val faceSizeRatio = faceArea.toFloat() / imageArea.toFloat()
        val faceSizeOk = faceSizeRatio in 0.1f..0.7f

        // Face should be roughly centered (within 30% of center)
        val centerX = imageWidth / 2f
        val centerY = imageHeight / 2f
        val faceCenterX = bbox.centerX()
        val faceCenterY = bbox.centerY()
        val distFromCenter = maxOf(
            abs(faceCenterX - centerX) / centerX,
            abs(faceCenterY - centerY) / centerY
        )
        val faceCentered = distFromCenter < 0.3f

        // Use ML Kit confidence as brightness proxy (very low confidence can indicate overexposure/underexposure)
        val trackingConfidence = face.trackingId?.let { 1.0f } ?: 0.8f
        val isBright = trackingConfidence > 0.3f

        // Blurriness is hard to detect from face detection alone; use eye confidence as proxy
        val eyeConfidence = minOf(
            face.leftEyeOpenProbability ?: 0.5f,
            face.rightEyeOpenProbability ?: 0.5f
        )
        val isBlurry = eyeConfidence < 0.2f

        // Overall quality score
        val qualityScore = (
            (if (faceSizeOk) 0.3f else 0.0f) +
            (if (faceCentered) 0.3f else 0.0f) +
            (if (isBright) 0.2f else 0.0f) +
            (if (!isBlurry) 0.2f else 0.0f)
        )

        return FrameQualityMetrics(
            isBright = isBright,
            isBlurry = isBlurry,
            faceSizeOk = faceSizeOk,
            faceCentered = faceCentered,
            quality = qualityScore
        )
    }

    fun processFrame(face: Face, imageWidth: Int = 1080, imageHeight: Int = 1920): Boolean {
        if (isPassed) return true

        val yaw = face.headEulerAngleY
        val pitch = face.headEulerAngleX
        val leftEyeOpen = face.leftEyeOpenProbability ?: 0.5f
        val rightEyeOpen = face.rightEyeOpenProbability ?: 0.5f
        val smile = face.smilingProbability ?: 0.0f

        val qualityMetrics = evaluateQuality(face, imageWidth, imageHeight)

        // If quality is too poor, don't include this frame in liveness evaluation
        if (qualityMetrics.quality < 0.3f) {  // Lowered from 0.5f to accept more frames
            Log.d(TAG, "Frame quality too low (${qualityMetrics.quality}), skipping liveness evaluation")
            return false
        }

        // Store frame data
        frameHistory.add(LivenessFrameData(yaw, pitch, leftEyeOpen, rightEyeOpen, smile, qualityMetrics))

        // Initialize reference on first good-quality frame
        if (initialYaw == null) {
            initialYaw = yaw
            initialPitch = pitch
            Log.d(TAG, "Initialized liveness baseline: yaw=$yaw, pitch=$pitch")
        }

        // Make aggregated liveness decision after collecting enough good-quality frames
        if (frameHistory.size >= REQUIRED_QUALITY_FRAMES) {
            val livenessDecision = evaluateMultiFrameLiveness()
            if (livenessDecision) {
                isPassed = true
                currentChallenge = LivenessChallenge.PASSED
                logLivenessSuccess()
            } else if (frameHistory.size >= MAX_FRAMES_TO_COLLECT) {
                // If we've collected max frames and still no liveness, fail
                logLivenessFailure()
                return false
            }
        }

        return isPassed
    }

    private fun evaluateMultiFrameLiveness(): Boolean {
        if (frameHistory.isEmpty() || initialYaw == null || initialPitch == null) {
            return false
        }

        var headMovementDetected = false
        var eyeBlinkDetected = false
        var smileDetected = false

        // Analyze across frames for head movement
        var maxYawDiff = 0f
        var maxPitchDiff = 0f
        var eyeClosureCount = 0

        for (frame in frameHistory) {
            val yawDiff = abs(frame.yaw - initialYaw!!)
            val pitchDiff = abs(frame.pitch - initialPitch!!)

            maxYawDiff = maxOf(maxYawDiff, yawDiff)
            maxPitchDiff = maxOf(maxPitchDiff, pitchDiff)

            // Detect eye blink: when both eyes are closed (< 0.4 open probability)
            if (frame.leftEyeOpen < EYE_CLOSED_THRESHOLD && frame.rightEyeOpen < EYE_CLOSED_THRESHOLD) {
                eyeClosureCount++
            }

            if (frame.smile > SMILE_THRESHOLD) {
                smileDetected = true
            }
        }

        headMovementDetected = maxYawDiff > YAWDIFF_THRESHOLD || maxPitchDiff > PITCHDIFF_THRESHOLD
        eyeBlinkDetected = eyeClosureCount >= 2 // At least 2 frames with both eyes closed

        Log.d(TAG, """
            Liveness analysis (${frameHistory.size} frames):
              headMovement: $headMovementDetected (yaw=$maxYawDiff°, pitch=$maxPitchDiff°)
              eyeBlink: $eyeBlinkDetected (closureCount=$eyeClosureCount)
              smile: $smileDetected
        """.trimIndent())

        // Accept if ANY liveness signal is detected across the frame sequence
        return headMovementDetected || eyeBlinkDetected || smileDetected
    }

    private fun logLivenessSuccess() {
        Log.d(TAG, "✓ LIVENESS PASSED: Multi-frame evaluation successful (${frameHistory.size} frames evaluated)")
    }

    private fun logLivenessFailure() {
        val avgQuality = frameHistory.map { it.quality.quality }.average()
        Log.e(TAG, """
            ✗ LIVENESS FAILED after ${frameHistory.size} frames:
              avgQuality: $avgQuality
              frameDetails: ${frameHistory.mapIndexed { i, f -> "F$i(q=${f.quality.quality})" }.joinToString(", ")}
        """.trimIndent())
    }

    fun getChallengePrompt(): String {
        return when (currentChallenge) {
            LivenessChallenge.ANY_MOVEMENT -> "Please blink or turn your head slightly"
            LivenessChallenge.PASSED -> "Liveness verified ✓"
        }
    }

    fun getFrameCount(): Int = frameHistory.size
}
