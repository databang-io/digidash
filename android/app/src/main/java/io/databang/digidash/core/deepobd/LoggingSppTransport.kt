package io.databang.digidash.core.deepobd

import java.io.Closeable

/** Sink for raw traffic lines. Kept minimal so tests can use a StringBuilder. */
fun interface RawTrafficSink {
    fun line(text: String)
}

/**
 * Wraps an [SppTransport] and tees every byte to a [RawTrafficSink] as a
 * hex dump with direction and elapsed-ms, so the KWP1281 framing can be
 * debugged from a capture (ticket 14 validation). Transparent: forwards all
 * calls unchanged.
 */
class LoggingSppTransport(
    private val delegate: SppTransport,
    private val sink: RawTrafficSink,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : SppTransport, Closeable {

    private var startMillis = 0L

    override val isConnected: Boolean get() = delegate.isConnected

    override fun connect() {
        startMillis = clockMillis()
        sink.line("# capture start")
        delegate.connect()
        sink.line(stamp() + " CONNECT")
    }

    override fun write(data: ByteArray) {
        sink.line(stamp() + " TX ${data.size}: ${hex(data)}")
        delegate.write(data)
    }

    override fun read(max: Int, timeoutMillis: Long): ByteArray {
        val out = delegate.read(max, timeoutMillis)
        if (out.isNotEmpty()) sink.line(stamp() + " RX ${out.size}: ${hex(out)}")
        return out
    }

    override fun close() {
        sink.line(stamp() + " CLOSE")
        delegate.close()
    }

    private fun stamp(): String = "+%06d".format(clockMillis() - startMillis)

    companion object {
        fun hex(data: ByteArray): String =
            data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    }
}
