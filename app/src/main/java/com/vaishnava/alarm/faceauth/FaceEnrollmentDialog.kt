package com.vaishnava.alarm.faceauth

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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

/**
 * Face enrollment (no separate liveness challenge: the poses prove a live head): two samples each looking straight, slightly to one side and
 * slightly to the other (see [FaceFrameProcessor]). The result is an aligned gallery template ([FaceTemplate]).
 */
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

    var sampleCount by remember { mutableIntStateOf(0) }
    var sampleTotal by remember { mutableIntStateOf(6) }
    var promptMessage by remember { mutableStateOf("Look at the camera") }

    val processor = remember {
        FaceFrameProcessor(context, FaceFrameProcessor.Mode.ENROLL) { event ->
            when (event) {
                is FaceEvent.Status -> promptMessage = event.text
                is FaceEvent.Progress -> {
                    sampleCount = event.done
                    sampleTotal = event.total
                }
                is FaceEvent.Enrolled -> {
                    promptMessage = "Face enrolled ✓"
                    onEnrollmentComplete(event.template)
                }
                else -> Unit
            }
        }
    }

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
            processor.close()
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

                    if (!processor.modelLoaded) {
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

                                    val imageAnalysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()
                                    // Frames are worked on in the background, never on the main thread.
                                    imageAnalysis.setAnalyzer(processor.analyzerExecutor, processor.analyzer)

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
                            progress = { sampleCount / sampleTotal.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Color(0xFF4CAF50),
                            trackColor = Color(0xFF333344)
                        )

                        Text(
                            text = "Samples Collected: $sampleCount / $sampleTotal",
                            color = Color.LightGray,
                            fontSize = 14.sp
                        )
                    }
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
