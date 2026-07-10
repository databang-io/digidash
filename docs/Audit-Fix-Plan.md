# Source-audit fix plan (60-agent audit vs PDF + reference repos)

**Systemic issue:** TOP SYSTEMIC ISSUE — Fabrication treated as ground truth. Across the transport decoder, the ECU model JSON, the DTC catalog, and the ignition/Basic-Settings UI, DigiDash repeatedly promotes its own inferred or borrowed data to the status of fact: sibling label files (037-906-023 / 025-ADY), generic VAG fault-code lists, single VCDS-Lite screenshots, klinelib-only high formulas, and convenient round-number thresholds are all emitted as calibrated values, units, DTC codes, and timing targets that contradict the two authoritative sources — the 2E workshop PDF (ed. 04.1993) and the KW1281 reference implementations (gmenounos, klinelib, blafusel). The same fabrication pattern recurs as: (a) invented calibration scales/units on raw bytes (group-000 rpm 35/-1120 fit, 0.1024 V/bit battery, °C/V/ms/° on raw fields), (b) invented DTC codes for components this ECU does not have (00521 CO-pot G74, 00519/00575 MAP, 00516/00517 idle/full-throttle switches, 00530 G88; 00552 mislabelled G70), (c) a whole invented measurement channel (group 011 "ECU-reported ignition advance"), and (d) invented operating windows in the safety-critical timing flow (ECU "holds ~2250 rpm", 2200-2300 target, 700-1000 idle, 12.5 V, 80 °C, "set CO on the pot"). This is most dangerous where it reaches the ignition-timing / Basic-Settings assistant, because fabricated on-screen numbers can mislead a mechanical distributor adjustment and brush CLAUDE.md rule 4. A distinct secondary systemic thread runs through the KWP1281 host state machine, which is a partial reimplementation that omits several mandatory reference behaviours (DTC loop-until-ACK, mid-stream 0x55 resync, block-counter validation, odd-parity 5-baud init) and even invents a 0x00 counter-reset no source defines. The unifying fix is to enforce the project's non-negotiable rule everywhere: only the PDF and the KW1281 reference repos/blafusel are authority; everything else must be raw/N/A, explicitly hedged, or removed.

**Counts:** 38 consolidated fixes (from ~50 raw findings after merging duplicates): 1 critical, 12 high, 19 medium, 6 low. By subsystem: Ignition/Basic-Settings/Lambda 10, KWP1281 transport 6, KWP1281 decode 7, DTC handling 5, ECU-model provenance 10.

Status legend: [ ] pending · [x] applied · [defer] deferred with reason

## 1. [CRITICAL] Ignition / Basic Settings / Lambda (safety)

**STATUS:** APPLIED

- **Change:** Remove the fabricated ECU-reported ignition-advance channel end to end. Delete group "011" field ignition_advance (unit °BTDC, formula raw, trip_card) from the ECU model; stop setting basicAdvance = value("ignition_advance") in the ViewModel; delete the "Ignition advance (ECU)" value line and the "ECU-reported advance … BTDC" block in the UI. Replace with instruction only: read timing with a strobe against the flywheel/pulley TDC mark at ~2000-2500 rpm and adjust mechanically at the distributor (spec 6°±1 / 4-8° BTDC). Keep the sibling rpm_g11/basicRpm readout. Merges the group-011-invented and ECU-reported-advance findings.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/ecu_models/vw/037906024AG.json:666-693 and demo-2e.json:659-690 (+ android/app/src/main/assets mirror); AppViewModel.kt:775-776; ui/ignition/IgnitionScreen.kt:205 and :411-416
- **Source:** PDF printed p.33-35 & p.66-67 (timing read by ignition tester/strobe against flywheel mark, adjusted by turning the distributor; ECU outputs no timing number); CLAUDE.md rule 4; manual defines no group 011 / no measuring-block timing value; self-contradicts 037906024AG.json:13

## 2. [HIGH] Ignition / Basic Settings / Lambda (safety)

**STATUS:** APPLIED

- **Change:** Replace the invented "ECU holds ~2250 rpm" copy and the 2200-2300 rpm target window with the manual's operator-controlled 2000-2500 rpm timing-check window: rpmOnTarget = basicRpm in 2000.0..2500.0; RpmTargetIndicator label "rev to 2000-2500 rpm"; rewrite the Basic-Settings copy (ECU matches throttle-pot at idle and switches timing control OFF, operator revs); drop "(or the ECU may hold it for you)" and the "~2250 rpm" phrasing. Merges the two 2250-rpm findings.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/AppViewModel.kt:106; ui/ignition/IgnitionScreen.kt:232, :419-425, :243, :210
- **Source:** PDF printed p.34-35 (1.7): check ignition timing at 2000...2500 rpm; basic setting matches throttle pot at idle and switches ignition-timing control OFF; no ECU-held rpm documented

## 3. [HIGH] Ignition / Basic Settings / Lambda (safety)

**STATUS:** APPLIED

- **Change:** Delete the "use it to set CO on the Digifant pot" instruction from the Lambda card and reframe it as a diagnostic-only lambda check; CO is fixed by the closed loop and not user-adjustable on this 2E.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/ui/ignition/IgnitionScreen.kt:170-172
- **Source:** PDF printed p.33 (1.6/1.7): "Idling speed and CO content are not adjustable"; no CO potentiometer documented for engine code 2E

## 4. [HIGH] KWP1281 transport / wire protocol

**STATUS:** defer — user: "DTC marche bien, ne casse pas"; needs their go

- **Change:** Make readDtc() loop until the terminating ACK, accumulating every 0xFC block instead of stopping at the first: exchange(TITLE_DTC_REQUEST, ByteArray(0), keepAcks=false, terminal={ it.title==TITLE_ACK || it.title==TITLE_NO_DATA }, giveUpBlocks≈16), then filter title==TITLE_DTC_RESPONSE and flatMap decodeDtcResponse over all collected blocks (the loop auto-ACKs each 0xFC on its keep-alive turn). Also change the read-miss branch to deliver already-collected 0xFC blocks rather than emptyList(), so a silent ECU with no trailing ACK does not regress to zero faults. Merges the two loop-until-ACK findings.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/core/deepobd/Kwp1281Session.kt:546-548 (and the miss-branch at :295-297)
- **Source:** gmenounos KW1281Dialog.cs:357-375 & :559-584 (ACK each 0xFC, receive until AckBlock, aggregate all FaultCodesBlocks); klinelib KLineKWP1281Lib.cpp:582-670 (loop until TYPE_ACK)

## 5. [HIGH] KWP1281 transport / wire protocol

**STATUS:** defer — 0x00+counter-reset IS KaPoder-sourced (obdisplay.cpp:1421-1441); 0x55 branch still to add

- **Change:** Add a block-start 0x55 resync branch in readBlock(): on a scanned 0x55, read the two following keyword bytes, wait ~25-40 ms, transmit the bitwise complement of the second keyword byte (~KB2), then resume the length scan; guard the branch to block-start only. Remove the fabricated `if (title == 0x00) counter = 0` line and its comment, and rename/redocument readGroupResync so it no longer claims 0x00 (=ReadIdent) resets the block counter.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/core/deepobd/Kwp1281Session.kt:679 (plus :629 and readGroupResync :421-458)
- **Source:** klinelib KLineKWP1281Lib.cpp:4715-4756,4321; gmenounos ReadAndAckByteFirst KW1281Dialog.cs:491-519; BlockTitle.cs:5-70 (0x00 = ReadIdent, not a counter reset)

## 6. [HIGH] KWP1281 decode (Kwp1281Protocol.kt)

**STATUS:** APPLIED

- **Change:** Fix the type-25 mass-flow formula to the blafusel/gmenounos arbiter: `25 -> 1.421 * b + a / 182.0` (a=A=NWb, b=B=MWb), and correct the inline comment which falsely labels this arbiter form as an unauthoritative KaPoder choice; klinelib's (256*b+a)/180 is the non-arbiter variant.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/core/deepobd/Kwp1281Protocol.kt:76
- **Source:** gmenounos SensorValue.cs:49 (B*1.421 + A/182 g/s), blafusel.de sec.4

## 7. [HIGH] KWP1281 decode (Kwp1281Protocol.kt)

**STATUS:** APPLIED

- **Change:** Change type-39 injection quantity from `a * b / 255.0` to `a * b / 256.0` (matches sibling type-23 idiom) and correct the comment that mislabels /256 as a KaPoder-only choice.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/core/deepobd/Kwp1281Protocol.kt:90
- **Source:** gmenounos SensorValue.cs:63 (InjQty = B/256*A mg/h); blafusel formula table

## 8. [HIGH] KWP1281 decode (Kwp1281Protocol.kt)

- **Change:** Decode the DTC status byte as the reference status1/status2 split instead of hi/lo nibbles: statusRaw = "%02d-%02d".format(status and 0x7F, (status shr 7) * 10); keep the raw byte in statusByte for logging. Merges the three duplicate status-nibble findings (this decoder is the single shared code path).
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/core/deepobd/Kwp1281Protocol.kt:259
- **Source:** gmenounos FaultCodesBlock.cs:48-50 (status1 = Status & 0x7F; status2 = (Status>>7)*10; printed 'DTC - status1-status2'), preserving the 0x80 sporadic bit

## 9. [HIGH] DTC handling (interpreter + catalog)

**STATUS:** APPLIED

- **Change:** Rebuild criticalCodes from the manual's fault table. Set criticalCodes = setOf("65535") (control unit defective) and, if drivability-critical CRITICALs are wanted, add only source-backed codes (e.g. 00525 Lambda G39, 00532 supply) with a comment that the ranking is a DigiDash decision. Drop the invented 00512/00513/00514/00515, which are absent from the authoritative table and currently demote real faults to WARNING.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/core/interpret/DtcInterpreter.kt:12
- **Source:** PDF printed p.4-5 (1.6 Fault table): only 00518,00522,00523,00524,00525,00532,00545,00552,65535 exist for the 2E

## 10. [HIGH] DTC handling (interpreter + catalog)

**STATUS:** APPLIED

- **Change:** Remove DTC 00521 "CO potentiometer G74" from the dtc_catalog (or mark 'not applicable — no CO pot on the closed-loop cat 2E'), and rename the group-000 zone-2 field key/name co_pot / "CO fuel trim potentiometer" to a source-neutral "raw z2 (sibling: mixture/CO trim)". Apply to both model files and the assets mirror. Merges the two 00521 findings.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/ecu_models/vw/037906024AG.json:707 and :38-48; demo-2e.json:704 (+ assets mirror)
- **Source:** PDF printed p.33 (CO content not adjustable); p.4-5 fault table lists no CO-pot code; sibling-label provenance is not authoritative

## 11. [HIGH] ECU model provenance (037906024AG.json)

**STATUS:** APPLIED

- **Change:** Demote group-000 field 4 rpm from the single-anchor scale_offset fit (scale 35 / offset -1120) to formula.type raw (counts) — or explicitly label it 'estimated' — and drop the "rpm" unit until ≥2 spread tachometer calibration points exist. This is the file's only non-raw formula and rests on one exact zero point plus one ±15% fuzzy point.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/ecu_models/vw/037906024AG.json:68-97
- **Source:** PDF printed p.34 (group-000 zone numbers 'not relevant', no scaling published); KW1281 group-0 raw-body contract (gmenounos KW1281Dialog.cs:714-837; klinelib raw path); file self-policy line 13 (≥2-anchor rule)

## 12. [HIGH] ECU model provenance (037906024AG.json)

- **Change:** Strip the invented klinelib high-formula IDs (0x8B/0x8C/0x85/0x88/0x89) and exact NWb constants (26/40/2/24/50) and derived conversions (volts=body/128, body×24/256, ms=body×0.5) from the group 001/002 zone notes, and remove the 'wire header self-describes formula+scale' claim. State plainly that the group 001-003 wire encoding is uncaptured; keep values raw/N/A and lower confidence until a real header+body capture exists.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/ecu_models/vw/037906024AG.json:202,232,244,260,279,291,325,341
- **Source:** klinelib KLineKWP1281Lib.cpp:1639-1645,3695-3736 (0x8B/0x8C need a captured 17-byte header table; observed block is 46-byte with 20-byte tables); docs/20-Digifant2E-Research.md:135-137 (mapping unknown, needs VCDS trace); blafusel sec.4

## 13. [HIGH] ECU model provenance (measurement interpreter)

- **Change:** Treat the 0x83/0x8F ignition-angle high formulas as klinelib-only and low-confidence: stop hard-forcing the unit to a bare "°"; derive "°BTDC"/"°ATDC" from the decoded sign (B>127 = ATDC per gmenounos), preserve the model's directional unit, and force confidence=low for these IDs regardless of the model index's confidence.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/core/interpret/MeasurementInterpreter.kt:56-59
- **Source:** Truth-fact CAUTION: klinelib 0x47+ formulas are not defined by gmenounos/RXTX/blafusel (unvalidated); gmenounos SensorValue.cs:28 & blafusel sec.4 (ATDC/BTDC by sign); klinelib KLineKWP1281Lib.cpp:2341-2372 (0x8F signed)

## 14. [MEDIUM] Ignition / Basic Settings / Lambda (safety)

- **Change:** State the sourced lambda pass criterion and fault branches in the Lambda card: after ~2500 rpm/1 min warm-up then idle, voltage must fluctuate by more than 0.3 V (evaluate peak-to-peak from lambdaHistory only if the value is a calibrated probe voltage, else show N/A); add the 0.00 V / 0.45 V connector-separated fault-branch text (0.45 V => renew G39; 0.00 V => electrical check step 16, renew J169).
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/ui/ignition/IgnitionScreen.kt:169-193
- **Source:** PDF printed p.36-37 (1.8): fluctuation >0.3 V after 2500 rpm/1 min; 0.00 V and 0.45 V branches for the separated 4-pin connector

## 15. [MEDIUM] Ignition / Basic Settings / Lambda (safety)

**STATUS:** APPLIED

- **Change:** Tighten the idle-stable checklist window from 700-1000 rpm to the spec 770-870 rpm (optionally a small, explicitly-labelled measurement tolerance) so an out-of-spec idle is not reported OK. Single ViewModel edit shared by both duplicate findings.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/AppViewModel.kt:764
- **Source:** PDF printed p.33 (1.6): idling speed 770...870 rpm for engine code 2E (not adjustable)

## 16. [MEDIUM] Ignition / Basic Settings / Lambda (safety)

**STATUS:** APPLIED

- **Change:** Replace the invented 12.5 V battery-OK floor with sourced limits: batteryOk = battery in 11.5..15.5 (and !hasDtc("00532")); badge low-confidence while the 0.1024 V/bit scale (imported from the 025-ADY successor) is unverified. Aligns with the existing 11.5/15.5 band in DiagnosticSessionRepository. Merges the two battery-threshold findings.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/AppViewModel.kt:765
- **Source:** PDF printed p.16 (min 11.5 V for trouble-free operation); p.4-5 fault 00532/2234 (>15.5 V too high, <6.1 V too low)

## 17. [MEDIUM] Ignition / Basic Settings / Lambda (safety)

**STATUS:** APPLIED

- **Change:** Fix the ValuesCard footnote from "6° (4-8°) BTDC at ~2250 rpm" to "6° (4-8°) BTDC, checked at 2000-2500 rpm"; the spec value is correct but the rpm point is invented.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/ui/ignition/IgnitionScreen.kt:208-213
- **Source:** PDF printed p.34-35 (1.7): 4...8° BTDC, setting figure 6±1°, checked at 2000...2500 rpm

## 18. [MEDIUM] Ignition / Basic Settings / Lambda (safety)

**STATUS:** x (déjà fait avant l'audit: groupe 1 partout)

- **Change:** Enter Basic Settings targeting display group 001 on all paths for this ≥1390 (1576) ECU: enterBasicSettings(group = 1) in the ViewModel and change the repository default to group = 1 so the fake/non-deepObd path also uses 001 (the deepObd coerceAtLeast(1) can remain as a guard). Group 00 is the ≤1083 ten-irrelevant-zone procedure.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/AppViewModel.kt:340; core/repository/DiagnosticSessionRepository.kt:260
- **Source:** PDF printed p.34-36 (1.7): ≤1083 -> group 00; ≥1390 -> group 01 (four zones, wait for 00000000 in zone 4); docs/20-Digifant2E-Research.md:106,128

## 19. [MEDIUM] Ignition / Basic Settings / Lambda (safety)

- **Change:** Gate Basic-Settings readiness on coolant ≥85 °C to match the group-01 zone-4 adjust-mode bit, and stop equating the coolant reading with the separate 80 °C oil-temp precondition. Raise OPERATING_TEMP to 85 °C and soften the 'ready for Basic Settings' copy; set coolantOk = coolant >= 85.0. Merges the warm-up and coolantOk findings.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/ui/ignition/IgnitionScreen.kt:127 & :151; AppViewModel.kt:763
- **Source:** PDF printed p.36 (zone-4 bit 00000001 = coolant below 85 °C blocks check/adjust mode); p.33 & p.4 (80 °C is the oil-temp / post-erase road-test precondition, a different sensor)

## 20. [MEDIUM] Ignition / Basic Settings / Lambda (safety)

**STATUS:** APPLIED

- **Change:** Gate the ignition-timing safety checklist only on source-backed DTC codes: noThrottleFault = !hasDtc("00518") (drop the nonexistent F60/F81 switch codes 00516/00517); keep the coolant check on 00522. Drop the vacuous noHallFault (00513/00515 do not exist for this ECU) and render the Hall item as 'not determinable' (tri-state / N/A) rather than a false green all-clear before a hazardous distributor adjustment.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/AppViewModel.kt:766-768
- **Source:** PDF printed p.4-5 fault table (only 00518 G69 throttle pot; no Hall/G40 code, no F60/F81 idle/full-throttle switch codes; 2E uses a single throttle potentiometer G69)

## 21. [MEDIUM] KWP1281 transport / wire protocol

- **Change:** Validate the block counter. Track a nullable expected counter: seed it on the first received block (and after a deliberate 0x00-sync reset), otherwise require blkCounter == (expected+1)&0xFF and return null (tear down) on mismatch; keep the single shared counter incremented symmetrically for TX and RX. Preserve the KaPoder resync by marking expected unset after a 0x00 sync so the next block re-seeds instead of failing validation.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/core/deepobd/Kwp1281Session.kt:699 (readBlock) and :618 (sendBlock)
- **Source:** gmenounos KW1281Dialog.cs:465-480 & :341-342 (first block seeds; mismatch is a fatal error); klinelib KLineKWP1281Lib.cpp:4974; blafusel sec.2

## 22. [MEDIUM] KWP1281 transport / wire protocol

**STATUS:** defer — init 5-baud PROUVÉ live sur cette voiture; ne pas toucher ce qui marche

- **Change:** Frame the 5-baud slow-init address as start + 7 data bits (LSB first) + odd-parity + stop instead of 8 data bits with no parity: addr7 = address and 0x7F; parity = odd of addr7; dataBits = (addr7 shl 1) or (parity shl 8) or 0x0200. Fix the comment claiming 8 data bits / no parity. (0x01 is unchanged; 0x03/0x06/0x11 are currently mis-framed.)
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/core/deepobd/AdapterProtocol.kt:106 (flags1 parity at :93-95)
- **Source:** gmenounos KwpCommon.cs:167-210 (BitBang5Baud) & Utils.AdjustParity; klinelib KLineKWP1281Lib.cpp:4241-4291 (ODD parity for KWP1281)

## 23. [MEDIUM] KWP1281 transport / wire protocol

**STATUS:** defer — pacing additif, à tester au garage seulement

- **Change:** Tuning/hardening (PARTIAL — byte-level handshake is delegated to firmware, and the 40 ms init-complement delay is already honored): raise the interByteTime knob toward ~2-5 ms and pulse autoKeyByteDelayMs toward 25-40 ms via the runtime-tunable Kwp1281Config, to add the safety margin blafusel recommends for the slow early-Digifant baud. Do NOT add a fixed 10-25 ms sleep before every host TX — it would slow the mandatory <500 ms keep-alive loop without demonstrated need.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/core/deepobd/AdapterProtocol.kt:69 & :103
- **Source:** blafusel sec.6 (~5 ms inter-byte makes the link error-free; immediate reply 'too fast'); gmenounos R6=2 ms; klinelib byteDelay=2/blockDelay=10/initComplementDelay=40 ms — but per-byte ack/echo is firmware's job per DeepOBD-Observed-API.md:75-86

## 24. [MEDIUM] KWP1281 decode (Kwp1281Protocol.kt)

**STATUS:** APPLIED

- **Change:** Type-4 ignition angle: change value() to the magnitude abs(b-127)*0.01*a and special-case it in display() to append the direction/unit (ATDC when b>127 else BTDC) instead of emitting a bare signed number.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/core/deepobd/Kwp1281Protocol.kt:56
- **Source:** gmenounos SensorValue.cs:28 (Math.Abs(B-127)*0.01*A °ATDC/BTDC), blafusel

## 25. [MEDIUM] KWP1281 decode (Kwp1281Protocol.kt)

**STATUS:** APPLIED

- **Change:** Type-27 ignition angle: change value() to abs(b-128)*0.01*a and route through display() to attach the ATDC (b<128) / BTDC label; current signed form is negative for B<128 and carries no direction (note the label polarity is opposite type 4).
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/core/deepobd/Kwp1281Protocol.kt:78
- **Source:** gmenounos SensorValue.cs (Math.Abs(B-128)*0.01*A °ATDC/BTDC, ATDC if B<128), blafusel sec.4

## 26. [MEDIUM] KWP1281 decode (Kwp1281Protocol.kt)

- **Change:** Implement the missing high formula 0x9F temperature: add `0x9F -> ((b - 127) * 256 + a) * 0.1` to highFormulaValue and a 0x9F -> "°C" entry to headerBodyUnit (currently 0x9F returns null and the raw MWb byte is printed).
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/core/deepobd/Kwp1281Protocol.kt:185 (unit at :205-218)
- **Source:** klinelib KLineKWP1281Lib.cpp:2372 (case 0x9F: ((MW-127)*256+NW)*0.1 [degC])

## 27. [MEDIUM] DTC handling (interpreter + catalog)

**STATUS:** APPLIED

- **Change:** Relabel 00552 to "Air mass meter G19" and drop the invented duplicate air-mass entries: remove the G70 designation from 00520 and delete 00553 "Air Mass Sensor". Apply to both model files and the assets mirror. Merges the two G70 findings.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/ecu_models/vw/037906024AG.json:723 (& :706); demo-2e.json:720,703,721 (+ assets mirror)
- **Source:** PDF printed p.4-5 (00552/2323 = Air mass meter G19); p.9-11 / p.45-46 (G19 vane air-flow-meter potentiometer; G70 is a later hot-film MAF, not the 2E)

## 28. [MEDIUM] DTC handling (interpreter + catalog)

**STATUS:** APPLIED

- **Change:** Restrict the confirmed dtc_catalog to the manual's 9 codes; move the ~25 generic-VAG extras to a clearly-labelled 'generic VAG, unverified for 2E' section and delete the hardware-impossible entries: 00519/00575 (MAP — 2E uses vane AFM G19), 00516/00517 (idle/full-throttle switches F60/F81 — 2E uses pot G69), 00530 (throttle positioner G88). Apply to both model files and the assets mirror.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/ecu_models/vw/037906024AG.json:696-732; demo-2e.json (+ assets mirror)
- **Source:** PDF printed p.4-5 (complete 2E fault table = 9 codes); p.45-46 (G19 vane AFM, no MAP); p.40-41 (G69 potentiometer, no idle/full-throttle switches)

## 29. [MEDIUM] DTC handling (interpreter + catalog)

**STATUS:** APPLIED

- **Change:** Drop the invented elaborationOf() fault-nature table (0x10 Open circuit, 0x11 Short to ground, etc.). Show only the opaque status1 (status and 0x7F), or gate any nature hint behind an explicit 'unverified' presentation in the UI; keep the source-backed sporadic bit (status and 0x80) unchanged and stop appending nature strings as authoritative in DtcScreen.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/core/interpret/DtcInterpreter.kt:38-51 (UI at ui/dtc/DtcScreen.kt:160)
- **Source:** gmenounos FaultCodesBlock.cs (status1 is an opaque 7-bit number, not decoded into named natures); PDF printed p.1 (1.2) documents only the sporadic 'SP' addendum and the 5 s rule

## 30. [MEDIUM] ECU model provenance (037906024AG.json)

- **Change:** Group 005: delete the fabricated "WOT >= 90°; 0.0° = open circuit; >=90° at idle = short to +" note and the "°" unit on the throttle field, and label group 005 as sibling-label-inferred/unverified (or drop it until captured on this ECU). The manual gives no absolute throttle angle and defines no group 05.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/ecu_models/vw/037906024AG.json:463-488
- **Source:** PDF printed p.40-41 (throttle pot check: no absolute angle, uniform increase only); manual documents groups 01-04 only for ≥1390

## 31. [MEDIUM] ECU model provenance (037906024AG.json)

- **Change:** Blank the physical unit on raw fields that lack authoritative backing (PARTIAL — do NOT blanket-blank): blank the throttle "°" on groups 003/005 and the confidence-low undocumented units on groups 006/009/010/011, but KEEP the wire-calibrated °C/V/ms on groups 001-002 (backed by the 0x8C/0x85/0x89 wire formulas the decoder applies at runtime). Cite the file's own line-13 provenance policy, not CLAUDE.md rule 8 (which governs N/A-vs-zero).
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/ecu_models/vw/037906024AG.json:376-388,481-488 (throttle) and groups 006/009/010/011
- **Source:** PDF printed p.41 (throttle: no degree value, uniform increase only); file self-policy line 13; groups 001-002 units backed by KW1281 wire formula table (klinelib/blafusel)

## 32. [MEDIUM] ECU model provenance (037906024AG.json)

**STATUS:** APPLIED

- **Change:** Downgrade group-002 zone-2 injection_time provenance (PARTIAL — keep the source-backed parts): rename to 'Injection time (likely — VCDS-only, unconfirmed)', set confidence low, and note the manual documents group 02 ≥1390 only as z3 battery / z4 IAT (injection appears only in group 01 z4 on the ≤1083 path). Do NOT remove the ms unit or the 0x89/×0.5 note — 0x89 (A*B*0.01 ms with NWb=50) is a documented klinelib formula and value_type is already raw.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/ecu_models/vw/037906024AG.json:282-296
- **Source:** PDF printed p.36-37,43-47 (group 02 ≥1390 = battery z3 / IAT z4); p.38 (injection period is group 01 z4 on ≤1083); VCDS-Lite screenshot is not an authoritative source

## 33. [LOW] KWP1281 transport / wire protocol

- **Change:** Widen the block-length acceptance to the full protocol range (PARTIAL — needs an echo/framing guard, not a bare cap change): move the transmit echo-drain out from under the config.depair=="on" guard (or filter echo-complement bytes) so the scan no longer relies on the 64 ceiling to reject 0x80+ echoes, then change `v in 3..64` to `v in 3..255` AND validate that the final logical byte == 0x03 before accepting the block.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/core/deepobd/Kwp1281Session.kt:679 (echo-drain at :641-653)
- **Source:** gmenounos KW1281Dialog.cs:335-355/397-457; klinelib KLineKWP1281Lib.cpp:4763 (length is a single 8-bit byte, no 64 cap; data bytes = blockLength - 3)

## 34. [LOW] KWP1281 decode (Kwp1281Protocol.kt)

- **Change:** Branch DTC codes in 0x4000..0x7FFF to standard-OBD rendering (P/C/B/U per category (code>>12)&0xF) via a getOBDFaultCode-style mapping, keeping the native 5-digit path for out-of-range codes; or document that this decoder assumes native VAG codes only (safe for the 037906024AG target). Merges the two OBD-range findings.
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/core/deepobd/Kwp1281Protocol.kt:254-258
- **Source:** klinelib KLineKWP1281Lib.cpp:746 (isOBDFaultCode for 0x4000..0x7FFF) and doc:579-581, getOBDFaultCode (:786-836)

## 35. [LOW] ECU model provenance (037906024AG.json)

- **Change:** Soften the remaining group-000 zone field names/keys to sibling-label form so the UI never asserts a bare physical meaning (e.g. "Intake air temp (raw)" -> "Zone 1 raw (sibling: intake air temp)"); keep confidence low/medium and leave the disclosed provenance notes. (Zone 2 / co_pot is already handled by the 00521 fix; rpm is handled by the group-000 rpm fix.)
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/ecu_models/vw/037906024AG.json:20-171
- **Source:** PDF printed p.34 (ten zones: numbers not relevant, no per-zone meaning published); docs/20-Digifant2E-Research.md:11-14,24-41 (no 024 label; layout inferred from 023/025-ADY siblings)

## 36. [LOW] ECU model provenance (037906024AG.json)

- **Change:** Keep groups 006/009/010 flagged undocumented/candidate (already hedged confidence low), gate them out of the default UI until captured on this ECU, and ensure they render N/A rather than fabricated units (group 006 currently attaches raw 'rpm'/'°C').
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/ecu_models/vw/037906024AG.json:514-664
- **Source:** PDF printed p.36-47 (only groups 01-04 documented for ≥1390); manual defines no group 05/06/09/10

## 37. [LOW] ECU model provenance (037906024AG.json)

**STATUS:** APPLIED

- **Change:** Lower the top-level source.confidence from "high" to "medium" (or low) to match the field-level reality: the body is pervasively raw-only, sibling-inferred, and admits the ≥1390 wire encoding is uncaptured (40/45 fields are below 'high').
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/ecu_models/vw/037906024AG.json:10-14
- **Source:** Internal inconsistency with field-level confidences (mostly low/medium) and the uncaptured-encoding admission in docs/20-Digifant2E-Research.md:135-137

## 38. [LOW] ECU model provenance (measurement interpreter)

- **Change:** Close the confidence-badging bypass: when a field is relabelled by the wire formula (0x83/0x8F -> ignition advance), force its confidence to the wire's own low confidence instead of inheriting the model index's confidence, so a future 'high' index cannot render a fabricated advance un-badged. Add `val confidence = if (angle) "low" else spec?.confidence` and use it in both InterpretedMeasurement return sites. Currently latent (no field is 'high' today).
- **Where:** /home/rodrigbe/DEV/Perso/T3/OBDTOOL/android/app/src/main/java/io/databang/digidash/core/interpret/MeasurementInterpreter.kt:67,77 (badging at AppViewModel.kt:570,591)
- **Source:** Provenance guardrail (CLAUDE.md: badge everything not fully calibrated/verified); relabelled ignition-advance is klinelib-only/unverified; demo-2e.json currently has 0 fields at confidence 'high'

