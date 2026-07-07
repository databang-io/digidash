# Changelog

## 1.0.0 — 2026-07-06 (V1 — full feature set)

- **Basic Settings** for ignition timing: enter/exit (guarded confirmation,
  auto-exit on leaving the screen), ACTIVE status, live ignition-advance
  readout (group 011). In demo mode the ECU holds ~2250 rpm and the advance
  sits at the 6° BTDC target so the full flow is testable without a car.
- **Demo mode** toggle (Tech/Settings, ON by default): the whole app runs on
  simulated ECU data — every screen usable without a dongle or vehicle.
- **Debug capture export**: reads every group once and writes a JSON snapshot
  (identity + groups + DTCs) to share for ECU-model validation.
- **Android Auto**: projected trip dashboard (PaneTemplate) sharing one
  app-scoped session with the phone UI; connect-in-demo action on the head unit.
- App-scoped `SessionHolder`/`DigiDashApplication` so phone and car surfaces
  stay in sync. Version 1.0.0.

## 0.3.0 — 2026-07-06 (phase 2)

- CSV logger (ticket 07): `CsvLogger` writes raw + interpreted sample rows and
  event rows; `LogRepository` stores logs app-private; `TripLogController`
  records live during a session. Logs screen: start/stop, list, share via
  FileProvider, delete with confirmation. Local only, no upload.
- DTC screen (ticket 08): read and interpret fault codes via the ECU-model
  catalog, coarse severity, guarded clear with the mandatory confirmation
  dialog (current codes logged before clearing). Fake `WITH_DTCS` scenario.
- Ignition assistant (ticket 09): mandatory JX/T3 mechanical-timing warning,
  auto checklist derived from ECU data + manual items, 2E Grundeinstellung
  guidance, Basic Settings status (unsupported-safe).
- On-demand raw blocks (ticket 06): group picker in Tech; session mutex
  serializes K-line access between the poll loop and one-off reads.
- Deep OBD adapter (ticket 13): `core/deepobd` — BMW-FAST/K-line telegram
  framing, KWP1281 group + DTC decoding, SPP transport, adapter probe
  (custom / ELM327 / Deep OBD replacement firmware). `DeepObdDiagnosticClient`
  behind `DiagnosticClient`; connect + probe implemented, ECU session pending
  real-vehicle validation (ticket 14). Fake/real backend toggle in Tech.
- Navigation: 5-tab responsive layout (Dash / Faults / Timing / Logs / Home),
  Tech reachable from Home. 48 unit tests, all green.

## 0.2.0 — 2026-07-06 (phase 1)

- DigiDash Android app (tickets 01–05): Kotlin 2.0, Jetpack Compose,
  Material 3 dynamic color, edge-to-edge; gauge Trip Dashboard, Tech mode,
  Home with connection + ECU identity + Bluetooth dongle picker (Deep OBD
  style, paired SPP devices).
- `DiagnosticClient` abstraction + fake backend replaying vehicle samples
  (scenarios: normal, timeout, dongle not found, ECU no response).
- ECU Model loader with ordered sources: optional **public git repo**
  (raw HTTPS + offline cache) then bundled assets; part-number normalization.
- ECU Model `037906024AG` enriched: groups 000–010 from sibling label
  037-906-023 + successor 025-ADY + web research; conservative thresholds;
  22-entry DTC catalog; group 000 modeled as primary display.
  See `docs/20-Digifant2E-Research.md`.
- LBL converter (`tools/lbl-converter`, Python stdlib): tolerant VCDS parser
  (real `G,F,text` format + legacy), encoding fallbacks, redirect resolution
  with cycle detection, wildcard/helper handling, conversion reports;
  validated on a real 558-file ZIP → 265 models; 32 unit tests.
- Schema: field index limit raised to 10 (group 000 has 10 raw fields).
- `docs/DeepOBD-Observed-API.md`: source-level Deep OBD dongle management and
  custom adapter K-line/KWP1281 wire protocol; Kotlin adapter plan (ticket 13).

## 0.1.0-spec

- Complete specification pack.
