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

    /** The box touches the top or bottom of the frame, so its height is cut off. */
    val clippedVertically: Boolean get() = top <= EDGE || bottom >= 1f - EDGE

    /** The box touches the left or right of the frame, so its width is cut off. */
    val clippedHorizontally: Boolean get() = left <= EDGE || right >= 1f - EDGE

    private companion object {
        const val EDGE = 0.01f
    }
}

data class RiskResult(
    val level: RiskLevel,
    /** The most urgent vehicle, or null when nothing needs attention. */
    val focus: TrackMotion?,
    /** How many vehicles in view are standing still (parked or waiting). */
    val stationaryCount: Int,
    /** How many vehicles were detected in this frame. */
    val vehicleCount: Int = 0,
    /** The phone itself was moving, so vehicle motion could not be measured. */
    val phoneMoving: Boolean = false
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
 * While the phone itself is moving, camera motion would look like vehicle
 * motion, so all history is dropped and only a conservative CAUTION for big
 * vehicles is given until the phone is still again.
 *
 * DANGER is held for a short time so the alarm does not flicker on and off.
 *
 * The thresholds are NOT calibrated on real traffic and the result must never be
 * presented as a guarantee that crossing is safe.
 */
class RiskEngine(
    private val cautionArea: Float = 0.04f,
    private val dangerTtcSeconds: Double = 4.0,
    private val cautionTtcSeconds: Double = 8.0,
    private val dangerHoldMs: Long = 700L,
    private val tracker: VehicleTracker = VehicleTracker()
) {
    private var lastDangerMs: Long? = null
    private var lastDangerFocus: TrackMotion? = null

    fun reset() {
        tracker.reset()
        lastDangerMs = null
        lastDangerFocus = null
    }

    fun update(
        boxes: List<VehicleBox>,
        nowMs: Long,
        calibration: CameraCalibration? = null,
        phoneSteady: Boolean = true
    ): RiskResult {
        val vehicles = boxes.filter { it.areaFraction > 0f }

        val raw = if (!phoneSteady) {
            tracker.reset()
            val big = vehicles.any { it.areaFraction >= cautionArea }
            RiskResult(
                level = if (big) RiskLevel.CAUTION else RiskLevel.NONE,
                focus = null,
                stationaryCount = 0,
                vehicleCount = vehicles.size,
                phoneMoving = true
            )
        } else {
            assess(vehicles, nowMs, calibration)
        }

        return holdDanger(raw, nowMs)
    }

    private fun assess(
        vehicles: List<VehicleBox>,
        nowMs: Long,
        calibration: CameraCalibration?
    ): RiskResult {
        val motions = tracker.update(vehicles, nowMs, calibration)

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
        return RiskResult(level, focus, stationary, motions.size)
    }

    private fun holdDanger(raw: RiskResult, nowMs: Long): RiskResult {
        if (raw.level == RiskLevel.DANGER) {
            lastDangerMs = nowMs
            lastDangerFocus = raw.focus
            return raw
        }
        val since = lastDangerMs?.let { nowMs - it }
        if (since != null && since in 0 until dangerHoldMs) {
            return raw.copy(level = RiskLevel.DANGER, focus = raw.focus ?: lastDangerFocus)
        }
        lastDangerMs = null
        lastDangerFocus = null
        return raw
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
