# 01 — Requirements

## Functional requirements

### Connection

- The app shall connect to the existing Deep OBD-compatible dongle.
- The app shall support a fake ECU backend for development.
- The app shall expose connection status: disconnected, connecting, connected-to-dongle, connected-to-ECU, degraded, error.
- The app shall recover from dropped communication without crashing.

### ECU identification

- The app shall read ECU part number.
- The app shall read ECU component/system text when available.
- The app shall detect `037 906 024 AG` and select the matching ECU Model.
- If the ECU is unknown, the app shall still show raw blocks.

### Measuring blocks

- The app shall read raw measuring block groups.
- The app shall display raw field values for each group.
- The app shall map raw fields through an ECU Model when available.
- The app shall preserve raw values in logs.
- The app shall tolerate unknown groups and fields.

### Dashboard

- The app shall present a Trip Dashboard with large critical values.
- Values shall display unit, status, and freshness.
- Missing values shall display `N/A`.
- Stale values shall be visually marked.

### CSV logging

- The app shall log timestamped raw and interpreted data.
- The app shall allow start/stop logging.
- The app shall export/share log files.
- The app shall never overwrite existing logs silently.

### DTC

- The app shall read DTCs if the communication stack supports it.
- The app shall display raw DTCs even without descriptions.
- The app shall require confirmation before clearing DTCs.
- The app shall write a log event when DTCs are cleared.

### Ignition setup

- The app shall provide an assistant for ignition timing setup.
- The assistant shall state that timing is adjusted mechanically by rotating the distributor.
- The assistant shall warn that target vehicle uses JX flywheel/clutch/bellhousing/gearbox and user-made timing marks.
- The assistant shall show relevant ECU values: rpm, coolant temp, idle state, ECU advance, voltage, DTC state.
- The assistant shall allow Basic Settings only after confirmation and only if supported.

### LBL conversion

- The project shall include a conversion tool from user-supplied Ross-Tech `.LBL` ZIP to ECU Model JSON.
- The converter shall resolve label redirects when possible.
- The converter shall not require distributing original `.LBL` files.

## Non-functional requirements

- Android native Kotlin recommended.
- UI with Jetpack Compose and Material 3.
- No cloud dependency.
- Offline-first.
- Crash-resistant with old ECU communication.
- Testable without vehicle.
- Clear separation between communication, ECU model interpretation, UI, and logging.

## Safety requirements

- No automatic ECU write operation.
- No automatic DTC clearing.
- No automatic adaptation.
- No coding changes.
- No claim that the application replaces a workshop manual.
- Every special action must have an explicit warning.

