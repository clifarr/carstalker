package com.carstalker.app

import com.carstalker.app.data.LogColumnMeta
import com.carstalker.app.data.LogMeta
import com.carstalker.app.data.LogSession
import com.carstalker.app.data.LogWriter
import com.carstalker.core.log.LogColumn
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogWriterTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val columns = listOf(
        LogColumn(0x0C, "Engine RPM", "rpm"),
        LogColumn(0x05, "Engine coolant temperature", "°C"),
    )

    private fun writer(dir: File, startedAt: Long = 1_000L): Pair<LogWriter, File> {
        val csv = File(dir, "session.csv")
        val meta = File(dir, "session.json")
        val logMeta = LogMeta(
            id = "session",
            startedAt = startedAt,
            appVersion = "test",
            formatVersion = 2,
            columns = columns.map { LogColumnMeta(it.id, it.name, it.unit) },
        )
        return LogWriter(csv, meta, logMeta, json, columns, startedAt) to meta
    }

    @Test
    fun `close reports success only after rows and metadata are written`() {
        val dir = temporary.newFolder("logs")
        val (logWriter, metaFile) = writer(dir)

        logWriter.append(1_500, mapOf(0x0C to 800.0))
        logWriter.append(2_500, mapOf(0x0C to 810.0, 0x05 to 90.0))
        val result = logWriter.close(endedAt = 3_000)

        assertTrue(result.success)
        assertEquals(2, result.rows)
        val meta = json.decodeFromString<LogMeta>(metaFile.readText())
        assertEquals(2, meta.rows)
        assertEquals(3_000L, meta.endedAt)
        assertEquals(2_500L, meta.lastSampleAt)
        assertEquals(2, meta.columns.size)

        val lines = File(dir, "session.csv").readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        // A channel without a sample stays blank instead of carrying the previous value.
        assertEquals(4, lines[0].split(',').size)
        assertTrue(lines[0].endsWith(","))
        assertTrue(lines[1].contains("90"))
    }

    @Test
    fun `append after close is rejected instead of silently dropping data`() {
        val (logWriter, _) = writer(temporary.newFolder("closed"))

        logWriter.close()

        assertThrows(IllegalStateException::class.java) {
            logWriter.append(2_000, mapOf(0x0C to 900.0))
        }
    }

    @Test
    fun `metadata failure is reported as a failed close`() {
        val dir = temporary.newFolder("broken")
        val (logWriter, _) = writer(dir)
        logWriter.append(1_500, mapOf(0x0C to 800.0))
        dir.deleteRecursively()

        val result = logWriter.close(endedAt = 2_000)

        assertFalse(result.success)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("partial file", ignoreCase = true))
    }

    @Test
    fun `interrupted recording leaves a readable incomplete index entry`() {
        val dir = temporary.newFolder("interrupted")
        val (logWriter, metaFile) = writer(dir, startedAt = 1_000)
        for (index in 0 until 32) {
            logWriter.append(1_500L + index * 100, mapOf(0x0C to 800.0 + index))
        }

        val meta = json.decodeFromString<LogMeta>(metaFile.readText())
        assertNull(meta.endedAt)
        assertEquals(32, meta.rows)
        assertEquals(4_600L, meta.lastSampleAt)

        val session = LogSession(meta, File(dir, "session.csv"))
        assertTrue(session.incomplete)
        assertEquals(3_600L, session.durationMs)
    }

    @Test
    fun `sampling stalls are persisted in the metadata`() {
        val dir = temporary.newFolder("stalls")
        val (logWriter, metaFile) = writer(dir)

        logWriter.append(1_500, mapOf(0x0C to 800.0))
        logWriter.noteStall(12_000)
        logWriter.noteStall(40_000)
        val result = logWriter.close(endedAt = 60_000)

        assertTrue(result.success)
        val meta = json.decodeFromString<LogMeta>(metaFile.readText())
        assertEquals(2, meta.stallCount)
        assertEquals(40_000L, meta.longestStallMs)
    }

    @Test
    fun `an index entry written before stalls existed still decodes`() {
        val meta = json.decodeFromString<LogMeta>("""{"id":"old","startedAt":1}""")

        assertEquals(0, meta.stallCount)
        assertEquals(0L, meta.longestStallMs)
    }
}
