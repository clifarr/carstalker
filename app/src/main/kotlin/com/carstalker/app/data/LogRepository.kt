package com.carstalker.app.data

import android.content.Context
import com.carstalker.core.log.LogColumn
import com.carstalker.core.log.WideLogFormat
import com.carstalker.core.log.WideLogMeta
import com.carstalker.core.obd.FormulaParser
import com.carstalker.core.obd.Pid
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class LogColumnMeta(
    val id: Int,
    val name: String,
    val unit: String,
)

@Serializable
data class LogMeta(
    val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val vin: String? = null,
    val adapter: String? = null,
    val protocol: String? = null,
    val appVersion: String? = null,
    val rows: Int = 0,
    val pids: List<String> = emptyList(),
    /** 1 = legacy long format, 2 = wide format. */
    val formatVersion: Int = 1,
    val columns: List<LogColumnMeta> = emptyList(),
    /** "drive" for a normal recording, "guided" for a saved guided capture. */
    val kind: String = "drive",
    /** Human-readable source, for example the guide title. */
    val label: String? = null,
    /** True when the recording came from the demo transport. */
    val isDemo: Boolean = false,
)

data class LogSession(
    val meta: LogMeta,
    val csvFile: File,
) {
    val durationMs: Long get() = (meta.endedAt ?: meta.startedAt) - meta.startedAt
    val sizeBytes: Long get() = csvFile.length()
}

/**
 * CSV logger: one row per PID sample (`timestamp_iso,pid,value,unit`), plus a JSON sidecar
 * with session metadata. Long format keeps files readable and tolerant of changing PID sets.
 */
class LogRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val directory: File
        get() = File(context.filesDir, "logs").apply { mkdirs() }

    fun listSessions(): List<LogSession> =
        directory.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { metaFile ->
                runCatching {
                    val meta = json.decodeFromString<LogMeta>(metaFile.readText())
                    val csv = File(directory, "${meta.id}.csv")
                    LogSession(meta, csv)
                }.getOrNull()
            }
            ?.sortedByDescending { it.meta.startedAt }
            ?: emptyList()

    fun createSession(
        vin: String?,
        adapter: String?,
        protocol: String?,
        appVersion: String,
        columns: List<LogColumnMeta>,
        startedAt: Long = System.currentTimeMillis(),
        kind: String = "drive",
        label: String? = null,
        isDemo: Boolean = false,
    ): LogWriter {
        val id = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(startedAt))
        val meta = LogMeta(
            id = id,
            startedAt = startedAt,
            vin = vin,
            adapter = adapter,
            protocol = protocol,
            appVersion = appVersion,
            formatVersion = 2,
            columns = columns,
            pids = columns.map { "${it.name} [${it.unit}]" },
            kind = kind,
            label = label,
            isDemo = isDemo,
        )
        val csv = File(directory, "$id.csv")
        val metaFile = File(directory, "$id.json")
        val wideColumns = columns.map { LogColumn(it.id, it.name, it.unit) }
        csv.writeText(
            WideLogFormat.header(
                WideLogMeta(
                    startedAt = startedAt,
                    appVersion = appVersion,
                    adapter = adapter,
                    protocol = protocol,
                    vin = vin,
                    columns = wideColumns,
                ),
            ),
        )
        metaFile.writeText(json.encodeToString(meta))
        return LogWriter(csv, metaFile, meta, json, wideColumns, startedAt)
    }

    fun delete(session: LogSession) {
        session.csvFile.delete()
        File(directory, "${session.meta.id}.json").delete()
    }

    fun fileFor(session: LogSession): File = session.csvFile
}

class LogWriter(
    csvFile: File,
    private val metaFile: File,
    private var meta: LogMeta,
    private val json: Json,
    private val columns: List<LogColumn>,
    private val startedAt: Long,
) {
    private val writer = java.io.BufferedWriter(java.io.FileWriter(csvFile, true))
    private var rows = 0
    private var sinceFlush = 0

    /**
     * Appends one wide row. Missing channels are left blank rather than carrying a stale value,
     * so the age of each sample is explicit when the file is analysed.
     */
    @Synchronized
    fun append(timestampMs: Long, values: Map<Int, Double>) {
        val row = WideLogFormat.row(timestampMs, timestampMs - startedAt, columns, values)
        synchronized(writer) {
            writer.append(row)
            sinceFlush++
            if (sinceFlush >= FLUSH_EVERY_ROWS) {
                writer.flush()
                sinceFlush = 0
            }
        }
        rows++
    }

    @Synchronized
    fun close(endedAt: Long = System.currentTimeMillis()) {
        runCatching {
            synchronized(writer) { writer.flush(); writer.close() }
        }
        meta = meta.copy(
            endedAt = endedAt,
            rows = rows,
            pids = columns.map { "${it.name} [${it.unit}]" },
            formatVersion = 2,
            columns = columns.map { LogColumnMeta(it.id, it.name, it.unit) },
        )
        runCatching { metaFile.writeText(json.encodeToString(meta)) }
    }

    private companion object {
        const val FLUSH_EVERY_ROWS = 32
    }
}

/** Converts a stored custom PID definition into a decodable [Pid]. */
fun CustomPid.toPid(): Pid? {
    if (!FormulaParser.isValid(formula)) return null
    val parser = runCatching { FormulaParser.compile(formula) }.getOrNull() ?: return null
    val id = pidHex.toIntOrNull(16) ?: return null
    if (id !in 0x01..0xFF) return null
    return Pid.of(
        id = id,
        name = name,
        shortName = shortName,
        unit = unit,
        min = min,
        max = max,
        category = Pid.Category.OTHER,
        decimals = decimals,
        requiredBytes = parser.requiredBytes,
    ) { parser.evaluate(it) }.copy(isCustom = true)
}
