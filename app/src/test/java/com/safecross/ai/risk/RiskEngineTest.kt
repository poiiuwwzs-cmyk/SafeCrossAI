package com.safecross.ai.risk

import kotlin.math.exp
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        startMs: Long = 0L
    ): RiskResult {
        var result = RiskResult(RiskLevel.NONE, null, 0)
        boxes.forEachIndexed { i, b ->
            result = engine.update(listOf(b), startMs + i * 100L, calibration)
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
}
