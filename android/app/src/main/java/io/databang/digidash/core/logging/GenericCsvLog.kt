package io.databang.digidash.core.logging

/**
 * Tolerant importer for arbitrary measuring-block CSV logs — VCDS-Lite's LOG
 * output or any comma/semicolon/tab-separated file. Produces the same
 * [ReplayData] the DigiDash replay graph consumes, so no new UI is needed.
 *
 * Strategy (kept deliberately simple):
 *  - Split every line on the delimiter that yields the most columns.
 *  - Any column whose data rows are mostly numeric becomes a graphable series.
 *  - The X axis is the first monotonically-increasing numeric column (a time or
 *    stamp column, as VCDS-Lite emits); otherwise the row ordinal.
 *  - Series are named from a header row if present, else "col N".
 */
object GenericCsvLog {

    fun parse(text: String): ReplayData {
        val rawLines = text.split(Regex("\r\n|\r|\n")).filter { it.isNotBlank() }
        if (rawLines.isEmpty()) return empty()

        val delimiter = bestDelimiter(rawLines)
        val rows = rawLines.map { it.split(delimiter).map(String::trim) }
        val width = rows.maxOf { it.size }
        if (width < 2) return empty()

        // Header = first row that has at least one non-numeric cell.
        val headerRowIdx = rows.indexOfFirst { row -> row.any { it.isNotEmpty() && it.toDoubleOrNull() == null } }
        val header = if (headerRowIdx >= 0) rows[headerRowIdx] else emptyList()
        val dataRows = rows.filterIndexed { i, _ -> i > headerRowIdx }
            .filter { row -> row.any { it.toDoubleOrNull() != null } }
        if (dataRows.isEmpty()) return empty()

        // Column value extraction.
        fun column(c: Int): List<Double?> = dataRows.map { it.getOrNull(c)?.toDoubleOrNull() }

        val numericCols = (0 until width).filter { c ->
            val vals = column(c).filterNotNull()
            vals.size >= dataRows.size / 2 && vals.size >= 2
        }
        if (numericCols.isEmpty()) return empty()

        // X axis: first strictly-increasing numeric column, else row index.
        val timeCol = numericCols.firstOrNull { c ->
            val vals = column(c).filterNotNull()
            vals.zipWithNext().all { (a, b) -> b >= a } && vals.first() != vals.last()
        }
        val xs: List<Long> = if (timeCol != null) {
            column(timeCol).mapIndexed { i, v -> (v ?: i.toDouble()).toLong() }
        } else {
            dataRows.indices.map { it.toLong() }
        }

        val seriesCols = numericCols.filter { it != timeCol }
        val series = LinkedHashMap<String, List<Pair<Long, Double>>>()
        for (c in seriesCols) {
            val name = header.getOrNull(c)?.takeIf { it.isNotBlank() } ?: "col $c"
            val pts = column(c).mapIndexedNotNull { i, v -> v?.let { xs[i] to it } }
            if (pts.size >= 2) series[uniqueKey(series, name)] = pts
        }
        return ReplayData(keys = series.keys.toList(), unitByKey = emptyMap(), series = series)
    }

    private fun uniqueKey(existing: Map<String, *>, base: String): String {
        if (base !in existing) return base
        var i = 2
        while ("$base ($i)" in existing) i++
        return "$base ($i)"
    }

    private fun bestDelimiter(lines: List<String>): Char {
        val sample = lines.take(10)
        return listOf(',', ';', '\t').maxBy { d -> sample.sumOf { it.count { ch -> ch == d } } }
    }

    private fun empty() = ReplayData(emptyList(), emptyMap(), emptyMap())
}
