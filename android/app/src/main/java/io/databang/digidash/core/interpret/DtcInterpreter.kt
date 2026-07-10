package io.databang.digidash.core.interpret

import io.databang.digidash.core.ecumodel.EcuModel
import io.databang.digidash.domain.model.DtcSeverity
import io.databang.digidash.domain.model.InterpretedDtc
import io.databang.digidash.domain.model.RawDtc

/** Looks up raw DTCs in the ECU Model catalog and assigns a coarse severity. */
object DtcInterpreter {

    // Codes that indicate the engine may stall or run unsafely on the 2E.
    private val criticalCodes = setOf("65535") // manual p.4: control unit defective — the only unambiguous critical

    fun interpret(raw: RawDtc, model: EcuModel?): InterpretedDtc {
        val title = model?.dtcCatalog?.get(raw.code) ?: raw.description
        val severity = when {
            raw.code in criticalCodes -> DtcSeverity.CRITICAL
            title == null -> DtcSeverity.INFO
            else -> DtcSeverity.WARNING
        }
        // KWP1281 fault status byte. SOURCED: high bit = sporadic (manual p.1
        // "SP" addendum; gmenounos FaultCodesBlock.cs keeps status1 = byte&0x7F
        // as an OPAQUE number). The named "fault nature" table previously here
        // was unsourced and is removed (RULE ZERO) — we show the raw status only.
        val status = raw.statusByte
        val sporadic = status != null && (status and 0x80) != 0
        val elaboration = status?.let { s ->
            (s and 0x7F).takeIf { it != 0 }?.let { "status %d (unmapped)".format(it) }
        }
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


    fun interpret(raws: List<RawDtc>, model: EcuModel?): List<InterpretedDtc> =
        raws.map { interpret(it, model) }
}
