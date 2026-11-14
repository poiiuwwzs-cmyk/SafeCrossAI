package com.safecross.ai.risk

/**
 * Decides whether the phone is held still, from gyroscope and linear-acceleration
 * readings. When the phone turns or shakes, everything in the camera image moves,
 * which would look like vehicles moving; the risk engine must not trust vehicle
 * motion until the phone has been still for [settleMs].
 *
 * Times are in milliseconds on one monotonic clock (the caller uses
 * SystemClock.elapsedRealtime() both for sensor events and for frames).
 *
 * If the phone has no motion sensors no readings ever arrive and the phone is
 * treated as steady; the README documents this limitation.
 */
class PhoneSteadiness(
    private val maxRotationRadPerSec: Float = 0.35f,
    private val maxLinearAccelMps2: Float = 1.2f,
    private val settleMs: Long = 700L
) {
    @Volatile
    private var lastShakeMs: Long = NEVER

    fun onRotation(radPerSec: Float, timeMs: Long) {
        if (radPerSec > maxRotationRadPerSec) markShake(timeMs)
    }

    fun onLinearAcceleration(mps2: Float, timeMs: Long) {
        if (mps2 > maxLinearAccelMps2) markShake(timeMs)
    }

    fun isSteady(nowMs: Long): Boolean {
        val shake = lastShakeMs
        return shake == NEVER || nowMs - shake >= settleMs
    }

    fun reset() {
        lastShakeMs = NEVER
    }

    private fun markShake(timeMs: Long) {
        if (lastShakeMs == NEVER || timeMs > lastShakeMs) lastShakeMs = timeMs
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
