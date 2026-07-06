# DigiDash — Android diagnostic for VW 2E / Digifant

**DigiDash** is an Android diagnostic dashboard for old VAG K-line ECUs, starting with the VW 2E Digifant `037 906 024 AG` in a VW T3 home conversion. This repository contains the app, the ECU Model database, the third-party .LBL converter, and the original specification pack.

## Status (V1)

| Piece | State |
|---|---|
| Android app `android/` (Kotlin, Compose, Material 3) | ✅ builds, 48 unit tests green |
| Gauge dashboard + Tech mode + Home/dongle picker | ✅ works in fake mode, responsive portrait/landscape |
| Fake ECU backend (samples replay, failure scenarios incl. DTCs) | ✅ tickets 01–05 |
| ECU Model loader: bundled assets + **public git repo** (raw HTTPS, offline cache) | ✅ |
| ECU Model `037906024AG` (groups 000–010, thresholds, 22 DTC) | ✅ researched, needs vehicle validation (ticket 14) |
| CSV logger, DTC screen (guarded clear), ignition assistant, on-demand raw blocks | ✅ tickets 06–09 |
| LBL converter `tools/lbl-converter/` (Python, 32 tests) | ✅ 558 labels → 265 models validated on a real ZIP |
| Deep OBD adapter in Kotlin (`core/deepobd`, ticket 13) | ✅ connect + probe + protocol; ECU session pending vehicle (ticket 14) |
| Real-vehicle validation (ticket 14), Android Auto | ⏳ backlog |

## Screens

- **Home** — connection, ECU identity, Bluetooth dongle picker, entry to Tech mode
- **Dash** — trip gauges (RPM, coolant, battery, IAT, injection…), N/A-safe, stale/status states
- **Faults** — DTCs with catalog descriptions, guarded clear
- **Timing** — ignition assistant with the mandatory JX/T3 warning and 2E Grundeinstellung checklist
- **Logs** — record CSV (raw + interpreted), share, delete
- **Tech** — raw measuring blocks, on-demand group reads, fake/real backend toggle, remote ECU-model repo

## Quick start

```bash
# Build the app (needs Android SDK + JDK 17+)
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest
adb install app/build/outputs/apk/debug/app-debug.apk

# Convert your own user-provided label ZIP (never committed)
python3 tools/lbl-converter/lbl-converter \
  --input ~/Downloads/Labels.zip --output converted_ecu_models --target 037906024AG
```

In the app: Home → Connect (fake backend, no vehicle needed) → Dashboard.
Tech tab: raw measuring blocks, failure scenarios, and the *ECU models from
public git repo* setting (raw base URL of an `ecu_models/` directory, e.g.
`https://raw.githubusercontent.com/<user>/<repo>/main/ecu_models`).

## Key docs

- `docs/20-Digifant2E-Research.md` — everything known about the 2E/037906024AG groups, Basic Settings procedure, DTC
- `docs/DeepOBD-Observed-API.md` — dongle probe/SPP/K-line adapter protocol (base for ticket 13)
- `START_HERE_FOR_CLAUDE.md`, `tickets/` — original spec pack

## Confirmed target configuration

- Vehicle: VW T3 conversion maison
- Engine: VW 2E 2.0 8V essence, Golf 3 GTI origin
- Engine management: VW/Bosch Digifant, old VAG K-line diagnostics
- ECU: `037 906 024 AG`
- Distributor: original 2E distributor with Hall sensor
- Engine-side components: 2E
- Flywheel: JX
- Clutch: JX
- Bellhousing: JX
- Gearbox: T3/JX
- Timing reference: user-made mark on crank pulley / real TDC, not Golf flywheel marks
- Existing Android tool: Deep OBD can communicate with ECU and read raw blocks but does not interpret them usefully
- Communication requirement: reuse the Deep OBD / EdiabasLib stack where possible

## Main goal

Build an Android app that does **not** try to be universal. The MVP is a robust dashboard and garage tool for the `037 906 024 AG` Digifant ECU:

- connect via existing Deep OBD dongle
- identify ECU
- read raw measuring blocks
- interpret values through an internal **ECU Model**
- display critical engine data clearly
- log CSV for trips
- support DTC read/clear if available
- support a safe ignition setup assistant using Basic Settings if available
- generate ECU Models by converting a user-provided label ZIP

## Important legal/design rule

Third-party label files (.LBL) must **not** be redistributed directly in the application unless the user has rights/permission. The app and tools should support importing/converting a local user-provided ZIP from the user's own VCDS/VCDS-Lite installation. The generated ECU Model is the application's internal representation.

## Recommended first Claude Code command

Use this repository as context, then read files in this order:

1. `CLAUDE.md`
2. `docs/00-Vision.md`
3. `docs/01-Requirements.md`
4. `docs/02-SystemArchitecture.md`
5. `docs/03-DeepOBDIntegration.md`
6. `docs/05-ECUModel.md`
7. `docs/06-LabelImport.md`
8. `docs/08-IgnitionSetup.md`
9. `tickets/00-ImplementationPlan.md`

Then start with ticket `tickets/01-CreateAndroidSkeleton.md`.

