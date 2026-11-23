package com.carstalker.core.elm

/** A protocol reported by `ATDPN`, with the details the client needs to configure itself. */
data class ElmProtocol(
    val number: Int?,
    val label: String,
    val isCan: Boolean = false,
    val isExtendedCan: Boolean = false,
)

/** Maps the protocol number reported by `ATDPN` to a name and its capabilities. */
object ElmProtocols {

    private val NAMES = mapOf(
        1 to "SAE J1850 PWM",
        2 to "SAE J1850 VPW",
        3 to "ISO 9141-2",
        4 to "ISO 14230-4 (KWP slow)",
        5 to "ISO 14230-4 (KWP fast)",
        6 to "ISO 15765-4 CAN (11-bit, 500k)",
        7 to "ISO 15765-4 CAN (29-bit, 500k)",
        8 to "ISO 15765-4 CAN (11-bit, 250k)",
        9 to "ISO 15765-4 CAN (29-bit, 250k)",
        10 to "SAE J1939 CAN",
        11 to "User CAN 1",
        12 to "User CAN 2",
    )

    /**
     * `ATDPN` prefixes the number with `A` when the protocol was chosen automatically,
     * so `A6` is automatic CAN and `6` is CAN pinned manually.
     */
    fun describe(reported: String): ElmProtocol {
        val cleaned = reported.trim().uppercase().replace(" ", "")
        if (cleaned.isEmpty()) return ElmProtocol(null, "Unknown")

        val automatic = cleaned.startsWith("A") && cleaned.length > 1
        val code = if (automatic) cleaned[1] else cleaned.last()

        val number = when (code) {
            in '1'..'9' -> code - '0'
            'A' -> 10
            'B' -> 11
            'C' -> 12
            // 0 means the adapter has not settled on a protocol yet.
            else -> null
        }

        val base = number?.let { NAMES[it] }
            ?: if (code == '0') "Automatic (searching)" else "Protocol $reported"
        val label = if (automatic && code != '0') "$base (auto)" else base

        return ElmProtocol(
            number = number,
            label = label,
            isCan = number != null && number in 6..12,
            isExtendedCan = number == 7 || number == 9,
        )
    }

    fun name(reported: String): String = describe(reported).label
}
