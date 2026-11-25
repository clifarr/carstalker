package com.carstalker.app.transport.demo

import com.carstalker.core.transport.Transport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.random.Random

/** A driving/operating condition the demo adapter can imitate. */
enum class DemoScenario(val label: String) {
    DRIVE("Driving cycle"),
    IDLE("Warm idle"),
    STEADY_2500("Steady 2,500 rpm"),
    STEADY_2000("Steady 2,000 rpm"),
    THROTTLE_BLIP("Throttle blip"),
    RICH_IDLE("Rich idle (−18 % trims)"),
    VOLTAGE_SPIKE("Voltage spikes"),
    ENGINE_OFF("Ignition on, engine off"),
    ELECTRICAL_LOAD("Idle with electrical load"),
    COLD_START("Cold start warm-up"),
    CRANKING("Cranking"),
}

/** Implemented by transports that can be steered for guided-test demonstrations. */
interface DemoControllable {
    fun setScenario(scenario: DemoScenario)
    val scenario: DemoScenario
}

/**
 * Self-contained ELM327 simulator so the app can be explored without an adapter or a car.
 * The default scenario follows smooth sine curves; guided tests can steer it to a stable
 * operating condition through [DemoControllable].
 */
class DemoTransport : Transport, DemoControllable {

    override val displayName: String = "Demo · 2013 BRZ"

    private val responses = Channel<ByteArray>(Channel.UNLIMITED)
    private val startNanos = System.nanoTime()
    private val commandBuffer = StringBuilder()
    private var codesCleared = false

    @Volatile
    override var scenario: DemoScenario = DemoScenario.DRIVE
        private set

    @Volatile
    private var scenarioStartNanos: Long = System.nanoTime()

    override fun setScenario(scenario: DemoScenario) {
        this.scenario = scenario
        scenarioStartNanos = System.nanoTime()
    }

    override suspend fun connect() = Unit

    override suspend fun write(data: ByteArray) {
        commandBuffer.append(String(data, Charsets.US_ASCII))
        while (true) {
            val end = commandBuffer.indexOf('\r')
            if (end < 0) break
            val command = commandBuffer.substring(0, end).trim().uppercase().replace(" ", "")
            commandBuffer.delete(0, end + 1)
            simulateLatency(command)
            responses.trySend(handle(command).toByteArray(Charsets.US_ASCII))
        }
    }

    override suspend fun read(timeoutMs: Long): ByteArray =
        withTimeoutOrNull(timeoutMs) { responses.receive() } ?: ByteArray(0)

    override suspend fun discardPending() {
        while (responses.tryReceive().isSuccess) Unit
    }

    override suspend fun close() {
        responses.close()
    }

    /**
     * A real ELM327 is a serial request/response device, not an instant in-memory lookup. Without
     * this delay the demo answers every PID in a few milliseconds, which makes the dashboard and
     * guided captures look far faster than any real adapter. The values include a little jitter so
     * cycle times and effective sample rates look plausible rather than perfectly periodic.
     */
    private suspend fun simulateLatency(command: String) {
        val delayMs = when {
            command == "ATZ" -> 350L
            command == "ATRV" -> 18L + Random.nextLong(0L, 14L)
            command.startsWith("09") -> 55L + Random.nextLong(0L, 35L) // multi-frame identity
            command.startsWith("06") -> 65L + Random.nextLong(0L, 45L) // Mode 06 monitor data
            command.startsWith("02") -> 42L + Random.nextLong(0L, 24L) // freeze frame
            command.startsWith("03") || command.startsWith("07") || command.startsWith("0A") ->
                38L + Random.nextLong(0L, 20L) // DTC reads
            command.startsWith("01") -> 34L + Random.nextLong(0L, 24L) // live PID
            command.startsWith("AT") -> 12L + Random.nextLong(0L, 16L)
            else -> 25L + Random.nextLong(0L, 15L)
        }
        delay(delayMs)
    }

    private fun handle(command: String): String = when {
        command == "ATZ" || command == "ATI" -> "ELM327 v2.2\r\r>"
        command == "AT@1" -> "CarStalker demo adapter\r\r>"
        command == "ATDPN" -> "A6\r\r>"
        command == "ATDP" -> "ISO 15765-4 CAN (11-bit, 500k)\r\r>"
        command == "ATRV" -> {
            val volts = if (scenario == DemoScenario.DRIVE) {
                // Drift a little so the voltage chart has something to show.
                val t = (System.nanoTime() - startNanos) / 1_000_000_000.0
                13.8 + 0.35 * sin(t / 3.0) + 0.08 * sin(t * 2.1)
            } else {
                scenarioVoltage()
            }
            String.format(java.util.Locale.US, "%.2fV\r\r>", volts)
        }
        command == "04" -> {
            codesCleared = true
            "44\r\r>"
        }
        command == "03" -> if (codesCleared) {
            "7E8 03 43 00 00\r7E9 03 43 00 00\r\r>"
        } else {
            "7E8 05 43 04 20 00 00\r7E9 03 43 00 00\r\r>"
        }
        command == "07" -> if (codesCleared) {
            "7E8 03 47 00 00\r\r>"
        } else {
            "7E8 04 47 04 20 00\r\r>"
        }
        command == "0A" -> "7E8 03 4A 00 00\r\r>"
        command == "0902" -> vinFrames()
        command == "0904" -> calibrationFrames()
        command == "0906" -> "49 06 01 12 34 56 78\r\r>"
        command == "090A" -> "49 0A 01 45 43 4D 2D 45 4E 47\r\r>"
        command.startsWith("02") -> mode02(command)
        command.startsWith("06") -> mode06(command)
        command.startsWith("01") -> mode01(command)
        command.startsWith("AT") -> "OK\r\r>"
        else -> "?\r\r>"
    }

    /** Mode 02: freeze frame for the stored P0301. Only frame 0 exists in this simulation. */
    private fun mode02(command: String): String {
        if (command.length < 6) return "NO DATA\r\r>"
        val pid = command.substring(2, 4).toIntOrNull(16) ?: return "NO DATA\r\r>"
        val frame = command.substring(4, 6).toIntOrNull(16) ?: return "NO DATA\r\r>"
        if (frame != 0) return "NO DATA\r\r>"
        if (pid == 0x02) return "42 02 00 04 20\r\r>"
        return when (pid) {
            0x04 -> "42 04 00 64\r\r>"
            0x05 -> "42 05 00 7B\r\r>"
            0x06 -> "42 06 00 80\r\r>"
            0x07 -> "42 07 00 84\r\r>"
            0x0B -> "42 0B 00 28\r\r>"
            0x0C -> "42 0C 00 1A F8\r\r>"
            0x0D -> "42 0D 00 32\r\r>"
            0x0E -> "42 0E 00 94\r\r>"
            0x0F -> "42 0F 00 50\r\r>"
            0x10 -> "42 10 00 10 00\r\r>"
            0x11 -> "42 11 00 80\r\r>"
            0x1F -> "42 1F 00 00 64\r\r>"
            0x2F -> "42 2F 00 9E\r\r>"
            0x33 -> "42 33 00 65\r\r>"
            0x42 -> "42 42 00 35 B4\r\r>"
            0x46 -> "42 46 00 3A\r\r>"
            else -> "NO DATA\r\r>"
        }
    }

    /** Mode 06: two monitor groups, one passing O2 test and one failing misfire test. */
    private fun mode06(command: String): String = when (command) {
        "0600" -> "46 00 80 00 00 01\r\r>" // MID 01 + next bitmap
        "0620" -> "46 20 80 00 80 81\r\r>" // MIDs 21, 31, 39 + next bitmap
        "0640" -> "46 40 00 00 00 01\r\r>" // MID 60 (next bitmap)
        "0660" -> "46 60 00 00 00 01\r\r>" // MID 80
        "0680" -> "46 80 00 00 00 01\r\r>" // MID A0
        "06A0" -> "46 A0 80 00 00 01\r\r>" // MID A1 + next bitmap
        "06C0" -> "46 C0 00 00 00 01\r\r>" // MID E0
        "06E0" -> "46 E0 00 00 00 00\r\r>"
        // O2 B1S1: threshold voltage (UAS 0x0C = 0.01 V), switch time (UAS 0x10 = 1 ms), misfire count (UAS 0x01).
        "0601" -> "46 01 01 0C 00 41 00 1E 00 64 01 05 10 00 48 00 00 00 64 01 0C 01 00 00 00 00 00 05\r\r>"
        // Catalyst B1: threshold voltage below the ECU's minimum, a genuine trusted FAIL.
        "0621" -> "46 21 01 0C 00 0A 00 1E 00 64\r\r>"
        // Misfire general: current and EWMA counts, both within limits.
        "06A1" -> "46 A1 0C 01 00 02 00 00 00 64 A1 0B 01 00 02 00 00 00 64\r\r>"
        else -> "NO DATA\r\r>"
    }

    private fun mode01(command: String): String {
        val pid = command.removePrefix("01").toIntOrNull(16) ?: return "?\r\r>"
        val t = (System.nanoTime() - startNanos) / 1_000_000_000.0
        val scenarioTime = (System.nanoTime() - scenarioStartNanos) / 1_000_000_000.0
        val cycle = (t % 60.0) / 60.0
        val driveRpm = 900 + 2400 * (0.5 + 0.5 * sin(cycle * 2 * Math.PI))
        val blipPhase = (scenarioTime % 12.0)
        val blip = when {
            blipPhase < 5.0 -> 0.0
            blipPhase < 7.0 -> (blipPhase - 5.0) / 2.0
            blipPhase < 9.0 -> 1.0
            else -> ((12.0 - blipPhase) / 3.0).coerceIn(0.0, 1.0)
        }
        val rpm = when (scenario) {
            DemoScenario.DRIVE -> driveRpm
            DemoScenario.IDLE, DemoScenario.ELECTRICAL_LOAD, DemoScenario.RICH_IDLE, DemoScenario.VOLTAGE_SPIKE -> 800.0
            DemoScenario.STEADY_2500 -> 2_500.0
            DemoScenario.STEADY_2000 -> 2_000.0
            DemoScenario.THROTTLE_BLIP -> 800.0 + 1_700.0 * blip
            DemoScenario.ENGINE_OFF -> 0.0
            DemoScenario.COLD_START -> 900.0
            DemoScenario.CRANKING -> 220.0
        }
        val speed = when (scenario) {
            DemoScenario.DRIVE -> 20 + 70 * (0.5 + 0.5 * sin(cycle * 2 * Math.PI - 0.6))
            else -> 0.0
        }
        val throttle = when (scenario) {
            DemoScenario.DRIVE -> 10 + 28 * (0.5 + 0.5 * sin(cycle * 2 * Math.PI))
            DemoScenario.ENGINE_OFF, DemoScenario.CRANKING -> 0.0
            DemoScenario.STEADY_2500 -> 12.0
            DemoScenario.STEADY_2000 -> 10.0
            DemoScenario.THROTTLE_BLIP -> 2.0 + 28.0 * blip
            else -> 2.0
        }
        val coolant = when (scenario) {
            DemoScenario.DRIVE -> 75 + (t / 4).coerceAtMost(15.0) + 1.5 * sin(t / 3)
            DemoScenario.COLD_START -> {
                // Hold the cold soak briefly so the user can start the capture after selecting it.
                val rise = ((scenarioTime - 15.0).coerceAtLeast(0.0) * 2.0)
                20.0 + rise.coerceAtMost(70.0)
            }
            DemoScenario.ENGINE_OFF -> 60.0
            DemoScenario.CRANKING -> 20.0
            else -> 90.0
        }
        val intake = if (scenario == DemoScenario.COLD_START) 20.0 else 24.0 + 4 * sin(t / 9)
        val load = when (scenario) {
            DemoScenario.DRIVE -> (25 + 40 * (throttle / 38.0)).roundToInt().coerceIn(0, 100)
            DemoScenario.ENGINE_OFF, DemoScenario.CRANKING -> 0
            DemoScenario.STEADY_2500 -> 26
            DemoScenario.STEADY_2000 -> 22
            DemoScenario.THROTTLE_BLIP -> (16 + 60 * blip).roundToInt()
            else -> 16
        }
        val maf = when (scenario) {
            DemoScenario.ENGINE_OFF -> 0.0
            else -> 2.8 + rpm / 240.0 + load / 4.0
        }
        val oil = coolant + 6
        val fuelRate = if (scenario == DemoScenario.ENGINE_OFF) 0.0 else 0.6 + maf / 14.7 * 3.6
        val map = when (scenario) {
            DemoScenario.DRIVE -> if (rpm < 1_500) 32.0 else 30.0
            DemoScenario.ENGINE_OFF -> 101.0
            DemoScenario.CRANKING -> 100.0
            DemoScenario.STEADY_2500 -> 30.0
            DemoScenario.STEADY_2000 -> 30.0
            DemoScenario.THROTTLE_BLIP -> 32.0 + 48.0 * blip
            DemoScenario.COLD_START -> 34.0
            else -> 32.0
        }
        val trim = when (scenario) {
            DemoScenario.DRIVE -> if (rpm < 1_500) 24.0 else 6.0
            DemoScenario.COLD_START -> 15.0
            DemoScenario.STEADY_2500 -> 6.0
            DemoScenario.STEADY_2000 -> 6.0
            DemoScenario.THROTTLE_BLIP -> if (blip > 0.3) 6.0 else 24.0
            DemoScenario.RICH_IDLE -> -18.0
            DemoScenario.IDLE, DemoScenario.ELECTRICAL_LOAD, DemoScenario.VOLTAGE_SPIKE -> 24.0
            DemoScenario.ENGINE_OFF, DemoScenario.CRANKING -> 0.0
        }
        val fuelSystem = when {
            scenario == DemoScenario.ENGINE_OFF || scenario == DemoScenario.CRANKING -> 0
            scenario == DemoScenario.COLD_START && coolant < 60 -> 1
            else -> 2
        }

        fun bytes(vararg values: Int) = values.joinToString(" ") { hex(it) }
        fun reply(vararg data: Int) = "41 ${hex(pid)} ${bytes(*data)}\r\r>"
        fun trimByte(percent: Double): Int = ((percent + 100.0) * 128.0 / 100.0).roundToInt().coerceIn(0, 255)

        return when (pid) {
            0x00 -> "41 00 BF BF 98 03\r\r>"
            0x20 -> "41 20 E0 1F E0 01\r\r>"
            0x40 -> "41 40 7E C0 28 01\r\r>"
            0x60 -> "41 60 E0 00 00 00\r\r>"
            0x01 -> if (codesCleared) "41 01 00 0F 00 00\r\r>" else "41 01 82 0F EF 00\r\r>"
            0x03 -> reply(fuelSystem)
            0x04 -> reply((load * 255 / 100).coerceIn(0, 255))
            0x05 -> reply((coolant + 40).roundToInt().coerceIn(0, 255))
            0x06, 0x08 -> reply(trimByte(trim))
            0x07, 0x09 -> reply(trimByte(trim))
            0x0B -> reply(map.roundToInt().coerceIn(0, 255))
            0x0C -> {
                val raw = (rpm * 4).roundToInt()
                reply((raw shr 8) and 0xFF, raw and 0xFF)
            }
            0x0D -> reply(speed.roundToInt().coerceIn(0, 255))
            0x0E -> reply(148)
            0x0F -> reply((intake + 40).roundToInt().coerceIn(0, 255))
            0x10 -> {
                val raw = (maf * 100).roundToInt()
                reply((raw shr 8) and 0xFF, raw and 0xFF)
            }
            0x11 -> reply((throttle * 255 / 100).roundToInt().coerceIn(0, 255))
            0x14 -> {
                val phase = (t * 1.2) % 1.0
                val voltage = if (scenario == DemoScenario.ENGINE_OFF) 0.1 else 0.1 + 0.8 * if (phase < 0.5) 1.0 else 0.0
                reply((voltage * 200).roundToInt().coerceIn(0, 255))
            }
            0x15 -> {
                val phase = (((t - 0.08) * 1.2) % 1.0 + 1.0) % 1.0
                val voltage = if (scenario == DemoScenario.ENGINE_OFF) 0.1 else 0.1 + 0.8 * if (phase < 0.5) 1.0 else 0.0
                reply((voltage * 200).roundToInt().coerceIn(0, 255))
            }
            0x1F -> {
                val seconds = t.roundToInt()
                reply((seconds shr 8) and 0xFF, seconds and 0xFF)
            }
            0x21 -> reply(0x00, 0x0C)
            0x22 -> reply(0x13, 0xC7)
            0x23 -> reply(0x01, 0x90)
            0x2C -> reply(0)
            0x2D -> reply(0x80)
            0x2E -> reply(13)
            0x2F -> reply(158)
            0x30 -> reply(23)
            0x31 -> reply(0x00, 0xFA)
            0x32 -> reply(0x80, 0x00)
            0x33 -> reply(101)
            0x3C -> reply(0x11, 0xF8)
            0x3D -> reply(0x10, 0x68)
            0x3E -> reply(0x11, 0xF8)
            0x3F -> reply(0x10, 0x68)
            0x42 -> {
                val millivolts = (scenarioVoltage() * 1000).roundToInt()
                reply((millivolts shr 8) and 0xFF, millivolts and 0xFF)
            }
            0x43 -> reply(0x00, 0x4C)
            0x44 -> reply(0x80, 0x00)
            0x45 -> reply((throttle * 255 / 100).roundToInt().coerceIn(0, 255))
            0x46 -> reply(58)
            0x47 -> reply((throttle * 255 / 100).roundToInt().coerceIn(0, 255))
            0x49 -> reply(((throttle + 2) * 255 / 100).roundToInt().coerceIn(0, 255))
            0x4A -> reply((throttle * 255 / 100).roundToInt().coerceIn(0, 255))
            0x4C -> reply((throttle * 255 / 100).roundToInt().coerceIn(0, 255))
            0x4D -> reply(0x00, 0x00)
            0x4E -> reply(0x00, 0x64)
            0x5C -> reply((oil + 40).roundToInt().coerceIn(0, 255))
            0x5E -> {
                val raw = (fuelRate * 20).roundToInt()
                reply((raw shr 8) and 0xFF, raw and 0xFF)
            }
            0x61 -> reply(145)
            0x62 -> reply(140)
            0x63 -> reply(0x01, 0x2C)
            else -> "NO DATA\r\r>"
        }
    }

    private fun scenarioVoltage(): Double = when (scenario) {
        DemoScenario.DRIVE -> 13.8
        DemoScenario.IDLE -> 14.1
        DemoScenario.STEADY_2500 -> 14.2
        DemoScenario.STEADY_2000 -> 14.2
        DemoScenario.THROTTLE_BLIP -> 14.1
        DemoScenario.RICH_IDLE -> 14.0
        DemoScenario.VOLTAGE_SPIKE -> {
            val phase = ((System.nanoTime() - scenarioStartNanos) / 1_000_000_000.0) % 12.0
            when {
                phase < 0.6 -> 15.9
                phase < 1.0 -> 13.2
                phase < 6.0 -> 14.1
                phase < 6.4 -> 12.4
                else -> 14.1
            }
        }
        DemoScenario.ENGINE_OFF -> 12.4
        DemoScenario.ELECTRICAL_LOAD -> 13.6
        DemoScenario.COLD_START -> 14.0
        DemoScenario.CRANKING -> 10.5
    }

    private fun vinFrames(): String {
        val vin = "JF1ZCAC10D2000001"
        return vin.chunked(4).mapIndexed { index, chunk ->
            "49 02 ${hex(index + 1)} " + chunk.map { hex(it.code) }.joinToString(" ")
        }.joinToString("\r") + "\r\r>"
    }

    private fun calibrationFrames(): String =
        "49 04 01 " + "D2Q0A00A".map { hex(it.code) }.joinToString(" ") + "\r\r>"

    private fun hex(value: Int): String = value.toString(16).uppercase().padStart(2, '0')
}
