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
    /** Last row actually flushed; lets an interrupted recording report a real duration. */
    val lastSampleAt: Long? = null,
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
    /** Sampling gaps longer than [SAMPLING_STALL_THRESHOLD_MS] noticed while recording. */
    val stallCount: Int = 0,
    /** Longest sampling gap, in milliseconds; 0 when the phone never paused the poll loop. */
    val longestStallMs: Long = 0L,
)

data class LogSession(
    val meta: LogMeta,
    val csvFile: File,
) {
    val durationMs: Long get() = (meta.endedAt ?: meta.lastSampleAt ?: meta.startedAt) - meta.startedAt
    val sizeBytes: Long get() = csvFile.length()
    /** True when the process stopped before the recording was closed cleanly. */
    val incomplete: Boolean get() = meta.endedAt == null && meta.rows > 0
}

/** Outcome of finishing a recording. A false [success] must never be shown as "Saved". */
data class LogWriteResult(
    val success: Boolean,
    val message: String? = null,
    val rows: Int = 0,
)

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
        val baseId = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(startedAt))
        var id = baseId
        var suffix = 1
        // Never overwrite an earlier recording, even if two sessions start in the same millisecond.
        while (File(directory, "$id.csv").exists() || File(directory, "$id.json").exists()) {
            id = "$baseId-$suffix"
            suffix++
        }
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
        try {
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
        } catch (t: Throwable) {
            // Do not leave an orphan CSV or a half-written index entry behind a failed start.
            runCatching { csv.delete() }
            runCatching { metaFile.delete() }
            throw t
        }
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
    private var closed = false
    private var failure: String? = null
    private var lastSampleAt: Long? = null
    private var stallCount = 0
    private var longestStallMs = 0L

    /**
     * Appends one wide row. Missing channels are left blank rather than carrying a stale value,
     * so the age of each sample is explicit when the file is analysed.
     *
     * Throws when the row could not be written: callers must surface a recording failure instead
     * of continuing to claim that data is being saved.
     */
    @Synchronized
    fun append(timestampMs: Long, values: Map<Int, Double>) {
        check(!closed) { "Recording is already closed" }
        failure?.let { throw java.io.IOException(it) }
        val row = WideLogFormat.row(timestampMs, timestampMs - startedAt, columns, values)
        try {
            writer.append(row)
            rows++
            lastSampleAt = timestampMs
            sinceFlush++
            if (sinceFlush >= FLUSH_EVERY_ROWS) {
                writer.flush()
                sinceFlush = 0
                persistMeta(lastSampleAt = timestampMs)
            }
        } catch (t: Throwable) {
            failure = t.message ?: "Could not write the recording file"
            throw t
        }
    }

    /**
     * Records a sampling gap so the file's metadata explains why the CSV has a hole.
     *
     * The poll loop calls this after it resumes: a stall is often followed by the process being
     * killed, so the index is updated immediately instead of waiting for the next flush.
     */
    @Synchronized
    fun noteStall(durationMs: Long) {
        if (closed || durationMs <= 0L) return
        stallCount++
        longestStallMs = maxOf(longestStallMs, durationMs)
        runCatching { persistMeta() }
    }

    /**
     * Flushes and closes the CSV, then writes the session index atomically. Returns an explicit
     * result: a flush, close or metadata failure is a failed recording, not a saved one.
     */
    @Synchronized
    fun close(endedAt: Long = System.currentTimeMillis()): LogWriteResult {
        if (closed) {
            return if (failure == null) LogWriteResult(true, rows = rows)
            else LogWriteResult(false, failure, rows)
        }
        closed = true
        var problem = failure
        try {
            writer.flush()
        } catch (t: Throwable) {
            problem = problem ?: (t.message ?: "Could not flush the recording file")
        }
        try {
            writer.close()
        } catch (t: Throwable) {
            problem = problem ?: (t.message ?: "Could not close the recording file")
        }
        meta = meta.copy(
            endedAt = endedAt,
            rows = rows,
            pids = columns.map { "${it.name} [${it.unit}]" },
            formatVersion = 2,
            columns = columns.map { LogColumnMeta(it.id, it.name, it.unit) },
            stallCount = stallCount,
            longestStallMs = longestStallMs,
        )
        try {
            persistMeta(endedAt = endedAt, lastSampleAt = lastSampleAt ?: endedAt)
        } catch (t: Throwable) {
            problem = problem ?: (t.message ?: "Could not write the recording metadata")
        }
        return if (problem == null) {
            LogWriteResult(true, rows = rows)
        } else {
            LogWriteResult(
                success = false,
                message = "The recording stopped with an error: $problem The partial file was kept.",
                rows = rows,
            )
        }
    }

    private fun persistMeta(endedAt: Long? = null, lastSampleAt: Long? = null) {
        meta = meta.copy(
            endedAt = endedAt ?: meta.endedAt,
            lastSampleAt = lastSampleAt ?: meta.lastSampleAt,
            rows = rows,
            pids = columns.map { "${it.name} [${it.unit}]" },
            formatVersion = 2,
            columns = columns.map { LogColumnMeta(it.id, it.name, it.unit) },
            stallCount = stallCount,
            longestStallMs = longestStallMs,
        )
        val payload = json.encodeToString(meta)
        // Write a temporary file and rename it over the index entry. A process kill can then
        // never leave a half-written JSON sidecar that hides an otherwise recoverable CSV.
        val tmp = File(metaFile.parentFile, "${metaFile.name}.tmp")
        tmp.writeText(payload)
        if (!tmp.renameTo(metaFile)) {
            metaFile.writeText(payload)
            tmp.delete()
        }
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
