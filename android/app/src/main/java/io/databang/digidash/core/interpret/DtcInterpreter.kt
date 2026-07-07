package io.databang.digidash.core.interpret

import io.databang.digidash.core.ecumodel.EcuModel
import io.databang.digidash.domain.model.DtcSeverity
import io.databang.digidash.domain.model.InterpretedDtc
import io.databang.digidash.domain.model.RawDtc

/** Looks up raw DTCs in the ECU Model catalog and assigns a coarse severity. */
object DtcInterpreter {

    // Codes that indicate the engine may stall or run unsafely on the 2E.
    private val criticalCodes = setOf("00515", "00513", "00512", "00514", "65535")

    fun interpret(raw: RawDtc, model: EcuModel?): InterpretedDtc {
        val title = model?.dtcCatalog?.get(raw.code) ?: raw.description
        val severity = when {
            raw.code in criticalCodes -> DtcSeverity.CRITICAL
            title == null -> DtcSeverity.INFO
            else -> DtcSeverity.WARNING
        }
        // KWP1281 fault status byte: high bit = sporadic/intermittent; the low
        // nibble is an elaboration code (fault nature). Mappings are the common
        // VAG conventions and stay best-effort until confirmed on the vehicle.
        val status = raw.statusByte
        val sporadic = status != null && (status and 0x80) != 0
        val elaboration = status?.let { elaborationOf(it and 0x7F) }
        return InterpretedDtc(
            code = raw.code,
            title = title,
            statusRaw = raw.statusRaw,
            severity = severity,
            raw = raw,
            sporadic = sporadic,
            elaboration = elaboration,
        )
    }

    private fun elaborationOf(code: Int): String? = when (code) {
        0x00 -> null
        0x10 -> "Open circuit"
        0x11 -> "Short to ground"
        0x12 -> "Short to plus"
        0x13 -> "Open or short to plus"
        0x14 -> "Short to ground / open"
        0x20 -> "Signal implausible"
        0x21 -> "Signal too high"
        0x22 -> "Signal too low"
        0x30 -> "No signal / no communication"
        0x40 -> "Mechanical fault"
        else -> "Elaboration 0x%02X".format(code)
    }

    fun interpret(raws: List<RawDtc>, model: EcuModel?): List<InterpretedDtc> =
        raws.map { interpret(it, model) }
}
