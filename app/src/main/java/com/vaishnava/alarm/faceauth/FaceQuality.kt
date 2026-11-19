package com.vaishnava.alarm.faceauth

/**
 * Real image checks on the aligned 112x112 face (the old code guessed brightness from a value that was always the
 * same, and blur from eye-open probability). A frame is used for matching only when it is bright enough, not
 * overexposed, sharp, frontal and with both eyes open.
 */
object FaceQuality {
    const val MIN_BRIGHTNESS = 50f
    const val MAX_BRIGHTNESS = 215f
    const val MIN_SHARPNESS = 20f
    const val MAX_FRONTAL_ANGLE = 12f
    const val MIN_EYE_OPEN = 0.5f

    data class Result(val brightness: Float, val sharpness: Float, val problem: String?) {
        val ok: Boolean get() = problem == null
    }

    /** Checks [pixels] (ARGB, row by row) of a [size]x[size] aligned face. */
    fun check(pixels: IntArray, size: Int): Result {
        val brightness = meanLuma(pixels)
        val sharpness = laplacianVariance(pixels, size)
        val problem = when {
            brightness < MIN_BRIGHTNESS -> "Too dark. Move to better light."
            brightness > MAX_BRIGHTNESS -> "Too bright. Avoid direct light on your face."
            sharpness < MIN_SHARPNESS -> "Hold the phone still."
            else -> null
        }
        return Result(brightness, sharpness, problem)
    }

    /** Null when the head is frontal with both eyes open, otherwise what to tell the user. */
    fun poseProblem(yaw: Float, pitch: Float, roll: Float, leftEyeOpen: Float?, rightEyeOpen: Float?): String? = when {
        kotlin.math.abs(yaw) > MAX_FRONTAL_ANGLE || kotlin.math.abs(pitch) > MAX_FRONTAL_ANGLE -> "Look straight at the camera."
        kotlin.math.abs(roll) > MAX_FRONTAL_ANGLE -> "Hold your head straight."
        (leftEyeOpen ?: 1f) < MIN_EYE_OPEN || (rightEyeOpen ?: 1f) < MIN_EYE_OPEN -> "Keep your eyes open."
        else -> null
    }
}

private fun luma(argb: Int): Float =
    0.299f * ((argb shr 16) and 0xFF) + 0.587f * ((argb shr 8) and 0xFF) + 0.114f * (argb and 0xFF)

/** Average brightness 0..255. */
internal fun meanLuma(pixels: IntArray): Float {
    if (pixels.isEmpty()) return 0f
    var sum = 0.0
    for (p in pixels) sum += luma(p)
    return (sum / pixels.size).toFloat()
}

/**
 * Sharpness: variance of the Laplacian over the inner part of the face (the warp leaves black corners). A blurred
 * face has few edges, so a low value.
 */
internal fun laplacianVariance(pixels: IntArray, size: Int): Float {
    val from = size / 7
    val to = size - from
    if (to - from < 3) return 0f
    val gray = FloatArray(pixels.size) { luma(pixels[it]) }
    var sum = 0.0
    var sumSq = 0.0
    var count = 0
    for (y in from + 1 until to - 1) {
        for (x in from + 1 until to - 1) {
            val i = y * size + x
            val lap = gray[i - 1] + gray[i + 1] + gray[i - size] + gray[i + size] - 4f * gray[i]
            sum += lap
            sumSq += lap * lap
            count++
        }
    }
    if (count == 0) return 0f
    val mean = sum / count
    return (sumSq / count - mean * mean).toFloat()
}
