package com.carstalker.app.data

/**
 * Sampling gaps in a recording.
 *
 * Android can freeze an app it considers idle even while a foreground service and a partial
 * wake lock are held; MIUI/HyperOS do this aggressively once the screen is off. The app then
 * keeps claiming "Recording" while nothing is written, and the CSV has a hole that nothing in
 * the file explains. Recording these gaps makes the loss visible instead of silent.
 */
data class RecordingStall(
    /** Longest single gap, in milliseconds. */
    val longestMs: Long,
    /** Every detected gap added together, in milliseconds. */
    val totalMs: Long,
    /** How many gaps were detected. */
    val count: Int,
    /** Wall-clock time the most recent gap was noticed. */
    val detectedAt: Long,
)

/**
 * A gap shorter than this is a slow Bluetooth Classic cycle, not a stall. The slowest supported
 * adapter still answers one exchange well inside this window, so a longer silence means the poll
 * loop itself was not running.
 */
const val SAMPLING_STALL_THRESHOLD_MS = 10_000L

/**
 * Returns the length of the gap between [lastProgressAt] and [now] when it is long enough to be
 * a stall. A loop that has not completed a cycle yet ([lastProgressAt] == 0) and a clock that
 * moved backwards are not stalls.
 */
fun samplingGapMs(
    lastProgressAt: Long,
    now: Long,
    thresholdMs: Long = SAMPLING_STALL_THRESHOLD_MS,
): Long? {
    if (lastProgressAt <= 0L || now <= lastProgressAt) return null
    val gap = now - lastProgressAt
    return gap.takeIf { it >= thresholdMs }
}
