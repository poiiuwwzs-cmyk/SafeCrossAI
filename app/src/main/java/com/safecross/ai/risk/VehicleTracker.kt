package com.safecross.ai.risk

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

enum class Motion { UNKNOWN, STATIONARY, APPROACHING, RECEDING, CROSSING }

/** What we currently know about one tracked vehicle. */
data class TrackMotion(
    val id: Int,
    val label: String,
    val state: Motion,
    val areaFraction: Float,
    /** d(ln height)/dt in 1/s. Positive means the vehicle is getting closer. */
    val closingRate: Double,
    /** Time to contact in seconds (only while approaching). Needs no calibration. */
    val ttcSeconds: Double?,
    /** Rough distance in meters (only with a valid camera calibration). */
    val distanceMeters: Double?,
    /** Rough closing speed in km/h (only with a valid camera calibration). */
    val speedKmh: Double?
)

/**
 * Follows vehicles from frame to frame (IoU matching) and works out whether each
 * one is parked, approaching, receding or moving sideways.
 *
 * Key idea: for an object at distance d moving toward the camera at speed v,
 * d(ln height)/dt = v / d = 1 / TTC. A parked car seen from a standing phone has
 * a rate near zero, so it is never treated as a threat, however big it looks.
 *
 * Assumes the phone is held roughly still (pedestrian waiting at the curb).
 */
class VehicleTracker(
    private val minIou: Float = 0.25f,
    private val maxGapMs: Long = 600L,
    private val windowMs: Long = 1500L,
    private val minSamples: Int = 5,
    private val minSpanMs: Long = 500L,
    private val approachRate: Double = 0.10,
    private val stillRate: Double = 0.05,
    private val lateralRate: Double = 0.10
) {
    private class Sample(val timeMs: Long, val box: VehicleBox)

    private class Track(val id: Int, var label: String) {
        val samples = ArrayList<Sample>()
        val lastTimeMs: Long get() = samples.last().timeMs
        val lastBox: VehicleBox get() = samples.last().box
    }

    private val tracks = ArrayList<Track>()
    private var nextId = 1

    fun reset() {
        tracks.clear()
    }

    fun update(
        boxes: List<VehicleBox>,
        nowMs: Long,
        calibration: CameraCalibration?
    ): List<TrackMotion> {
        tracks.removeAll { nowMs - it.lastTimeMs > maxGapMs }

        val candidates = boxes.filter { it.areaFraction > 0f }

        // Greedy matching: best overlaps first.
        val pairs = ArrayList<Triple<Float, Int, Int>>()
        for (ti in tracks.indices) {
            for (bi in candidates.indices) {
                val overlap = iou(tracks[ti].lastBox, candidates[bi])
                if (overlap >= minIou) pairs.add(Triple(overlap, ti, bi))
            }
        }
        pairs.sortByDescending { it.first }

        val usedTracks = BooleanArray(tracks.size)
        val usedBoxes = BooleanArray(candidates.size)
        for ((_, ti, bi) in pairs) {
            if (usedTracks[ti] || usedBoxes[bi]) continue
            usedTracks[ti] = true
            usedBoxes[bi] = true
            addSample(tracks[ti], nowMs, candidates[bi])
        }
        for (bi in candidates.indices) {
            if (usedBoxes[bi]) continue
            val track = Track(nextId++, candidates[bi].label)
            addSample(track, nowMs, candidates[bi])
            tracks.add(track)
        }

        // Only report vehicles actually seen in this frame.
        return tracks.filter { it.lastTimeMs == nowMs }.map { analyse(it, calibration) }
    }

    private fun addSample(track: Track, nowMs: Long, box: VehicleBox) {
        track.label = box.label
        track.samples.add(Sample(nowMs, box))
        while (track.samples.size > 2 && nowMs - track.samples[0].timeMs > windowMs) {
            track.samples.removeAt(0)
        }
    }

    private fun analyse(track: Track, calibration: CameraCalibration?): TrackMotion {
        val s = track.samples
        val last = s.last().box
        val spanMs = s.last().timeMs - s.first().timeMs
        if (s.size < minSamples || spanMs < minSpanMs) {
            return TrackMotion(track.id, track.label, Motion.UNKNOWN, last.areaFraction, 0.0, null, null, null)
        }

        val t0 = s[0].timeMs
        val times = DoubleArray(s.size) { (s[it].timeMs - t0) / 1000.0 }
        val logHeights = DoubleArray(s.size) { ln(max(s[it].box.height, 1e-4f).toDouble()) }
        val centers = DoubleArray(s.size) { s[it].box.centerX.toDouble() }

        val rate = slope(times, logHeights)
        val lateral = abs(slope(times, centers))

        val state = when {
            rate > approachRate -> Motion.APPROACHING
            lateral > lateralRate -> Motion.CROSSING
            rate < -stillRate -> Motion.RECEDING
            else -> Motion.STATIONARY
        }

        val ttc = if (state == Motion.APPROACHING) 1.0 / rate else null

        var distance: Double? = null
        var speed: Double? = null
        if (calibration != null && state == Motion.APPROACHING && s.size >= MIN_SPEED_SAMPLES) {
            val realHeight = REAL_HEIGHT_M[track.label] ?: DEFAULT_HEIGHT_M
            val d = realHeight / (calibration.frameHeightPerMeter * last.height)
            if (d in MIN_DISTANCE_M..MAX_DISTANCE_M) {
                distance = d
                speed = (rate * d * 3.6).coerceAtMost(MAX_SPEED_KMH)
            }
        }

        return TrackMotion(track.id, track.label, state, last.areaFraction, rate, ttc, distance, speed)
    }

    /** Least-squares slope of y against x. */
    private fun slope(x: DoubleArray, y: DoubleArray): Double {
        val mx = x.average()
        val my = y.average()
        var num = 0.0
        var den = 0.0
        for (i in x.indices) {
            num += (x[i] - mx) * (y[i] - my)
            den += (x[i] - mx) * (x[i] - mx)
        }
        return if (den < 1e-9) 0.0 else num / den
    }

    private fun iou(a: VehicleBox, b: VehicleBox): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val inter = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val union = a.areaFraction + b.areaFraction - inter
        return if (union <= 0f) 0f else inter / union
    }

    private companion object {
        // Typical real-world heights, used only for the rough distance/speed estimate.
        val REAL_HEIGHT_M = mapOf(
            "car" to 1.5,
            "truck" to 3.0,
            "bus" to 3.1,
            "motorcycle" to 1.4,
            "bicycle" to 1.5
        )
        const val DEFAULT_HEIGHT_M = 1.5
        const val MIN_SPEED_SAMPLES = 6
        const val MIN_DISTANCE_M = 2.0
        const val MAX_DISTANCE_M = 120.0
        const val MAX_SPEED_KMH = 120.0
    }
}
