package io.databang.digidash.core.ecumodel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Serialized form of schemas/ecu-model.schema.json. */
@Serializable
data class EcuModel(
    @SerialName("ecu_model_version") val ecuModelVersion: Int,
    @SerialName("ecu_part_number") val ecuPartNumber: String,
    @SerialName("display_name") val displayName: String,
    val protocol: String,
    val system: String? = null,
    @SerialName("engine_codes") val engineCodes: List<String> = emptyList(),
    val source: EcuModelSourceInfo? = null,
    val compatibility: EcuCompatibility? = null,
    val groups: Map<String, EcuGroup> = emptyMap(),
    @SerialName("dtc_catalog") val dtcCatalog: Map<String, String> = emptyMap(),
) {
    /** Fields flagged for the Trip dashboard, ordered by their display order. */
    fun tripCardFields(): List<Pair<Int, EcuField>> =
        groups.flatMap { (groupKey, group) ->
            val groupNumber = groupKey.toIntOrNull() ?: return@flatMap emptyList()
            group.fields.filter { it.display?.tripCard == true }
                .map { groupNumber to it }
        }.sortedBy { (_, field) -> field.display?.order ?: Int.MAX_VALUE }

    fun group(number: Int): EcuGroup? = groups[groupKey(number)]

    companion object {
        fun groupKey(number: Int): String = number.toString().padStart(3, '0')
    }
}

@Serializable
data class EcuModelSourceInfo(
    val type: String,
    val confidence: String? = null,
    val notes: String? = null,
)

@Serializable
data class EcuCompatibility(
    @SerialName("vehicle_notes") val vehicleNotes: String? = null,
)

@Serializable
data class EcuGroup(
    val label: String = "",
    @SerialName("polling_priority") val pollingPriority: String? = null,
    val fields: List<EcuField> = emptyList(),
) {
    fun field(index: Int): EcuField? = fields.find { it.index == index }
}

@Serializable
data class EcuField(
    val index: Int,
    val key: String,
    val name: String,
    val unit: String = "",
    @SerialName("value_type") val valueType: String = "unknown",
    val formula: Formula = Formula(type = "raw"),
    val critical: Boolean = false,
    val confidence: String? = null,
    val notes: String? = null,
    val display: DisplaySpec? = null,
    val thresholds: Thresholds? = null,
)

@Serializable
data class Formula(
    val type: String,
    val scale: Double? = null,
    val offset: Double? = null,
    val map: Map<String, String>? = null,
)

@Serializable
data class DisplaySpec(
    @SerialName("trip_card") val tripCard: Boolean = false,
    val order: Int? = null,
)

@Serializable
data class Thresholds(
    val normal: ThresholdRange? = null,
    val warning: ThresholdRange? = null,
    val critical: ThresholdRange? = null,
)

@Serializable
data class ThresholdRange(
    val min: Double? = null,
    val max: Double? = null,
) {
    fun contains(value: Double): Boolean =
        (min == null || value >= min) && (max == null || value <= max)
}

@Serializable
data class EcuModelIndex(
    val version: Int,
    val models: List<EcuModelIndexEntry> = emptyList(),
)

@Serializable
data class EcuModelIndexEntry(
    @SerialName("ecu_part_number") val ecuPartNumber: String,
    @SerialName("display_name") val displayName: String,
    val file: String,
    val confidence: String? = null,
)
