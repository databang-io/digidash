package io.databang.digidash.core.logging

import io.databang.digidash.domain.model.EcuIdentity
import io.databang.digidash.domain.model.InterpretedDtc
import io.databang.digidash.domain.model.InterpretedMeasurement
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a JSON debug capture (ECU identity + latest interpreted measurements
 * grouped by block + DTCs) to send back for ECU-model validation (ticket 14).
 * Pure string builder so it is unit-testable.
 */
object CaptureExporter {

    fun buildJson(
        identity: EcuIdentity?,
        modelName: String?,
        measurements: Collection<InterpretedMeasurement>,
        dtcs: List<InterpretedDtc>,
        isoTimestamp: String,
    ): String {
        val root = JSONObject()
        root.put("captured_at", isoTimestamp)
        root.put("ecu_model", modelName ?: JSONObject.NULL)

        val id = JSONObject()
        if (identity != null) {
            id.put("part_number_raw", identity.partNumberRaw)
            id.put("part_number_normalized", identity.partNumberNormalized)
            id.put("component", identity.component)
            id.put("protocol", identity.protocol ?: JSONObject.NULL)
        }
        root.put("identity", id)

        val groups = JSONObject()
        measurements.groupBy { it.group }.toSortedMap().forEach { (group, fields) ->
            val arr = JSONArray()
            fields.sortedBy { it.fieldIndex }.forEach { m ->
                arr.put(
                    JSONObject().apply {
                        put("index", m.fieldIndex)
                        put("key", m.key)
                        put("name", m.name)
                        put("raw", m.rawString ?: JSONObject.NULL)
                        put("value", m.displayValue)
                        put("unit", m.unit)
                        put("status", m.status.name.lowercase())
                        put("confidence", m.confidence ?: JSONObject.NULL)
                    }
                )
            }
            groups.put(group.toString().padStart(3, '0'), arr)
        }
        root.put("groups", groups)

        val dtcArr = JSONArray()
        dtcs.forEach { d ->
            dtcArr.put(
                JSONObject().apply {
                    put("code", d.code)
                    put("title", d.title ?: JSONObject.NULL)
                    put("status", d.statusRaw ?: JSONObject.NULL)
                    put("severity", d.severity.name.lowercase())
                }
            )
        }
        root.put("dtcs", dtcArr)

        return root.toString(2)
    }

    fun fileName(compactTimestamp: String): String = "capture_$compactTimestamp.json"
}
