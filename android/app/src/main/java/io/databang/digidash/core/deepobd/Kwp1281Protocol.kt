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
    const val TITLE_BASIC_SETTING = 0x28
    // Early Digifant (KW1281) uses a param-less raw-display read (group 0) and a
    // param-less Basic Settings, both answered with title 0xF4 — NOT 0x29/0xE7.
    const val TITLE_RAW_DATA_REQUEST = 0x12
    const val TITLE_RAW_DATA_RESPONSE = 0xF4
    const val TITLE_BASIC_SETTING_START = 0x11
    /** Titles that carry measuring data (new 0xF4, VAG-COM 0xE7, legacy 0x02 header). */
    val MEASURING_TITLES = setOf(TITLE_RAW_DATA_RESPONSE, TITLE_GROUP_RESPONSE, 0x02)
    const val TITLE_DTC_REQUEST = 0x07
    const val TITLE_DTC_RESPONSE = 0xFC
    const val TITLE_DTC_CLEAR = 0x05
    const val TITLE_END_OUTPUT = 0x06
    const val TITLE_ASCII = 0xF6
    const val TITLE_NO_DATA = 0x0A

    /** A decoded measuring-block field: value plus the VAG data-type id. */
    data class Kw1281Field(val type: Int, val a: Int, val b: Int) {
        /** Formatted physical value, or null when the type is unknown/raw. */
        fun toRawField(index: Int): RawField = RawField(index = index, raw = display())

        fun display(): String {
            // Type 10 is a boolean flag (warm-up), not a number.
            if (type == 0x0A) return if (b != 0) "WARM" else "COLD"
            val v = value()
            return v?.let { formatNumber(it) } ?: "$a/$b"
        }

        /**
         * Physical value per the standard KW1281 measuring-block type table
         * (matched to RXTX4816/OBDisplay-Uno's KWPSensorDecode). Unknown/flag
         * types return null so the UI shows raw a/b rather than a fake 0.
         */
        fun value(): Double? = when (type) {
            1 -> 0.2 * a * b                          // rpm
            2 -> 0.002 * a * b                        // %
            3 -> 0.002 * a * b                        // deg
            4 -> (b - 127) * a * -0.01                // deg BTDC/ATDC (SIGNED)
            5 -> (b - 100) * 0.1 * a                  // °C
            6 -> 0.001 * a * b                        // V
            7 -> 0.01 * a * b                         // km/h
            8 -> 0.1 * a * b                          // scaled
            9 -> (b - 127) * 0.02 * a                 // deg
            // 10 WARM/COLD flag -> handled in display()
            11 -> 0.0001 * a * (b - 128) + 1.0        // lambda
            12 -> 0.001 * a * b                       // Ohm
            13 -> (b - 127) * 0.001 * a               // mm
            14 -> 0.005 * a * b                       // bar
            15 -> 0.01 * a * b                        // ms
            // 16 bitfield, 17 ascii -> raw
            18 -> 0.04 * a * b                        // mbar
            19 -> 0.01 * a * b                        // L
            20 -> (b - 128) * a / 128.0               // %
            21 -> 0.001 * a * b                       // V
            22 -> 0.001 * a * b                       // ms
            23 -> a * b / 256.0                       // %
            24 -> 0.001 * a * b                       // A
            25 -> (256 * b + a) / 180.0 // DISPUTED: KaPoder uses 1.421*b + a/182; no authority — unused by the 2E               // g/s
            26 -> (b - a).toDouble()                  // °C
            27 -> (b - 128) * a * 0.01                // deg (SIGNED)
            28 -> (b - a).toDouble()
            // 29 flag -> raw
            30 -> a * b / 12.0                         // deg k/w
            31 -> a * b / 2560.0                       // °C
            32 -> (if (b > 128) b - 256 else b).toDouble() // signed8
            33 -> if (a == 0) 100.0 * b else 100.0 * b / a // %
            34 -> (b - 128) * a * 0.01                 // deg k/w (idle corr, NOT kW)
            35 -> 0.01 * a * b                         // l/h
            36 -> (a * 256 + b) * 10.0                 // km
            37 -> b.toDouble()
            38 -> (b - 128) * a * 0.001                // deg k/w
            39 -> a * b / 255.0 // DISPUTED: KaPoder divides by 256; unused by the 2E                        // mg/h
            40 -> (a * 255 + b - 4000) * 0.1           // A
            41 -> (a * 255 + b).toDouble()             // Ah
            42 -> (a * 255 + b - 4000) * 0.1           // kW
            43 -> (a * 255 + b) * 0.1                  // V
            // 44 h:m -> raw
            45 -> 0.001 * a * b
            46 -> (a * b - 3200) * 0.0027              // deg k/w
            47 -> ((b - 128) * a).toDouble()           // ms
            48 -> (a * 255 + b).toDouble()
            49 -> a * b * 0.025                         // mg/h
            50 -> if (a == 0) (b - 128) * 100.0 else (b - 128) * 100.0 / a // mbar
            51 -> (b - 128) * a / 255.0                 // mg/h
            52 -> a * (0.02 * b - 1)                    // Nm
            53 -> (b - 128) * 1.4222 + 0.006 * a        // g/s
            54 -> (a * 256 + b).toDouble()              // count
            55 -> 0.005 * a * b                         // s
            56 -> (a * 256 + b).toDouble()              // WSC
            57 -> (a * 256 + b + 65536).toDouble()      // WSC
            58 -> if (b > 128) 1.0225 * (256 - b) else 1.0225 * b // deg
            59 -> (a * 256 + b) / 32768.0
            60 -> (a * 256 + b) * 0.01                  // s
            61 -> if (a == 0) (b - 128).toDouble() else (b - 128) / a.toDouble()
            62 -> 0.256 * a * b                         // s
            // 63 ascii -> raw
            64 -> (a + b).toDouble()                    // Ohm
            65 -> (b - 127) * 0.01 * a                  // mm
            66 -> a * b / 511.12                        // V
            67 -> 640.0 * a + 2.5 * b                   // deg
            68 -> (256 * a + b) / 7.365                 // deg/s
            69 -> (256 * a + b) * 0.3254                // bar
            70 -> (256 * a + b) * 0.192                 // m/s²
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

    // --- Header+body measuring format (old Digifant; SOURCE KLineKWP1281Lib) ---
    // 0x29 <g> is answered by a GROUP HEADER (title 0x02): per-zone records
    // [formula, NWb, tableLen, table...]; an immediate second 0x29 <g> returns
    // the GROUP BODY (title 0xF4): one MWb byte per zone. Formulas 0x8B/0x8C/
    // 0x93 interpolate MWb over the 17-byte table; others use (formula,NWb,MWb).
    const val TITLE_GROUP_HEADER = 0x02

    class HeaderRecord(val formula: Int, val nwb: Int, val table: ByteArray)

    fun parseGroupHeader(data: ByteArray): List<HeaderRecord> {
        val recs = mutableListOf<HeaderRecord>()
        var i = 0
        while (i + 2 < data.size) {
            val f = data[i].toInt() and 0xFF
            val n = data[i + 1].toInt() and 0xFF
            val len = data[i + 2].toInt() and 0xFF
            val end = i + 3 + len
            if (end > data.size) break
            recs.add(HeaderRecord(f, n, data.copyOfRange(i + 3, end)))
            i = end
        }
        return recs
    }

    /** Value of one header+body zone (KLineKWP1281Lib algorithm, verbatim). */
    fun headerBodyValue(rec: HeaderRecord, mwb: Int): Double? = when (rec.formula) {
        0x8B, 0x8C, 0x93 -> {
            if (rec.table.size != 17) null else {
                val idx = (mwb / 16).coerceAtMost(15)
                val mapByte = rec.table[idx].toInt() and 0xFF
                val diff = (rec.table[idx + 1].toInt() and 0xFF) - mapByte
                val res = mapByte + diff * (mwb % 16) / 16.0
                if (rec.formula == 0x8B) res * rec.nwb else res - rec.nwb
            }
        }
        else -> highFormulaValue(rec.formula, rec.nwb, mwb)
    }

    /** Standard formulas >=0x80 (SOURCE KLineKWP1281Lib getMeasurementValue). */
    fun highFormulaValue(k: Int, a: Int, b: Int): Double? = when (k) {
        0x80, 0x86, 0x87, 0x9A -> (a * b).toDouble()
        0x81, 0x85 -> a * b / 256.0
        0x82 -> a * b / 2560.0
        0x83 -> a * b * 0.5 - 30
        0x84 -> a * b * 0.5
        0x88 -> (b and a).toDouble()
        0x89, 0x90, 0x91 -> a * b * 0.01
        0x8A -> a * b * 0.001
        0x8F, 0x97 -> (b - 128) * a * 0.01
        0x92 -> 1 + (b - 128) * a * 0.0001
        0x94 -> (b - 128) * a * 0.25
        0x95 -> (b - 100) * a * 0.1
        0x96 -> (256 * b + a) / 180.0
        0x98 -> a * b * 0.025
        0x99 -> (b - 128) * a / 255.0
        0x9B -> a * b * 0.01 - 90
        else -> if (k in 1..0x7F) Kw1281Field(k, a, b).value() else null
    }

    fun headerBodyUnit(formula: Int): String = when (formula) {
        0x80, 0x8B -> "rpm"
        0x81, 0x93 -> "%"
        0x82 -> "A"
        0x83, 0x84, 0x8F, 0x9A, 0x9B -> "°"
        0x85, 0x8A -> "V"
        0x86 -> "km/h"
        0x89 -> "ms"
        0x8C, 0x95 -> "°C"
        0x90 -> "l/h"
        0x96 -> "g/s"
        0x98 -> "mg"
        else -> ""
    }

    /** Decode a header+body pair into per-zone fields (bits shown as binary). */
    fun decodeHeaderBody(header: ByteArray, body: ByteArray): List<RawField> {
        val recs = parseGroupHeader(header)
        return recs.mapIndexed { i, rec ->
            val mwb = body.getOrNull(i)?.toInt()?.and(0xFF)
            val display = when {
                mwb == null -> "N/A"
                rec.formula == 0x88 ->
                    Integer.toBinaryString(mwb and rec.nwb).padStart(8, '0')
                else -> headerBodyValue(rec, mwb)?.let { formatNumber(it) } ?: "$mwb"
            }
            RawField(index = i + 1, raw = display)
        }
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
                    statusByte = status,
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
