package com.streamcam.app

import android.util.Log
import android.util.Range
import android.view.MotionEvent
import com.pedro.common.ConnectChecker
import com.pedro.library.view.OpenGlView
import com.pedro.rtspserver.RtspServerCamera2
import java.net.Inet4Address
import java.net.NetworkInterface

class RtspStreamServer(
    openGlView: OpenGlView,
    private val port: Int = DEFAULT_PORT,
) : ConnectChecker {

    companion object {
        private const val TAG = "RtspStreamServer"
        const val DEFAULT_PORT = 8554
    }

    private val rtspCamera = RtspServerCamera2(openGlView, this, port)
    private var connectedClients = 0

    var onClientCountChanged: ((Int) -> Unit)? = null

    val isStreaming: Boolean
        get() = rtspCamera.isStreaming

    fun start(
        width: Int = 1920,
        height: Int = 1080,
        fps: Int = 30,
        videoBitrate: Int = 4_000_000,
        audioBitrate: Int = 128_000,
        audioSampleRate: Int = 44100,
    ): Boolean {
        return try {
            val videoReady = rtspCamera.prepareVideo(width, height, fps, videoBitrate, 0)
            val audioReady = rtspCamera.prepareAudio(audioBitrate, audioSampleRate, true)

            if (!videoReady || !audioReady) {
                Log.e(TAG, "Failed to prepare: video=$videoReady, audio=$audioReady")
                return false
            }

            rtspCamera.startStream("")
            Log.i(TAG, "RTSP server started on port $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTSP server", e)
            false
        }
    }

    fun stop() {
        try {
            rtspCamera.stopStream()
            connectedClients = 0
            onClientCountChanged?.invoke(0)
            Log.i(TAG, "RTSP server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping RTSP server", e)
        }
    }

    fun switchCamera() {
        rtspCamera.switchCamera()
    }

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
}
