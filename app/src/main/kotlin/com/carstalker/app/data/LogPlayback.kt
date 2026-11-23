package com.carstalker.app.data

import java.io.File
import java.time.Instant
import java.time.OffsetDateTime

data class PlaybackPoint(val timeMs: Long, val value: Double)

data class PlaybackSeries(
    val key: String,
    val name: String,
    val unit: String,
    val points: List<PlaybackPoint>,
) {
    val min: Double
    val max: Double
    val average: Double

    init {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        var sum = 0.0
        for (point in points) {
            if (point.value < lo) lo = point.value
            if (point.value > hi) hi = point.value
            sum += point.value
        }
        min = if (points.isEmpty()) 0.0 else lo
        max = if (points.isEmpty()) 0.0 else hi
        average = if (points.isEmpty()) 0.0 else sum / points.size
    }

    val rangeMin: Double get() = if (min == max) min - 1.0 else min
    val rangeMax: Double get() = if (min == max) max + 1.0 else max

    /** Value at or just before [timeMs]; null when the series has no sample yet. */
    fun valueAt(timeMs: Long): Double? {
        var low = 0
        var high = points.lastIndex
        var found: PlaybackPoint? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            val point = points[mid]
            if (point.timeMs <= timeMs) {
                found = point
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found?.value
    }
}

data class PlaybackData(
    val series: List<PlaybackSeries>,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1L)
    val sampleCount: Int get() = series.sumOf { it.points.size }
    val isEmpty: Boolean get() = series.isEmpty()
}

/**
 * Reads CarStalker CSV logs (`timestamp_iso,pid,value,unit`) for playback.
 * Values stay in the metric units written by the recorder.
 */
object LogPlaybackParser {

    fun parse(file: File): PlaybackData = file.useLines { parse(it) }

    fun parse(lines: Sequence<String>): PlaybackData {
        val all = lines.toList()
        return if (all.any { it.trim() == com.carstalker.core.log.WideLogFormat.MARKER }) {
            parseWide(all)
        } else {
            parseLong(all)
        }
    }

    /** Wide format: one row per tick, one column per channel, units in the header. */
    private fun parseWide(lines: List<String>): PlaybackData {
        val builders = LinkedHashMap<String, SeriesBuilder>()
        val columns = mutableListOf<Pair<String, String>>()
        var start = Long.MAX_VALUE
        var end = Long.MIN_VALUE
        var sawHeader = false

        for (line in lines) {
            if (line.isBlank()) continue
            if (line.startsWith("#")) continue
            val fields = parseCsvLine(line)
            if (!sawHeader) {
                // timestamp_iso,elapsed_s,Name [unit],...
                sawHeader = true
                for (index in 2 until fields.size) {
                    columns += splitColumnLabel(fields[index].trim())
                }
                continue
            }
            if (fields.size < 2) continue
            val time = parseTime(fields[0]) ?: continue
            for (index in columns.indices) {
                val value = fields.getOrNull(index + 2)?.trim()?.toDoubleOrNull() ?: continue
                val (name, unit) = columns[index]
                if (name.isEmpty()) continue
                val key = "$name|$unit"
                builders.getOrPut(key) { SeriesBuilder(key, name, unit) }.points += PlaybackPoint(time, value)
            }
            if (time < start) start = time
            if (time > end) end = time
        }

        val series = builders.values.mapNotNull { it.build() }
        return PlaybackData(
            series = series,
            startMs = if (start == Long.MAX_VALUE) 0L else start,
            endMs = if (end == Long.MIN_VALUE) 0L else end,
        )
    }

    private fun parseLong(lines: List<String>): PlaybackData {
        val builders = LinkedHashMap<String, SeriesBuilder>()
        var start = Long.MAX_VALUE
        var end = Long.MIN_VALUE

        for (line in lines) {
            if (line.isBlank()) continue
            if (line.startsWith("timestamp_iso")) continue
            val fields = parseCsvLine(line)
            if (fields.size < 4) continue
            val time = parseTime(fields[0]) ?: continue
            val name = fields[1].trim()
            val value = fields[2].trim().toDoubleOrNull() ?: continue
            val unit = fields[3].trim()
            if (name.isEmpty()) continue
            val key = "$name|$unit"
            builders.getOrPut(key) { SeriesBuilder(key, name, unit) }.points += PlaybackPoint(time, value)
            if (time < start) start = time
            if (time > end) end = time
        }

        val series = builders.values.mapNotNull { it.build() }
        return PlaybackData(
            series = series,
            startMs = if (start == Long.MAX_VALUE) 0L else start,
            endMs = if (end == Long.MIN_VALUE) 0L else end,
        )
    }

    private fun splitColumnLabel(label: String): Pair<String, String> {
        val open = label.lastIndexOf(" [")
        if (open <= 0 || !label.endsWith("]")) return label to ""
        return label.substring(0, open).trim() to label.substring(open + 2, label.length - 1).trim()
    }

    private fun parseTime(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        runCatching { OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        return runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrNull()
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    fields += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        fields += current.toString()
        return fields
    }

    private class SeriesBuilder(
        private val key: String,
        private val name: String,
        private val unit: String,
    ) {
        val points = mutableListOf<PlaybackPoint>()

        fun build(): PlaybackSeries? {
            if (points.isEmpty()) return null
            val sorted = points.sortedBy { it.timeMs }
            val deduped = ArrayList<PlaybackPoint>(sorted.size)
            for (point in sorted) {
                if (deduped.isNotEmpty() && deduped.last().timeMs == point.timeMs) {
                    deduped[deduped.lastIndex] = point
                } else {
                    deduped += point
                }
            }
            return PlaybackSeries(key = key, name = name, unit = unit, points = deduped)
        }
    }
}
