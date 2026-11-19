package com.vaishnava.alarm.faceauth

import android.Manifest
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
fun FaceEnrollmentDialog(
    alarmId: Int,
    onDismiss: () -> Unit,
    onEnrollmentComplete: (enrollment: FloatArray?) -> Unit
) {
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
        Log.d("FaceAuthFlow", "📱 FaceEnrollmentDialog LaunchedEffect: alarmId=$alarmId, hasCameraPermission=$hasCameraPermission")
        if (!hasCameraPermission) {
            Log.d("FaceAuthFlow", "🔌 Requesting camera permission...")
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val extractor = remember { FaceEmbeddingExtractor(context) }
    val livenessDetector = remember { LivenessDetector() }

    var sampleCount by remember { mutableIntStateOf(0) }
    var promptMessage by remember { mutableStateOf("Look at the camera and blink or turn slightly") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var lastSampleTimeMs by remember { mutableLongStateOf(0L) }

    val collectedSamples = remember { mutableStateListOf<FloatArray>() }

    // bindToLifecycle() binds to the ACTIVITY's lifecycle, which stays RESUMED after this
    // dialog closes - so without an explicit unbind the camera keeps running and the device
    // camera indicator stays lit.
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
            extractor.close()
        }
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
            color = Color(0xFF1E1E2C)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Face Enrollment",
                    fontSize = 22.sp,
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
                                text = "Camera permission is required for face enrollment.",
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                            ) {
                                Text("Grant Camera Permission")
                            }
                        }
                    }
                } else {
                    Text(
                        text = promptMessage,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF4CAF50),
                        textAlign = TextAlign.Center
                    )

                    if (!extractor.isModelLoaded) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF332222)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Model asset missing: Please place 'mobilefacenet.tflite' in app/src/main/assets/",
                                color = Color(0xFFFF6B6B),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .clip(CircleShape)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                Log.d("FaceAuthFlow", "📹 AndroidView factory called - initializing camera provider")
                                // The manifest removes ML Kit's auto-init provider so it does
                                // not load on every app launch; initialize it here instead,
                                // at the point face detection is actually needed.
                                runCatching { com.google.mlkit.common.MlKit.initialize(ctx) }

                                val previewView = PreviewView(ctx)
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                                cameraProviderFuture.addListener({
                                    Log.d("FaceAuthFlow", "📷 Camera provider ready - binding to lifecycle")
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
                                        val now = System.currentTimeMillis()

                                        if (mediaImage != null && !isProcessing && sampleCount < 5 && extractor.isModelLoaded) {
                                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                            detector.process(image)
                                                .addOnSuccessListener { faces ->
                                                    if (faces.size > 1) {
                                                        // Round preview frame is a visual guide only - the analyzed
                                                        // image is the full sensor frame, so a bystander elsewhere
                                                        // in view would otherwise be indistinguishable from faces[0].
                                                        promptMessage = "More than one face detected. Make sure only you are in frame."
                                                        livenessDetector.reset()
                                                    } else if (faces.isNotEmpty()) {
                                                        val face = faces[0]
                                                        val position = livenessDetector.evaluateQuality(
                                                            face,
                                                            imageWidth = mediaImage.width,
                                                            imageHeight = mediaImage.height
                                                        )

                                                        if (!position.faceCentered || !position.faceSizeOk) {
                                                            promptMessage = "Center your face in the frame."
                                                        } else {
                                                        // Pass image dimensions for quality evaluation
                                                        val livenessPassed = livenessDetector.processFrame(
                                                            face,
                                                            imageWidth = mediaImage.width,
                                                            imageHeight = mediaImage.height
                                                        )

                                                        // Only capture samples when liveness is genuinely verified
                                                        if (livenessPassed) {
                                                            if (now - lastSampleTimeMs >= 500L) {
                                                                lastSampleTimeMs = now
                                                                val bitmap = imageProxy.toBitmap()
                                                                val croppedFace = bitmap?.let {
                                                                    val rotatedBitmap = rotateBitmap(it, imageProxy.imageInfo.rotationDegrees.toFloat())
                                                                    cropFaceBitmap(rotatedBitmap, face.boundingBox)
                                                                }
                                                                if (bitmap == null) {
                                                                    Log.w("FaceEnrollmentDialog", "toBitmap() returned null - frame decode failed, will retry next frame")
                                                                }

                                                                if (croppedFace != null) {
                                                                    val embedding = extractor.extractEmbedding(croppedFace)
                                                                    if (embedding != null) {
                                                                        collectedSamples.add(embedding)
                                                                        sampleCount = collectedSamples.size
                                                                        promptMessage = "Captured sample $sampleCount/5. Hold still..."

                                                                        if (sampleCount >= 5) {
                                                                            isProcessing = true
                                                                            promptMessage = "Processing face template..."
                                                                            val enrolledEmbedding = FaceTemplateStore.aggregateEmbeddings(collectedSamples)
                                                                            if (enrolledEmbedding != null) {
                                                                                onEnrollmentComplete(enrolledEmbedding)
                                                                            } else {
                                                                                errorMessage = "Failed to process face template."
                                                                                onEnrollmentComplete(null)
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        } else {
                                                            // Provide user-friendly feedback without showing frame counts
                                                            promptMessage = livenessDetector.getChallengePrompt()
                                                        }
                                                        }
                                                    } else {
                                                        promptMessage = "No face detected. Center your face."
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
                                            Log.d("FaceEnrollmentDialog", "Disposed before camera ready - not binding")
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
                                        Log.e("FaceEnrollmentDialog", "Camera binding failed", e)
                                    }
                                }, ContextCompat.getMainExecutor(ctx))

                                previewView
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    if (sampleCount > 0) {
                        LinearProgressIndicator(
                            progress = { sampleCount / 5.0f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Color(0xFF4CAF50),
                            trackColor = Color(0xFF333344)
                        )

                        Text(
                            text = "Samples Collected: $sampleCount / 5",
                            color = Color.LightGray,
                            fontSize = 14.sp
                        )
                    }
                }

                errorMessage?.let {
                    Text(text = it, color = Color.Red, fontSize = 13.sp)
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
// luma data, not a decodable image. Convert YUV -> NV21 -> JPEG -> Bitmap properly.
// Never lets an exception escape: setAnalyzer's caller only calls imageProxy.close()
// after this returns, so a thrown exception here would leak the proxy and stall the
// camera (STRATEGY_KEEP_ONLY_LATEST waits for the previous frame to close).
private fun ImageProxy.toBitmap(): Bitmap? = try {
    toBitmapOrThrow()
} catch (e: Exception) {
    Log.w("FaceEnrollmentDialog", "toBitmap() failed: ${e.message}")
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
