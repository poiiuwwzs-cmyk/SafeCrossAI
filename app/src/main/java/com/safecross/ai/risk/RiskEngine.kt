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
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2f
    val areaFraction: Float get() = width * height
}

data class RiskResult(
    val level: RiskLevel,
    /** The most urgent vehicle, or null when nothing needs attention. */
    val focus: TrackMotion?,
    /** How many vehicles in view are standing still (parked or waiting). */
    val stationaryCount: Int
)

/**
 * Motion-based risk engine.
 *
 * A big box alone is NOT a threat: a parked car close to the phone looks big but
 * does not move. Risk comes from how each tracked vehicle moves:
 *  - approaching with a short time-to-contact -> DANGER
 *  - approaching slowly, moving sideways, or not yet known -> CAUTION
 *  - standing still or moving away -> ignored
 *
 * The thresholds are NOT calibrated on real traffic and the result must never be
 * presented as a guarantee that crossing is safe.
 */
class RiskEngine(
    private val cautionArea: Float = 0.04f,
    private val dangerTtcSeconds: Double = 4.0,
    private val cautionTtcSeconds: Double = 8.0,
    private val tracker: VehicleTracker = VehicleTracker()
) {
    fun reset() {
        tracker.reset()
    }

    fun update(
        boxes: List<VehicleBox>,
        nowMs: Long,
        calibration: CameraCalibration? = null
    ): RiskResult {
        val motions = tracker.update(boxes, nowMs, calibration)

        var focus: TrackMotion? = null
        var level = RiskLevel.NONE
        for (m in motions) {
            val l = levelFor(m)
            if (l.ordinal > level.ordinal ||
                (l == level && l != RiskLevel.NONE && moreUrgent(m, focus))
            ) {
                level = l
                focus = m
            }
        }
        val stationary = motions.count { it.state == Motion.STATIONARY }
        return RiskResult(level, focus, stationary)
    }

    private fun levelFor(m: TrackMotion): RiskLevel = when (m.state) {
        Motion.STATIONARY, Motion.RECEDING -> RiskLevel.NONE
        Motion.UNKNOWN, Motion.CROSSING ->
            if (m.areaFraction >= cautionArea) RiskLevel.CAUTION else RiskLevel.NONE
        Motion.APPROACHING -> {
            val ttc = m.ttcSeconds ?: Double.MAX_VALUE
            when {
                ttc <= dangerTtcSeconds -> RiskLevel.DANGER
                ttc <= cautionTtcSeconds -> RiskLevel.CAUTION
                m.areaFraction >= cautionArea -> RiskLevel.CAUTION
                else -> RiskLevel.NONE
            }
        }
    }

    private fun moreUrgent(a: TrackMotion, b: TrackMotion?): Boolean =
        b == null || (a.ttcSeconds ?: Double.MAX_VALUE) < (b.ttcSeconds ?: Double.MAX_VALUE)
}
