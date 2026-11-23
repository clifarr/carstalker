package com.carstalker.app.transport.ble

/** Name heuristics used only to label and pre-filter the BLE picker. Never used to connect. */
object BleAdapterNames {

    private val HINTS = listOf(
        "obd", "elm", "vgate", "veepeak", "konnwei", "bafx", "icar", "vlink", "obdlink",
        "vlinker", "scan", "kts", "autel", "launch", "thinkdiag", "carly", "hc-05", "hc05",
        "bt-05", "bt05", "mini", "diag",
    )

    /** Devices that expose SPP but are clearly not car adapters. */
    private val EXCLUSIONS = listOf(
        "watch", "airpod", "buds", "headphone", "speaker", "hfp", "car kit", "my fiat",
        "printer", "keyboard", "mouse", "band", "tracker", "phone", "tv",
    )

    /** True for devices we never want to offer as an adapter (watches, audio, phones…). */
    fun isExcluded(name: String): Boolean =
        name.isNotBlank() && EXCLUSIONS.any { name.contains(it, ignoreCase = true) }

    fun looksLikeAdapter(name: String): Boolean {
        if (name.isBlank()) return false
        if (EXCLUSIONS.any { name.contains(it, ignoreCase = true) }) return false
        return HINTS.any { name.contains(it, ignoreCase = true) }
    }

    /** BLE serial services used by ELM327 clones. */
    val SERIAL_SERVICES: Set<java.util.UUID> = setOf(
        java.util.UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB"),
        java.util.UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB"),
        java.util.UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB"),
    )

    /**
     * A BLE device is only listed when it either advertises a known serial service
     * or its name matches an adapter. Unrelated devices are never shown.
     */
    fun isCompatibleBle(name: String, serviceUuids: Collection<java.util.UUID>?): Boolean {
        if (name.isNotBlank() && EXCLUSIONS.any { name.contains(it, ignoreCase = true) }) return false
        if (serviceUuids != null && serviceUuids.any { it in SERIAL_SERVICES }) return true
        return looksLikeAdapter(name)
    }
}
