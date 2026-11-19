package com.vaishnava.alarm.faceauth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

sealed class FaceEvent {
    data class Status(val text: String) : FaceEvent()
    data class Progress(val done: Int, val total: Int) : FaceEvent()
    /** Matched. [upgradedTemplate] is set when an old (pre-1.0.6) face was matched: store it in place of the old one. */
    data class Verified(val score: Float, val upgradedTemplate: FloatArray?) : FaceEvent()
    data class Enrolled(val template: FloatArray) : FaceEvent()
}

/**
 * One camera frame at a time, on its own background thread (detection and the face model used to run on the main
 * thread and made the preview lag):
 *  1. ML Kit finds exactly one face, near the middle.
 *  2. The random liveness challenge ([LivenessDetector]).
 *  3. Only frontal, sharp, well-lit frames with eyes open are used ([FaceQuality]); each is aligned on its 5
 *     landmarks ([FaceAlignment]) before MobileFaceNet turns it into an embedding.
 *  4. VERIFY: several frames are scored against the stored face and decided together ([FaceMatcher.decide]).
 *     ENROLL: two samples each looking straight, slightly to one side and slightly to the other.
 * Events are delivered on the main thread.
 */
class FaceFrameProcessor(
    context: Context,
    private val mode: Mode,
    private val template: FloatArray? = null,
    private val action: MatchAction = MatchAction.CREATE_OR_EDIT,
    private val onEvent: (FaceEvent) -> Unit
) {
    enum class Mode { VERIFY, ENROLL }

    private companion object {
        const val TAG = "FaceAuthFlow"
        const val FRAMES_WITHOUT_FACE_TO_RESET = 8
        const val ENROLL_SAMPLES_PER_POSE = 2
        const val ENROLL_SAMPLE_GAP_MS = 300L
        const val SIDE_MIN_YAW = 10f
        const val SIDE_MAX_YAW = 30f
        const val ENROLL_MIN_CONSISTENCY = 0.45f
    }

    private val appContext = context.applicationContext
    // One worker that ends by itself 5 s after the last frame. It is never shut down: the camera may hand over a
    // last frame after close(), which must be skipped, not refused with an exception inside CameraX.
    private val executor: ExecutorService = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "face-frames").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val extractor = FaceEmbeddingExtractor(appContext)

    // The manifest removes ML Kit's auto-init provider (lazy startup), so ML Kit must be initialized before the
    // detector below is created; without it getClient() throws and the app closes when a face screen opens (1.0.6).
    // A second call when it is already initialized throws too, hence runCatching.
    init {
        runCatching { com.google.mlkit.common.MlKit.initialize(appContext) }
    }

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
    )
    private val liveness = LivenessDetector()
    private val legacy = template != null && FaceTemplate.isLegacy(template)
    private val gallery = template?.let { FaceTemplate.gallery(it) }.orEmpty()

    @Volatile private var finished = false
    @Volatile private var closed = false
    private var lastStatus: String? = null
    private var framesWithoutFace = 0

    // VERIFY
    private val scores = mutableListOf<Float>()
    private val alignedEmbeddings = mutableListOf<FloatArray>()

    // ENROLL: 0 = straight, 1 = one side, 2 = other side
    private var enrollPose = 0
    private var firstSideSign = 0f
    private var lastSampleAt = 0L
    private val samples = mutableListOf<FloatArray>()

    val modelLoaded: Boolean get() = extractor.isModelLoaded
    val analyzerExecutor: ExecutorService get() = executor

    val analyzer = ImageAnalysis.Analyzer { proxy ->
        try {
            if (!finished) analyze(proxy)
        } catch (e: Exception) {
            Log.w(TAG, "frame failed: ${e.message}")
        } finally {
            proxy.close()
        }
    }

    /** Stops after the frame being worked on; the model is closed on the same thread that uses it. */
    fun close() {
        closed = true
        finished = true
        executor.execute {
            runCatching { extractor.close() }
            runCatching { detector.close() }
        }
    }

    private fun analyze(proxy: ImageProxy) {
        if (!extractor.isModelLoaded) return status("Face model missing. Reinstall Chrona.")
        val mediaImage = proxy.image ?: return
        val rotation = proxy.imageInfo.rotationDegrees
        val faces = Tasks.await(detector.process(InputImage.fromMediaImage(mediaImage, rotation)))
        val uprightWidth = if (rotation % 180 == 0) mediaImage.width else mediaImage.height
        val uprightHeight = if (rotation % 180 == 0) mediaImage.height else mediaImage.width

        if (faces.isEmpty()) {
            // The face left the view: whoever comes back must do the challenge again.
            if (++framesWithoutFace == FRAMES_WITHOUT_FACE_TO_RESET) restart()
            return status("No face detected. Center your face.")
        }
        framesWithoutFace = 0
        if (faces.size > 1) {
            restart()
            return status("More than one face detected. Make sure only you are in frame.")
        }
        val face = faces[0]
        val position = liveness.evaluateQuality(face, uprightWidth, uprightHeight)
        if (!position.faceCentered || !position.faceSizeOk) return status("Center your face in the frame.")

        if (!liveness.isPassed) {
            liveness.processFrame(face)
            return status(liveness.prompt() ?: firstCapturePrompt())
        }

        when (mode) {
            Mode.VERIFY -> verifyFrame(proxy, rotation, face)
            Mode.ENROLL -> enrollFrame(proxy, rotation, face)
        }
    }

    private fun firstCapturePrompt() = if (mode == Mode.VERIFY) "Look straight at the camera." else "Look straight at the camera and hold still."

    private fun verifyFrame(proxy: ImageProxy, rotation: Int, face: Face) {
        FaceQuality.poseProblem(face.headEulerAngleY, face.headEulerAngleX, face.headEulerAngleZ,
            face.leftEyeOpenProbability, face.rightEyeOpenProbability)?.let { return status(it) }
        val upright = upright(proxy, rotation)
        val aligned = FaceAlignment.align(upright, face) ?: return status("Keep your whole face in view.")
        qualityProblem(aligned)?.let { return status(it) }
        val embedding = extractor.extractEmbedding(aligned) ?: return
        val stored = template ?: return

        val score = if (legacy) {
            // Old face: compare the way it was made (unaligned crop), so it keeps working; the aligned embeddings
            // of the same frames become its replacement once it matches.
            val legacyEmbedding = legacyCrop(upright, face.boundingBox)?.let { extractor.extractEmbedding(it) } ?: return
            FaceMatcher.calculateCosineSimilarity(stored, legacyEmbedding)
        } else {
            FaceTemplate.bestScore(gallery, embedding)
        }
        scores += score
        alignedEmbeddings += embedding
        status("Verifying... ${scores.size}")

        val decision = FaceMatcher.decide(scores, action) ?: return
        Log.d(TAG, "VERIFY_RESULT decision=$decision scores=${scores.joinToString { "%.3f".format(it) }} " +
            "action=$action legacyTemplate=$legacy galleryPoses=${gallery.size}")
        when (decision) {
            MatchDecision.ALLOWED -> {
                finished = true
                val upgraded = if (legacy) FaceTemplate.encode(alignedEmbeddings.toList()) else null
                val best = scores.sortedDescending().take(2).average().toFloat()
                deliver(FaceEvent.Verified(best, upgraded))
            }
            MatchDecision.DENIED -> {
                restart()
                status("This face doesn't match. Try again.")
            }
            else -> {
                restart()
                status("Not sure it's you. Try again in better light.")
            }
        }
    }

    private fun enrollFrame(proxy: ImageProxy, rotation: Int, face: Face) {
        val yaw = face.headEulerAngleY
        val poseHint = when (enrollPose) {
            0 -> FaceQuality.poseProblem(yaw, face.headEulerAngleX, face.headEulerAngleZ,
                face.leftEyeOpenProbability, face.rightEyeOpenProbability)
            else -> sidePoseProblem(face)
        }
        if (poseHint != null) return status(poseHint)
        val now = System.currentTimeMillis()
        if (now - lastSampleAt < ENROLL_SAMPLE_GAP_MS) return

        val upright = upright(proxy, rotation)
        val aligned = FaceAlignment.align(upright, face) ?: return status("Keep your whole face in view.")
        qualityProblem(aligned)?.let { return status(it) }
        val embedding = extractor.extractEmbedding(aligned) ?: return
        if (enrollPose == 1 && firstSideSign == 0f) firstSideSign = if (yaw >= 0) 1f else -1f
        lastSampleAt = now
        samples += embedding
        val total = 3 * ENROLL_SAMPLES_PER_POSE
        deliver(FaceEvent.Progress(samples.size, total))
        if (samples.size % ENROLL_SAMPLES_PER_POSE == 0) enrollPose++

        if (samples.size < total) return status(enrollPrompt())
        val front = FaceTemplateStore.aggregateEmbeddings(samples.take(ENROLL_SAMPLES_PER_POSE)) ?: return
        val weakest = samples.minOf { FaceMatcher.calculateCosineSimilarity(front, it) }
        Log.d(TAG, "ENROLL_RESULT samples=${samples.size} weakestConsistency=${"%.3f".format(weakest)}")
        if (weakest < ENROLL_MIN_CONSISTENCY) {
            restart()
            return status("The face changed during setup. Keep only your face in view and start again.")
        }
        finished = true
        val result = FaceTemplate.encode(samples.toList())
        deliver(FaceEvent.Enrolled(result))
    }

    private fun enrollPrompt(): String = when (enrollPose) {
        0 -> "Look straight at the camera and hold still."
        1 -> "Turn your head slightly to one side."
        else -> "Now turn slightly to the other side."
    }

    /** Null when the head is turned a little (not too far) to the side this pose wants, eyes open. */
    private fun sidePoseProblem(face: Face): String? {
        val yaw = face.headEulerAngleY
        val wantedSign = if (enrollPose == 1) firstSideSign else -firstSideSign
        return when {
            abs(yaw) < SIDE_MIN_YAW -> enrollPrompt()
            abs(yaw) > SIDE_MAX_YAW -> "A little less. Turn only slightly."
            wantedSign != 0f && yaw * wantedSign < 0 -> enrollPrompt()
            (face.leftEyeOpenProbability ?: 1f) < FaceQuality.MIN_EYE_OPEN ||
                (face.rightEyeOpenProbability ?: 1f) < FaceQuality.MIN_EYE_OPEN -> "Keep your eyes open."
            else -> null
        }
    }

    private fun qualityProblem(aligned: Bitmap): String? {
        val pixels = IntArray(FaceAlignment.SIZE * FaceAlignment.SIZE)
        aligned.getPixels(pixels, 0, FaceAlignment.SIZE, 0, 0, FaceAlignment.SIZE, FaceAlignment.SIZE)
        val quality = FaceQuality.check(pixels, FaceAlignment.SIZE)
        if (!quality.ok) Log.d(TAG, "frame skipped: ${quality.problem} brightness=${quality.brightness} sharpness=${quality.sharpness}")
        return quality.problem
    }

    /** Starts over: a new random challenge and no collected frames. */
    private fun restart() {
        liveness.reset()
        scores.clear()
        alignedEmbeddings.clear()
        samples.clear()
        enrollPose = 0
        firstSideSign = 0f
        deliver(FaceEvent.Progress(0, 3 * ENROLL_SAMPLES_PER_POSE))
    }

    /** Events reach the screen on the main thread, and never after it closed. */
    private fun deliver(event: FaceEvent) {
        main.post { if (!closed) onEvent(event) }
    }

    private fun status(text: String) {
        if (text == lastStatus) return
        lastStatus = text
        deliver(FaceEvent.Status(text))
    }
}

/** The camera frame turned upright, the way ML Kit's face box and landmarks are given. */
private fun upright(proxy: ImageProxy, rotation: Int): Bitmap {
    val bitmap = proxy.toBitmap()
    if (rotation == 0) return bitmap
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/** The face crop the way faces were enrolled before 1.0.6 (box + 15%, squashed to 112x112 by the extractor). */
private fun legacyCrop(bitmap: Bitmap, bounds: Rect): Bitmap? = runCatching {
    val marginX = (bounds.width() * 0.15f).toInt()
    val marginY = (bounds.height() * 0.15f).toInt()
    val left = (bounds.left - marginX).coerceIn(0, bitmap.width - 1)
    val top = (bounds.top - marginY).coerceIn(0, bitmap.height - 1)
    val right = (bounds.right + marginX).coerceIn(left + 1, bitmap.width)
    val bottom = (bounds.bottom + marginY).coerceIn(top + 1, bitmap.height)
    if (right - left > 20 && bottom - top > 20) Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top) else null
}.getOrNull()
