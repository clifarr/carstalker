package com.carstalker.core.obd

/**
 * Decides which parameters to request on each poll cycle.
 *
 * An ELM327 handles one command at a time, so the only way to keep the gauges responsive is
 * to ask for fast-changing values more often than slow ones, skip parameters the ECU does not
 * advertise, and stop asking for parameters that never answer.
 */
class PollScheduler(
    private val fast: Set<Int> = DEFAULT_FAST,
    private val medium: Set<Int> = DEFAULT_MEDIUM,
    private val silentLimit: Int = DEFAULT_SILENT_LIMIT,
) {

    private val misses = mutableMapOf<Int, Int>()
    private val silent = mutableSetOf<Int>()

    var cycle: Long = 0L
        private set

    fun advance() {
        cycle++
    }

    /** How often a parameter is polled, in cycles. */
    fun intervalFor(pidId: Int): Long = when (pidId) {
        in fast -> 1L
        in medium -> 2L
        else -> 4L
    }

    /**
     * @param requested parameters the dashboard wants
     * @param supported PIDs the ECU advertises; empty means "not known yet"
     * @param exempt parameters that have no support bitmap, such as custom PIDs
     */
    fun next(requested: List<Int>, supported: Set<Int>, exempt: Set<Int> = emptySet()): List<Int> =
        requested.distinct().filter { id ->
            when {
                id in silent -> false
                id !in exempt && supported.isNotEmpty() && id !in supported -> false
                cycle % intervalFor(id) == 0L -> true
                else -> false
            }
        }

    fun record(pidId: Int, answered: Boolean) {
        if (answered) {
            misses.remove(pidId)
            return
        }
        val count = (misses[pidId] ?: 0) + 1
        misses[pidId] = count
        if (count >= silentLimit) silent += pidId
    }

    fun reset() {
        misses.clear()
        silent.clear()
        cycle = 0
    }

    fun isSilent(pidId: Int): Boolean = pidId in silent

    companion object {
        /** What the driver watches: polled every cycle. PID 42 is here for cranking dips. */
        val DEFAULT_FAST = setOf(0x0C, 0x0D, 0x42)

        /** Changes over seconds: polled every other cycle. */
        val DEFAULT_MEDIUM = setOf(0x04, 0x05, 0x0F, 0x10, 0x11, 0x2F, 0x5E)

        const val DEFAULT_SILENT_LIMIT = 3
    }
}
