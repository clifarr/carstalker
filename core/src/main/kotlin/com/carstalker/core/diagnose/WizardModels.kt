package com.carstalker.core.diagnose

/** A guided diagnostic that combines live data, ECU self-tests and a few human observations. */
data class WizardDefinition(
    val id: String,
    val title: String,
    val subtitle: String,
    val description: String,
    val estimatedMinutes: Int,
    val safetyNotes: List<String>,
)

/** The channels a wizard wants to record, and for how long. */
data class CaptureSpec(
    val id: String,
    val title: String,
    val pids: List<Int>,
    val durationMs: Long,
    val minSamplesPerPid: Int = 20,
)

/** One value from one PID at one instant. Per-PID timestamps keep O2 switching analysis honest. */
data class CapturePoint(
    val pid: Int,
    val timestampMs: Long,
    val value: Double,
)

data class CaptureData(
    val points: List<CapturePoint>,
    val requestedPids: List<Int>,
    val startedAt: Long,
    val endedAt: Long,
    /** User-marked symptom timestamps for passive/triggered captures. */
    val markers: List<Long> = emptyList(),
) {
    val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0L)

    fun series(pid: Int): List<CapturePoint> = points.filter { it.pid == pid }

    fun sampleCount(pid: Int): Int = points.count { it.pid == pid }

    fun averageHz(pid: Int): Double {
        val values = series(pid)
        if (values.size < 2) return 0.0
        val seconds = (values.last().timestampMs - values.first().timestampMs) / 1000.0
        return if (seconds > 0.0) (values.size - 1) / seconds else 0.0
    }

    fun missingPids(): List<Int> = requestedPids.filter { sampleCount(it) == 0 }

    companion object {
        val EMPTY = CaptureData(emptyList(), emptyList(), 0L, 0L)
    }
}

data class CaptureProgress(
    val elapsedMs: Long,
    val durationMs: Long,
    val points: Int,
    val currentPid: Int?,
) {
    val fraction: Float
        get() = if (durationMs <= 0L) 0f else (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

enum class Answer { YES, NO, UNKNOWN }

enum class Likelihood(val label: String) {
    LIKELY("Likely"),
    POSSIBLE("Possible"),
    UNLIKELY("Unlikely"),
}

enum class Confidence(val label: String) {
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low"),
}

data class WizardEvidence(
    val label: String,
    val value: String,
    val source: String,
)

data class WizardCause(
    val id: String,
    val title: String,
    val likelihood: Likelihood,
    val confidence: Confidence,
    val explanation: String,
    val evidence: List<String>,
    val nextSteps: List<String>,
)

data class CaptureQuality(
    val sampleCount: Int,
    val durationMs: Long,
    val averageHz: Double,
    val missingPids: List<Int>,
    val issues: List<String>,
    val averageRpm: Double? = null,
    val averageCoolantC: Double? = null,
) {
    val usable: Boolean get() = issues.isEmpty()
}

data class WizardResult(
    val wizardId: String,
    val title: String,
    val summary: String,
    val confidence: Confidence,
    val causes: List<WizardCause>,
    val evidence: List<WizardEvidence>,
    val warnings: List<String>,
    val nextSteps: List<String>,
    val captureQuality: CaptureQuality?,
    val generatedAt: Long,
) {
    val primaryCause: WizardCause? get() = causes.firstOrNull { it.likelihood == Likelihood.LIKELY }
        ?: causes.firstOrNull()
}
