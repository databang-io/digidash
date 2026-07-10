# Captured KW1281 group frames — 037906024AG (VW 2E Digifant, SW 1576)

Real frames captured live at the garage on 2026-07-08 over the Deep OBD adapter.
Provenance-honest record (CLAUDE.md guardrails): what is REAL vs what is not.

## REAL — group HEADERS (title 0x02, header of the header/body pair)

Reply to `0x29 01` (group 1), 46 data bytes:
```
8B 1A 11  FA E1 E0 BC AD 96 84 75 61 53 48 39 28 1F 19 12 00   # zone1 formula 8B (RPM),  NWb=0x1A, 17-byte table
8C 28 11  A0 64 50 44 3A 32 2C 26 21 1B 16 10 0B 04 00 00 00   # zone2 formula 8C (Temp),  NWb=0x28, 17-byte table
85 02 00                                                        # zone3 formula 85 (Voltage), NWb=0x02 -> V=MWb/128  (LAMBDA)
88 FF 00                                                        # zone4 formula 88 (bits mask NWb=0xFF)  (condition bits)
```

Reply to `0x29 02` (group 2), 46 data bytes:
```
8B 1A 11  FA E1 E0 BC AD 96 84 75 61 53 48 39 28 1F 19 12 00   # zone1 formula 8B (RPM),  NWb=0x1A
89 32 00                                                        # zone2 formula 89 (Injection), NWb=0x32 -> ms=MWb*0.5
85 18 00                                                        # zone3 formula 85 (Voltage), NWb=0x18=24 -> V=MWb*24/256 (BATTERY)
8C 28 11  A0 64 50 44 3A 32 2C 26 21 1B 16 10 0B 04 00 00 00   # zone4 formula 8C (Temp), NWb=0x28  (INTAKE AIR)
```

Cross-check: with the VCDS-Lite screenshot values (12.38 V, 5.00 ms, 1.22 V) the
formulas above reproduce them to 2 decimals — see HeaderBodyDecodeTest.

## REAL — group 000 raw display (title 0xF4, 10 bytes; param-less 0x12)

```
22 84 07 20 38 20 80 2B 0A F0     # engine off, battery falling
28 81 12 20 38 20 80 2A 0A F0
26 92 08 4A 39 00 00 2A 05 D2     # engine running
```

## NOT CAPTURED (the open gap)

- **No real BODY (0xF4, <=4 bytes) for groups 1/2/3.** The body only arrives on
  an immediate second 0x29 with NO intervening ACK; that no-ACK body request was
  never issued live (our old loop ACKed between every command). Every 0x02 we
  ever received was a HEADER. HeaderBodyDecodeTest therefore feeds the REAL
  headers with SYNTHETIC bodies — the decode MATH is validated, the live
  header->body handshake is NOT.
- **No group 003 header** — only the two layouts above (g1, g2) were seen.

## To close the gap (next garage session)

Connect with the streaming build; watch `adb logcat -s DIGIDASH_DBG` for
`stream` lines: a `0x02` header followed by a `0xF4` body of <=4 bytes on the
same group = the handshake confirmed. Capture one real header+body pair per
group here and the decode is fully grounded.
