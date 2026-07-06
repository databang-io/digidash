# 09 — Logging

## Goals

Logs are critical because mapping may be uncertain. Every log must include raw values and interpreted values.

## CSV file naming

```text
digifant_YYYYMMDD_HHMMSS.csv
```

## Required columns

- timestamp_iso
- monotonic_ms
- ecu_part_number
- ecu_model_file
- connection_state
- group
- field_index
- raw_string
- raw_numeric
- interpreted_key
- interpreted_name
- interpreted_value
- unit
- status
- confidence

For Trip Mode, also write a wide snapshot row format if useful:

- rpm
- coolant_temp
- battery_voltage
- lambda_state
- injection_time
- ignition_advance
- throttle_state
- dtc_count

## Log events

The logger must also support event rows:

- connection established
- connection lost
- ECU identified
- ECU Model loaded
- DTC read
- DTC cleared
- Basic Settings entered
- Basic Settings exited
- user note

## Privacy

Logs are local only. No cloud upload.

## Export

Use Android share sheet for CSV export. Do not require storage permissions unless necessary.

