package com.carstalker.core.diagnose

import kotlin.math.abs
import kotlin.math.sqrt

data class SignalStats(
    val count: Int,
    val durationMs: Long,
    val min: Double,
    val max: Double,
    val mean: Double,
    val stdDev: Double,
    val amplitude: Double,
    val switchingCount: Int,
    val frequencyHz: Double,
    val dutyHigh: Double,
)

/**
 * Small, deterministic signal-analysis helpers for wizard captures.
 *
 * These are intentionally simple: a wizard should be explainable and testable, not a black box.
 * O2 switching is measured around 0.45 V (the narrowband stoichiometric midpoint); other signals
 * fall back to the midpoint of their own range.
 */
object SignalAnalysis {

    fun stats(points: List<CapturePoint>): SignalStats {
        if (points.isEmpty()) {
            return SignalStats(0, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0, 0.0)
        }
        val sorted = points.sortedBy { it.timestampMs }
        val values = sorted.map { it.value }
        val min = values.min()
        val max = values.max()
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        val threshold = if (min <= 0.45 && max >= 0.45) 0.45 else (min + max) / 2.0
        var transitions = 0
        var previousSign = 0
        var high = 0
        for (value in values) {
            if (value > threshold) high++
            val sign = when {
                value > threshold -> 1
                value < threshold -> -1
                else -> 0
            }
            if (sign != 0) {
                if (previousSign != 0 && sign != previousSign) transitions++
                previousSign = sign
            }
        }
        val durationMs = (sorted.last().timestampMs - sorted.first().timestampMs).coerceAtLeast(0L)
        val seconds = durationMs / 1000.0
        return SignalStats(
            count = values.size,
            durationMs = durationMs,
            min = min,
            max = max,
            mean = mean,
            stdDev = sqrt(variance),
            amplitude = max - min,
            switchingCount = transitions,
            frequencyHz = if (seconds > 0.0) transitions / (2.0 * seconds) else 0.0,
            dutyHigh = if (values.isEmpty()) 0.0 else high.toDouble() / values.size,
        )
    }

    /**
     * Pearson correlation after resampling both series onto a common 20 ms grid. Returns null
     * when there is too little overlap or either signal is flat.
     */
    fun correlation(a: List<CapturePoint>, b: List<CapturePoint>, stepMs: Long = 20L): Double? {
        if (a.size < 5 || b.size < 5) return null
        val start = maxOf(a.minOf { it.timestampMs }, b.minOf { it.timestampMs })
        val end = minOf(a.maxOf { it.timestampMs }, b.maxOf { it.timestampMs })
        if (end <= start) return null
        val samples = ((end - start) / stepMs).toInt().coerceAtMost(5_000)
        if (samples < 10) return null
        val xs = DoubleArray(samples)
        val ys = DoubleArray(samples)
        for (index in 0 until samples) {
            val time = start + index * stepMs
            xs[index] = interpolate(a, time) ?: return null
            ys[index] = interpolate(b, time) ?: return null
        }
        val meanX = xs.average()
        val meanY = ys.average()
        var covariance = 0.0
        var varianceX = 0.0
        var varianceY = 0.0
        for (index in xs.indices) {
            val dx = xs[index] - meanX
            val dy = ys[index] - meanY
            covariance += dx * dy
            varianceX += dx * dx
            varianceY += dy * dy
        }
        if (varianceX <= 1e-9 || varianceY <= 1e-9) return null
        return covariance / sqrt(varianceX * varianceY)
    }

    /** Zero-order-hold interpolation; captures are sampled asynchronously per PID. */
    fun interpolate(points: List<CapturePoint>, timeMs: Long): Double? {
        if (points.isEmpty()) return null
        val sorted = points.sortedBy { it.timestampMs }
        if (timeMs < sorted.first().timestampMs || timeMs > sorted.last().timestampMs) return null
        var low = 0
        var high = sorted.lastIndex
        var result = sorted.first()
        while (low <= high) {
            val mid = (low + high) ushr 1
            val point = sorted[mid]
            if (point.timestampMs <= timeMs) {
                result = point
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result.value
    }

    fun approximatelyEqual(a: Double, b: Double, tolerance: Double = 0.01): Boolean = abs(a - b) <= tolerance
}
