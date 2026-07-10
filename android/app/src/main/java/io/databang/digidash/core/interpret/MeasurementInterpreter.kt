package io.databang.digidash.core.interpret

import io.databang.digidash.core.ecumodel.EcuField
import io.databang.digidash.core.ecumodel.EcuModel
import io.databang.digidash.core.ecumodel.Thresholds
import io.databang.digidash.domain.model.InterpretedBlock
import io.databang.digidash.domain.model.InterpretedMeasurement
import io.databang.digidash.domain.model.MeasurementStatus
import io.databang.digidash.domain.model.RawMeasuringBlock
import java.util.Locale

interface MeasurementInterpreter {
    fun interpret(block: RawMeasuringBlock, model: EcuModel): InterpretedBlock
}

/**
 * Applies ECU Model formulas and thresholds to raw fields.
 * Raw values are always preserved; a value that cannot be interpreted is
 * reported as UNAVAILABLE with display text "N/A", never as zero.
 */
class DefaultMeasurementInterpreter : MeasurementInterpreter {

    override fun interpret(block: RawMeasuringBlock, model: EcuModel): InterpretedBlock {
        val group = model.group(block.group)
        val fieldCount = maxOf(block.fields.size, group?.fields?.maxOfOrNull { it.index } ?: 0)
        val measurements = (1..fieldCount).map { index ->
            interpretField(
                raw = block.fields.find { it.index == index }?.raw,
                wire = block.fields.find { it.index == index }?.wire,
                spec = group?.field(index),
                group = block.group,
                index = index,
                timestampMillis = block.timestampMillis,
            )
        }
        return InterpretedBlock(
            group = block.group,
            label = group?.label ?: "Group ${EcuModel.groupKey(block.group)}",
            measurements = measurements,
        )
    }

    private fun interpretField(
        raw: String?,
        wire: String? = null,
        spec: EcuField?,
        group: Int,
        index: Int,
        timestampMillis: Long,
    ): InterpretedMeasurement {
        val key = spec?.key ?: "raw_${EcuModel.groupKey(group)}_$index"
        val name = spec?.name ?: "Field $index"
        val unit = spec?.unit.orEmpty()

        if (raw == null || raw.isBlank()) {
            return InterpretedMeasurement(
                key = key, name = name, group = group, fieldIndex = index,
                rawString = raw, wireRaw = wire, value = null,
                displayValue = "N/A", unit = unit,
                status = MeasurementStatus.UNAVAILABLE,
                confidence = spec?.confidence, timestampMillis = timestampMillis,
            )
        }

        val (value, display) = applyFormula(raw, spec)
        return InterpretedMeasurement(
            key = key, name = name, group = group, fieldIndex = index,
            rawString = raw, wireRaw = wire, value = value,
            displayValue = display, unit = unit,
            status = statusOf(value, spec?.thresholds),
            confidence = spec?.confidence, timestampMillis = timestampMillis,
        )
    }

    /** Returns numeric value (when any) and the text to display. */
    private fun applyFormula(raw: String, spec: EcuField?): Pair<Double?, String> {
        val formula = spec?.formula
        val numericRaw = raw.trim().toDoubleOrNull()
        return when (formula?.type) {
            null, "raw", "unsupported" -> numericRaw to (numericRaw?.let(::format) ?: raw)
            "string" -> null to raw
            "scale_offset" -> {
                if (numericRaw == null) {
                    null to raw
                } else {
                    val value = numericRaw * (formula.scale ?: 1.0) + (formula.offset ?: 0.0)
                    value to format(value)
                }
            }
            "enum" -> {
                val label = formula.map?.get(raw.trim()) ?: raw
                numericRaw to label
            }
            else -> numericRaw to raw
        }
    }

    private fun statusOf(value: Double?, thresholds: Thresholds?): MeasurementStatus {
        if (value == null) return MeasurementStatus.UNKNOWN
        if (thresholds == null) return MeasurementStatus.UNKNOWN
        return when {
            thresholds.critical?.contains(value) == true -> MeasurementStatus.CRITICAL
            thresholds.normal?.contains(value) == true -> MeasurementStatus.NORMAL
            thresholds.warning?.contains(value) == true -> MeasurementStatus.WARNING
            else -> MeasurementStatus.WARNING
        }
    }

    private fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString()
        else String.format(Locale.US, "%.1f", value)
}
