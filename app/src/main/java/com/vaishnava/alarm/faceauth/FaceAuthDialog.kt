package com.vaishnava.alarm.faceauth

import android.Manifest
import com.vaishnava.alarm.data.Alarm
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import java.io.ByteArrayOutputStream
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

@Composable
fun FaceAuthDialog(
    action: MatchAction,
    alarmId: Int,
    alarm: Alarm?,
    onDismiss: () -> Unit,
    onVerificationSuccess: () -> Unit,
    onRequestEnrollment: () -> Unit
) {
    Log.d("FaceAuthFlow", "🎬 FaceAuthDialog composed: action=$action, alarmId=$alarmId")
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // bindToLifecycle() below binds to the ACTIVITY's lifecycle, which stays RESUMED after
    // this dialog closes - so without an explicit unbind the camera keeps running and the
    // device camera indicator stays lit. Release it when the dialog leaves composition.
    val boundCameraProvider = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    // The provider arrives asynchronously. If this dialog closes before that callback
    // fires, onDispose has nothing to unbind and the late binding leaves the camera on
    // with no owner - the callback checks this flag and releases instead of binding.
    val disposed = remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            disposed.value = true
            runCatching { boundCameraProvider.value?.unbindAll() }
            boundCameraProvider.value = null
        }
    }

    val extractor = remember { FaceEmbeddingExtractor(context) }
    val livenessDetector = remember { LivenessDetector() }
    val enrolledEmbedding = remember { alarm?.enrolledFaceEmbedding }

    var statusMessage by remember { mutableStateOf("Verifying identity...") }
    var resultText by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            extractor.close()
        }
    }

    if (enrolledEmbedding == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Face Enrollment Required") },
            text = { Text("This alarm doesn't have a face enrollment yet. Please enroll your face first.") },
            confirmButton = {
                Button(onClick = {
                    onDismiss()
                    onRequestEnrollment()
                }) {
                    Text("Enroll Face")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF191C24)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = when (action) {
                        MatchAction.CREATE_OR_EDIT -> "Face Auth: Create/Edit Alarm"
                        MatchAction.DELETE -> "Face Auth: Delete Alarm"
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                if (!hasCameraPermission) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF332222)),
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Camera permission is required for face authentication.",
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                            ) {
                                Text("Grant Camera Permission")
                            }
                        }
                    }
                } else {
                    Text(
                        text = statusMessage,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF64B5F6),
                        textAlign = TextAlign.Center
                    )

                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .clip(CircleShape)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                // The manifest removes ML Kit's auto-init provider so it does
                                // not load on every app launch; initialize it here instead,
                                // at the point face detection is actually needed.
                                runCatching { com.google.mlkit.common.MlKit.initialize(ctx) }

                                val previewView = PreviewView(ctx)
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                                cameraProviderFuture.addListener({
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }

                                    val faceDetectorOptions = FaceDetectorOptions.Builder()
                                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                                        .build()

                                    val detector = FaceDetection.getClient(faceDetectorOptions)

                                    val imageAnalysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()

                                    imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                                        val mediaImage = imageProxy.image
                                        if (mediaImage != null && !isProcessing && extractor.isModelLoaded) {
                                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                            detector.process(image)
                                                .addOnSuccessListener { faces ->
                                                    if (faces.size > 1) {
                                                        // Round preview frame is a visual guide only - the analyzed
                                                        // image is the full sensor frame, so a bystander elsewhere
                                                        // in view would otherwise be indistinguishable from faces[0].
                                                        statusMessage = "More than one face detected. Make sure only you are in frame."
                                                        livenessDetector.reset()
                                                    } else if (faces.isNotEmpty()) {
                                                        val face = faces[0]
                                                        val position = livenessDetector.evaluateQuality(
                                                            face,
                                                            imageWidth = mediaImage.width,
                                                            imageHeight = mediaImage.height
                                                        )

                                                        if (!position.faceCentered || !position.faceSizeOk) {
                                                            statusMessage = "Center your face in the frame."
                                                        } else {
                                                        // Pass image dimensions for quality evaluation
                                                        val livenessPassed = livenessDetector.processFrame(
                                                            face,
                                                            imageWidth = mediaImage.width,
                                                            imageHeight = mediaImage.height
                                                        )
                                                        statusMessage = livenessDetector.getChallengePrompt()

                                                        if (livenessPassed && !isProcessing) {
                                                            val bitmap = imageProxy.toBitmap()
                                                            val croppedFace = bitmap?.let {
                                                                val rotatedBitmap = rotateBitmap(it, imageProxy.imageInfo.rotationDegrees.toFloat())
                                                                cropFaceBitmap(rotatedBitmap, face.boundingBox)
                                                            }
                                                            if (bitmap == null) {
                                                                Log.w("FaceAuthFlow", "toBitmap() returned null - frame decode failed, will retry next frame")
                                                            }

                                                            if (croppedFace != null) {
                                                                val capturedEmbedding = extractor.extractEmbedding(croppedFace)
                                                                if (capturedEmbedding == null) {
                                                                    Log.w("FaceAuthFlow", "extractEmbedding returned null (model/crop issue) - will retry next frame")
                                                                }
                                                                val verification = FaceMatcher.verify(
                                                                    enrolledEmbedding = enrolledEmbedding,
                                                                    capturedEmbedding = capturedEmbedding,
                                                                    livenessPassed = true,
                                                                    action = action
                                                                )

                                                                Log.d(
                                                                    "FaceAuthFlow",
                                                                    "VERIFY_RESULT decision=${verification.decision} " +
                                                                        "score=${verification.similarityScore} action=$action " +
                                                                        "capturedEmbSize=${capturedEmbedding?.size} " +
                                                                        "enrolledEmbSize=${enrolledEmbedding.size} " +
                                                                        "isProcessingBefore=$isProcessing"
                                                                )

                                                                resultText = verification.message
                                                                when (verification.decision) {
                                                                    MatchDecision.ALLOWED -> {
                                                                        isProcessing = true
                                                                        Log.d("FaceAuthFlow", "ALLOWED -> isProcessing=true, calling onVerificationSuccess()")
                                                                        onVerificationSuccess()
                                                                    }
                                                                    MatchDecision.UNCERTAIN -> {
                                                                        livenessDetector.reset()
                                                                        Log.d("FaceAuthFlow", "UNCERTAIN -> livenessDetector.reset() called, isProcessing=$isProcessing")
                                                                    }
                                                                    MatchDecision.DENIED, MatchDecision.SPOOF_DENIED -> {
                                                                        // Reset liveness detector to allow retry
                                                                        livenessDetector.reset()
                                                                        Log.d("FaceAuthFlow", "${verification.decision} -> livenessDetector.reset() called, isProcessing=$isProcessing (should stay false)")
                                                                        // Do NOT set isProcessing=true; let user try again
                                                                    }
                                                                    else -> {
                                                                        Log.w("FaceAuthFlow", "UNHANDLED decision=${verification.decision} - no reset, no isProcessing change")
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        }
                                                    } else {
                                                        statusMessage = "No face detected. Center your face."
                                                    }
                                                    imageProxy.close()
                                                }
                                                .addOnFailureListener {
                                                    imageProxy.close()
                                                }
                                        } else {
                                            imageProxy.close()
                                        }
                                    }

                                    try {
                                        cameraProvider.unbindAll()
                                        if (disposed.value) {
                                            Log.d("FaceAuthDialog", "Disposed before camera ready - not binding")
                                            return@addListener
                                        }
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_FRONT_CAMERA,
                                            preview,
                                            imageAnalysis
                                        )
                                        boundCameraProvider.value = cameraProvider
                                    } catch (e: Exception) {
                                        Log.e("FaceAuthDialog", "Camera binding failed", e)
                                    }
                                }, ContextCompat.getMainExecutor(ctx))

                                previewView
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                resultText?.let {
                    Text(
                        text = it,
                        color = Color.Yellow,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color.White)
                    }
                }
            }
        }
    }
}

private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
    if (degrees == 0f) return bitmap
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun cropFaceBitmap(bitmap: Bitmap, bounds: Rect): Bitmap? {
    return try {
        val marginX = (bounds.width() * 0.15f).toInt()
        val marginY = (bounds.height() * 0.15f).toInt()

        val left = (bounds.left - marginX).coerceIn(0, bitmap.width - 1)
        val top = (bounds.top - marginY).coerceIn(0, bitmap.height - 1)
        val right = (bounds.right + marginX).coerceIn(left + 1, bitmap.width)
        val bottom = (bounds.bottom + marginY).coerceIn(top + 1, bitmap.height)

        val width = right - left
        val height = bottom - top

        if (width > 20 && height > 20) {
            Bitmap.createBitmap(bitmap, left, top, width, height)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

// ImageAnalysis delivers YUV_420_888 frames (no JPEG plane), so planes[0] is raw
// luma data, not a decodable image - decodeByteArray on it returns null. That null
// silently propagates as a "non-null" Bitmap (Kotlin trusts the Java platform type),
// causing a later NPE inside cropFaceBitmap's try/catch, which swallows it and returns
// null - so verification never runs, but the exception happens *after* imageProxy has
// already been read, so it doesn't explain a stuck camera. What it does mean is capture
// silently fails for everyone. Convert YUV -> NV21 -> JPEG -> Bitmap properly instead.
// Never lets an exception escape: setAnalyzer's caller only calls imageProxy.close()
// after this returns, so a thrown exception here would leak the proxy and stall the
// camera (STRATEGY_KEEP_ONLY_LATEST waits for the previous frame to close).
private fun ImageProxy.toBitmap(): Bitmap? = try {
    toBitmapOrThrow()
} catch (e: Exception) {
    Log.w("FaceAuthFlow", "toBitmap() failed: ${e.message}")
    null
}

private fun ImageProxy.toBitmapOrThrow(): Bitmap? {
    if (format != ImageFormat.YUV_420_888 || planes.size < 3) {
        val planeProxy = planes[0]
        val buffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val nv21 = ByteArray(width * height * 3 / 2)

    var pos = 0
    val yBuffer = yPlane.buffer
    val yRowStride = yPlane.rowStride
    for (row in 0 until height) {
        yBuffer.position(row * yRowStride)
        yBuffer.get(nv21, pos, width)
        pos += width
    }

    val uvRowStride = vPlane.rowStride
    val uvPixelStride = vPlane.pixelStride
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val chromaHeight = height / 2
    val chromaWidth = width / 2
    for (row in 0 until chromaHeight) {
        for (col in 0 until chromaWidth) {
            val index = row * uvRowStride + col * uvPixelStride
            nv21[pos++] = vBuffer.get(index)
            nv21[pos++] = uBuffer.get(index)
        }
    }

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
    val jpegBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
}
