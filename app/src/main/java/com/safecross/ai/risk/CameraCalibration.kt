package com.safecross.ai.risk

import kotlin.math.atan
import kotlin.math.tan

/** Vertical field of view of the analysed (already rotated, upright) frame. */
data class CameraCalibration(val verticalFovRad: Double) {
    /** Height of the scene covered by the frame at a distance of 1 meter. */
    val frameHeightPerMeter: Double get() = 2.0 * tan(verticalFovRad / 2.0)
}

/**
 * Raw lens data read from the phone's own camera characteristics.
 *
 * Assumes the analysed frame covers the whole sensor (no crop, 1x zoom).
 * If that is not true the speed estimate is off by the crop factor.
 */
data class CameraOptics(
    val focalLengthMm: Float,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float
) {
    /** [rotationDegrees] is the rotation CameraX reports for the frame (0, 90, 180, 270). */
    fun calibrationFor(rotationDegrees: Int): CameraCalibration? {
        if (focalLengthMm <= 0f || sensorWidthMm <= 0f || sensorHeightMm <= 0f) return null
        // A portrait frame (90/270) has the sensor's long side running vertically.
        val verticalSensorMm = if (rotationDegrees % 180 == 0) sensorHeightMm else sensorWidthMm
        return CameraCalibration(2.0 * atan(verticalSensorMm / (2.0 * focalLengthMm)))
    }
}
