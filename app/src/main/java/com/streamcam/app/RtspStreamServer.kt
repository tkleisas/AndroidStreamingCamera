package com.streamcam.app

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.MotionEvent
import com.pedro.common.ConnectChecker
import com.pedro.library.view.OpenGlView
import com.pedro.rtspserver.RtspServerCamera2
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CameraOption(val id: String, val label: String, val focalLength: Float)

class RtspStreamServer(
    private val context: Context,
    openGlView: OpenGlView,
    private val port: Int = DEFAULT_PORT,
) : ConnectChecker {

    companion object {
        private const val TAG = "RtspStreamServer"
        const val DEFAULT_PORT = 8554
    }

    private val rtspCamera = RtspServerCamera2(openGlView, this, port)
    private var connectedClients = 0
    private var isPrepared = false
    private var currentRecordingUri: Uri? = null
    private var currentRecordingPfd: ParcelFileDescriptor? = null
    private var recordingStartTimeMs: Long = 0L
    private var currentRecordingName: String? = null

    var onClientCountChanged: ((Int) -> Unit)? = null
    var onMarkerAdded: ((Float) -> Unit)? = null

    val isStreaming: Boolean
        get() = rtspCamera.isStreaming

    val markers = mutableListOf<Float>()

    fun addMarker(): Float {
        if (!isRecording) return -1f
        val elapsed = (System.currentTimeMillis() - recordingStartTimeMs) / 1000f
        markers.add(elapsed)
        onMarkerAdded?.invoke(elapsed)
        Log.i(TAG, "Marker added at ${elapsed}s (total: ${markers.size})")
        return elapsed
    }

    fun getLastRecordingUri(): Uri? = currentRecordingUri

    private fun prepare(): Boolean {
        if (isPrepared) return true
        val videoReady = rtspCamera.prepareVideo(1920, 1080, 30, 4_000_000, 0)
        val audioReady = rtspCamera.prepareAudio(128_000, 44100, true)
        if (!videoReady || !audioReady) {
            Log.e(TAG, "Failed to prepare: video=$videoReady audio=$audioReady")
            return false
        }
        isPrepared = true
        return true
    }

    fun startPreview() {
        try {
            if (!rtspCamera.isOnPreview) rtspCamera.startPreview()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start preview", e)
        }
    }

    fun stopPreview() {
        try {
            if (rtspCamera.isOnPreview) rtspCamera.stopPreview()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop preview", e)
        }
    }

    fun startStreaming(): Boolean {
        return try {
            if (!prepare()) return false
            if (!rtspCamera.isStreaming) {
                rtspCamera.startStream("")
                Log.i(TAG, "RTSP server started on port $port")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming", e)
            false
        }
    }

    fun stopStreaming() {
        try {
            if (rtspCamera.isStreaming) {
                rtspCamera.stopStream()
                connectedClients = 0
                onClientCountChanged?.invoke(0)
                Log.i(TAG, "RTSP server stopped")
            }
            // If nothing else is using the encoder, force a re-prepare on next start
            // so a fresh restart doesn't fail with "encoder still busy"
            if (!rtspCamera.isRecording) {
                isPrepared = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping streaming", e)
        }
    }

    // Backwards-compat wrappers
    fun start(): Boolean = startStreaming()
    fun stop() = stopStreaming()

    fun switchCamera() {
        rtspCamera.switchCamera()
    }

    fun switchCamera(cameraId: String) {
        try {
            rtspCamera.switchCamera(cameraId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch to camera $cameraId", e)
        }
    }

    fun getBackCameras(): List<CameraOption> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
        val ids = cm.cameraIdList
        Log.i(TAG, "All camera IDs reported: ${ids.toList()}")
        val out = mutableListOf<CameraOption>()
        for (id in ids) {
            try {
                val ch = cm.getCameraCharacteristics(id)
                val facing = ch.get(CameraCharacteristics.LENS_FACING)
                val focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val capList = caps?.toList() ?: emptyList()
                val isBackwardCompat = capList.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)
                val isDepthOnly = capList.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT) && !isBackwardCompat
                val facingStr = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                    CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                    else -> "?"
                }
                val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try { ch.physicalCameraIds } catch (e: Exception) { emptySet() }
                } else emptySet()
                val zoomRatioRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                } else null
                val maxDigitalZoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                Log.i(
                    TAG,
                    "Camera $id: facing=$facingStr focals=${focals?.toList()} " +
                        "caps=$capList backwardCompat=$isBackwardCompat depthOnly=$isDepthOnly " +
                        "physicalSubCameras=$physicalIds zoomRatioRange=$zoomRatioRange " +
                        "maxDigitalZoom=$maxDigitalZoom",
                )
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
                if (isDepthOnly) continue
                val focal = focals?.firstOrNull() ?: 0f
                out.add(CameraOption(id, "f=${"%.1f".format(focal)}", focal))
            } catch (e: Exception) {
                Log.w(TAG, "Skipping camera $id: ${e.message}")
            }
        }
        out.sortBy { it.focalLength }
        // Compute zoom-style labels relative to the smallest focal length (0.5x, 1x, 2x, ...)
        val ref = out.firstOrNull { it.focalLength > 0 }?.focalLength
        val withLabels = if (ref != null) {
            out.map { it.copy(label = "${"%.1f".format(it.focalLength / ref)}x") }
        } else out
        Log.i(TAG, "Back cameras to expose: ${withLabels.map { it.id + "->" + it.label }}")
        return withLabels
    }

    fun startRecording(): String? {
        return try {
            if (!prepare()) return null
            recordingStartTimeMs = System.currentTimeMillis()
            markers.clear()
            val name = "streamcam-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.mp4"
            currentRecordingName = name
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/StreamCam")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                val pfd = resolver.openFileDescriptor(uri, "w") ?: run {
                    resolver.delete(uri, null, null); return null
                }
                currentRecordingUri = uri
                currentRecordingPfd = pfd
                rtspCamera.startRecord(pfd.fileDescriptor)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "StreamCam")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, name)
                rtspCamera.startRecord(file.absolutePath)
            }
            Log.i(TAG, "Recording started: $name")
            "DCIM/StreamCam/$name"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            null
        }
    }

    fun stopRecording() {
        try {
            if (rtspCamera.isRecording) rtspCamera.stopRecord()
            writeMarkersFile()
            currentRecordingPfd?.close()
            currentRecordingPfd = null
            currentRecordingUri?.let { uri ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    context.contentResolver.update(uri, values, null, null)
                }
                currentRecordingUri = null
            }
            if (!rtspCamera.isStreaming) {
                isPrepared = false
            }
            Log.i(TAG, "Recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop recording", e)
        }
    }

    val isRecording: Boolean get() = rtspCamera.isRecording

    fun getZoomRange(): Range<Float> = rtspCamera.zoomRange

    fun getZoom(): Float = rtspCamera.zoom

    fun setZoom(level: Float) {
        rtspCamera.zoom = level
    }

    fun handleZoomEvent(event: MotionEvent) {
        rtspCamera.setZoom(event)
    }

    fun getStreamUrl(): String {
        val ip = getDeviceIpAddress()
        return "rtsp://$ip:$port/"
    }

    override fun onConnectionStarted(url: String) {
        Log.i(TAG, "Client connecting: $url")
    }

    override fun onConnectionSuccess() {
        Log.i(TAG, "Client connected")
        connectedClients++
        onClientCountChanged?.invoke(connectedClients)
    }

    override fun onConnectionFailed(reason: String) {
        Log.w(TAG, "Client connection failed: $reason")
    }

    override fun onNewBitrate(bitrate: Long) {}

    override fun onDisconnect() {
        Log.i(TAG, "Client disconnected")
        connectedClients = (connectedClients - 1).coerceAtLeast(0)
        onClientCountChanged?.invoke(connectedClients)
    }

    override fun onAuthError() {
        Log.w(TAG, "Client auth error")
    }

    override fun onAuthSuccess() {
        Log.i(TAG, "Client auth success")
    }

    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IP address", e)
        }
        return "0.0.0.0"
    }

    private fun writeMarkersFile() {
        val name = currentRecordingName ?: return
        if (markers.isEmpty()) return
        try {
            val json = buildString {
                append("{\"video\":\"$name\",\"markers\":[")
                markers.forEachIndexed { i, t ->
                    if (i > 0) append(",")
                    append(String.format(Locale.US, "%.2f", t))
                }
                append("]}")
            }
            val dir = File(context.filesDir, "markers")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "$name.markers.json")
            file.writeText(json)
            Log.i(TAG, "Markers saved: ${markers.size} markers to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write markers file", e)
        }
    }
}
