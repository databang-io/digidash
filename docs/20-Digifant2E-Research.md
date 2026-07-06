# 20 — Digifant 2E / 037 906 024 AG Research Findings

Status: web research consolidated 2026-07-06. Feeds `ecu_models/vw/037906024AG.json`.
Everything below requires final validation on the real vehicle (ticket 14).

## Identity

- `037 906 024 AG` = "DIGIFANT 1.7", Siemens 5WP4-151/-158 hardware, 45-pin,
  2E engine. Suffixes AB/BE are Digifant 3.x for ABF 16v (different beast).
- 2E cars also shipped with `037 906 022 xx` (Digifant 1.23).
- **No official VCDS label file exists for 037-906-024** (verified against the
  complete VCDS-Lite 1.2 label set, 558 files). Family coverage comes from
  `037-906-022.lbl`, `037-906-023*.lbl` and `037-906-025-ADY.lbl` (successor
  on the same 2.0l 8v engine).

## Measuring groups

### Group 000 — primary display (10 raw fields, no data-type bytes)

Raw 0-255 only, no scaling transmitted. Gateway to Basic Settings
(function 04, group 000). Two candidate layouts documented in the family —
**firmware-specific, must be verified on the 2E**:

Layout A (from 037-906-025-ADY label, successor ECU — our current model bet):

| # | Meaning | Raw at warm idle | Physical | Scaling |
|---|---------|------------------|----------|---------|
| 1 | Intake air temp | 70–160 | 4.5–72 °C | non-linear NTC |
| 2 | Battery voltage | 115–161 | 12.0–16.5 V | V ≈ raw × 0.1024 |
| 3 | Coolant temp | 120–150 | 80–110 °C | °C = raw − 40 |
| 4 | Engine load | 25–55 | 9.75–19.5 % | % ≈ raw × 0.39 |
| 5 | Lambda voltage | 0–55 | 0–1.10 V | V = raw × 0.02 |
| 6 | Lambda learning (additive) | 0–22 | 0–0.75 ms | ms ≈ raw × 0.034 |
| 7 | Operating condition bitfield | — | — | bits |
| 8 | Throttle angle | 5–14 | 2.5–6.5 ° | ° ≈ raw × 0.5 |
| 9 | Injection time | 2–4 | 2–4 ms | ms ≈ raw × 1 |
| 10 | Engine speed | 23–27 | 736–864 rpm | rpm = raw × 32 |

Layout B (Audi ABK twin, VW Repleitfaden quote): 1=coolant raw (184–215),
2=load, 3=rpm (≈×10), 4–7=idle stabilisation, 8=lambda regulation (128
neutral), 9=lambda learning, 10=ignition angle at idle.

### Groups 001–005, 009, 010

As mapped from sibling `037-906-023.lbl` (already in the ECU model):
field 1 always engine speed; 001=load/coolant/injection; 002=IAT;
003=engine temp; 004=decel-cut check; 005=throttle angle; 009/010=lambda.
Groups >0 use the normal KW1281 3-byte encoding (type-id, a, b) so units
decode properly. **Some family firmwares return "invalid" for groups > 0** —
the app must fall back to group 000 gracefully.

### KW1281 field encoding (groups 001+)

Per-field 3 bytes (type, a, b). Common types: 1 rpm=0.2·a·b;
5 temp=a·(b−100)·0.1 °C; 6 V=0.001·a·b; 7 km/h=0.01·a·b; 15 ms=0.01·a·b;
16 bitfield; 33 %=100·b/a; 4/27 ignition=|b−127|·0.01·a.
Block titles: 0x29 group request → 0xE7 response; DTC read 0x07 → 0xFC;
DTC clear 0x05; ACK 0x09 keep-alive mandatory (<~500 ms) or session drops.
5-baud slow init at address 0x01, then 9600 baud (early units 4800).

## Basic Settings (Grundeinstellung) — 2E official procedure

Preconditions: oil > 80 °C, A/C and lights off, no limp mode.
- Up to MY92 (ECU suffix not ending "FL"): enter adjust mode by unplugging
  the blue coolant temp sensor with engine running.
- From MY93 (incl. the 024 family): **must** be entered via tester —
  KW1281 function 04, group 000.
Then: ignition timing 6° (4–8°) BTDC at 2250 rpm, crankcase breather clamped;
CO 0.7 % ± 0.4 via potentiometer; idle 800 ± 50 via bypass screw.
Leaving the diagnostic session returns the ECU to normal (lambda resumes).
On the T3 conversion the timing mark is user-made (JX flywheel) — the app
only displays values; adjustment is mechanical at the distributor.

## DTC catalog

See `dtc_catalog` in the ECU model. Classic 2E failures: 00515 Hall sensor
G40 (stall → distributor), 00522 G62 coolant sensor (1200–3000 rpm warm-start
idle), 00518 throttle pot G69, 00537 lambda regulation at limit.

## Quirks

- Group 000 fields may freeze/read 000 under load; short comm dropouts happen
  and are not necessarily an ECU defect.
- Digifant-III/AG4 use a hybrid data type needing a long header on group
  change — irrelevant for 2E (AG suffix) but relevant if ABF variants added.

## Sources

- VCDS-Lite labels: https://www.ross-tech.com/vcds-lite/download/index.php
- KW1281 protocol + formulas: https://www.blafusel.de/obd/obd2_kw1281.html
- Grundeinstellung 2E: https://www.passat35i.de/content/88-Grundeinstellung-2E
- Group 000 (ABK): https://www.motor-talk.de/forum/audi-80-b4-vag-com-gruppe-000-der-messwertbloecke-t2632181.html
- DTC: https://wiki.ross-tech.com/wiki/ and https://t4zone.info/cms/wiki/wiki.php?title=vagcom-liste-des-codes-erreur-vag
- Blink codes: https://a2resource.com/electrical/codes/digifant1.html
- 024-AG hardware: https://www.tav-autoverwertung.de/shop/Engine-control-unit-Seat-VW-037-906-024-AG-Siemens-5WP4-151
