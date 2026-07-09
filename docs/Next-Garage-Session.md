# Next garage session — typed groups 1-3 unlock protocol

Goal: get the ECU's TYPED 4-zone measuring replies (battery, throttle, air-mass)
that VCDS-Lite displays, instead of the static per-group 0x02 table.

**Surviving hypothesis (h4, from the exhaustive KaPoder-repo workflow)**: the
0x29 handler is session-state dependent. Our production connect fires DTC read +
param-less 0x12 (group 000) + adapter FC voltage telegrams BEFORE the first
0x29 — that traffic latches the measuring service into the legacy/table path.
VCDS reaches 0x29 clean and gets typed zones. Secondary door: `0x28 <group>`
(the manual's function 04), whose probe a disconnect cut short.

All timing/framing/counter hypotheses were adversarially REFUTED — the
transport is fine; only ORDER/STATE remains.

## Prep (at home, done)
- Build installed with: `connectbare`/`disconnectbare` (client-direct connect —
  NO DTC read, NO polling, NO 0x12, NO FC), readGroup accepting ANY 12-data-byte
  reply as typed triplets regardless of title, debugExchange collecting 8
  blocks, full ident pump after 0x00 resync, baud-scaled validated echo drain,
  foreground service against Samsung kills.
- Tablet: unlock, open DigiDash, keep screen on. `adb logcat -s DIGIDASH_DBG`.

Shortcut: `B() { adb shell am broadcast -a io.databang.digidash.DEBUG "$@"; }`

## Tests (ordered, most decisive first — each ~30 s)

### TEST A — clean-session 0x29 (h4 main probe)
Key OFF ≥ 30 s, contact ON, then:
```
B --es cmd connectbare
B --es cmd sendblock --ei title 41 --es data "01"   # 0x29 group 1, FIRST post-ident block
B --es cmd sendblock --ei title 41 --es data "02"
```
- **Typed reply** (any title with 12 data bytes) → h4 CONFIRMED. Immediately:
  `B --es cmd voltage` (a type-06 triplet must ≈ FC volts) and rev to ~2500
  (zone-1 RPM triplet must track). → production fix: reorder connect preamble.
- Static 46-byte 0x02 table → this disjunct dead, go C.

### TEST B — 0x12 latch demo (only if A was TYPED)
Same session: `sendblock 0x12` once, then `0x29 01` again.
Typed→static flip = the 0x12 latch proven → never send 0x12 in group sessions.

### TEST C — 0x28 basic-setting group (manual function 04; if A static)
Engine idling, same clean session (no 0x12 sent yet):
```
B --es cmd sendblock --ei title 40 --es data "01"   # 0x28 group 1
```
User-initiated probe (rule 3: the app-proper path stays behind the confirmation
dialog). Do NOT send 0x06 after; keep-alives resume alone.
- 12-data-byte or live-content reply → typed replies PROVEN via basic setting;
  hold ~2500 and re-send: payload must differ. Then `0x29 01` once more
  (checks the "basic setting arms 0x29" sub-claim).

### TEST D — DTC-first arming (fresh ignition cycle)
```
B --es cmd connectbare
B --es cmd sendblock --ei title 7          # 0x07 DTC read
B --es cmd sendblock --ei title 41 --es data "01"
```
Typed → production fix = replay 0x07 once post-ident before group reads.

### TEST E — control (fresh cycle): connectbare → 0x12 → 0x29 01.
Expected: static table (reproduces production; proves connectbare isn't the
variable). If TYPED here → transport confound, stop and re-capture.

### TEST F — unexplored groups (15 s, any session)
`0x29` with data 04, 05, 81. A third static variant at most is expected.

### TEST G — ground truth fallback (laptop)
If A–F all static: VCDS-Lite debug/serial sniff (com0com/Portmon) of ONE
groups-001/002 read on the KKL COM port → the exact TX bytes end the mystery.

## Once typed replies confirmed
readGroup already decodes them (12-byte rule). Then: map zones per the official
manual (g1 rpm/coolant/lambda/bits, g2 …/battery/intake-air, g3 …/throttle/
air-mass%, g4 mode bits) in the ECU model with wire formulas from the triplet
types, restore the battery card to group 2 zone 3, and calibrate against the
adapter FC voltage + tachometer.
