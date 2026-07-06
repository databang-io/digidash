package io.databang.digidash.core.deepobd

import java.io.Closeable

/**
 * A raw byte pipe to the adapter over Bluetooth SPP. Kept as an interface so the
 * protocol/probe logic can be unit-tested against an in-memory fake without a
 * real socket.
 */
interface SppTransport : Closeable {
    /** Open the underlying socket. Throws on failure. */
    fun connect()

    fun write(data: ByteArray)

    /**
     * Read up to [max] bytes, blocking until at least one byte is available or
     * [timeoutMillis] elapses. Returns the bytes read (possibly empty on timeout).
     */
    fun read(max: Int, timeoutMillis: Long): ByteArray

    val isConnected: Boolean
}

/** SPP service-record UUID used by ELM327 / custom Deep OBD adapters. */
const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
