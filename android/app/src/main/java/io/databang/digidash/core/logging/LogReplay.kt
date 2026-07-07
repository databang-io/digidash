package io.databang.digidash.core.logging

import java.io.File

/** A parsed CSV log ready to graph: per interpreted_key, a list of (ms, value). */
data class ReplayData(
    val keys: List<String>,
    val unitByKey: Map<String, String>,
    val series: Map<String, List<Pair<Long, Double>>>,
) {
    val isEmpty: Boolean get() = series.isEmpty()
}

/** Parses a DigiDash CSV log (see [CsvLogger.HEADER]) into graphable series. */
object LogReplay {

    fun parse(file: File): ReplayData {
        if (!file.isFile) return ReplayData(emptyList(), emptyMap(), emptyMap())
        val lines = file.readLines()
        if (lines.size < 2) return ReplayData(emptyList(), emptyMap(), emptyMap())
        val header = splitCsv(lines.first())
        fun idx(name: String) = header.indexOf(name)
        val iType = idx("row_type")
        val iMs = idx("monotonic_ms")
        val iKey = idx("interpreted_key")
        val iVal = idx("interpreted_value")
        val iUnit = idx("unit")
        if (iMs < 0 || iKey < 0 || iVal < 0) {
            return ReplayData(emptyList(), emptyMap(), emptyMap())
        }

        val series = LinkedHashMap<String, MutableList<Pair<Long, Double>>>()
        val units = HashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val cols = splitCsv(line)
            if (iType >= 0 && cols.getOrNull(iType) != "sample") continue
            val key = cols.getOrNull(iKey)?.takeIf { it.isNotBlank() } ?: continue
            val ms = cols.getOrNull(iMs)?.toLongOrNull() ?: continue
            val value = cols.getOrNull(iVal)?.toDoubleOrNull() ?: continue
            series.getOrPut(key) { mutableListOf() }.add(ms to value)
            if (iUnit >= 0) cols.getOrNull(iUnit)?.let { if (it.isNotBlank()) units[key] = it }
        }
        return ReplayData(
            keys = series.keys.toList(),
            unitByKey = units,
            series = series,
        )
    }

    /** Minimal CSV field splitter honouring double-quoted fields. */
    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
