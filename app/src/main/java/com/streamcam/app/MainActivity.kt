package com.streamcam.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Range
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.pedro.library.view.OpenGlView

class MainActivity : ComponentActivity() {

    private var streamServer: RtspStreamServer? = null
    private var isCurrentlyStreaming = false
    private var showExitDialog = mutableStateOf(false)

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (!permissions.values.all { it }) {
            Toast.makeText(this, "Camera and microphone permissions are required", Toast.LENGTH_LONG).show()
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

        setContent {
            StreamCamTheme {
                var showDialog by remember { showExitDialog }

                if (showDialog) {
                    ExitConfirmationDialog(
                        onConfirm = {
                            showDialog = false
                            streamServer?.stop()
                            finish()
                        },
                        onDismiss = { showDialog = false },
                    )
                }

                StreamingScreen(
                    onOpenGlViewCreated = { view ->
                        streamServer = RtspStreamServer(view)
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
                        streamServer?.stop()
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
                )
            }
        }
    }

    override fun onDestroy() {
        streamServer?.stop()
        super.onDestroy()
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
                }.also {
                    onOpenGlViewCreated(it)
                    zoomRange = getZoomRange()
                    zoomLevel = getZoom()
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (isStreaming) {
                StreamInfoBar(streamUrl, clientCount, context)
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
fun StreamInfoBar(streamUrl: String, clientCount: Int, context: Context) {
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
fun ControlBar(
    isStreaming: Boolean,
    onToggleStream: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleOrientation: () -> Unit,
) {
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
    }
}
