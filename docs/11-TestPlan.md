# 11 — Test Plan

## Unit tests

### ECU part normalization

- `037 906 024 AG` -> `037906024AG`
- `037-906-024-ag` -> `037906024AG`
- empty input rejected

### ECU Model loader

- valid model loads
- missing file handled
- unknown formula handled
- invalid threshold rejected

### Measurement interpreter

- raw formula
- scale_offset formula
- enum formula
- unsupported formula
- missing field
- stale value handling

### LBL converter

- reads ZIP
- finds `.LBL` files
- handles Windows-1252
- parses simple groups
- resolves redirect
- detects redirect loop
- emits warnings
- writes index
- validates target model

## UI tests

- Trip Dashboard with all values
- Trip Dashboard with N/A values
- stale values
- DTC clear confirmation
- Basic Settings confirmation
- fake mode connection

## Integration tests with fake backend

- connect
- identify ECU `037906024AG`
- read blocks
- show interpreted data
- start/stop log
- simulate timeout
- simulate dropped connection

## Vehicle validation checklist

On real vehicle:

1. Connect dongle.
2. Confirm ECU identification.
3. Confirm raw group read matches Deep OBD raw output.
4. Confirm no crash when reading missing group.
5. Record 2-minute idle log.
6. Record warm-up log.
7. Compare dashboard values to VCDS-Lite if available.
8. Validate coolant temp plausibility.
9. Validate RPM plausibility.
10. Validate battery voltage with multimeter.
11. Validate throttle/idle state by pressing throttle.
12. Validate DTC read, but do not clear unless intentional.
13. Test Basic Settings only in garage conditions.

## Acceptance for MVP

MVP is accepted when fake mode fully works and real ECU can at least identify and display raw blocks through the app.

