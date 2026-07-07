package io.databang.digidash.domain.model

/** Identity of the connected ECU as reported over the diagnostic link. */
data class EcuIdentity(
    val partNumberRaw: String,
    val partNumberNormalized: String,
    val component: String,
    val serialNumber: String? = null,
    val protocol: String? = null,
    /** VIN if the ECU reports one. Old KWP1281 Digifant ECUs usually do not. */
    val vin: String? = null,
) {
    companion object {
        /** `037 906 024 AG` / `037-906-024-ag` -> `037906024AG` */
        fun normalizePartNumber(raw: String): String =
            raw.replace(Regex("[\\s.\\-]"), "").uppercase()

        fun fromRaw(
            partNumberRaw: String,
            component: String,
            serialNumber: String? = null,
            protocol: String? = null,
            vin: String? = null,
        ): EcuIdentity = EcuIdentity(
            partNumberRaw = partNumberRaw,
            partNumberNormalized = normalizePartNumber(partNumberRaw),
            component = component,
            serialNumber = serialNumber,
            protocol = protocol,
            vin = vin,
        )
    }
}

/** One field of a measuring block exactly as the ECU returned it. */
data class RawField(
    val index: Int,
    val raw: String,
) {
    /** Numeric view of the raw value, or null when the ECU sent text. */
    val numeric: Double? = raw.trim().toDoubleOrNull()
}

/** One measuring block (group) read from the ECU. */
data class RawMeasuringBlock(
    val group: Int,
    val fields: List<RawField>,
    val timestampMillis: Long,
)

/** A diagnostic trouble code as reported, before catalog lookup. */
data class RawDtc(
    val code: String,
    val statusRaw: String? = null,
    val description: String? = null,
    /** Raw KWP1281 fault status byte, when available. */
    val statusByte: Int? = null,
)

enum class DtcSeverity { INFO, WARNING, CRITICAL }

/** A DTC after ECU-model catalog lookup. */
data class InterpretedDtc(
    val code: String,
    val title: String?,
    val statusRaw: String?,
    val severity: DtcSeverity,
    val raw: RawDtc,
    /** True when the fault is intermittent (not currently present). */
    val sporadic: Boolean = false,
    /** Best-effort fault nature (open circuit, short, implausible…), if decodable. */
    val elaboration: String? = null,
) {
    val hasDescription: Boolean get() = !title.isNullOrBlank()

    /** "Sporadic" / "Present" label for the UI. */
    val presenceLabel: String get() = if (sporadic) "Sporadic (intermittent)" else "Present"
}

enum class MeasurementStatus { NORMAL, WARNING, CRITICAL, UNKNOWN, UNAVAILABLE }

/** A raw field after interpretation through the ECU Model. */
data class InterpretedMeasurement(
    val key: String,
    val name: String,
    val group: Int,
    val fieldIndex: Int,
    val rawString: String?,
    val value: Double?,
    val displayValue: String,
    val unit: String,
    val status: MeasurementStatus,
    val confidence: String? = null,
    val timestampMillis: Long = 0L,
)

/** All interpreted fields of one group. */
data class InterpretedBlock(
    val group: Int,
    val label: String,
    val measurements: List<InterpretedMeasurement>,
)

/** State of a single dashboard card. Missing values are shown as N/A, never 0. */
data class DashboardCardState(
    val key: String,
    val title: String,
    val valueText: String,
    val unit: String,
    val status: MeasurementStatus,
    val stale: Boolean = false,
    val lowConfidence: Boolean = false,
) {
    companion object {
        const val NOT_AVAILABLE = "N/A"

        fun unavailable(key: String, title: String, unit: String = "") = DashboardCardState(
            key = key,
            title = title,
            valueText = NOT_AVAILABLE,
            unit = unit,
            status = MeasurementStatus.UNAVAILABLE,
        )
    }
}
