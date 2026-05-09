package com.streamcam.app

object DumlProtocol {
    private const val SOF: Byte = 0x55
    private const val VERSION = 1

    const val CMD_SET_GIMBAL: Byte = 0x04
    const val CMD_SPEED_CONTROL: Byte = 0x0C
    const val CMD_ABSOLUTE_ANGLE: Byte = 0x14

    private val crc8Table = IntArray(256).also { table ->
        for (i in 0..255) {
            var crc = i
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8C
                else crc ushr 1
            }
            table[i] = crc
        }
    }

    private val crc16Table = IntArray(256).also { table ->
        for (i in 0..255) {
            var crc = i
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001
                else crc ushr 1
            }
            table[i] = crc
        }
    }

    private fun crc8(data: ByteArray, offset: Int, length: Int): Byte {
        var crc = 0x77
        for (i in offset until offset + length) {
            crc = crc8Table[(crc xor (data[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc.toByte()
    }

    private fun crc16(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xDF0C
        for (i in offset until offset + length) {
            crc = (crc ushr 8) xor crc16Table[(crc xor (data[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc and 0xFFFF
    }

    @Volatile
    private var seq = 0

    fun buildFrame(
        cmdSet: Byte,
        cmdId: Byte,
        payload: ByteArray = byteArrayOf(),
        sender: Byte = 0x02,
        receiver: Byte = 0x04,
        flags: Byte = 0x40,
        explicitSeq: Int = -1,
    ): ByteArray {
        val len = 13 + payload.size
        val frame = ByteArray(len)

        frame[0] = SOF
        frame[1] = (len and 0xFF).toByte()
        frame[2] = (((len shr 8) and 0x03) or (VERSION shl 2)).toByte()

        frame[3] = crc8(frame, 0, 3)

        frame[4] = sender
        frame[5] = receiver

        val s = if (explicitSeq >= 0) explicitSeq else seq++
        frame[6] = (s and 0xFF).toByte()
        frame[7] = ((s shr 8) and 0xFF).toByte()

        frame[8] = flags
        frame[9] = cmdSet
        frame[10] = cmdId

        payload.copyInto(frame, 11)

        val c = crc16(frame, 4, len - 6)
        frame[len - 2] = (c and 0xFF).toByte()
        frame[len - 1] = ((c shr 8) and 0xFF).toByte()

        return frame
    }

    fun speedCommand(pitchDps: Float, rollDps: Float, yawDps: Float): ByteArray {
        val payload = ByteArray(7)
        putS16LE(payload, 0, (pitchDps * 10).toInt())
        putS16LE(payload, 2, (rollDps * 10).toInt())
        putS16LE(payload, 4, (yawDps * 10).toInt())
        payload[6] = 0x01
        return buildFrame(CMD_SET_GIMBAL, CMD_SPEED_CONTROL, payload)
    }

    fun absoluteAngleCommand(
        pitchDeg: Float,
        rollDeg: Float,
        yawDeg: Float,
        durationDs: Int = 10,
    ): ByteArray {
        val payload = ByteArray(8)
        putS16LE(payload, 0, (pitchDeg * 10).toInt())
        putS16LE(payload, 2, (rollDeg * 10).toInt())
        putS16LE(payload, 4, (yawDeg * 10).toInt())
        payload[6] = 0x0F
        payload[7] = durationDs.toByte()
        return buildFrame(CMD_SET_GIMBAL, CMD_ABSOLUTE_ANGLE, payload)
    }

    fun recenterCommand(): ByteArray = absoluteAngleCommand(0f, 0f, 0f, 10)

    fun heartbeatGimbal(): ByteArray =
        buildFrame(0x00, 0x00, byteArrayOf(0x02, 0x00), receiver = 0x27)

    fun heartbeatMain(): ByteArray =
        buildFrame(0x00, 0x00, byteArrayOf(0x00, 0x11), receiver = 0x04)

    fun pollStatus(variant: Byte): ByteArray =
        buildFrame(0x04, 0x10, byteArrayOf(variant))

    fun pollGimbalConfig(): ByteArray =
        buildFrame(0x04, 0x68, hex("32080000800000000000"))

    fun pollGimbalSetting(): ByteArray =
        buildFrame(0x04, 0x50, hex("010405"))

    fun pollSync(receiver: Byte): ByteArray =
        buildFrame(0xEE.toByte(), 0x02, hex("223C0000803F24"), receiver = receiver)

    fun ackFor(notifyFrame: ByteArray): ByteArray? {
        if (notifyFrame.size < 13 || notifyFrame[0] != SOF) return null
        if (notifyFrame[8].toInt() and 0xE0 != 0x40) return null  // not a request
        val theirSender = notifyFrame[4]
        val seqLo = notifyFrame[6].toInt() and 0xFF
        val seqHi = notifyFrame[7].toInt() and 0xFF
        val theirSeq = seqLo or (seqHi shl 8)
        val cmdSet = notifyFrame[9]
        val cmdId = notifyFrame[10]
        return buildFrame(
            cmdSet = cmdSet,
            cmdId = cmdId,
            payload = byteArrayOf(),
            sender = 0x02,
            receiver = theirSender,
            flags = 0x80.toByte(),
            explicitSeq = theirSeq,
        )
    }

    fun joystickPitch(value: Int): ByteArray =
        buildFrame(0x04, 0x07, byteArrayOf(value.toByte(), 0x00))

    fun mimoInitSequence(): List<ByteArray> = listOf(
        buildFrame(0x04, 0x12, hex("77FE0000000000D60000000000000000000000000000000000000000000000000000")),
        buildFrame(0x04, 0x10, byteArrayOf(0x0A)),
        buildFrame(0x04, 0x12, hex("66FE0000000000000000000000000000100000000000000000000000000000000000")),
        buildFrame(0x04, 0x12, hex("66FE0000000000000000000000000000180000000000000000000000000000000000")),
        buildFrame(0x12, 0x0C, byteArrayOf(), receiver = 0x27),
        buildFrame(0x04, 0x65, byteArrayOf(0x01, 0x01, 0x00)),
        buildFrame(0x04, 0x12, hex("66FE0000000000000000800000000000180000000000000000000000000000000000")),
        buildFrame(0x04, 0x12, hex("66FE0000000000000000800000000010180000000000000000000000000000000000")),
        buildFrame(0x04, 0x12, hex("66FE0000000000000000800000000018180000000000000000000000000000000000")),
        buildFrame(0x12, 0x07, byteArrayOf(), receiver = 0x27),
        buildFrame(0x00, 0x34, hex("0101000000000000"), receiver = 0x27),
        buildFrame(0x00, 0x4F, hex("040000000000000000"), receiver = 0x27),
        buildFrame(0x04, 0x1E, hex("71FF0400")),
    )

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) {
        ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte()
    }

    fun activeTrackEnable(): ByteArray =
        buildFrame(0x04, 0x4C, byteArrayOf(0x01, 0x00))

    fun activeTrackDisable(): ByteArray =
        buildFrame(0x04, 0x4C, byteArrayOf(0x02, 0x00))

    fun activeTrackStart(): ByteArray =
        buildFrame(0x04, 0x0F, byteArrayOf(0x82.toByte(), 0x01, 0x00))

    fun activeTrackStop(): ByteArray =
        buildFrame(0x04, 0x0F, byteArrayOf(0x82.toByte(), 0x01, 0xFF.toByte()))

    private val ACTIVE_TRACK_HEADER = byteArrayOf(
        0xD0.toByte(), 0x02, 0x00, 0x05,
        0x02, 0x02, 0x01, 0x05,
        0x34, 0x00, 0x00, 0x00,
    )

    @Volatile
    private var trackFrameCounter = 0

    fun imuStream(
        qx: Float = 0f, qy: Float = 0f, qz: Float = 0f, qw: Float = 1f,
        ax: Float = 0f, ay: Float = 0f, az: Float = 9.81f,
        gx: Float = 0f, gy: Float = 0f, gz: Float = 0f,
    ): ByteArray {
        val payload = ByteArray(40)
        putFloatLE(payload, 0, qx)
        putFloatLE(payload, 4, qy)
        putFloatLE(payload, 8, qz)
        putFloatLE(payload, 12, qw)
        putFloatLE(payload, 16, ax)
        putFloatLE(payload, 20, ay)
        putFloatLE(payload, 24, az)
        putFloatLE(payload, 28, gx)
        putFloatLE(payload, 32, gy)
        putFloatLE(payload, 36, gz)
        return buildFrame(0x04, 0x52, payload)
    }

    fun activeTrackBox(x: Float, y: Float, w: Float, h: Float): ByteArray {
        val payload = ByteArray(68)
        val ts = (System.nanoTime() / 1000L).toInt()
        putU32LE(payload, 0, ts)
        putU32LE(payload, 4, trackFrameCounter++)
        ACTIVE_TRACK_HEADER.copyInto(payload, 8)
        putFloatLE(payload, 20, x)
        putFloatLE(payload, 24, y)
        putFloatLE(payload, 28, w)
        putFloatLE(payload, 32, h)
        return buildFrame(0x23, 0x09, payload)
    }

    private fun putS16LE(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun putU32LE(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = ((value shr 8) and 0xFF).toByte()
        arr[offset + 2] = ((value shr 16) and 0xFF).toByte()
        arr[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun putFloatLE(arr: ByteArray, offset: Int, value: Float) {
        putU32LE(arr, offset, java.lang.Float.floatToIntBits(value))
    }
}
