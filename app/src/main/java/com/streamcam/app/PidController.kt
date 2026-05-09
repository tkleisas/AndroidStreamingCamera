package com.streamcam.app

import kotlin.math.abs

class PidController(
    private val kp: Float,
    private val ki: Float = 0f,
    private val kd: Float = 0f,
    private val maxOutput: Float = 45f,
    private val deadzone: Float = 0.03f,
) {
    private var integral = 0f
    private var prevError = 0f
    private var prevTime = 0L

    fun update(error: Float): Float {
        if (abs(error) < deadzone) {
            prevError = 0f
            integral = 0f
            return 0f
        }

        val now = System.nanoTime()
        val dt = if (prevTime == 0L) 0.1f else (now - prevTime) / 1e9f
        prevTime = now

        integral = (integral + error * dt).coerceIn(-1f, 1f)
        val derivative = if (dt > 0f) (error - prevError) / dt else 0f
        prevError = error

        return (kp * error + ki * integral + kd * derivative).coerceIn(-maxOutput, maxOutput)
    }

    fun reset() {
        integral = 0f
        prevError = 0f
        prevTime = 0L
    }
}
