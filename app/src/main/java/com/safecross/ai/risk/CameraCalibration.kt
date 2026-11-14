package com.safecross.ai.risk

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.tan

/** Vertical field of view of the analysed (already rotated, upright) frame. */
data class CameraCalibration(val verticalFovRad: Double) {

    /** Only a plausible phone-camera field of view is accepted. */
    val isValid: Boolean
        get() = verticalFovRad.isFinite() && verticalFovRad in MIN_FOV_RAD..MAX_FOV_RAD

    /** Height of the scene covered by the frame at a distance of 1 meter. */
    val frameHeightPerMeter: Double get() = 2.0 * tan(verticalFovRad / 2.0)

    companion object {
        const val MIN_FOV_RAD = 15.0 * PI / 180.0
        const val MAX_FOV_RAD = 120.0 * PI / 180.0
    }
}

/**
 * Lens data read from the camera that is actually in use.
 *
 * [activeWidthFraction]/[activeHeightFraction] describe how much of the physical
 * sensor is the active pixel area. [singleFocalLength] is false for cameras that
 * report several focal lengths (optical zoom / logical multi-camera); in that case
 * the real focal length is unknown and no calibration is produced.
 */
data class CameraOptics(
    val focalLengthMm: Float,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val activeWidthFraction: Float = 1f,
    val activeHeightFraction: Float = 1f,
    val singleFocalLength: Boolean = true
) {
    /**
     * Calibration for one analysis frame.
     *
     * [frameWidthPx]/[frameHeightPx] are the frame size as delivered by the camera
     * (sensor orientation, before rotation). If the frame aspect ratio differs from
     * the sensor's, the frame is a centered crop of the sensor and the field of
     * view is reduced accordingly. [rotationDegrees] is the rotation CameraX
     * reports for the frame (0, 90, 180, 270).
     *
     * Returns null when any input is missing or the result is not plausible, so
     * that no km/h value is ever shown from a bad calibration.
     */
    fun calibrationFor(rotationDegrees: Int, frameWidthPx: Int, frameHeightPx: Int): CameraCalibration? {
        if (!singleFocalLength) return null
        if (focalLengthMm <= 0f || sensorWidthMm <= 0f || sensorHeightMm <= 0f) return null
        if (activeWidthFraction !in 0.5f..1f || activeHeightFraction !in 0.5f..1f) return null
        if (frameWidthPx <= 0 || frameHeightPx <= 0) return null
        if (rotationDegrees % 90 != 0) return null

        val activeW = sensorWidthMm * activeWidthFraction.toDouble()
        val activeH = sensorHeightMm * activeHeightFraction.toDouble()
        val frameAspect = frameWidthPx.toDouble() / frameHeightPx
        val sensorAspect = activeW / activeH

        // Part of the sensor that the frame really covers (centered crop).
        val coveredW: Double
        val coveredH: Double
        if (frameAspect >= sensorAspect) {
            coveredW = activeW
            coveredH = activeW / frameAspect
        } else {
            coveredH = activeH
            coveredW = activeH * frameAspect
        }

        // A portrait frame (90/270) has the sensor's long side running vertically.
        val verticalMm = if (rotationDegrees % 180 == 0) coveredH else coveredW
        val calibration = CameraCalibration(2.0 * atan(verticalMm / (2.0 * focalLengthMm)))
        return calibration.takeIf { it.isValid }
    }
}
