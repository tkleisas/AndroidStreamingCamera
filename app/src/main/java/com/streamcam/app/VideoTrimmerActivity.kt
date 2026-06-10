package com.streamcam.app

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileOutputStream

class VideoTrimmerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "VideoTrimmer"
    }

    private var player: ExoPlayer? = null
    private var resolvedVideoPath: String? = null
    private var videoUri: Uri? = null
    private var videoName: String? = null
    private var loadError: String? = null
    var onTrimProgress: ((Int) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        videoUri = intent.getStringExtra("video_uri")?.let { Uri.parse(it) }
        videoName = intent.getStringExtra("video_name")

        // Take persistable permission if this URI came from a file picker
        videoUri?.let { uri ->
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {}
        }

        resolveVideoPath { path, error ->
            resolvedVideoPath = path
            loadError = error
            runOnUiThread {
                setContent {
                    StreamCamTheme {
                        VideoTrimmerScreen(
                            videoUri = videoUri,
                            resolvedPath = resolvedVideoPath,
                            videoName = videoName,
                            loadError = loadError,
                            onBack = { finish() },
                            onGetPlayer = { player = it },
                            markersFile = videoName?.let { name ->
                                File(filesDir, "markers/$name.markers.json").takeIf { it.exists() }
                            },
                            onTrim = { pointA, pointB, onResult ->
                                startTrim(pointA, pointB, onResult)
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        super.onDestroy()
    }

    private fun resolveVideoPath(onReady: (String?, String?) -> Unit) {
        Thread {
            try {
                val path: String? = when {
                    videoUri != null -> resolveFromUri(videoUri!!)
                    videoName != null -> {
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
                        val file = File(dir, "StreamCam/$videoName")
                        if (file.exists()) file.absolutePath else null
                    }
                    else -> null
                }
                onReady(path, null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve video path", e)
                onReady(null, e.message ?: "Unknown error")
            }
        }.start()
    }

    private fun resolveFromUri(uri: Uri): String? {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val tempFile = File(cacheDir, "trim_input_${System.currentTimeMillis()}.mp4")
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Copied to temp: ${tempFile.absolutePath} (${tempFile.length()} bytes)")
            return tempFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve URI: ${e.message}", e)
            return null
        }
    }

    private fun startTrim(pointA: Float, pointB: Float, onResult: (Boolean, String?) -> Unit) {
        val path = resolvedVideoPath
        if (path == null) {
            Log.e(TAG, "startTrim: resolvedVideoPath is null")
            onResult(false, "No video source")
            return
        }
        val inputFile = File(path)
        Log.i(TAG, "startTrim: input=${inputFile.absolutePath} exists=${inputFile.exists()} size=${inputFile.length()}")

        if (!inputFile.exists() || inputFile.length() == 0L) {
            Log.e(TAG, "startTrim: input file missing or empty")
            onResult(false, "Video file not found or empty")
            return
        }

        val inputName = videoName ?: "trimmed.mp4"
        val outputName = inputName.replace(".mp4", "_trimmed.mp4")
        val isReversed = pointB < pointA
        val start = pointA.coerceAtMost(pointB)
        val end = pointA.coerceAtLeast(pointB)
        val tempOutput = File(cacheDir, "trimmed_$outputName").absolutePath

        Log.i(TAG, "========================================")
        Log.i(TAG, "Trim START - A=$pointA B=$pointB reversed=$isReversed")
        Log.i(TAG, "Input:  $path (${inputFile.length()} bytes)")
        Log.i(TAG, "Output: $tempOutput")
        Log.i(TAG, "Segment: ${start}s - ${end}s")
        Log.i(TAG, "========================================")

        Thread {
            val resultPath = if (isReversed) {
                VideoTrimmer.trimReverse(path, tempOutput, start, end) { p ->
                    runOnUiThread { onTrimProgress?.invoke(p) }
                }
            } else {
                VideoTrimmer.trim(path, tempOutput, start, end) { p ->
                    runOnUiThread { onTrimProgress?.invoke(p) }
                }
            }

            runOnUiThread {
                if (resultPath != null) {
                    val savedName = saveOutput(tempOutput, outputName)
                    try { File(tempOutput).delete() } catch (_: Exception) {}
                    onTrimProgress?.invoke(100)
                    onResult(true, savedName)
                    Toast.makeText(this, "Saved: $savedName", Toast.LENGTH_LONG).show()
                } else {
                    onTrimProgress?.invoke(-1)
                    onResult(false, "Trim failed - check logcat for details")
                }
            }
        }.start()
    }

    private fun saveOutput(tempPath: String, outputName: String): String {
        val tempFile = File(tempPath)
        if (!tempFile.exists()) return outputName

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, outputName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/StreamCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                val pfd = contentResolver.openFileDescriptor(uri, "w")
                if (pfd != null) {
                    try {
                        tempFile.inputStream().use { input ->
                            FileOutputStream(pfd.fileDescriptor).use { output ->
                                input.copyTo(output)
                            }
                        }
                        pfd.close()
                        val updateValues = ContentValues().apply {
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                        }
                        contentResolver.update(uri, updateValues, null, null)
                        return outputName
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to write to MediaStore", e)
                        try { pfd.close() } catch (_: Exception) {}
                        contentResolver.delete(uri, null, null)
                    }
                } else {
                    contentResolver.delete(uri, null, null)
                }
            }
        } else {
            try {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "StreamCam",
                )
                if (!dir.exists()) dir.mkdirs()
                val dest = File(dir, outputName)
                tempFile.copyTo(dest, overwrite = true)
                return outputName
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save to DCIM", e)
            }
        }
        return outputName
    }

    private fun runOnUiThread(action: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(action)
        }
    }
}

@Composable
fun VideoTrimmerScreen(
    videoUri: Uri?,
    resolvedPath: String?,
    videoName: String?,
    loadError: String? = null,
    onBack: () -> Unit,
    onGetPlayer: (ExoPlayer) -> Unit,
    markersFile: File? = null,
    onTrim: (Float, Float, (Boolean, String?) -> Unit) -> Unit,
) {
    val context = LocalContext.current

    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableFloatStateOf(0f) }
    var pointA by remember { mutableFloatStateOf(-1f) }
    var pointB by remember { mutableFloatStateOf(-1f) }
    var isTrimming by remember { mutableStateOf(false) }
    var trimComplete by remember { mutableStateOf(false) }
    var trimError by remember { mutableStateOf<String?>(null) }
    var trimProgress by remember { mutableIntStateOf(-1) }
    var markers by remember { mutableStateOf<List<Float>>(emptyList()) }
    var isPlayerReady by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }

    val player = remember {
        try {
            ExoPlayer.Builder(context).build().apply {
                playWhenReady = false
                val item = when {
                    videoUri != null -> MediaItem.fromUri(videoUri)
                    resolvedPath != null -> MediaItem.fromUri(Uri.fromFile(File(resolvedPath)))
                    else -> null
                }
                if (item != null) {
                    setMediaItem(item)
                    prepare()
                }
                onGetPlayer(this)
            }
        } catch (e: Exception) {
            Log.e("VideoTrimmer", "Failed to create player", e)
            playerError = e.message
            onGetPlayer(ExoPlayer.Builder(context).build())
            ExoPlayer.Builder(context).build().also { onGetPlayer(it) }
        }
    }

    LaunchedEffect(Unit) {
        markersFile?.let { file ->
            try {
                val content = file.readText()
                val markerPattern = Regex("""markers":\[([^\]]+)\]""")
                val match = markerPattern.find(content)
                if (match != null) {
                    markers = match.groupValues[1]
                        .split(",")
                        .mapNotNull { it.trim().toFloatOrNull() }
                }
            } catch (e: Exception) {
                Log.e("VideoTrimmer", "Failed to load markers", e)
            }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                isPlaying = isPlayingNow
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    duration = (player.duration.coerceAtLeast(0) / 1000f)
                    isPlayerReady = true
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playerError = error.message
                Log.e("VideoTrimmer", "Player error: ${error.message}")
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = (player.currentPosition.coerceAtLeast(0) / 1000f)
            kotlinx.coroutines.delay(50)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.9f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("Back", color = Color.White, fontSize = 14.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = videoName ?: "Trim Video",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Player or loading/error
            if (loadError != null) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Error loading video: $loadError", color = Color(0xFFEF5350), fontSize = 14.sp)
                }
            } else if (playerError != null) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Playback error: $playerError", color = Color(0xFFEF5350), fontSize = 14.sp)
                }
            } else if (videoUri == null && resolvedPath == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Color(0xFF4FC3F7))
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }

            // Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A))
                    .padding(16.dp),
            ) {
                // Markers row
                if (markers.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Markers:", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                        markers.forEach { markerTime ->
                            Text(
                                text = formatTime(markerTime),
                                color = Color(0xFFFFB74D),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .clickable {
                                        player.seekTo((markerTime * 1000).toLong())
                                        currentPosition = markerTime
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Seekbar with A/B labels
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "A: ${if (pointA >= 0) formatTime(pointA) else "--"}",
                        color = Color(0xFF4FC3F7),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(70.dp),
                    )
                    Slider(
                        value = currentPosition.coerceIn(0f, duration.coerceAtLeast(0.1f)),
                        onValueChange = { value ->
                            player.seekTo((value * 1000).toLong())
                            currentPosition = value
                        },
                        valueRange = 0f..duration.coerceAtLeast(0.1f),
                        modifier = Modifier.weight(1f),
                        enabled = isPlayerReady,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF4FC3F7),
                            activeTrackColor = Color(0xFF4FC3F7),
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                    )
                    Text(
                        text = "B: ${if (pointB >= 0) formatTime(pointB) else "--"}",
                        color = Color(0xFFEF5350),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(70.dp),
                    )
                }

                // Time display
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${formatTime(currentPosition)} / ${formatTime(duration)}",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    if (pointA >= 0 && pointB >= 0) {
                        val segDuration = kotlin.math.abs(pointB - pointA)
                        Text(
                            text = "Selection: ${formatTime(segDuration)}",
                            color = Color(0xFFFFB74D),
                            fontSize = 12.sp,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Buttons row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Button(
                        onClick = {
                            try { if (isPlaying) player.pause() else player.play() } catch (_: Exception) {}
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4FC3F7)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        Text(
                            if (isPlaying) "Pause" else "Play",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }

                    Button(
                        onClick = { pointA = currentPosition },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4FC3F7),
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        enabled = isPlayerReady,
                    ) {
                        Text("Set A", color = Color.White, fontSize = 14.sp)
                    }

                    Button(
                        onClick = { pointB = currentPosition },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEF5350),
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        enabled = isPlayerReady,
                    ) {
                        Text("Set B", color = Color.White, fontSize = 14.sp)
                    }

                    Button(
                        onClick = {
                            if (pointA < 0 || pointB < 0) return@Button
                            isTrimming = true
                            trimComplete = false
                            trimError = null
                            trimProgress = 0
                            (context as? VideoTrimmerActivity)?.onTrimProgress = { p -> trimProgress = p }
                            onTrim(pointA, pointB) { success, error ->
                                isTrimming = false
                                if (success) {
                                    trimProgress = 100
                                    trimComplete = true
                                } else {
                                    trimProgress = -1
                                    trimError = error
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (pointA >= 0 && pointB >= 0 && !isTrimming)
                                Color(0xFF66BB6A) else Color.Gray,
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        enabled = pointA >= 0 && pointB >= 0 && !isTrimming && isPlayerReady,
                    ) {
                        Text(
                            if (pointB < pointA && pointA >= 0 && pointB >= 0) "Reverse" else "Trim",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                }

                if (isTrimming) {
                    Spacer(Modifier.height(8.dp))
                    if (trimProgress > 0) {
                        LinearProgressIndicator(
                            progress = { trimProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF4FC3F7),
                            trackColor = Color.White.copy(alpha = 0.15f),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF4FC3F7),
                        )
                    }
                    Text(
                        if (trimProgress > 0) "Processing... $trimProgress%" else "Processing...",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                    )
                }

                if (trimComplete) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Trim complete!",
                        color = Color(0xFF66BB6A),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                trimError?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text("Error: $error", color = Color(0xFFEF5350), fontSize = 12.sp)
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

private fun formatTime(seconds: Float): String {
    val totalSecs = seconds.toInt()
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    val frac = ((seconds - totalSecs) * 10).toInt()
    return "%d:%02d.%d".format(mins, secs, frac)
}
