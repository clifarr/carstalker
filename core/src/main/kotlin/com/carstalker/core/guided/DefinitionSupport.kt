package com.carstalker.core.guided

/** Shared stage builders so later-phase definitions stay consistent with the Phase 1 guides. */
internal object DefinitionSupport {

    fun warmIdle(
        pids: List<Int>,
        captureMs: Long = 20_000,
        settlingMs: Long = 6_000,
        instruction: String = "Let the engine idle in park or neutral. Do not touch the throttle.",
        note: String? = null,
    ): StageDefinition = StageDefinition(
        id = "warm_idle",
        title = "Warm idle",
        instruction = instruction,
        condition = StageCondition(
            kind = StageKind.WARM_IDLE,
            rpmRange = 550..1_100,
            minCoolantC = 70.0,
            maxSpeedKph = 5.0,
            requireEngineRunning = true,
            stabilityToleranceRpm = 120,
            settlingMs = settlingMs,
            captureMs = captureMs,
        ),
        pids = pids,
        note = note,
    )

    fun steadyRpm(
        pids: List<Int>,
        rpmRange: IntRange = 2_300..2_700,
        captureMs: Long = 20_000,
        settlingMs: Long = 5_000,
        instruction: String = "Hold a steady ~2,500 rpm in park or neutral only if this is safe and appropriate for your engine. Use a light, constant throttle.",
        note: String? = null,
    ): StageDefinition = StageDefinition(
        id = "steady_rpm",
        title = "Steady ${rpmRange.first}–${rpmRange.last} rpm",
        instruction = instruction,
        condition = StageCondition(
            kind = StageKind.STEADY_RPM,
            rpmRange = rpmRange,
            minCoolantC = 70.0,
            maxSpeedKph = 5.0,
            requireEngineRunning = true,
            stabilityToleranceRpm = 180,
            settlingMs = settlingMs,
            captureMs = captureMs,
        ),
        pids = pids,
        note = note,
    )

    fun freeRev(
        pids: List<Int>,
        captureMs: Long = 20_000,
        instruction: String = "Open the throttle gently once or twice, then let it return to idle. Stay well below the redline.",
        note: String? = null,
    ): StageDefinition = StageDefinition(
        id = "throttle_response",
        title = "Throttle response",
        instruction = instruction,
        condition = StageCondition(
            kind = StageKind.FREE_REV,
            minCoolantC = 70.0,
            requireEngineRunning = true,
            stabilityToleranceRpm = 0,
            settlingMs = 3_000,
            captureMs = captureMs,
        ),
        pids = pids,
        note = note,
    )

    fun keyOn(
        pids: List<Int>,
        captureMs: Long = 6_000,
        instruction: String = "Turn the ignition on but do not start the engine. Leave the throttle closed.",
    ): StageDefinition = StageDefinition(
        id = "key_on_engine_off",
        title = "Key on, engine off",
        instruction = instruction,
        condition = StageCondition(
            kind = StageKind.KEY_ON_ENGINE_OFF,
            requireEngineOff = true,
            stabilityToleranceRpm = 0,
            settlingMs = 2_000,
            captureMs = captureMs,
        ),
        pids = pids,
    )

    fun passiveCapture(
        pids: List<Int>,
        captureMs: Long = 60_000,
        minSpeedKph: Double = 10.0,
        instruction: String = "Drive normally with a passenger operating the app. Tap Mark symptom when the power loss occurs; do not use the phone while driving.",
    ): StageDefinition = StageDefinition(
        id = "passive_capture",
        title = "Passive symptom capture",
        instruction = instruction,
        condition = StageCondition(
            kind = StageKind.PASSIVE_CAPTURE,
            minSpeedKph = minSpeedKph,
            requireEngineRunning = true,
            stabilityToleranceRpm = 0,
            settlingMs = 2_000,
            captureMs = captureMs,
        ),
        pids = pids,
        allowFinishEarly = true,
        note = "The marker is manual, so event timing depends on the passenger and the adapter sample rate.",
    )

    fun cranking(
        pids: List<Int>,
        captureMs: Long = 8_000,
        instruction: String = "Crank the engine as you normally would. Stop cranking if it starts or if anything sounds abnormal.",
    ): StageDefinition = StageDefinition(
        id = "cranking",
        title = "Cranking",
        instruction = instruction,
        condition = StageCondition(
            kind = StageKind.CRANKING,
            rpmRange = 60..700,
            requireEngineRunning = false,
            stabilityToleranceRpm = 0,
            settlingMs = 0,
            captureMs = captureMs,
        ),
        pids = pids,
        allowFinishEarly = true,
        note = "If the engine starts, stop cranking. The stage can be finished early.",
    )
}
