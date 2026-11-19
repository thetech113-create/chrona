package com.vaishnava.alarm.faceauth

import android.Manifest
import com.vaishnava.alarm.data.Alarm
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
 * Face check with the front camera (see [FaceFrameProcessor] for the steps). [onTemplateUpgraded] receives the new
 * aligned face when an old (pre-1.0.6) stored face was matched, so the caller can store it in its place.
 */
@Composable
fun FaceAuthDialog(
    action: MatchAction,
    alarmId: Int,
    alarm: Alarm?,
    onDismiss: () -> Unit,
    onVerificationSuccess: () -> Unit,
    onRequestEnrollment: () -> Unit,
    onTemplateUpgraded: (FloatArray) -> Unit = {}
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

    val enrolledEmbedding = remember { alarm?.enrolledFaceEmbedding }

    var statusMessage by remember { mutableStateOf("Verifying identity...") }

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

    val processor = remember {
        FaceFrameProcessor(context, FaceFrameProcessor.Mode.VERIFY, enrolledEmbedding, action) { event ->
            when (event) {
                is FaceEvent.Status -> statusMessage = event.text
                is FaceEvent.Verified -> {
                    statusMessage = "Face verified ✓"
                    Log.d("FaceAuthFlow", "ALLOWED score=${event.score} upgraded=${event.upgradedTemplate != null}")
                    event.upgradedTemplate?.let(onTemplateUpgraded)
                    onVerificationSuccess()
                }
                else -> Unit
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { processor.close() }
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

                                    val imageAnalysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()
                                    // Frames are worked on in the background, never on the main thread.
                                    imageAnalysis.setAnalyzer(processor.analyzerExecutor, processor.analyzer)

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
