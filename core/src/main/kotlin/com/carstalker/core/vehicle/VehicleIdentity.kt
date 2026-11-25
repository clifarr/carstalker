package com.carstalker.core.vehicle

import com.carstalker.core.util.Hex

/** Vehicle identity reported by OBD mode 09. */
data class VehicleIdentity(
    val vin: String? = null,
    /** ECU calibration identifier (mode 09 PID 04) — software, not a vehicle name. */
    val calibrationId: String? = null,
    val cvn: List<String> = emptyList(),
    val ecuName: String? = null,
) {
    val isKnown: Boolean
        get() = vin != null || calibrationId != null || ecuName != null || cvn.isNotEmpty()

    /** Model year from VIN position 10 (ISO 3779), when present. */
    val modelYear: Int? get() = vin?.let { Vin.modelYear(it) }

    /** Manufacturer from the public WMI prefix, when recognised. */
    val make: String? get() = Wmi.make(vin)

    val displayName: String
        get() = when {
            vin != null -> listOfNotNull(modelYear?.toString(), make)
                .joinToString(" ")
                .ifBlank { "VIN ${vin.takeLast(6)}" }
            calibrationId != null || ecuName != null || cvn.isNotEmpty() -> "Vehicle"
            else -> "Unidentified vehicle"
        }
}

object Mode09Decoder {

    fun decodeVin(messages: List<IntArray>): String? =
        asciiPayload(messages, pid = 0x02)?.take(17)?.takeIf { it.length == 17 }

    fun decodeCalibrationId(messages: List<IntArray>): String? =
        asciiPayload(messages, pid = 0x04)?.takeIf { it.isNotBlank() }

    fun decodeEcuName(messages: List<IntArray>): String? =
        asciiPayload(messages, pid = 0x0A)?.takeIf { it.isNotBlank() }

    fun decodeCvn(messages: List<IntArray>): List<String> {
        val bytes = payload(messages, pid = 0x06) ?: return emptyList()
        return bytes.toList().chunked(4).map { chunk ->
            chunk.joinToString("") { Hex.toHex(it, 2) }
        }.filter { it.length == 8 }
    }

    private fun asciiPayload(messages: List<IntArray>, pid: Int): String? {
        val bytes = payload(messages, pid = pid) ?: return null
        return bytes
            .filter { it in 0x20..0x7E }
            .map { it.toChar() }
            .joinToString("")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    /** Concatenates data across frames, skipping the `49 <pid> <frame>` prefix. */
    private fun payload(messages: List<IntArray>, pid: Int): IntArray? {
        val chunks = messages.mapNotNull { message ->
            if (message.size < 3) return@mapNotNull null
            if (message[0] != 0x49 || message[1] != pid) return@mapNotNull null
            val rest = message.copyOfRange(2, message.size)
            // Multi-frame responses carry a 1-based frame counter in the first byte.
            if (rest.isNotEmpty() && rest[0] in 0x01..0x0F) rest.copyOfRange(1, rest.size) else rest
        }
        return chunks.reduceOrNull { acc, next -> acc + next }?.takeIf { it.isNotEmpty() }
    }
}

/** Minimal VIN helpers. */
object Vin {

    /** ISO 3779 model-year codes: 1-9 then A-Y, skipping I, O, Q, U and Z. */
    private const val YEAR_CHARS = "123456789ABCDEFGHJKLMNPRSTVWXY"

    fun modelYear(vin: String, currentYear: Int = java.time.Year.now().value): Int? {
        if (vin.length != 17) return null
        val index = YEAR_CHARS.indexOf(vin[9].uppercaseChar())
        if (index < 0) return null
        // The code repeats every 30 years; pick the most recent cycle that is not in the future.
        var year = if (index >= 9) 1980 + (index - 9) else 2001 + index
        while (year + 30 <= currentYear + 1) year += 30
        return year
    }

    fun isValid(vin: String): Boolean =
        vin.length == 17 && vin.all { it in '0'..'9' || it in 'A'..'Z' } && vin.none { it in "IOQ" }
}
