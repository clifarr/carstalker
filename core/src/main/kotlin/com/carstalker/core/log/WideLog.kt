package com.carstalker.core.log

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One channel in a wide-format recording. */
data class LogColumn(
    val id: Int,
    val name: String,
    val unit: String,
)

/** Metadata written at the top of a wide-format recording. */
data class WideLogMeta(
    val startedAt: Long,
    val appVersion: String,
    val adapter: String? = null,
    val protocol: String? = null,
    val vin: String? = null,
    val unitSystem: String = "METRIC",
    val columns: List<LogColumn> = emptyList(),
)

/**
 * Wide-format CSV, one row per sample tick and one column per channel.
 *
 * Long format (`timestamp,pid,value,unit`) is easy to append but forces a pivot before any
 * analysis. Wide format keeps "what was true at time t" intact, loads directly into a spreadsheet
 * or MoTeC i2, and makes missing samples explicit.
 *
 * The preamble lines start with `#`; spreadsheet tools ignore or preserve them, while a reader
 * can recover the units, vehicle and adapter without guessing.
 */
object WideLogFormat {

    const val VERSION = 2
    const val MARKER = "# CarStalker log v2"
    const val TIMESTAMP_HEADER = "timestamp_iso"
    const val ELAPSED_HEADER = "elapsed_s"

    private val timestampFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    fun header(meta: WideLogMeta): String = buildString {
        appendLine(MARKER)
        appendLine("# started_at=${timestamp(meta.startedAt)}")
        appendLine("# app_version=${meta.appVersion}")
        meta.adapter?.let { appendLine("# adapter=${sanitizeMeta(it)}") }
        meta.protocol?.let { appendLine("# protocol=${sanitizeMeta(it)}") }
        meta.vin?.let { appendLine("# vin=${sanitizeMeta(it)}") }
        appendLine("# units=${meta.unitSystem}")
        append(TIMESTAMP_HEADER)
        append(',')
        append(ELAPSED_HEADER)
        for (column in meta.columns) {
            append(',')
            append(escape(columnLabel(column)))
        }
        append('\n')
    }

    fun row(timestampMs: Long, elapsedMs: Long, columns: List<LogColumn>, values: Map<Int, Double?>): String =
        buildString {
            append(timestamp(timestampMs))
            append(',')
            append(String.format(Locale.US, "%.3f", elapsedMs / 1000.0))
            for (column in columns) {
                append(',')
                val value = values[column.id]
                if (value != null && value.isFinite()) append(format(value))
            }
            append('\n')
        }

    fun columnLabel(column: LogColumn): String = "${column.name} [${column.unit}]"

    fun escape(text: String): String =
        if (text.contains(',') || text.contains('"') || text.contains('\n')) {
            "\"${text.replace("\"", "\"\"")}\""
        } else {
            text
        }

    private fun sanitizeMeta(text: String): String = text.replace('\n', ' ').replace('\r', ' ').trim()

    private fun timestamp(epochMs: Long): String =
        timestampFormat.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    private fun format(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(Locale.US, "%.3f", value)
}
