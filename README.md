# Digifant Dashboard — Android diagnostic for VW 2E / Digifant

This repository is a **complete specification pack for Claude Code**. It describes the Android application, the ECU Model database, the Ross-Tech `.LBL` conversion workflow, and the first implementation tickets.

The first supported vehicle is a VW T3 home conversion with a VW 2E 2.0 8V Digifant engine.

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
- generate ECU Models by converting a user-supplied Ross-Tech label ZIP

## Important legal/design rule

Ross-Tech `.LBL` files must **not** be redistributed directly in the application unless the user has rights/permission. The app and tools should support importing/converting a local user-provided ZIP from the user's own VCDS/VCDS-Lite installation. The generated ECU Model is the application's internal representation.

## Recommended first Claude Code command

Use this repository as context, then read files in this order:

1. `CLAUDE.md`
2. `docs/00-Vision.md`
3. `docs/01-Requirements.md`
4. `docs/02-SystemArchitecture.md`
5. `docs/03-DeepOBDIntegration.md`
6. `docs/05-ECUModel.md`
7. `docs/06-RossTechLBL.md`
8. `docs/08-IgnitionSetup.md`
9. `tickets/00-ImplementationPlan.md`

Then start with ticket `tickets/01-CreateAndroidSkeleton.md`.

