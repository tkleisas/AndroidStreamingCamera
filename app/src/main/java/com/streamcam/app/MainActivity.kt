package com.streamcam.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pedro.library.view.OpenGlView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val SHOW_GIMBAL_UI = false

class MainActivity : ComponentActivity() {

    private var streamServer: RtspStreamServer? = null
    private var openGlView: OpenGlView? = null
    private var yoloDetector: YoloDetector? = null
    private var gimbalController: GimbalController? = null
    private var isCurrentlyStreaming = false
    private var showExitDialog = mutableStateOf(false)
    private var detections = mutableStateOf<List<Detection>>(emptyList())
    private var isDetecting = mutableStateOf(false)
    private var detectionJob: Job? = null

    private var trackingClassId = -1
    private var trackingCx = 0f
    private var trackingCy = 0f
    private var lastSeenMs = 0L
    private val trackedIdx = mutableIntStateOf(-1)
    private val yawPid = PidController(kp = 60f, ki = 3f, kd = 10f)
    private val pitchPid = PidController(kp = 60f, ki = 3f, kd = 10f)

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

    private val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            streamServer?.startPreview()
        } else {
            Toast.makeText(this, "Camera and microphone permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            gimbalController?.startScan(lifecycleScope)
        } else {
            Toast.makeText(this, "Bluetooth permissions required for gimbal", Toast.LENGTH_SHORT).show()
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (isCurrentlyStreaming) {
                showExitDialog.value = true
            } else {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this, backPressedCallback)

        if (!hasPermissions()) {
            permissionLauncher.launch(requiredPermissions)
        }

        yoloDetector = YoloDetector.create(this)
        gimbalController = GimbalController(this)

        setContent {
            StreamCamTheme {
                var showDialog by remember { showExitDialog }
                val currentDetections by remember { detections }
                val detecting by remember { isDetecting }
                val gimbal = gimbalController
                val gimbalState by remember { gimbal?.state ?: mutableStateOf(GimbalController.State.DISCONNECTED) }
                val gimbalName by remember { gimbal?.deviceName ?: mutableStateOf(null) }
                val currentTrackedIdx by remember { trackedIdx }
                var backCameras by remember { mutableStateOf<List<CameraOption>>(emptyList()) }
                var isRecording by remember { mutableStateOf(false) }

                if (showDialog) {
                    ExitConfirmationDialog(
                        onConfirm = {
                            showDialog = false
                            stopDetection()
                            if (streamServer?.isRecording == true) streamServer?.stopRecording()
                            streamServer?.stopStreaming()
                            finish()
                        },
                        onDismiss = { showDialog = false },
                    )
                }

                StreamingScreen(
                    onOpenGlViewCreated = { view ->
                        openGlView = view
                        val server = RtspStreamServer(this@MainActivity, view)
                        streamServer = server
                        backCameras = server.getBackCameras()
                    },
                    onSurfaceReady = {
                        if (hasPermissions()) streamServer?.startPreview()
                    },
                    onStartStream = { onResult ->
                        if (!hasPermissions()) {
                            permissionLauncher.launch(requiredPermissions)
                            onResult(false, "")
                            return@StreamingScreen
                        }
                        val server = streamServer ?: run {
                            onResult(false, "")
                            return@StreamingScreen
                        }
                        val success = server.start()
                        isCurrentlyStreaming = success
                        onResult(success, if (success) server.getStreamUrl() else "")
                    },
                    onStopStream = {
                        stopDetection()
                        streamServer?.stopStreaming()
                        isCurrentlyStreaming = false
                    },
                    onSwitchCamera = { streamServer?.switchCamera() },
                    onClientCountListener = { listener ->
                        streamServer?.onClientCountChanged = listener
                    },
                    onZoomChanged = { level -> streamServer?.setZoom(level) },
                    onPinchZoom = { event -> streamServer?.handleZoomEvent(event) },
                    getZoomRange = { streamServer?.getZoomRange() ?: Range(1f, 1f) },
                    getZoom = { streamServer?.getZoom() ?: 1f },
                    onToggleOrientation = {
                        requestedOrientation = when (requestedOrientation) {
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        }
                    },
                    detections = currentDetections,
                    isDetecting = detecting,
                    isDetectorAvailable = yoloDetector != null,
                    onToggleDetection = { toggleDetection() },
                    backCameras = backCameras,
                    onSelectBackCamera = { id -> streamServer?.switchCamera(id) },
                    onToggleRecording = onToggleRecording@{
                        val server = streamServer ?: return@onToggleRecording false
                        if (server.isRecording) {
                            server.stopRecording()
                            isRecording = false
                            Toast.makeText(this@MainActivity, "Recording saved", Toast.LENGTH_SHORT).show()
                            false
                        } else {
                            val path = server.startRecording()
                            isRecording = path != null
                            if (path != null) {
                                Toast.makeText(this@MainActivity, "Recording to ${path.substringAfterLast('/')}", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@MainActivity, "Failed to start recording", Toast.LENGTH_SHORT).show()
                            }
                            isRecording
                        }
                    },
                    isRecording = isRecording,
                    gimbalState = gimbalState,
                    gimbalDeviceName = gimbalName,
                    onToggleGimbal = { toggleGimbal() },
                    onTiltStart = { dir -> gimbalController?.startTilt(dir) },
                    onTiltStop = { gimbalController?.stopTilt() },
                    trackedIndex = currentTrackedIdx,
                    onDetectionTapped = if (detecting) { det -> onDetectionTapped(det) } else null,
                    isTracking = currentTrackedIdx >= 0,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasPermissions()) streamServer?.startPreview()
    }

    override fun onPause() {
        if (streamServer?.isStreaming != true && streamServer?.isRecording != true) {
            streamServer?.stopPreview()
        }
        super.onPause()
    }

    override fun onDestroy() {
        stopDetection()
        gimbalController?.disconnect()
        if (streamServer?.isRecording == true) streamServer?.stopRecording()
        streamServer?.stopStreaming()
        yoloDetector?.close()
        super.onDestroy()
    }

    private fun toggleDetection() {
        if (isDetecting.value) {
            stopDetection()
        } else {
            startDetection()
        }
    }

    private fun startDetection() {
        val view = openGlView ?: return
        val detector = yoloDetector ?: return

        isDetecting.value = true

        detectionJob = lifecycleScope.launch {
            var bitmap: Bitmap? = null
            try {
                while (isActive) {
                    if (view.width > 0 && view.height > 0) {
                        if (bitmap == null || bitmap.width != view.width || bitmap.height != view.height) {
                            bitmap?.recycle()
                            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        }
                        val captured = captureFrame(view, bitmap)
                        if (captured) {
                            val bmp = bitmap
                            val results = withContext(Dispatchers.Default) {
                                detector.detect(bmp)
                            }
                            detections.value = results
                            updateTracking(results)
                        }
                    }
                    delay(100)
                }
            } finally {
                bitmap?.recycle()
            }
        }
    }

    private fun stopDetection() {
        stopTracking()
        detectionJob?.cancel()
        detectionJob = null
        isDetecting.value = false
        detections.value = emptyList()
    }

    private fun toggleGimbal() {
        val controller = gimbalController ?: return
        Log.d("MainActivity", "toggleGimbal: state=${controller.state.value}")
        when (controller.state.value) {
            GimbalController.State.DISCONNECTED -> {
                if (hasBlePermissions()) {
                    controller.startScan(lifecycleScope)
                } else {
                    blePermissionLauncher.launch(blePermissions)
                }
            }
            GimbalController.State.SCANNING -> controller.stopScan()
            GimbalController.State.CONNECTING -> controller.disconnect()
            GimbalController.State.CONNECTED -> {
                stopTracking()
                controller.recenter()
            }
        }
    }

    private fun hasBlePermissions(): Boolean = blePermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateTracking(results: List<Detection>) {
        if (trackingClassId < 0) {
            trackedIdx.intValue = -1
            return
        }

        val gimbal = gimbalController
        var bestIdx = -1
        var bestDist = Float.MAX_VALUE

        for ((i, det) in results.withIndex()) {
            if (det.classId != trackingClassId) continue
            val cx = (det.boundingBox.left + det.boundingBox.right) / 2
            val cy = (det.boundingBox.top + det.boundingBox.bottom) / 2
            val dx = cx - trackingCx
            val dy = cy - trackingCy
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }

        if (bestIdx >= 0 && bestDist < 0.09f) {
            val det = results[bestIdx]
            val box = det.boundingBox
            val cx = (box.left + box.right) / 2
            val cy = (box.top + box.bottom) / 2
            trackingCx = cx
            trackingCy = cy
            trackedIdx.intValue = bestIdx
            lastSeenMs = System.currentTimeMillis()

            if (gimbal?.state?.value == GimbalController.State.CONNECTED) {
                gimbal.updateTrackTarget(cx, cy, box.width(), box.height())
            }
        } else {
            trackedIdx.intValue = -1
            if (lastSeenMs > 0 && System.currentTimeMillis() - lastSeenMs > 2000) {
                Log.i("MainActivity", "Target lost for >2s, stopping ActiveTrack")
                stopTracking()
            }
        }
    }

    private fun onDetectionTapped(detection: Detection) {
        val box = detection.boundingBox
        val cx = (box.left + box.right) / 2
        val cy = (box.top + box.bottom) / 2

        if (trackingClassId == detection.classId && trackedIdx.intValue >= 0) {
            val dx = cx - trackingCx
            val dy = cy - trackingCy
            if (dx * dx + dy * dy < 0.04f) {
                stopTracking()
                return
            }
        }

        trackingClassId = detection.classId
        trackingCx = cx
        trackingCy = cy
        lastSeenMs = System.currentTimeMillis()

        val gimbal = gimbalController
        if (gimbal?.state?.value == GimbalController.State.CONNECTED) {
            gimbal.startActiveTrack(cx, cy, box.width(), box.height())
        }
    }

    private fun stopTracking() {
        trackingClassId = -1
        trackedIdx.intValue = -1
        lastSeenMs = 0L
        gimbalController?.stopActiveTrack()
    }

    private suspend fun captureFrame(view: OpenGlView, dest: Bitmap): Boolean {
        return suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(view, dest, { result ->
                    cont.resume(result == PixelCopy.SUCCESS)
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                Log.w("MainActivity", "PixelCopy failed", e)
                cont.resume(false)
            }
        }
    }

    private fun hasPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
}

@Composable
fun ExitConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop streaming?") },
        text = { Text("A stream is currently active. Leaving will stop the stream and disconnect all clients.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Stop & Exit", color = Color(0xFFEF5350))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Continue Streaming")
            }
        },
    )
}

@Composable
fun StreamCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF4FC3F7),
            onPrimary = Color.Black,
            surface = Color(0xFF121212),
            onSurface = Color.White,
            background = Color(0xFF0A0A0A),
            onBackground = Color.White,
            error = Color(0xFFEF5350),
        ),
        content = content,
    )
}

@Composable
fun StreamingScreen(
    onOpenGlViewCreated: (OpenGlView) -> Unit,
    onStartStream: (onResult: (Boolean, String) -> Unit) -> Unit,
    onStopStream: () -> Unit,
    onSwitchCamera: () -> Unit,
    onClientCountListener: ((Int) -> Unit) -> Unit,
    onZoomChanged: (Float) -> Unit,
    onPinchZoom: (MotionEvent) -> Unit,
    getZoomRange: () -> Range<Float>,
    getZoom: () -> Float,
    onToggleOrientation: () -> Unit,
    onSurfaceReady: () -> Unit = {},
    detections: List<Detection>,
    isDetecting: Boolean,
    isDetectorAvailable: Boolean,
    onToggleDetection: () -> Unit,
    backCameras: List<CameraOption> = emptyList(),
    onSelectBackCamera: (String) -> Unit = {},
    onToggleRecording: () -> Boolean = { false },
    isRecording: Boolean = false,
    gimbalState: GimbalController.State = GimbalController.State.DISCONNECTED,
    gimbalDeviceName: String? = null,
    onToggleGimbal: () -> Unit = {},
    onTiltStart: (Int) -> Unit = {},
    onTiltStop: () -> Unit = {},
    trackedIndex: Int = -1,
    onDetectionTapped: ((Detection) -> Unit)? = null,
    isTracking: Boolean = false,
) {
    val context = LocalContext.current
    var isStreaming by remember { mutableStateOf(false) }
    var streamUrl by remember { mutableStateOf("") }
    var clientCount by remember { mutableIntStateOf(0) }
    var zoomLevel by remember { mutableFloatStateOf(1f) }
    var zoomRange by remember { mutableStateOf(Range(1f, 10f)) }

    LaunchedEffect(Unit) {
        onClientCountListener { count -> clientCount = count }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                OpenGlView(ctx).apply {
                    setOnTouchListener { _, event ->
                        if (event.pointerCount > 1) {
                            onPinchZoom(event)
                            zoomLevel = getZoom()
                            true
                        } else {
                            false
                        }
                    }
                    holder.addCallback(object : android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                            onSurfaceReady()
                        }
                        override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
                    })
                }.also {
                    onOpenGlViewCreated(it)
                    zoomRange = getZoomRange()
                    zoomLevel = getZoom()
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (isDetecting && detections.isNotEmpty()) {
            DetectionOverlay(
                detections = detections,
                trackedIndex = trackedIndex,
                onDetectionTapped = onDetectionTapped,
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (isStreaming) {
                StreamInfoBar(
                    streamUrl, clientCount, context, isDetecting, detections.size,
                    gimbalConnected = gimbalState == GimbalController.State.CONNECTED,
                    gimbalName = gimbalDeviceName,
                    isTracking = isTracking,
                )
            } else {
                Spacer(Modifier.height(1.dp))
            }

            Column {
                ZoomBar(
                    zoomLevel = zoomLevel,
                    zoomRange = zoomRange,
                    onZoomChanged = { level ->
                        zoomLevel = level
                        onZoomChanged(level)
                    },
                )

                Spacer(Modifier.height(8.dp))

                ControlBar(
                    isStreaming = isStreaming,
                    onToggleStream = {
                        if (isStreaming) {
                            onStopStream()
                            isStreaming = false
                            streamUrl = ""
                        } else {
                            onStartStream { success, url ->
                                isStreaming = success
                                streamUrl = url
                                if (!success) {
                                    Toast.makeText(context, "Failed to start stream", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    onSwitchCamera = {
                        onSwitchCamera()
                        zoomLevel = 1f
                    },
                    onToggleOrientation = onToggleOrientation,
                    isDetecting = isDetecting,
                    isDetectorAvailable = isDetectorAvailable && isStreaming,
                    onToggleDetection = onToggleDetection,
                    backCameras = backCameras,
                    onSelectBackCamera = onSelectBackCamera,
                    onToggleRecording = onToggleRecording,
                    isRecording = isRecording,
                    gimbalState = gimbalState,
                    onToggleGimbal = onToggleGimbal,
                    onTiltStart = onTiltStart,
                    onTiltStop = onTiltStop,
                )
            }
        }
    }
}

@Composable
fun ZoomBar(
    zoomLevel: Float,
    zoomRange: Range<Float>,
    onZoomChanged: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = String.format("%.1fx", zoomLevel),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(48.dp),
        )
        Slider(
            value = zoomLevel,
            onValueChange = onZoomChanged,
            valueRange = zoomRange.lower..zoomRange.upper,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF4FC3F7),
                activeTrackColor = Color(0xFF4FC3F7),
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
        )
    }
}

@Composable
fun StreamInfoBar(
    streamUrl: String,
    clientCount: Int,
    context: Context,
    isDetecting: Boolean,
    detectionCount: Int,
    gimbalConnected: Boolean = false,
    gimbalName: String? = null,
    isTracking: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color.Red),
            )
            Spacer(Modifier.width(8.dp))
            Text("LIVE", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.width(16.dp))
            Text(
                text = clientCount.toString() + if (clientCount != 1) " clients connected" else " client connected",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            if (SHOW_GIMBAL_UI) {
                if (isTracking) {
                    Text(
                        text = "TRACKING",
                        color = Color(0xFFEF5350),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                if (gimbalConnected) {
                    Text(
                        text = gimbalName ?: "Gimbal",
                        color = Color(0xFF66BB6A),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(12.dp))
                }
            }
            if (isDetecting) {
                Text(
                    text = "$detectionCount detected",
                    color = Color(0xFFFFB74D),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text("Stream URL (tap to copy):", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            text = streamUrl,
            color = Color(0xFF4FC3F7),
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Stream URL", streamUrl))
                Toast.makeText(context, "URL copied!", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
fun TiltHoldButton(
    label: String,
    direction: Int,
    onTiltStart: (Int) -> Unit,
    onTiltStop: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    LaunchedEffect(pressed) {
        if (pressed) onTiltStart(direction) else onTiltStop()
    }
    Button(
        onClick = {},
        interactionSource = source,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (pressed) Color(0xFFFFB74D) else Color.White.copy(alpha = 0.2f),
        ),
        shape = CircleShape,
        contentPadding = PaddingValues(16.dp),
    ) {
        Text(label, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
fun ControlBar(
    isStreaming: Boolean,
    onToggleStream: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleOrientation: () -> Unit,
    isDetecting: Boolean,
    isDetectorAvailable: Boolean,
    onToggleDetection: () -> Unit,
    backCameras: List<CameraOption> = emptyList(),
    onSelectBackCamera: (String) -> Unit = {},
    onToggleRecording: () -> Boolean = { false },
    isRecording: Boolean = false,
    gimbalState: GimbalController.State = GimbalController.State.DISCONNECTED,
    onToggleGimbal: () -> Unit = {},
    onTiltStart: (Int) -> Unit = {},
    onTiltStop: () -> Unit = {},
) {
    var lensIndex by remember { mutableIntStateOf(0) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onSwitchCamera,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f)),
            shape = CircleShape,
            contentPadding = PaddingValues(16.dp),
        ) {
            Text("Flip", color = Color.White, fontSize = 14.sp)
        }

        if (backCameras.size >= 2) {
            Button(
                onClick = {
                    lensIndex = (lensIndex + 1) % backCameras.size
                    onSelectBackCamera(backCameras[lensIndex].id)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f)),
                shape = CircleShape,
                contentPadding = PaddingValues(16.dp),
            ) {
                Text(backCameras[lensIndex].label, color = Color.White, fontSize = 13.sp)
            }
        }

        Button(
            onClick = { onToggleRecording() },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) Color(0xFFEF5350) else Color.White.copy(alpha = 0.2f),
            ),
            shape = CircleShape,
            contentPadding = PaddingValues(16.dp),
        ) {
            Text(if (isRecording) "Stop Rec" else "Rec", color = Color.White, fontSize = 12.sp)
        }

        if (isDetectorAvailable) {
            Button(
                onClick = onToggleDetection,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDetecting) Color(0xFFFFB74D) else Color.White.copy(alpha = 0.2f),
                ),
                shape = CircleShape,
                contentPadding = PaddingValues(16.dp),
            ) {
                Text(
                    "Detect",
                    color = if (isDetecting) Color.Black else Color.White,
                    fontSize = 12.sp,
                )
            }
        }

        Button(
            onClick = onToggleStream,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isStreaming) Color(0xFFEF5350) else Color(0xFF4FC3F7),
            ),
            shape = RoundedCornerShape(28.dp),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
        ) {
            Text(
                text = if (isStreaming) "Stop Stream" else "Start Stream",
                color = if (isStreaming) Color.White else Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }

        Button(
            onClick = onToggleOrientation,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f)),
            shape = CircleShape,
            contentPadding = PaddingValues(16.dp),
        ) {
            Text("Rotate", color = Color.White, fontSize = 12.sp)
        }

        if (SHOW_GIMBAL_UI) {
            Button(
                onClick = onToggleGimbal,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (gimbalState) {
                        GimbalController.State.DISCONNECTED -> Color.White.copy(alpha = 0.2f)
                        GimbalController.State.SCANNING,
                        GimbalController.State.CONNECTING -> Color(0xFFFFEB3B)
                        GimbalController.State.CONNECTED -> Color(0xFF66BB6A)
                    },
                ),
                shape = CircleShape,
                contentPadding = PaddingValues(16.dp),
            ) {
                Text(
                    text = when (gimbalState) {
                        GimbalController.State.DISCONNECTED -> "Gimbal"
                        GimbalController.State.SCANNING -> "Scan..."
                        GimbalController.State.CONNECTING -> "..."
                        GimbalController.State.CONNECTED -> "Center"
                    },
                    color = when (gimbalState) {
                        GimbalController.State.SCANNING,
                        GimbalController.State.CONNECTING -> Color.Black
                        else -> Color.White
                    },
                    fontSize = 12.sp,
                )
            }

            if (gimbalState == GimbalController.State.CONNECTED) {
                TiltHoldButton("Up", 1, onTiltStart, onTiltStop)
                TiltHoldButton("Down", -1, onTiltStart, onTiltStop)
            }
        }
    }
}
