package io.databang.digidash.core.deepobd

import io.databang.digidash.domain.model.RawDtc
import io.databang.digidash.domain.model.RawField

/**
 * KWP1281 (KW1281) application-layer helpers: block titles and the decoding of
 * measuring-group and fault-code responses. Reference: blafusel.de KW1281 notes
 * and docs/DeepOBD-Observed-API.md.
 *
 * On the wire a KWP1281 block is `<len> <counter> <title> <data...> 0x03`, but
 * the Deep OBD adapter firmware strips the framing and block-counter handshake,
 * so here we work on the title + data payload the firmware hands back.
 */
object Kwp1281Protocol {

    const val TITLE_ACK = 0x09
    const val TITLE_GROUP_REQUEST = 0x29
    const val TITLE_GROUP_RESPONSE = 0xE7
    const val TITLE_DTC_REQUEST = 0x07
    const val TITLE_DTC_RESPONSE = 0xFC
    const val TITLE_DTC_CLEAR = 0x05
    const val TITLE_ASCII = 0xF6
    const val TITLE_NO_DATA = 0x0A

    /** A decoded measuring-block field: value plus the VAG data-type id. */
    data class Kw1281Field(val type: Int, val a: Int, val b: Int) {
        /** Formatted physical value, or null when the type is unknown/raw. */
        fun toRawField(index: Int): RawField = RawField(index = index, raw = display())

        fun display(): String {
            val v = value()
            return v?.let { formatNumber(it) } ?: "$a/$b"
        }

        /** Physical value per the standard KW1281 type table, when known. */
        fun value(): Double? = when (type) {
            0x01 -> 0.2 * a * b               // rpm
            0x05 -> a * (b - 100) * 0.1       // temperature °C
            0x06 -> 0.001 * a * b             // voltage
            0x07 -> 0.01 * a * b              // speed km/h
            0x0F -> 0.01 * a * b              // time ms
            0x12 -> 0.001 * a * b             // pressure/voltage
            0x21 -> if (a != 0) 100.0 * b / a else 0.0 // percent
            0x04, 0x1B -> kotlin.math.abs(b - 127) * 0.01 * a // ignition deg
            else -> null
        }
    }

    /**
     * Decode a group response: the data after the title is a sequence of 3-byte
     * triplets `(type, a, b)`. Group 000 is the exception — 10 raw single bytes
     * with no type — handled by [decodeGroup000].
     */
    fun decodeGroupResponse(data: ByteArray): List<RawField> {
        val fields = ArrayList<RawField>()
        var i = 0
        var index = 1
        while (i + 2 < data.size) {
            val type = data[i].toInt() and 0xFF
            val a = data[i + 1].toInt() and 0xFF
            val b = data[i + 2].toInt() and 0xFF
            fields.add(Kw1281Field(type, a, b).toRawField(index))
            i += 3
            index++
        }
        return fields
    }

    /** Group 000: up to 10 raw bytes, no data types. */
    fun decodeGroup000(data: ByteArray): List<RawField> =
        data.take(10).mapIndexed { i, b -> RawField(index = i + 1, raw = (b.toInt() and 0xFF).toString()) }

    fun decodeGroup(group: Int, data: ByteArray): List<RawField> =
        if (group == 0) decodeGroup000(data) else decodeGroupResponse(data)

    /**
     * Decode a fault-code response block. Classic KWP1281 encodes each DTC as
     * 3 bytes: two code bytes + a status byte. The 5-digit code is
     * `hi*256 + lo` (rendered zero-padded to 5 digits).
     */
    fun decodeDtcResponse(data: ByteArray): List<RawDtc> {
        val dtcs = ArrayList<RawDtc>()
        var i = 0
        while (i + 2 < data.size) {
            val hi = data[i].toInt() and 0xFF
            val lo = data[i + 1].toInt() and 0xFF
            val status = data[i + 2].toInt() and 0xFF
            val codeNum = hi * 256 + lo
            if (codeNum == 0xFFFF || codeNum == 0) { i += 3; continue }
            dtcs.add(
                RawDtc(
                    code = codeNum.toString().padStart(5, '0'),
                    statusRaw = "%02d-%02d".format(status shr 4, status and 0x0F),
                )
            )
            i += 3
        }
        return dtcs
    }

    private fun formatNumber(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.2f", v)
}
