package com.vaishnava.alarm.faceauth

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark

/**
 * Turns a detected face into the 112x112 picture MobileFaceNet was trained on: eyes, nose and mouth corners moved to
 * the standard ArcFace positions with one rotation + uniform scale + shift. Without this, a tilted head or a
 * non-square crop gives the model a distorted face and the same person scores lower.
 */
object FaceAlignment {
    const val SIZE = 112

    /** ArcFace 112x112 reference points: eye, eye, nose tip, mouth corner, mouth corner (image left to right). */
    val REFERENCE = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f
    )

    /**
     * The 5 face points in the same order as [REFERENCE] (x,y pairs), from ML Kit landmarks in upright image
     * coordinates; null when one is missing. Eyes and mouth corners are ordered by x, so mirroring does not matter.
     */
    fun landmarks(face: Face): FloatArray? {
        fun p(type: Int): PointF? = face.getLandmark(type)?.position
        val eyes = listOf(p(FaceLandmark.LEFT_EYE) ?: return null, p(FaceLandmark.RIGHT_EYE) ?: return null).sortedBy { it.x }
        val nose = p(FaceLandmark.NOSE_BASE) ?: return null
        val mouth = listOf(p(FaceLandmark.MOUTH_LEFT) ?: return null, p(FaceLandmark.MOUTH_RIGHT) ?: return null).sortedBy { it.x }
        return floatArrayOf(eyes[0].x, eyes[0].y, eyes[1].x, eyes[1].y, nose.x, nose.y, mouth[0].x, mouth[0].y, mouth[1].x, mouth[1].y)
    }

    /** The upright frame warped to the aligned 112x112 face, or null without all 5 landmarks. */
    fun align(upright: Bitmap, face: Face): Bitmap? {
        val points = landmarks(face) ?: return null
        val t = similarityTransform(points, REFERENCE)
        val matrix = Matrix().apply { setValues(floatArrayOf(t[0], -t[1], t[2], t[1], t[0], t[3], 0f, 0f, 1f)) }
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(upright, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }
}

/**
 * Least-squares similarity transform (rotation, uniform scale, shift) taking [src] points onto [dst] points, both
 * x,y pairs. Returns [a, b, tx, ty] meaning x' = a*x - b*y + tx, y' = b*x + a*y + ty.
 */
internal fun similarityTransform(src: FloatArray, dst: FloatArray): FloatArray {
    require(src.size == dst.size && src.size >= 4 && src.size % 2 == 0)
    val n = src.size / 2
    var msx = 0.0; var msy = 0.0; var mdx = 0.0; var mdy = 0.0
    for (i in 0 until n) { msx += src[2 * i]; msy += src[2 * i + 1]; mdx += dst[2 * i]; mdy += dst[2 * i + 1] }
    msx /= n; msy /= n; mdx /= n; mdy /= n
    var sxx = 0.0; var sxy = 0.0; var norm = 0.0
    for (i in 0 until n) {
        val ax = src[2 * i] - msx; val ay = src[2 * i + 1] - msy
        val bx = dst[2 * i] - mdx; val by = dst[2 * i + 1] - mdy
        sxx += ax * bx + ay * by
        sxy += ax * by - ay * bx
        norm += ax * ax + ay * ay
    }
    if (norm < 1e-9) return floatArrayOf(1f, 0f, (mdx - msx).toFloat(), (mdy - msy).toFloat())
    val a = sxx / norm
    val b = sxy / norm
    val tx = mdx - (a * msx - b * msy)
    val ty = mdy - (b * msx + a * msy)
    return floatArrayOf(a.toFloat(), b.toFloat(), tx.toFloat(), ty.toFloat())
}
