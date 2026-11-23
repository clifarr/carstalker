package com.carstalker.core.obd

import com.carstalker.core.dtc.Dtc
import com.carstalker.core.dtc.DtcDecoder

/**
 * A Mode 02 freeze-frame snapshot: the sensor values recorded when the ECU stored a DTC.
 *
 * [frame] is the 0-based snapshot number. [dtc] is the code that created the snapshot, as
 * reported by Mode 02 PID 02. Values are canonical metric, keyed by Mode 01 PID.
 */
data class FreezeFrame(
    val frame: Int,
    val source: Int?,
    val dtc: String?,
    val values: Map<Int, Double>,
    val readAt: Long,
)

object FreezeFrameParser {

    /**
     * Decodes `42 02 <frame> <high> <low>`.
     * Returns null when the frame is empty (DTC 0000).
     */
    fun decodeDtc(payload: IntArray, frame: Int): Dtc? {
        if (payload.size < 5) return null
        if (payload[0] != ObdMode.FREEZE_FRAME + 0x40 || payload[1] != 0x02 || payload[2] != frame) return null
        return DtcDecoder.decode(payload.copyOfRange(3, 5)).firstOrNull()
    }

    /** Decodes `42 <pid> <frame> <data...>` with the supplied PID definition. */
    fun decodeValue(payload: IntArray, pid: Pid, frame: Int): Double? {
        if (payload.size < 4) return null
        if (payload[0] != ObdMode.FREEZE_FRAME + 0x40 || payload[1] != pid.id || payload[2] != frame) return null
        return pid.decode(payload.copyOfRange(3, payload.size))
    }
}
