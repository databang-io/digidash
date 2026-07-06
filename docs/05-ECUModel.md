# 05 — ECU Model Specification

## Purpose

An ECU Model is the app's internal description of how to interpret a specific ECU's diagnostic data. It is not a label file. It is a normalized JSON representation generated manually or from imported label files.

## Main target model

- Part number: `037906024AG`
- Display name: `VW Digifant 2E - 037 906 024 AG`
- Protocol: `KWP1281`
- Engine: `2E`

## Normalization

ECU part numbers must be normalized by removing spaces, hyphens and dots and uppercasing:

- `037 906 024 AG` -> `037906024AG`
- `037-906-024-AG` -> `037906024AG`

## Entity overview

```text
ECUModel
  identity
  compatibility
  groups
    fields
      conversion
      status thresholds
      dashboard role
  dtc catalog
  safety metadata
  source metadata
```

## JSON structure

See `schemas/ecu-model.schema.json`.

Minimum:

```json
{
  "ecu_model_version": 1,
  "ecu_part_number": "037906024AG",
  "display_name": "VW Digifant 2E - 037 906 024 AG",
  "protocol": "KWP1281",
  "system": "Digifant",
  "engine_codes": ["2E"],
  "source": {
    "type": "manual-bootstrap",
    "confidence": "low"
  },
  "groups": {}
}
```

## Groups

Groups are keyed as 3-digit strings.

```json
"001": {
  "label": "Basic engine values",
  "polling_priority": "trip",
  "fields": [
    {
      "index": 1,
      "key": "rpm",
      "name": "Engine speed",
      "unit": "rpm",
      "value_type": "number",
      "formula": { "type": "raw" },
      "critical": true,
      "display": { "trip_card": true, "order": 10 }
    }
  ]
}
```

## Formula types

MVP formula support:

- `raw`: numeric raw value as-is
- `scale_offset`: `value = raw * scale + offset`
- `enum`: raw/string map to labels
- `string`: keep raw string
- `unsupported`: display raw only

Example:

```json
"formula": { "type": "scale_offset", "scale": 0.1, "offset": 0 }
```

## Status thresholds

Thresholds must be conservative and configurable. Do not hard-code thresholds in UI.

```json
"thresholds": {
  "normal": { "min": 80, "max": 100 },
  "warning": { "min": 70, "max": 105 },
  "critical": { "max": 110 }
}
```

Rules:

- If no thresholds: status = unknown.
- If value missing: status = unavailable.
- If thresholds conflict: validator reports error.

## Dashboard roles

Known keys for MVP:

- `rpm`
- `coolant_temp`
- `intake_air_temp`
- `battery_voltage`
- `throttle_state`
- `idle_switch`
- `wot_switch`
- `lambda_state`
- `lambda_correction`
- `injection_time`
- `ignition_advance`
- `engine_load`
- `dtc_count`
- `ecu_connection_quality`

## Unknown mapping policy

The Digifant 2E label mapping may be incomplete. The model must permit uncertain fields:

```json
"confidence": "low",
"notes": "Needs vehicle validation"
```

UI should show a small warning icon or developer note in Garage Mode for low-confidence fields.

## Manual override

Later versions may allow the user to edit mappings. Keep model format stable and versioned.

