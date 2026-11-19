package com.vaishnava.alarm.faceauth

import kotlin.math.sqrt

enum class MatchAction {
    CREATE_OR_EDIT,
    DELETE
}

enum class MatchDecision {
    ALLOWED,
    DENIED,
    UNCERTAIN
}

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

    /**
     * Decides from several good frames instead of one (one frame can be unlucky either way): null while more frames
     * are needed. From 3 frames on, the mean of the best two must reach the action's threshold; if no frame comes
     * near the face, or 5 frames still do not settle it, the attempt ends.
     */
    fun decide(scores: List<Float>, action: MatchAction): MatchDecision? {
        if (scores.size < MIN_FRAMES) return null
        val threshold = when (action) {
            MatchAction.CREATE_OR_EDIT -> MATCH_THRESHOLD_CREATE_EDIT
            MatchAction.DELETE -> MATCH_THRESHOLD_DELETE
        }
        val bestTwo = scores.sortedDescending().take(2).average().toFloat()
        return when {
            bestTwo >= threshold -> MatchDecision.ALLOWED
            scores.max() < NON_MATCH_THRESHOLD -> MatchDecision.DENIED
            scores.size >= MAX_FRAMES -> if (bestTwo < NON_MATCH_THRESHOLD) MatchDecision.DENIED else MatchDecision.UNCERTAIN
            else -> null
        }
    }

    private const val MIN_FRAMES = 3
    private const val MAX_FRAMES = 5
}
