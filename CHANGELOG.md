# Changelog

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

- Complete specification pack for Claude Code.
