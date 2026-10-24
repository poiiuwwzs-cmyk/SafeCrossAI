package com.safecross.ai.risk

enum class RiskLevel { NONE, CAUTION, DANGER }

/** A detected vehicle. Coordinates are normalized to 0..1 relative to the frame. */
data class VehicleBox(
    val label: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val areaFraction: Float
        get() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
}

/**
 * Simple heuristic risk engine.
 *
 * A bigger box means a closer vehicle; a box that keeps growing between frames
 * means the vehicle is approaching. The thresholds are NOT calibrated and the
 * result must never be presented as a guarantee that crossing is safe.
 */
class RiskEngine(
    private val cautionArea: Float = 0.04f,
    private val dangerArea: Float = 0.15f,
    private val growthFactor: Float = 1.15f,
    private val dangerStreak: Int = 2
) {
    private var lastMaxArea = 0f
    private var growthStreak = 0

    fun reset() {
        lastMaxArea = 0f
        growthStreak = 0
    }

    fun update(boxes: List<VehicleBox>): RiskLevel {
        val vehicles = boxes.filter { it.areaFraction > 0f }
        if (vehicles.isEmpty()) {
            reset()
            return RiskLevel.NONE
        }

        val maxArea = vehicles.maxOf { it.areaFraction }
        val growing = lastMaxArea > 0f && maxArea > lastMaxArea * growthFactor
        growthStreak = if (growing) growthStreak + 1 else 0
        lastMaxArea = maxArea

        return when {
            maxArea >= dangerArea -> RiskLevel.DANGER
            maxArea >= cautionArea && growthStreak >= dangerStreak -> RiskLevel.DANGER
            else -> RiskLevel.CAUTION
        }
    }
}
