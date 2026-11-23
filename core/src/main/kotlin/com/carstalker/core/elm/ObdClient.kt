package com.carstalker.core.elm

import com.carstalker.core.dtc.Dtc
import com.carstalker.core.dtc.DtcDecoder
import com.carstalker.core.dtc.EcuDtcReport
import com.carstalker.core.obd.Ecu
import com.carstalker.core.obd.FreezeFrame
import com.carstalker.core.obd.FreezeFrameParser
import com.carstalker.core.obd.Mode06Parser
import com.carstalker.core.obd.Mode06Report
import com.carstalker.core.obd.Mode06Result
import com.carstalker.core.obd.MonitorStatus
import com.carstalker.core.obd.MonitorStatusParser
import com.carstalker.core.obd.ObdMode
import com.carstalker.core.obd.Pid
import com.carstalker.core.obd.PidCatalog
import com.carstalker.core.obd.PidSupport
import com.carstalker.core.util.Hex
import com.carstalker.core.vehicle.Mode09Decoder

/** Result of a short adapter round-trip benchmark. */
data class AdapterBenchmark(
    val samples: Int,
    val averageLatencyMs: Double,
    val minLatencyMs: Long,
    val maxLatencyMs: Long,
    val requestsPerSecond: Double,
)

/** High-level OBD-II operations on top of a connected [Elm327Client]. */
class ObdClient(private val elm: Elm327Client) {

    suspend fun initialize(): AdapterInfo = elm.initialize()

    suspend fun readVoltage(): Double? {
        val raw = elm.command("ATRV", 2_000).raw
        return Regex("(\\d{1,2}\\.\\d{1,2})").find(raw)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /** Probes 0100, 0120, ... until the ECU stops advertising another range. */
    suspend fun readSupportedPids(source: Int? = null): Set<Int> {
        val supported = mutableSetOf<Int>()
        for (base in PidSupport.probeBases) {
            val response = elm.queryPid(ObdMode.CURRENT_DATA, base)
            val message = select(response.messages, source) {
                it.size >= 2 && it[0] == ObdMode.CURRENT_DATA + 0x40 && it[1] == base
            } ?: break
            val decoded = PidSupport.decode(base, message.payload.copyOfRange(2, message.payload.size))
            supported += decoded
            if (base + 0x20 !in decoded) break
        }
        return supported
    }

    suspend fun readPid(pid: Pid, source: Int? = null): Double? {
        val response = elm.queryPid(ObdMode.CURRENT_DATA, pid.id)
        return decodePidResponse(response, pid, source)
    }

    suspend fun readPid(id: Int): Double? {
        val pid = PidCatalog.find(id) ?: return null
        return readPid(pid)
    }

    suspend fun readMonitorStatus(source: Int? = null): MonitorStatus? {
        val response = elm.queryPid(ObdMode.CURRENT_DATA, 0x01)
        val message = select(response.messages, source) {
            it.size >= 2 && it[0] == ObdMode.CURRENT_DATA + 0x40 && it[1] == 0x01
        } ?: return null
        return MonitorStatusParser.parse(message.payload.copyOfRange(2, message.payload.size))
    }

    /**
     * Reads stored, pending and permanent codes from every ECU that answers.
     *
     * Generic OBD-II is broadcast on the functional address; a CAN adapter with headers on will
     * return one response per module. Modules that answer with zero codes are kept so the UI can
     * show that they were scanned and are clean.
     */
    suspend fun readDtcScan(): List<EcuDtcReport> {
        val bySource = LinkedHashMap<Int?, MutableMap<Int, List<Dtc>>>()
        for (mode in listOf(ObdMode.STORED_DTCS, ObdMode.PENDING_DTCS, ObdMode.PERMANENT_DTCS)) {
            val response = elm.command(Hex.toHex(mode), 6_000)
            for (message in response.messages) {
                if (message.payload.isEmpty() || message.payload[0] != mode + 0x40) continue
                val codes = DtcDecoder.decode(message.payload.copyOfRange(1, message.payload.size))
                bySource.getOrPut(message.source) { mutableMapOf() }[mode] = codes
            }
        }
        return bySource.map { (source, modes) ->
            EcuDtcReport(
                source = source,
                stored = modes[ObdMode.STORED_DTCS].orEmpty(),
                pending = modes[ObdMode.PENDING_DTCS].orEmpty(),
                permanent = modes[ObdMode.PERMANENT_DTCS].orEmpty(),
            )
        }.sortedWith(compareBy({ if (Ecu.isEngine(it.source)) 0 else 1 }, { it.source ?: 0 }))
    }

    /** Flattened view retained for callers that only need codes from one mode. */
    suspend fun readDtcs(mode: Int): List<Dtc> {
        val response = elm.command(Hex.toHex(mode), 6_000)
        return response.messages
            .filter { it.payload.isNotEmpty() && it.payload[0] == mode + 0x40 }
            .flatMap { DtcDecoder.decode(it.payload.copyOfRange(1, it.payload.size)) }
            .distinctBy { it.code }
    }

    /**
     * Reads Mode 02 freeze frames. [sources] should be the ECUs returned by [readDtcScan];
     * when empty the engine/headerless response is used.
     */
    suspend fun readFreezeFrames(
        sources: List<Int?> = emptyList(),
        maxFrames: Int = 3,
    ): List<FreezeFrame> {
        val candidates = sources.ifEmpty { listOf(null) }
        val frames = mutableListOf<FreezeFrame>()
        for (source in candidates) {
            for (frame in 0 until maxFrames) {
                val dtc = readFreezeFrameDtc(frame, source) ?: break
                val values = LinkedHashMap<Int, Double>()
                for (pid in FREEZE_FRAME_PIDS) {
                    if (pid.id == 0x02) continue
                    val value = readFreezeFrameValue(frame, pid, source) ?: continue
                    values[pid.id] = value
                }
                frames += FreezeFrame(
                    frame = frame,
                    source = source,
                    dtc = dtc.code,
                    values = values,
                    readAt = System.currentTimeMillis(),
                )
                // Most ECUs only implement frame 0; stop early when it is the only snapshot.
                if (frame == 0 && sources.isEmpty()) break
            }
        }
        return frames.distinctBy { it.frame to it.dtc }
    }

    private suspend fun readFreezeFrameDtc(frame: Int, source: Int?): Dtc? {
        val response = elm.command("0202" + Hex.toHex(frame), 4_000)
        val message = select(response.messages, source) {
            it.size >= 3 && it[0] == ObdMode.FREEZE_FRAME + 0x40 && it[1] == 0x02 && it[2] == frame
        } ?: return null
        return FreezeFrameParser.decodeDtc(message.payload, frame)
    }

    private suspend fun readFreezeFrameValue(frame: Int, pid: Pid, source: Int?): Double? {
        val response = elm.command("02" + Hex.toHex(pid.id) + Hex.toHex(frame), 3_000)
        val message = select(response.messages, source) {
            it.size >= 4 && it[0] == ObdMode.FREEZE_FRAME + 0x40 && it[1] == pid.id && it[2] == frame
        } ?: return null
        return FreezeFrameParser.decodeValue(message.payload, pid, frame)
    }

    /**
     * Reads Mode 06 on-board monitor test results from the engine ECU.
     *
     * Supported MIDs are discovered first, then each MID is requested once. A response can carry
     * many 9-byte test blocks, which [Mode06Parser] decodes with their UAS scaling and limits.
     */
    suspend fun readMode06(maxMids: Int = 64): Mode06Report {
        val supported = mutableSetOf<Int>()
        for (base in PidSupport.probeBases) {
            val response = elm.command("06" + Hex.toHex(base), 4_000)
            val message = select(response.messages, null) {
                it.size >= 2 && it[0] == ObdMode.ONBOARD_MONITOR + 0x40 && it[1] == base
            } ?: break
            val decoded = Mode06Parser.parseSupportedMids(base, message.payload.copyOfRange(2, message.payload.size))
            supported += decoded
            if (base + 0x20 !in decoded) break
        }

        val results = mutableListOf<Mode06Result>()
        val monitorMids = supported.filterNot { it in PidSupport.probeBases }
            .ifEmpty { FALLBACK_MIDS }
        for (mid in monitorMids.sorted().take(maxMids)) {
            val response = elm.command("06" + Hex.toHex(mid), 6_000)
            for (message in response.messages) {
                if (message.payload.size < 2 || message.payload[0] != ObdMode.ONBOARD_MONITOR + 0x40) continue
                results += Mode06Parser.parse(message.payload, mid, message.source)
            }
        }
        return Mode06Report(
            results = results,
            supportedMids = monitorMids.toSet(),
            readAt = System.currentTimeMillis(),
        )
    }

    suspend fun clearDtcs(): Boolean {
        val response = elm.command(Hex.toHex(ObdMode.CLEAR_DTCS), 8_000)
        return response.status == ElmResponse.Status.OK ||
            response.messages.any { it.payload.isNotEmpty() && it.payload[0] == ObdMode.CLEAR_DTCS + 0x40 }
    }

    suspend fun readVin(): String? =
        Mode09Decoder.decodeVin(elm.command("0902", 8_000).payloads)

    suspend fun readCalibrationId(): String? =
        Mode09Decoder.decodeCalibrationId(elm.command("0904", 6_000).payloads)

    suspend fun readCvn(): List<String> =
        Mode09Decoder.decodeCvn(elm.command("0906", 6_000).payloads)

    suspend fun readEcuName(): String? =
        Mode09Decoder.decodeEcuName(elm.command("090A", 6_000).payloads)

    /**
     * Measures serial round-trip time with repeated `0100` requests. This is intentionally simple:
     * it tells the user whether the adapter is healthy, not a laboratory benchmark.
     */
    suspend fun benchmark(samples: Int = 8): AdapterBenchmark {
        val count = samples.coerceIn(1, 50)
        val latencies = mutableListOf<Long>()
        repeat(count) {
            val started = System.nanoTime()
            runCatching { elm.command("0100", 4_000) }
            latencies += (System.nanoTime() - started) / 1_000_000
        }
        val average = latencies.average()
        return AdapterBenchmark(
            samples = count,
            averageLatencyMs = average,
            minLatencyMs = latencies.minOrNull() ?: 0L,
            maxLatencyMs = latencies.maxOrNull() ?: 0L,
            requestsPerSecond = if (average > 0.0) 1_000.0 / average else 0.0,
        )
    }

    private fun decodePidResponse(response: ElmResponse, pid: Pid, source: Int? = null): Double? {
        val message = select(response.messages, source) {
            it.size >= 2 && it[0] == ObdMode.CURRENT_DATA + 0x40 && it[1] == pid.id
        } ?: return null
        return pid.decode(message.payload.copyOfRange(2, message.payload.size))
    }

    /**
     * Selects the best response for a request. With no explicit source, an engine/headerless
     * response wins; otherwise the first matching response is used.
     */
    private fun select(
        messages: List<EcuMessage>,
        source: Int? = null,
        predicate: (IntArray) -> Boolean,
    ): EcuMessage? {
        val matching = messages.filter { predicate(it.payload) }
        if (matching.isEmpty()) return null
        if (source != null) return matching.firstOrNull { it.source == source } ?: matching.first()
        return matching.firstOrNull { Ecu.isEngine(it.source) } ?: matching.first()
    }

    private companion object {
        /** Common Mode 06 monitor IDs used when the ECU does not answer the 0600 bitmap. */
        val FALLBACK_MIDS = listOf(0x01, 0x21, 0x31, 0x39, 0xA1)

        /** The subset of generic PIDs worth showing in a freeze-frame snapshot. */
        val FREEZE_FRAME_PIDS: List<Pid> = listOfNotNull(
            PidCatalog.find(0x04), // load
            PidCatalog.find(0x05), // coolant
            PidCatalog.find(0x06), // STFT B1
            PidCatalog.find(0x07), // LTFT B1
            PidCatalog.find(0x0B), // MAP
            PidCatalog.find(0x0C), // RPM
            PidCatalog.find(0x0D), // speed
            PidCatalog.find(0x0E), // timing
            PidCatalog.find(0x0F), // intake
            PidCatalog.find(0x10), // MAF
            PidCatalog.find(0x11), // throttle
            PidCatalog.find(0x1F), // run time
            PidCatalog.find(0x2F), // fuel level
            PidCatalog.find(0x33), // baro
            PidCatalog.find(0x42), // module voltage
            PidCatalog.find(0x46), // ambient
        )
    }
}
