package com.safecross.ai.risk

import org.junit.Assert.assertEquals
import org.junit.Test

class RiskEngineTest {

    // A box whose normalized area equals [area].
    private fun box(area: Float) = VehicleBox("car", 0f, 0f, area, 1f)

    @Test
    fun noVehicles_isNone() {
        assertEquals(RiskLevel.NONE, RiskEngine().update(emptyList()))
    }

    @Test
    fun farVehicle_isCaution() {
        assertEquals(RiskLevel.CAUTION, RiskEngine().update(listOf(box(0.02f))))
    }

    @Test
    fun veryLargeVehicle_isDanger() {
        assertEquals(RiskLevel.DANGER, RiskEngine().update(listOf(box(0.30f))))
    }

    @Test
    fun steadilyGrowingVehicle_becomesDanger() {
        val engine = RiskEngine()
        assertEquals(RiskLevel.CAUTION, engine.update(listOf(box(0.05f))))
        assertEquals(RiskLevel.CAUTION, engine.update(listOf(box(0.06f))))
        assertEquals(RiskLevel.DANGER, engine.update(listOf(box(0.07f))))
    }

    @Test
    fun vehicleLeaving_resetsToNone() {
        val engine = RiskEngine()
        engine.update(listOf(box(0.30f)))
        assertEquals(RiskLevel.NONE, engine.update(emptyList()))
    }
}
