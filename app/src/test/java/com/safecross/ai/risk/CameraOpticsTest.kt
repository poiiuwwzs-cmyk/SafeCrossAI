package com.safecross.ai.risk

import kotlin.math.atan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraOpticsTest {

    // Typical phone main camera: 4:3 sensor, 6.4 x 4.8 mm, 5.6 mm lens.
    private val optics = CameraOptics(5.6f, 6.4f, 4.8f)

    private fun fov(sensorMm: Double, focalMm: Double) = 2.0 * atan(sensorMm / (2.0 * focalMm))

    @Test
    fun portraitFrame_usesSensorLongSideVertically() {
        val c = optics.calibrationFor(90, 640, 480)
        assertNotNull(c)
        assertEquals(fov(6.4, 5.6), c!!.verticalFovRad, 1e-6)
    }

    @Test
    fun landscapeFrame_usesSensorShortSideVertically() {
        val c = optics.calibrationFor(0, 640, 480)
        assertEquals(fov(4.8, 5.6), c!!.verticalFovRad, 1e-6)
    }

    @Test
    fun wideFrame_onFourThirdsSensor_isCroppedVertically() {
        // 16:9 frame: full width (6.4 mm), only 3.6 mm of the height.
        val c = optics.calibrationFor(0, 1280, 720)
        assertEquals(fov(3.6, 5.6), c!!.verticalFovRad, 1e-6)
    }

    @Test
    fun activeArea_reducesCoveredSensor() {
        val c = optics.copy(activeWidthFraction = 0.9f, activeHeightFraction = 0.9f)
            .calibrationFor(0, 640, 480)
        assertEquals(fov(4.8 * 0.9, 5.6), c!!.verticalFovRad, 1e-5)
    }

    @Test
    fun severalFocalLengths_givesNoCalibration() {
        assertNull(optics.copy(singleFocalLength = false).calibrationFor(90, 640, 480))
    }

    @Test
    fun missingOrBadData_givesNoCalibration() {
        assertNull(optics.copy(focalLengthMm = 0f).calibrationFor(90, 640, 480))
        assertNull(optics.copy(sensorWidthMm = -1f).calibrationFor(90, 640, 480))
        assertNull(optics.copy(activeWidthFraction = 0.1f).calibrationFor(90, 640, 480))
        assertNull(optics.calibrationFor(90, 0, 480))
        assertNull(optics.calibrationFor(45, 640, 480))
    }

    @Test
    fun implausibleFieldOfView_isRejected() {
        // 100 mm "lens" on a small sensor -> ~3 degrees: not a phone camera.
        assertNull(optics.copy(focalLengthMm = 100f).calibrationFor(90, 640, 480))
    }

    @Test
    fun calibrationValidity() {
        assertTrue(CameraCalibration(1.0).isValid)
        assertFalse(CameraCalibration(0.05).isValid)
        assertFalse(CameraCalibration(2.5).isValid)
        assertFalse(CameraCalibration(Double.NaN).isValid)
    }
}
