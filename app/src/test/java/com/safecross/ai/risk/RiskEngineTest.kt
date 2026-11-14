package com.safecross.ai.risk

import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class RiskEngineTest {

    /** A car box of the given normalized height, centered at [cx]. */
    private fun car(height: Float, cx: Float = 0.5f) = VehicleBox(
        "car", cx - height * 0.75f, 0.5f - height / 2f, cx + height * 0.75f, 0.5f + height / 2f
    )

    /** Feeds one frame per box, 100 ms apart, and returns the last result. */
    private fun run(
        engine: RiskEngine,
        boxes: List<VehicleBox>,
        calibration: CameraCalibration? = null,
        startMs: Long = 0L,
        phoneSteady: Boolean = true
    ): RiskResult {
        var result = RiskResult(RiskLevel.NONE, null, 0)
        boxes.forEachIndexed { i, b ->
            result = engine.update(listOf(b), startMs + i * 100L, calibration, phoneSteady)
        }
        return result
    }

    /** Boxes whose height grows exponentially: ln(height) rises by [rate] per second. */
    private fun growing(startHeight: Double, rate: Double, frames: Int): List<VehicleBox> =
        (0 until frames).map { car((startHeight * exp(rate * it * 0.1)).toFloat()) }

    @Test
    fun noVehicles_isNone() {
        val result = RiskEngine().update(emptyList(), 0L)
        assertEquals(RiskLevel.NONE, result.level)
        assertNull(result.focus)
    }

    @Test
    fun smallVehicleWithNoHistory_isNone() {
        assertEquals(RiskLevel.NONE, RiskEngine().update(listOf(car(0.1f)), 0L).level)
    }

    @Test
    fun newBigVehicle_isCautionNotDanger() {
        assertEquals(RiskLevel.CAUTION, RiskEngine().update(listOf(car(0.5f)), 0L).level)
    }

    @Test
    fun parkedCar_neverTriggersAlert_evenWhenHuge() {
        val result = run(RiskEngine(), growing(0.5, 0.0, 12))
        assertEquals(RiskLevel.NONE, result.level)
        assertEquals(1, result.stationaryCount)
    }

    @Test
    fun fastApproachingCar_isDanger() {
        val result = run(RiskEngine(), growing(0.1, 0.5, 10))
        assertEquals(RiskLevel.DANGER, result.level)
        assertEquals(2.0, result.focus!!.ttcSeconds!!, 0.2)
    }

    @Test
    fun slowApproachingCar_isCaution() {
        val result = run(RiskEngine(), growing(0.2, 0.12, 10))
        assertEquals(RiskLevel.CAUTION, result.level)
        assertEquals(Motion.APPROACHING, result.focus!!.state)
    }

    @Test
    fun carMovingAway_isIgnored() {
        val result = run(RiskEngine(), growing(0.3, -0.4, 10))
        assertEquals(RiskLevel.NONE, result.level)
    }

    @Test
    fun carCrossingSideways_isCaution() {
        val boxes = (0 until 10).map { car(0.2f, cx = 0.25f + 0.03f * it) }
        val result = run(RiskEngine(), boxes)
        assertEquals(RiskLevel.CAUTION, result.level)
        assertEquals(Motion.CROSSING, result.focus?.state)
    }

    @Test
    fun carThatStops_stopsBeingDanger() {
        val engine = RiskEngine()
        val approach = growing(0.1, 0.5, 10)
        assertEquals(RiskLevel.DANGER, run(engine, approach).level)

        val stopped = List(15) { approach.last() }
        val result = run(engine, stopped, startMs = 1000L)
        assertEquals(RiskLevel.NONE, result.level)
        assertEquals(1, result.stationaryCount)
    }

    @Test
    fun speedEstimate_needsCalibration() {
        val result = run(RiskEngine(), growing(0.1, 0.3, 10))
        assertNull(result.focus?.speedKmh)
    }

    @Test
    fun speedEstimate_withCalibration_matchesPinholeMath() {
        val boxes = growing(0.1, 0.3, 10)
        val result = run(RiskEngine(), boxes, CameraCalibration(1.0))
        val speed = result.focus?.speedKmh
        assertNotNull(speed)

        val distance = 1.5 / (2.0 * tan(0.5) * boxes.last().height.toDouble())
        assertEquals(0.3 * distance * 3.6, speed!!, 0.5)
    }

    // ---- Parked cars and detector noise ----

    @Test
    fun parkedCar_withDetectorJitter_isNeverDanger() {
        // Box size wobbles by about +-3 % frame to frame, like a real detector does.
        val boxes = (0 until 30).map { i -> car((0.4 * (1.0 + 0.03 * sin(i * 2.3))).toFloat()) }
        val engine = RiskEngine()
        boxes.forEachIndexed { i, b ->
            val result = engine.update(listOf(b), i * 100L)
            assertNotEquals("frame $i", RiskLevel.DANGER, result.level)
        }
        assertEquals(RiskLevel.NONE, run(RiskEngine(), boxes).level)
    }

    @Test
    fun recedingVehicle_isCountedButNotParked() {
        val result = run(RiskEngine(), growing(0.3, -0.4, 10))
        assertEquals(1, result.vehicleCount)
        assertEquals(0, result.stationaryCount)
    }

    // ---- Phone motion ----

    @Test
    fun phoneMoving_bigVehicle_isCautionWithoutMotionData() {
        val result = run(RiskEngine(), growing(0.3, 0.0, 5), phoneSteady = false)
        assertTrue(result.phoneMoving)
        assertEquals(RiskLevel.CAUTION, result.level)
        assertNull(result.focus)
    }

    @Test
    fun phoneMoving_smallFarVehicle_isNone() {
        val result = run(RiskEngine(), growing(0.05, 0.0, 5), phoneSteady = false)
        assertTrue(result.phoneMoving)
        assertEquals(RiskLevel.NONE, result.level)
    }

    @Test
    fun phoneMoving_dropsHistory_soPanningIsNotAnApproach() {
        val engine = RiskEngine()
        // The user swings the phone toward a parked car: the box grows fast.
        run(engine, growing(0.1, 0.8, 8), phoneSteady = false)
        // Phone is still again: the first frame has no history, so no DANGER.
        val first = engine.update(listOf(car(0.18f)), 900L, null, true)
        assertFalse(first.phoneMoving)
        assertEquals(Motion.UNKNOWN, first.focus?.state)
        assertNotEquals(RiskLevel.DANGER, first.level)
    }

    @Test
    fun phoneMoving_neverShowsSpeed() {
        val result = run(RiskEngine(), growing(0.1, 0.3, 10), CameraCalibration(1.0), phoneSteady = false)
        assertNull(result.focus?.speedKmh)
    }

    // ---- DANGER hold ----

    @Test
    fun danger_isHeldBriefly_thenReleased() {
        val engine = RiskEngine()
        assertEquals(RiskLevel.DANGER, run(engine, growing(0.1, 0.5, 10)).level)
        // Detector misses the car for one frame: alarm must not flicker off.
        assertEquals(RiskLevel.DANGER, engine.update(emptyList(), 1000L).level)
        // Long after, with nothing in view, it is released.
        assertEquals(RiskLevel.NONE, engine.update(emptyList(), 2500L).level)
    }

    // ---- Calibration and speed ----

    @Test
    fun speedEstimate_invalidCalibration_isNotShown() {
        val boxes = growing(0.1, 0.3, 10)
        assertNull(run(RiskEngine(), boxes, CameraCalibration(0.01)).focus?.speedKmh)
        assertNull(run(RiskEngine(), boxes, CameraCalibration(3.0)).focus?.speedKmh)
        assertNull(run(RiskEngine(), boxes, CameraCalibration(Double.NaN)).focus?.speedKmh)
    }

    @Test
    fun speedEstimate_needsEnoughHistory() {
        val result = run(RiskEngine(), growing(0.1, 0.3, 5), CameraCalibration(1.0))
        assertNull(result.focus?.speedKmh)
    }

    // ---- Boxes cut off by the frame edge ----

    @Test
    fun boxCutOffTopAndBottom_usesWidth_andGivesNoSpeed() {
        // Very close truck: always taller than the frame, but its width keeps growing.
        val boxes = (0 until 10).map { i ->
            val w = (0.3 * exp(0.5 * i * 0.1)).toFloat()
            VehicleBox("truck", 0.5f - w / 2f, 0f, 0.5f + w / 2f, 1f)
        }
        val result = run(RiskEngine(), boxes, CameraCalibration(1.0))
        assertEquals(RiskLevel.DANGER, result.level)
        assertEquals(Motion.APPROACHING, result.focus?.state)
        assertTrue(result.focus!!.clipped)
        assertNull(result.focus?.speedKmh)
    }

    @Test
    fun boxCutOffOnAllSides_isCautionNotParked() {
        val boxes = List(10) { VehicleBox("bus", 0f, 0f, 1f, 1f) }
        val result = run(RiskEngine(), boxes)
        assertEquals(RiskLevel.CAUTION, result.level)
        assertEquals(Motion.UNKNOWN, result.focus?.state)
        assertEquals(0, result.stationaryCount)
    }
}
