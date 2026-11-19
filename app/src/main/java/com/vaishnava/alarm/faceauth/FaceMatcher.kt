package com.vaishnava.alarm.faceauth

import kotlin.math.sqrt

enum class MatchAction {
    CREATE_OR_EDIT,
    DELETE
}

enum class MatchDecision {
    ALLOWED,
    DENIED,
    UNCERTAIN,
    SPOOF_DENIED,
    NOT_ENROLLED
}

data class VerificationResult(
    val decision: MatchDecision,
    val similarityScore: Float,
    val message: String
)

object FaceMatcher {
    // Calibrated MobileFaceNet thresholds for mobile front camera face crops:
    // Authorized user typically yields 0.55 .. 0.85 similarity
    // Unauthorized user / different person typically yields < 0.40
    private const val MATCH_THRESHOLD_CREATE_EDIT = 0.55f
    private const val MATCH_THRESHOLD_DELETE = 0.65f // Stricter gate for deletion
    private const val NON_MATCH_THRESHOLD = 0.42f

    fun calculateCosineSimilarity(emb1: FloatArray, emb2: FloatArray): Float {
        if (emb1.size != emb2.size || emb1.isEmpty()) return 0f

        var dot = 0.0f
        var norm1 = 0.0f
        var norm2 = 0.0f

        for (i in emb1.indices) {
            dot += emb1[i] * emb2[i]
            norm1 += emb1[i] * emb1[i]
            norm2 += emb2[i] * emb2[i]
        }

        val denominator = sqrt((norm1 * norm2).toDouble()).toFloat().coerceAtLeast(1e-6f)
        return (dot / denominator).coerceIn(-1.0f, 1.0f)
    }

    fun verify(
        enrolledEmbedding: FloatArray?,
        capturedEmbedding: FloatArray?,
        livenessPassed: Boolean,
        action: MatchAction
    ): VerificationResult {
        if (enrolledEmbedding == null) {
            return VerificationResult(
                decision = MatchDecision.NOT_ENROLLED,
                similarityScore = 0f,
                message = "No face enrolled. Please enroll your face first."
            )
        }

        if (capturedEmbedding == null) {
            return VerificationResult(
                decision = MatchDecision.DENIED,
                similarityScore = 0f,
                message = "Failed to capture face embedding."
            )
        }

        if (!livenessPassed) {
            return VerificationResult(
                decision = MatchDecision.SPOOF_DENIED,
                similarityScore = 0f,
                message = "Please look at the camera and blink. Try again."
            )
        }

        val score = calculateCosineSimilarity(enrolledEmbedding, capturedEmbedding)
        val requiredThreshold = when (action) {
            MatchAction.CREATE_OR_EDIT -> MATCH_THRESHOLD_CREATE_EDIT
            MatchAction.DELETE -> MATCH_THRESHOLD_DELETE
        }

        return when {
            score >= requiredThreshold -> VerificationResult(
                decision = MatchDecision.ALLOWED,
                similarityScore = score,
                message = "Face verified ✓"
            )
            score < NON_MATCH_THRESHOLD -> VerificationResult(
                decision = MatchDecision.DENIED,
                similarityScore = score,
                message = "This face doesn't match. Try again."
            )
            else -> VerificationResult(
                decision = MatchDecision.UNCERTAIN,
                similarityScore = score,
                message = "Try again in better lighting"
            )
        }
    }
}
