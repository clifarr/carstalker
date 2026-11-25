package com.carstalker.core.obd

/**
 * Decides when live values stop being live.
 *
 * An ELM327 stays powered and answers `ATRV` after the ignition is switched off, so adapter
 * liveness says nothing about the vehicle. Only a sample from the ECU restarts the window; a
 * request that produced no answer is the evidence that the vehicle has gone away.
 *
 * Requiring a failed request matters for dashboards that poll slowly: a parameter scheduled
 * every fourth cycle can legitimately have no answer due for several seconds, and that gap
 * must not be mistaken for an outage.
 */
class LiveDataFreshness(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    @Volatile
    private var sessionStartedAt: Long = 0L

    @Volatile
    private var lastEcuSampleAt: Long = 0L

    @Volatile
    private var lastMissAt: Long = 0L

    /** Starts a polling session; the ECU gets one full window to answer before it is offline. */
    fun start(now: Long) {
        sessionStartedAt = now
        lastEcuSampleAt = 0L
        lastMissAt = 0L
    }

    /** Records a decoded PID sample. Adapter-only measurements must not call this. */
    fun sample(now: Long) {
        lastEcuSampleAt = now
    }

    /** Records a request that produced no ECU answer. */
    fun miss(now: Long) {
        lastMissAt = now
    }

    /**
     * True when the dashboard expects ECU values, at least one request has gone unanswered since
     * the last sample, and that sample is older than [timeoutMs]. A voltage-only dashboard never
     * goes offline: there is nothing from the ECU to wait for.
     */
    fun isOffline(now: Long, ecuExpected: Boolean): Boolean {
        if (!ecuExpected) return false
        if (lastMissAt <= lastEcuSampleAt) return false
        val reference = if (lastEcuSampleAt > 0) lastEcuSampleAt else sessionStartedAt
        return reference > 0 && now - reference >= timeoutMs
    }

    /** Wall-clock time of the last ECU sample, or 0 when none has arrived since [start]. */
    val lastSampleAt: Long get() = lastEcuSampleAt

    fun reset() {
        sessionStartedAt = 0L
        lastEcuSampleAt = 0L
        lastMissAt = 0L
    }

    companion object {
        /**
         * Longer than a slow Bluetooth Classic poll cycle, short enough that switching the
         * ignition off is visible within a few seconds.
         */
        const val DEFAULT_TIMEOUT_MS = 2_500L
    }
}
