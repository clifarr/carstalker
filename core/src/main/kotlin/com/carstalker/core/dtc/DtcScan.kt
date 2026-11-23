package com.carstalker.core.dtc

import com.carstalker.core.obd.Ecu

/** DTCs reported by one ECU, grouped by the OBD-II mode that returned them. */
data class EcuDtcReport(
    val source: Int?,
    val name: String = Ecu.name(source),
    val stored: List<Dtc> = emptyList(),
    val pending: List<Dtc> = emptyList(),
    val permanent: List<Dtc> = emptyList(),
) {
    val total: Int get() = stored.size + pending.size + permanent.size
    val isEmpty: Boolean get() = total == 0

    fun forGroup(group: DtcGroup): List<Dtc> = when (group) {
        DtcGroup.STORED -> stored
        DtcGroup.PENDING -> pending
        DtcGroup.PERMANENT -> permanent
    }
}
