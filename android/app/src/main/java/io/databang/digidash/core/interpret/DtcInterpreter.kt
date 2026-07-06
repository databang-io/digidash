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
        return InterpretedDtc(
            code = raw.code,
            title = title,
            statusRaw = raw.statusRaw,
            severity = severity,
            raw = raw,
        )
    }

    fun interpret(raws: List<RawDtc>, model: EcuModel?): List<InterpretedDtc> =
        raws.map { interpret(it, model) }
}
