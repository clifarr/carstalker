package com.carstalker.core.dtc

/** A diagnostic trouble code, e.g. P0301. */
data class Dtc(
    val code: String,
    val description: String,
    val category: Category,
) {
    enum class Category(val label: String) {
        POWERTRAIN("Powertrain"),
        CHASSIS("Chassis"),
        BODY("Body"),
        NETWORK("Network"),
        UNKNOWN("Unknown"),
    }

    /** True for codes the manufacturer defined rather than the SAE generic set. */
    val isManufacturerSpecific: Boolean
        get() = !DtcCatalog.isGeneric(code)
}

enum class DtcGroup(val label: String, val mode: Int) {
    STORED("Stored", 0x03),
    PENDING("Pending", 0x07),
    PERMANENT("Permanent", 0x0A),
}
