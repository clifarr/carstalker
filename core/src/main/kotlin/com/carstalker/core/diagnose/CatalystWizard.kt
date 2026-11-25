package com.carstalker.core.diagnose

import com.carstalker.core.obd.Mode06Report
import java.util.Locale

/** The catalyst wizard's human-input answers. */
data class CatalystAnswers(
    val engineHealthyConfirmed: Boolean = false,
    val flashingMil: Boolean = false,
    val fuelLevelOk: Boolean = false,
    val exhaustLeakSuspected: Answer = Answer.UNKNOWN,
    val recentExhaustWork: Answer = Answer.UNKNOWN,
    val oilConsumption: Answer = Answer.UNKNOWN,
    val coolantLoss: Answer = Answer.UNKNOWN,
    val downstreamRespondedQuickly: Answer = Answer.UNKNOWN,
    val inletTempC: Double? = null,
    val outletTempC: Double? = null,
    val rottenEggSmell: Boolean = false,
    val poorMileage: Boolean = false,
    val powerLoss: Boolean = false,
)

data class CatalystInput(
    val dtcs: List<String>,
    val mode06: Mode06Report,
    val capture: CaptureData?,
    val answers: CatalystAnswers,
)

/**
 * Deterministic catalyst-vs-O2 analysis.
 *
 * The rules are deliberately conservative: a P0420 plus a matching downstream O2 trace is
 * supporting evidence, not a parts order. Engine health, exhaust leaks and downstream-sensor
 * behaviour are checked first, and Mode 06 is preferred over a short ELM327 capture.
 */
object CatalystWizard {

    const val ID = "catalyst_o2"
    const val UPSTREAM_PID = 0x14
    const val DOWNSTREAM_PID = 0x15
    const val RPM_PID = 0x0C
    const val COOLANT_PID = 0x05

    val definition = WizardDefinition(
        id = ID,
        title = "Catalyst or oxygen sensor?",
        subtitle = "P0420 / P0430 · catalyst efficiency",
        description = "Compare the upstream and downstream oxygen sensors, read the ECU's Mode 06 catalyst test, and rule out the faults that mimic a dead converter before spending money on one.",
        estimatedMinutes = 15,
        safetyNotes = listOf(
            "Do not interact with the phone while driving. Use a passenger or set up the capture before you drive.",
            "Hot exhaust and moving engine parts: use gloves, eye protection, and never work under a running car.",
            "A flashing check-engine light or active misfire can destroy the catalyst. Stop driving and fix the misfire first.",
            "Do not use O2 spacers, non-foulers or a converter delete to fake the monitor. It may violate emissions laws where you drive and does not fix the cause.",
        ),
    )

    val captureSpec = CaptureSpec(
        id = "catalyst_o2_capture",
        title = "Upstream and downstream O2",
        pids = listOf(UPSTREAM_PID, DOWNSTREAM_PID, RPM_PID, COOLANT_PID),
        durationMs = 30_000,
        minSamplesPerPid = 20,
    )

    /** Result of the catalyst wizard's safety gate. */
    data class SafetyGate(
        val canProceed: Boolean,
        val blockedReason: String?,
    )

    /**
     * One predicate for the catalyst wizard. A danger condition always wins over a positive
     * attestation, and the capture is blocked until every required positive is confirmed.
     */
    fun safetyGate(
        engineHealthy: Boolean,
        fuelLevelOk: Boolean,
        flashingMil: Boolean,
        activeMisfireCodes: List<String> = emptyList(),
    ): SafetyGate {
        val misfires = activeMisfireCodes.filter { it.trim().uppercase().startsWith("P030") }
        val reason = when {
            flashingMil ->
                "A flashing MIL or active misfire can destroy the catalyst. Stop here and fix the misfire first."
            misfires.isNotEmpty() ->
                "Misfire code(s) ${misfires.joinToString()} are present. Fix the misfire before testing the catalyst."
            !engineHealthy ->
                "Confirm the engine is not misfiring and the MIL is not flashing."
            !fuelLevelOk ->
                "Confirm the fuel level is between 1/4 and 3/4."
            else -> null
        }
        return SafetyGate(canProceed = reason == null, blockedReason = reason)
    }

    fun analyze(input: CatalystInput): WizardResult {
        val codes = input.dtcs.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
        val misfireCodes = codes.filter { it.startsWith("P030") }
        val trimCodes = codes.filter { it in setOf("P0171", "P0172", "P0174", "P0175") }
        val o2Codes = codes.filter { it.startsWith("P013") || it.startsWith("P014") }
        val overheatCodes = codes.filter { it == "P0128" || it == "P0217" }
        val answers = input.answers

        val upstream = input.capture?.series(UPSTREAM_PID).orEmpty()
        val downstream = input.capture?.series(DOWNSTREAM_PID).orEmpty()
        val upstreamStats = SignalAnalysis.stats(upstream)
        val downstreamStats = SignalAnalysis.stats(downstream)
        val correlation = if (upstream.size >= 5 && downstream.size >= 5) {
            SignalAnalysis.correlation(upstream, downstream)
        } else {
            null
        }

        val quality = captureQuality(input.capture, upstreamStats, downstreamStats)
        val mode06 = mode06Catalyst(input.mode06)
        // Sparse or asynchronous O2 sampling cannot support a high-confidence converter verdict.
        val upstreamHz = input.capture?.averageHz(UPSTREAM_PID) ?: 0.0
        val downstreamHz = input.capture?.averageHz(DOWNSTREAM_PID) ?: 0.0
        val samplingAdequate = input.capture != null &&
            upstreamStats.count >= 30 &&
            downstreamStats.count >= 30 &&
            upstreamStats.switchingCount >= 8 &&
            minOf(upstreamHz, downstreamHz) >= 1.5 &&
            input.capture.durationMs >= 20_000
        val samplingNote = if (samplingAdequate) {
            null
        } else {
            "O2 sampling was too sparse or too slow for a high-confidence converter verdict " +
                "(${oneDecimal(upstreamStats.frequencyHz)} Hz upstream, ${oneDecimal(downstreamStats.frequencyHz)} Hz downstream, " +
                "${upstreamStats.switchingCount} switches)."
        }
        val engineRisk = answers.flashingMil ||
            misfireCodes.isNotEmpty() ||
            trimCodes.isNotEmpty() ||
            overheatCodes.isNotEmpty() ||
            answers.oilConsumption == Answer.YES ||
            answers.coolantLoss == Answer.YES
        val exhaustRisk = answers.exhaustLeakSuspected == Answer.YES ||
            answers.recentExhaustWork == Answer.YES

        val upstreamActive = upstreamStats.count >= 10 &&
            upstreamStats.amplitude >= 0.30 &&
            upstreamStats.switchingCount >= 4
        val downstreamSteady = downstreamStats.count >= 10 &&
            downstreamStats.stdDev <= 0.10 &&
            downstreamStats.amplitude <= 0.30 &&
            downstreamStats.mean in 0.35..0.90 &&
            downstreamStats.switchingCount <= 4
        val downstreamMirrors = upstreamActive &&
            downstreamStats.amplitude >= 0.35 * upstreamStats.amplitude &&
            downstreamStats.switchingCount >= maxOf(3, upstreamStats.switchingCount / 2) &&
            (
                (correlation != null && correlation >= 0.45) ||
                    downstreamStats.switchingCount >= (upstreamStats.switchingCount * 0.60).toInt()
                )
        val downstreamStuck = downstreamStats.count >= 10 &&
            downstreamStats.stdDev <= 0.02 &&
            (downstreamStats.mean < 0.20 || downstreamStats.mean > 0.90)
        val downstreamLazy = answers.downstreamRespondedQuickly == Answer.NO
        val upstreamFlat = upstreamStats.count >= 10 && upstreamStats.amplitude <= 0.15
        val tempDelta = if (answers.inletTempC != null && answers.outletTempC != null) {
            answers.outletTempC - answers.inletTempC
        } else {
            null
        }
        val tempAgainstCat = tempDelta != null && tempDelta <= 5.0
        val tempSupportsCat = tempDelta != null && tempDelta >= 20.0

        val sensorScore = (if (downstreamStuck) 3 else 0) +
            (if (downstreamLazy) 3 else 0) +
            (if (downstreamStats.count >= 10 && downstreamStats.mean !in 0.20..0.90) 1 else 0) +
            (if (upstreamFlat) 2 else 0) -
            (if (downstreamMirrors) 1 else 0)

        val catConfirmed = !engineRisk && !exhaustRisk && !downstreamStuck && !downstreamLazy &&
            mode06 != Mode06State.PASS &&
            (
                (mode06 == Mode06State.FAIL && downstreamMirrors && samplingAdequate) ||
                    (downstreamMirrors && tempAgainstCat && samplingAdequate) ||
                    (mode06 == Mode06State.FAIL && tempAgainstCat && samplingAdequate)
                )
        val catProbablyOk = !engineRisk && !exhaustRisk && !downstreamStuck && !downstreamLazy &&
            !downstreamMirrors &&
            (mode06 == Mode06State.PASS || (mode06 == Mode06State.UNKNOWN && downstreamSteady))
        val sensorLikely = !engineRisk && !exhaustRisk && (downstreamStuck || downstreamLazy || sensorScore >= 3)
        val conflicting = !engineRisk && !exhaustRisk &&
            ((mode06 == Mode06State.FAIL && downstreamSteady && !downstreamMirrors) ||
                (mode06 == Mode06State.PASS && downstreamMirrors))
        val mode06FailOnly = mode06 == Mode06State.FAIL &&
            !engineRisk && !exhaustRisk && !downstreamMirrors && !downstreamSteady && !sensorLikely
        val prepIncomplete = !answers.engineHealthyConfirmed || !answers.fuelLevelOk

        val causes = mutableListOf<WizardCause>()
        val evidence = mutableListOf<WizardEvidence>()
        val warnings = mutableListOf<String>()
        val nextSteps = mutableListOf<String>()

        if (engineRisk) {
            causes += WizardCause(
                id = "engine_root_cause",
                title = "Engine fault is the root cause",
                likelihood = Likelihood.LIKELY,
                confidence = Confidence.HIGH,
                explanation = "A misfire, rich/lean mixture, overheating, oil consumption or coolant loss can damage the catalyst and invalidate the O2 comparison.",
                evidence = buildList {
                    if (answers.flashingMil) add("Flashing MIL reported")
                    if (misfireCodes.isNotEmpty()) add("Misfire codes: ${misfireCodes.joinToString()}")
                    if (trimCodes.isNotEmpty()) add("Fuel-trim codes: ${trimCodes.joinToString()}")
                    if (overheatCodes.isNotEmpty()) add("Temperature codes: ${overheatCodes.joinToString()}")
                    if (answers.oilConsumption == Answer.YES) add("Oil consumption reported")
                    if (answers.coolantLoss == Answer.YES) add("Coolant loss reported")
                },
                nextSteps = listOf(
                    "Fix the misfire, fuel-trim or cooling fault first.",
                    "Do not replace the converter until the engine is healthy.",
                    "Re-run this test after the repair.",
                ),
            )
        }

        if (exhaustRisk) {
            causes += WizardCause(
                id = "exhaust_leak",
                title = "Exhaust leak before the downstream sensor",
                likelihood = Likelihood.LIKELY,
                confidence = Confidence.MEDIUM,
                explanation = "A leak pulls fresh air into the exhaust and can make a healthy catalyst look inefficient, or a bad one look different.",
                evidence = buildList {
                    if (answers.exhaustLeakSuspected == Answer.YES) add("Hiss, soot or leak reported")
                    if (answers.recentExhaustWork == Answer.YES) add("Recent exhaust work reported")
                },
                nextSteps = listOf(
                    "Inspect the manifold, downpipe, flex section and flange for soot or leaks.",
                    "Repair the leak before testing the catalyst again.",
                ),
            )
        }

        if (catConfirmed) {
            causes += WizardCause(
                id = "catalyst_worn",
                title = "Catalytic converter is likely worn",
                likelihood = Likelihood.LIKELY,
                confidence = if (mode06 == Mode06State.FAIL && downstreamMirrors && samplingAdequate) {
                    Confidence.HIGH
                } else {
                    Confidence.MEDIUM
                },
                explanation = "The downstream sensor follows the upstream switching and/or the ECU's catalyst monitor failed, after engine and exhaust faults were ruled out. " +
                    (samplingNote ?: "The O2 sample rate was adequate for this comparison."),
                evidence = buildList {
                    if (mode06 == Mode06State.FAIL) add("Mode 06 catalyst test failed")
                    if (downstreamMirrors) add("Downstream O2 mirrors upstream switching")
                    if (tempAgainstCat) add("Outlet temperature is not higher than inlet")
                    if (answers.rottenEggSmell) add("Rotten-egg smell reported")
                    if (answers.poorMileage) add("Poor fuel mileage reported")
                    if (answers.powerLoss) add("Power loss reported")
                },
                nextSteps = listOf(
                    "Get a second confirmation: a temperature test or a shop backpressure test.",
                    "Verify there are no exhaust leaks or engine faults before buying a converter.",
                    "Replace only with a correct, legal converter for the vehicle.",
                ),
            )
        }

        if (sensorLikely) {
            causes += WizardCause(
                id = "downstream_o2_sensor",
                title = "Downstream oxygen sensor is lazy or stuck",
                likelihood = Likelihood.LIKELY,
                confidence = if (downstreamStuck || downstreamLazy) Confidence.HIGH else Confidence.MEDIUM,
                explanation = "A slow or stuck downstream sensor can mimic a failed catalyst. The sensor must respond to a mixture change before its data can condemn the converter.",
                evidence = buildList {
                    if (downstreamStuck) add("Downstream signal is flat at an extreme voltage")
                    if (downstreamLazy) add("Downstream did not respond within ~3 seconds to a throttle snap")
                    if (downstreamStats.count >= 10) add("Downstream mean ${volts(downstreamStats.mean)}, range ${volts(downstreamStats.min)}–${volts(downstreamStats.max)}")
                },
                nextSteps = listOf(
                    "Check the downstream sensor connector, wiring and heater circuit.",
                    "Verify the sensor responds to a throttle snap before replacing it.",
                    "Use a known-good part; counterfeit sensors are common.",
                ),
            )
        }

        if (catProbablyOk) {
            causes += WizardCause(
                id = "catalyst_ok",
                title = "Catalyst looks healthy",
                likelihood = Likelihood.LIKELY,
                confidence = if (mode06 == Mode06State.PASS && downstreamSteady) Confidence.HIGH else Confidence.MEDIUM,
                explanation = "The ECU's catalyst monitor passed and/or the downstream sensor stayed steady instead of following the upstream switching.",
                evidence = buildList {
                    if (mode06 == Mode06State.PASS) add("Mode 06 catalyst test passed")
                    if (downstreamSteady) add("Downstream O2 is steady (${volts(downstreamStats.mean)}, ${volts(downstreamStats.min)}–${volts(downstreamStats.max)})")
                    if (tempSupportsCat) add("Outlet temperature is higher than inlet")
                },
                nextSteps = listOf(
                    "Look for a lazy downstream sensor, exhaust leak or a known false-positive/TSB.",
                    "Check the sensor response and part number before replacing anything.",
                    "Re-read Mode 06 after a full drive cycle.",
                ),
            )
        }

        if (conflicting) {
            causes += WizardCause(
                id = "conflicting_evidence",
                title = "Evidence is conflicting",
                likelihood = Likelihood.POSSIBLE,
                confidence = Confidence.MEDIUM,
                explanation = "Mode 06 and the live O2 trace disagree. That usually means the capture conditions were wrong, the sensor is lazy, or the ECU monitor has not run under valid conditions.",
                evidence = buildList {
                    if (mode06 == Mode06State.FAIL) add("Mode 06 catalyst failed but downstream looked steady")
                    if (mode06 == Mode06State.PASS) add("Mode 06 catalyst passed but downstream followed upstream")
                },
                nextSteps = listOf(
                    "Re-run the capture at a steady 2,500 rpm or 50–60 km/h.",
                    "Read Mode 06 again after a full drive cycle.",
                    "If the disagreement persists, get a shop temperature/backpressure test.",
                ),
            )
        }

        if (mode06FailOnly) {
            causes += WizardCause(
                id = "catalyst_possible",
                title = "Catalyst may be worn",
                likelihood = Likelihood.POSSIBLE,
                confidence = Confidence.MEDIUM,
                explanation = "The ECU's catalyst monitor failed, but the live capture did not confirm the downstream sensor mirroring the upstream signal.",
                evidence = buildList {
                    add("Mode 06 catalyst test failed")
                    if (quality != null && !quality.usable) add("Capture quality: ${quality.issues.joinToString("; ")}")
                },
                nextSteps = listOf(
                    "Re-run the capture at a steady 2,500 rpm.",
                    "Get a temperature or backpressure confirmation before buying a converter.",
                ),
            )
        }

        if (!engineRisk && !exhaustRisk && !catConfirmed && !catProbablyOk && !sensorLikely && !conflicting && !mode06FailOnly) {
            causes += WizardCause(
                id = "inconclusive",
                title = "Not enough evidence yet",
                likelihood = Likelihood.POSSIBLE,
                confidence = Confidence.LOW,
                explanation = "The capture or Mode 06 data was not strong enough to separate a worn catalyst from a sensor or test-condition problem.",
                evidence = buildList {
                    if (quality != null) add("Capture: ${quality.issues.joinToString("; ").ifBlank { "usable" }}")
                    if (mode06 == Mode06State.UNKNOWN) add("No completed Mode 06 catalyst test")
                },
                nextSteps = listOf(
                    "Warm the engine fully and re-run a 30-second capture at a steady 2,500 rpm.",
                    "Drive a full cycle and read Mode 06 again.",
                    "Consider a shop temperature or backpressure test.",
                ),
            )
        }

        if (mode06 == Mode06State.PASS) {
            causes += WizardCause(
                id = "false_positive",
                title = "False positive or monitor condition",
                likelihood = Likelihood.POSSIBLE,
                confidence = Confidence.MEDIUM,
                explanation = "The catalyst monitor can fail on one drive cycle and pass on another; some vehicles also have calibration/TSB fixes for P0420.",
                evidence = listOf("Mode 06 catalyst test passed on this read"),
                nextSteps = listOf(
                    "Check for a known TSB or ECU calibration update for this vehicle.",
                    "Re-check after a full drive cycle before replacing parts.",
                ),
            )
        }

        evidence += WizardEvidence("Trouble codes", codes.ifEmpty { listOf("None") }.joinToString(), "OBD")
        if (o2Codes.isNotEmpty()) {
            evidence += WizardEvidence("O2 sensor codes", o2Codes.joinToString(), "OBD")
        }
        evidence += WizardEvidence(
            "Mode 06 catalyst",
            when (mode06) {
                Mode06State.PASS -> "PASS (verified monitor identity)"
                Mode06State.FAIL -> "FAIL (verified monitor identity)"
                Mode06State.UNKNOWN -> when (input.mode06.state) {
                    com.carstalker.core.obd.MonitorDataState.NOT_REQUESTED -> "Not checked"
                    com.carstalker.core.obd.MonitorDataState.UNSUPPORTED -> "Not supported"
                    com.carstalker.core.obd.MonitorDataState.FAILED_TO_READ -> "Could not read"
                    else -> "No completed catalyst monitor test"
                }
            },
            "Mode 06",
        )
        val untrusted = input.mode06.results.count { !it.mappingTrusted }
        if (untrusted > 0) {
            evidence += WizardEvidence(
                "Mode 06 decoding",
                "$untrusted result(s) had an unrecognised monitor/unit mapping and were excluded",
                "Mode 06",
            )
        }
        if (upstreamStats.count > 0) {
            evidence += WizardEvidence(
                "Upstream O2",
                "${volts(upstreamStats.min)}–${volts(upstreamStats.max)}, ${oneDecimal(upstreamStats.frequencyHz)} Hz, ${upstreamStats.switchingCount} switches",
                "Live capture",
            )
        }
        if (downstreamStats.count > 0) {
            evidence += WizardEvidence(
                "Downstream O2",
                "${volts(downstreamStats.min)}–${volts(downstreamStats.max)}, ${oneDecimal(downstreamStats.frequencyHz)} Hz, ${downstreamStats.switchingCount} switches",
                "Live capture",
            )
        }
        correlation?.let {
            evidence += WizardEvidence("Upstream/downstream correlation", twoDecimals(it), "Live capture")
        }
        quality?.let {
            evidence += WizardEvidence(
                "Capture quality",
                "${it.sampleCount} samples, ${oneDecimal(it.averageHz)} Hz, ${it.durationMs / 1000}s",
                "Live capture",
            )
            it.averageRpm?.let { rpm ->
                evidence += WizardEvidence("Engine speed", "${oneDecimal(rpm)} rpm", "Live capture")
            }
            it.averageCoolantC?.let { coolant ->
                evidence += WizardEvidence("Coolant", "${oneDecimal(coolant)} °C", "Live capture")
            }
        }
        tempDelta?.let {
            evidence += WizardEvidence(
                "Converter temperature",
                "Inlet ${oneDecimal(answers.inletTempC!!)}°C, outlet ${oneDecimal(answers.outletTempC!!)}°C (Δ ${oneDecimal(it)}°C)",
                "Manual",
            )
        }

        warnings += "Do not replace the catalytic converter on P0420/P0430 alone."
        warnings += "Do not use O2 spacers, non-foulers or a converter delete. It may violate emissions laws where you drive and does not fix the cause."
        if (!answers.engineHealthyConfirmed) {
            warnings += "You have not confirmed the engine is healthy; treat this result as provisional."
        }
        if (!answers.fuelLevelOk) {
            warnings += "Fuel level outside 1/4–3/4 can prevent the catalyst monitor from running."
        }
        if (answers.flashingMil || misfireCodes.isNotEmpty()) {
            warnings += "A flashing MIL or active misfire can destroy the catalyst. Stop driving and fix the misfire first."
        }
        if (catConfirmed) {
            warnings += "Confirm with a second test (temperature or backpressure) before buying a converter."
        }
        samplingNote?.let { warnings += it }
        if (sensorLikely) {
            warnings += "Check the sensor circuit and part number before replacing the converter."
        }

        nextSteps += when {
            engineRisk -> "Repair the engine fault first, then repeat the catalyst test."
            exhaustRisk -> "Repair the exhaust leak first, then repeat the test."
            catConfirmed -> "Get a second confirmation, then replace the converter only if the evidence still points to it."
            sensorLikely -> "Test the downstream sensor response and circuit, then re-test."
            catProbablyOk -> "Check for a lazy sensor, exhaust leak, or a known TSB/false positive."
            else -> "Re-run the capture at a steady 2,500 rpm and read Mode 06 after a full drive cycle."
        }

        val rawConfidence = when {
            engineRisk || exhaustRisk -> Confidence.LOW
            conflicting -> Confidence.MEDIUM
            catConfirmed && mode06 == Mode06State.FAIL && downstreamMirrors && samplingAdequate -> Confidence.HIGH
            catProbablyOk && mode06 == Mode06State.PASS && downstreamSteady && samplingAdequate -> Confidence.HIGH
            sensorLikely && (downstreamStuck || downstreamLazy) && samplingAdequate -> Confidence.HIGH
            mode06 == Mode06State.FAIL && !engineRisk && !exhaustRisk -> Confidence.MEDIUM
            samplingAdequate && (quality?.usable == true || mode06 != Mode06State.UNKNOWN) -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        val confidence = if (prepIncomplete && rawConfidence == Confidence.HIGH) Confidence.MEDIUM else rawConfidence

        val summary = when {
            engineRisk -> "Fix the engine fault first. The catalyst test cannot be trusted until misfires, fuel trims, oil loss and coolant loss are addressed."
            exhaustRisk -> "Fix the exhaust leak first. A leak before the downstream sensor can mimic a failed catalyst."
            catConfirmed -> "The catalyst is the most likely cause, but confirm with a temperature or backpressure test before buying a converter."
            sensorLikely -> "The downstream oxygen sensor is the most likely cause. Test its response and circuit before replacing the converter."
            conflicting -> "Mode 06 and the live O2 trace disagree. Re-run the capture at a steady 2,500 rpm and read Mode 06 after a full drive cycle."
            mode06FailOnly -> "Mode 06 says the catalyst failed, but the live capture did not confirm it. Re-run the capture or get a temperature/backpressure test."
            catProbablyOk -> "The catalyst looks healthy. The code is more likely a sensor, exhaust leak or false positive."
            else -> "The evidence is mixed. Re-run the capture at a steady 2,500 rpm or read Mode 06 after a full drive cycle."
        }

        return WizardResult(
            wizardId = ID,
            title = definition.title,
            summary = summary,
            confidence = confidence,
            causes = causes.sortedWith(
                compareBy<WizardCause> { it.likelihood.ordinal }
                    .thenBy { it.confidence.ordinal },
            ),
            evidence = evidence,
            warnings = warnings.distinct(),
            nextSteps = nextSteps.distinct(),
            captureQuality = quality,
            generatedAt = System.currentTimeMillis(),
        )
    }

    private enum class Mode06State { PASS, FAIL, UNKNOWN }

    private fun mode06Catalyst(report: Mode06Report): Mode06State {
        if (report.state != com.carstalker.core.obd.MonitorDataState.AVAILABLE) return Mode06State.UNKNOWN
        val results = report.trustedResults.filter { it.mid == 0x21 || it.mid == 0x22 }
        if (results.isEmpty()) return Mode06State.UNKNOWN
        if (results.any { it.passed == false }) return Mode06State.FAIL
        if (results.any { it.passed == true }) return Mode06State.PASS
        return Mode06State.UNKNOWN
    }

    private fun captureQuality(
        capture: CaptureData?,
        upstream: SignalStats,
        downstream: SignalStats,
    ): CaptureQuality? {
        if (capture == null || capture.points.isEmpty()) return null
        val issues = mutableListOf<String>()
        if (upstream.count < captureSpec.minSamplesPerPid) issues += "Not enough upstream O2 samples"
        if (downstream.count < captureSpec.minSamplesPerPid) issues += "Not enough downstream O2 samples"
        if (capture.durationMs < 10_000) issues += "Capture shorter than 10 seconds"
        val hz = minOf(capture.averageHz(UPSTREAM_PID), capture.averageHz(DOWNSTREAM_PID))
        if (hz in 0.0..0.99) issues += "Sample rate below 1 Hz"

        val rpm = capture.series(RPM_PID)
        val coolant = capture.series(COOLANT_PID)
        val rpmMean = if (rpm.size >= 5) SignalAnalysis.stats(rpm).mean else null
        val coolantMean = if (coolant.size >= 5) SignalAnalysis.stats(coolant).mean else null
        if (rpmMean != null && rpmMean < 1_000) issues += "Engine speed too low; hold ~2,500 rpm"
        if (rpmMean != null && rpmMean > 4_000) issues += "Engine speed too high; hold ~2,500 rpm"
        if (coolantMean != null && coolantMean < 70) issues += "Coolant below 70 °C; warm the engine first"

        return CaptureQuality(
            sampleCount = minOf(upstream.count, downstream.count),
            durationMs = capture.durationMs,
            averageHz = hz,
            missingPids = capture.missingPids(),
            issues = issues,
            averageRpm = rpmMean,
            averageCoolantC = coolantMean,
        )
    }

    private fun volts(value: Double): String = String.format(Locale.US, "%.2f V", value)

    private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun twoDecimals(value: Double): String = String.format(Locale.US, "%.2f", value)
}
