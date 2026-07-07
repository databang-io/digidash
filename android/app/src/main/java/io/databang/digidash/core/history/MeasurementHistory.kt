package io.databang.digidash.core.history

/** A single time-stamped numeric sample. */
data class Sample(val timeMillis: Long, val value: Double)

/** Min/max envelope of a measurement over the session. */
data class PeakHold(val min: Double, val max: Double) {
    fun update(v: Double) = PeakHold(minOf(min, v), maxOf(max, v))
    companion object {
        fun of(v: Double) = PeakHold(v, v)
    }
}

/**
 * Rolling per-key history and min/max tracking for graphs and peak-hold.
 * Bounded ring buffers so memory stays flat on a long trip. Not thread-safe;
 * always mutated from the main-scoped ViewModel.
 */
class MeasurementHistory(private val capacity: Int = 600) {

    private val series = HashMap<String, ArrayDeque<Sample>>()
    private val peaks = HashMap<String, PeakHold>()

    fun record(key: String, timeMillis: Long, value: Double) {
        val q = series.getOrPut(key) { ArrayDeque(capacity) }
        // Skip duplicate timestamps (the measurements map re-emits in full).
        if (q.lastOrNull()?.timeMillis == timeMillis) return
        q.addLast(Sample(timeMillis, value))
        while (q.size > capacity) q.removeFirst()
        peaks[key] = peaks[key]?.update(value) ?: PeakHold.of(value)
    }

    fun history(key: String): List<Sample> = series[key]?.toList().orEmpty()

    fun peak(key: String): PeakHold? = peaks[key]

    fun reset() {
        series.clear()
        peaks.clear()
    }
}
