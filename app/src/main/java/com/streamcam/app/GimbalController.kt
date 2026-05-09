package com.streamcam.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class GimbalController(private val context: Context) {

    enum class State { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    val state = mutableStateOf(State.DISCONNECTED)
    val deviceName = mutableStateOf<String?>(null)

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var scanCb: ScanCallback? = null
    private var scanTimeout: Job? = null
    private var keepaliveJob: Job? = null
    private var trackJob: Job? = null
    private var imuJob: Job? = null
    private var scope: CoroutineScope? = null
    private val seenDevices = mutableSetOf<String>()

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    @Volatile private var imuQx = 0f
    @Volatile private var imuQy = 0f
    @Volatile private var imuQz = 0f
    @Volatile private var imuQw = 1f
    @Volatile private var imuAx = 0f
    @Volatile private var imuAy = 0f
    @Volatile private var imuAz = 9.81f
    @Volatile private var imuGx = 0f
    @Volatile private var imuGy = 0f
    @Volatile private var imuGz = 0f

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val q = FloatArray(4)
                    SensorManager.getQuaternionFromVector(q, event.values)
                    imuQw = q[0]; imuQx = q[1]; imuQy = q[2]; imuQz = q[3]
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    imuAx = event.values[0]; imuAy = event.values[1]; imuAz = event.values[2]
                }
                Sensor.TYPE_GYROSCOPE -> {
                    imuGx = event.values[0]; imuGy = event.values[1]; imuGz = event.values[2]
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    @Volatile private var trackingActive = false
    @Volatile private var targetX = 0.5f
    @Volatile private var targetY = 0.5f
    @Volatile private var targetW = 0.2f
    @Volatile private var targetH = 0.2f
    val isTracking get() = trackingActive

    val isAvailable: Boolean get() = adapter?.isEnabled == true

    companion object {
        private const val TAG = "GimbalController"
        private val SERVICE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        private val CHAR_WRITE = UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")
        private val CHAR_NOTIFY = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    @SuppressLint("MissingPermission")
    fun startScan(scope: CoroutineScope) {
        if (state.value != State.DISCONNECTED) return
        val scanner = adapter?.bluetoothLeScanner ?: return

        this.scope = scope
        state.value = State.SCANNING
        seenDevices.clear()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device?.name
                if (name != null && seenDevices.add(name)) {
                    Log.d(TAG, "BLE device: '$name' addr=${result.device.address}")
                }
                if (name != null && (
                    name.contains("OSMO", ignoreCase = true) ||
                    name.startsWith("OM", ignoreCase = true) ||
                    name.contains("DJI", ignoreCase = true)
                )) {
                    Log.i(TAG, "Found gimbal: $name")
                    stopScan()
                    connect(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan failed: $errorCode")
                state.value = State.DISCONNECTED
            }
        }

        scanCb = cb
        Log.i(TAG, "Starting BLE scan...")
        scanner.startScan(cb)

        scanTimeout = scope.launch {
            delay(15_000)
            if (state.value == State.SCANNING) {
                Log.i(TAG, "Scan timed out")
                stopScan()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCb?.let { cb ->
            try {
                adapter?.bluetoothLeScanner?.stopScan(cb)
            } catch (_: Exception) {
            }
        }
        scanCb = null
        scanTimeout?.cancel()
        scanTimeout = null
        if (state.value == State.SCANNING) {
            state.value = State.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        state.value = State.CONNECTING
        deviceName.value = device.name
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopImuStreaming()
        stopKeepalive()
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeChar = null
        state.value = State.DISCONNECTED
        deviceName.value = null
    }

    private fun startImuStreaming() {
        sensorManager?.let { sm ->
            sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
                sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
            }
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
            }
            sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        imuJob?.cancel()
        imuJob = scope?.launch {
            while (true) {
                delay(100)
                if (state.value != State.CONNECTED) continue
                send(DumlProtocol.imuStream(
                    imuQx, imuQy, imuQz, imuQw,
                    imuAx, imuAy, imuAz,
                    imuGx, imuGy, imuGz,
                ))
            }
        }
    }

    private fun stopImuStreaming() {
        imuJob?.cancel()
        imuJob = null
        sensorManager?.unregisterListener(sensorListener)
    }

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope?.launch {
            var tick = 0
            while (true) {
                delay(500)
                if (state.value != State.CONNECTED) continue
                send(DumlProtocol.heartbeatGimbal())
                if (tick % 3 == 0) send(DumlProtocol.heartbeatMain())
                tick++
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    fun setSpeed(pitchDps: Float, yawDps: Float) {
        send(DumlProtocol.speedCommand(pitchDps, 0f, yawDps))
    }

    fun recenter() {
        Log.i(TAG, "Sending recenter command")
        send(DumlProtocol.recenterCommand())
    }

    fun startActiveTrack(x: Float, y: Float, w: Float, h: Float) {
        Log.i(TAG, "startActiveTrack(x=$x, y=$y, w=$w, h=$h) state=${state.value} active=$trackingActive")
        if (state.value != State.CONNECTED) return
        targetX = x; targetY = y; targetW = w; targetH = h
        if (trackingActive) return
        trackingActive = true
        send(DumlProtocol.activeTrackEnable())
        send(DumlProtocol.activeTrackStart())
        trackJob?.cancel()
        trackJob = scope?.launch {
            while (trackingActive) {
                send(DumlProtocol.activeTrackBox(targetX, targetY, targetW, targetH))
                delay(100)
            }
        }
    }

    fun updateTrackTarget(x: Float, y: Float, w: Float, h: Float) {
        targetX = x; targetY = y; targetW = w; targetH = h
    }

    private var tiltJob: Job? = null

    fun startTilt(direction: Int) {
        if (state.value != State.CONNECTED) return
        Log.i(TAG, "startTilt direction=$direction")
        tiltJob?.cancel()
        tiltJob = scope?.launch {
            while (true) {
                send(DumlProtocol.joystickPitch(direction))
                delay(140)
            }
        }
    }

    fun stopTilt() {
        if (tiltJob == null) return
        Log.i(TAG, "stopTilt")
        tiltJob?.cancel()
        tiltJob = null
        send(DumlProtocol.joystickPitch(0))
    }

    fun stopActiveTrack() {
        if (!trackingActive) return
        Log.i(TAG, "Disabling ActiveTrack")
        trackingActive = false
        trackJob?.cancel()
        trackJob = null
        send(DumlProtocol.activeTrackStop())
        send(DumlProtocol.activeTrackDisable())
    }

    @SuppressLint("MissingPermission")
    private fun send(frame: ByteArray) {
        val g = gatt ?: run {
            Log.w(TAG, "Send failed: gatt is null")
            return
        }
        val c = writeChar ?: run {
            Log.w(TAG, "Send failed: writeChar is null")
            return
        }
        Log.d(TAG, "Send ${frame.size}B: ${frame.joinToString("") { "%02X".format(it) }}")
        if (Build.VERSION.SDK_INT >= 33) {
            val result = g.writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            Log.d(TAG, "Write result: $result")
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            c.value = frame
            @Suppress("DEPRECATION")
            val ok = g.writeCharacteristic(c)
            Log.d(TAG, "Write result: $ok")
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT connected, requesting MTU")
                gatt.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT disconnected (status=$status)")
                stopImuStreaming()
                stopKeepalive()
                gatt.close()
                this@GimbalController.gatt = null
                writeChar = null
                state.value = State.DISCONNECTED
                deviceName.value = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU=$mtu")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed: $status")
                disconnect()
                return
            }

            val svc = gatt.getService(SERVICE)
            if (svc == null) {
                Log.w(TAG, "Service FFF0 not found")
                disconnect()
                return
            }

            writeChar = svc.getCharacteristic(CHAR_WRITE)
            val notifyCh = svc.getCharacteristic(CHAR_NOTIFY)

            if (writeChar == null) {
                Log.w(TAG, "Write characteristic FFF5 not found")
                disconnect()
                return
            }

            if (notifyCh != null) {
                gatt.setCharacteristicNotification(notifyCh, true)
                notifyCh.getDescriptor(CCCD)?.let { desc ->
                    if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(desc)
                    }
                }
            }

            state.value = State.CONNECTED
            Log.i(TAG, "Gimbal ready: ${deviceName.value}")
            startKeepalive()
            scope?.launch {
                delay(200)
                Log.i(TAG, "Running Mimo init sequence")
                for (frame in DumlProtocol.mimoInitSequence()) {
                    send(frame)
                    delay(40)
                }
                Log.i(TAG, "Init sequence done, starting IMU stream")
                startImuStreaming()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            logNotification(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            logNotification(value)
        }

        private fun logNotification(data: ByteArray?) {
            Log.d(TAG, "Notify: ${data?.joinToString("") { "%02X".format(it) }}")
            if (data != null) {
                DumlProtocol.ackFor(data)?.let { ack -> send(ack) }
            }
        }
    }
}
