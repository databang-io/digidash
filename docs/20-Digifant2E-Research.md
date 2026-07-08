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

## Official workshop manual findings (2026-07-08)

Source: *VW Golf 1992/Vento 1992 — Digifant Injection and Ignition system (2 valve), Edition 04.1993*
(user-provided PDF; Repair groups 01/24/28). This is authoritative for group semantics.

### Programme levels explain the two display formats

- **Up to programme level 1083**: basic setting / measuring display uses **display group 00 with TEN zones**.
  This matches the 10-byte `0x12 → 0xF4` raw block confirmed live on our ECU.
- **From programme level 1390** (our ECU reports **1576**): displays use **groups 01–04 with FOUR zones** each.
  This is what VCDS shows as measuring blocks 01/02/03.
- Our ECU answers *both* paths. Identification format: `037906024x DIGIFANT 1.7 <level>`.

### Official group/zone semantics (level >= 1390)

| Group | Zone 1 | Zone 2 | Zone 3 | Zone 4 |
|---|---|---|---|---|
| 01 | Engine speed (idle 770–870) | Coolant temp (>85 °C for checks) | Lambda voltage (fluctuates >0.3 V) | Condition bits |
| 02 | (undocumented) | (undocumented) | Battery voltage (constant) | Intake air temp |
| 03 | (undocumented) | (undocumented) | Throttle valve angle (uniform rise) | Air mass meter, 10–30 % idle |
| 04 | (undocumented) | (undocumented) | (undocumented) | Operating mode bits |

Group 01 zone-4 bits: `01000000` auto-gearbox signal not OK · `00100000` A/C not off ·
`00000100` throttle not closed · `00000010` rpm outside range · `00000001` coolant < 85 °C.
Group 04 zone-4 bits (from left): overrun cut-off / idling / part load / full load.

### Official specs

- Idle 770–870 rpm; CO 0.2–1.2 Vol.%; governed speed 6400–6500 rpm.
- Ignition timing: check **4–8° BTDC at 2000–2500 rpm** in basic setting; set **6 ± 1°** by rotating distributor.
- Full advance check: **35–45° BTDC at ~2800 rpm** (ignition tester).
- Basic setting (function 04) requires a display group: 00 on <=1083, **01 on >=1390** — wire-wise
  `0x28 <group>` (standard KW1281) for numbered groups; param-less `0x11` only for the legacy 10-zone display.
- Basic setting engages check mode only when zone 4 reads `00000000`; ignition timing control is switched off
  during basic setting, control unit matches itself to the throttle pot at idle. End it with function 06.

### Live wire findings vs. manual (garage sessions 2026-07-07/08)

- `0x29 <group>` on this ECU is answered by a **title `0x02` 46-byte block** (counter = request+1), NOT `0xE7`.
  Payload contains two 20-byte descending tables + 3-byte records; mapping to the four documented zones is
  **unknown** — needs a VCDS debug trace to calibrate (VCDS displays these groups fine).
- The spontaneous `0x02`/`0xF4` pushes seen at connect are the same blocks (static with engine off).
- Session-loop bug fixed: a command whose reply never matched stayed pending forever and blocked all
  subsequent sends. Group requests actually transmit now.

### Next garage session checklist

1. Engine running, connect, single `group --ei n 1` → capture ALL RX blocks (keepAcks) — find the 4 zones.
2. Rev to ~2500: re-read group 1 — zone 1 must track rpm; zone 2 coolant rises during warm-up.
3. Try `basic --ei n 1` (0x28 group 01) — expect 4-zone response; zone 4 must reach 00000000 warm.
4. Capture a VCDS debug log of measuring blocks 1–3 for byte-level calibration of the 0x02 payload.
