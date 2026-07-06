# Deep OBD / EdiabasLib — Observed API and Dongle Management

Status: source-level analysis of https://github.com/uholeschak/ediabaslib
(master, read 2026-07-06). This is the ticket 12 knowledge base for building
`DeepObdDiagnosticClient` (ticket 13) in Kotlin.

## 0. Architecture reality check

**EdiabasLib is C#/.NET (Mono for Android). No Java/Kotlin binding exists.**
DigiDash therefore reimplements the *adapter wire protocol* in Kotlin behind
`DiagnosticClient`, rather than linking the library. The C# host sources are
authoritative documentation for that protocol.

For VAG KWP1281 the only supported dongles are the **custom Bluetooth D-CAN/
K-Line adapter** or an **ELM327 flashed with the Deep OBD replacement
firmware** (PIC18F25K80). Stock ELM327s are limited to BMW-FAST over D-CAN
(`docs/AdapterTypes.md` in their repo).

## 1. Paired-device listing / selection (DeviceListActivity.cs)

- Paired list = `BluetoothAdapter.BondedDevices`; discovery via classic
  `StartDiscovery()` + BroadcastReceiver (`ACTION_FOUND`, `ACTION_NAME_CHANGED`,
  `ACTION_DISCOVERY_FINISHED`). Discovery is cancelled before any connect.
- SPP validity filter: `device.GetUuids()` must be empty (unknown) or contain
  SPP UUID `00001101-0000-1000-8000-00805F9B34FB`.
- After selection the adapter is **probed** (see §3) and the persisted address
  carries a type tag: `MAC;ELM327` (stock), `MAC;ELMDEEPOBD` (replacement
  firmware), `MAC;RAW` (echo-only), bare `MAC` (custom adapter).
- Persisted: device name + tagged address (XML settings). EDIABAS port string
  is `BLUETOOTH:<taggedAddress>`.

DigiDash mirrors: paired-device picker done; probe + tag to add in ticket 13.

## 2. SPP connection (EdBluetoothInterfaceAndroid.cs)

- UUID `00001101-0000-1000-8000-00805F9B34FB`.
- Bonded device → `createRfcommSocketToServiceRecord` (secure);
  unbonded → `createInsecureRfcommSocketToServiceRecord`.
- Connect on a worker thread; a failed `Connect()` is retried once on the same
  socket; final fallback = hidden `createRfcommSocket(channel=1)` via
  reflection (known to lose data on long telegrams).
- After connect: wait up to 2000 ms for `ACTION_ACL_CONNECTED`, then 50 ms
  settle delay. Timeouts: echo 500 ms, read offset 100/1000 ms.
- On failure mid-session: full disconnect + reconnect, 2 attempts.

## 3. Adapter probe sequence (AdapterTypeDetect.cs, 1000 ms/step)

1. **Custom adapter test**: send BMW-FAST "read ignition"
   `82 F1 F1 FE FE 58` (checksum = 8-bit sum). Adapter echoes the request then
   appends a checksummed reply. If valid: configure escape mode
   (`84 F1 F1 06 <mode^55> <FF^55> <80^55> <cks>`), read firmware version
   (`82 F1 F1 FD FD <cks>` → adapterTypeId + fwVersion), read serial
   (`82 F1 F1 FB FB <cks>`).
2. **ELM327 test**: `ATI` → regex `\w+\s+v(\d+)\.(\d+)`; then init commands all
   requiring OK: `ATD, ATE0, ATSH6F1, ATCF600, ATCM700, ATPBC001, ATSPB, ATAT0,
   ATSTFF, ATAL, ATH1, ATS0, ATL0` (+ version-gated `ATCSM0, ATCTM5, ATJE`).
3. **Replacement-firmware discriminator**: `AT@2` — response starting with
   `DEEPOBD` ⇒ Deep OBD firmware (`;ELMDEEPOBD`). That's the whole check.
4. Fake/limited detection: `AT@1` (CARLY), `AT#1` (WGSOFT), `STI/STIX` (STN),
   `ATPP2COFF`, CAN probes (`CAN ERROR` analysis).

## 4. Custom adapter protocol — K-line / KWP1281 essentials

Transparent UART over SPP (38400 baud stock BT module, 115200 with custom BT
firmware). At vehicle baud ≠ 115200 every request is wrapped:

```
K-line telegram (telType 0x02, fw >= 0x0008):
00 02 <baud/2 hi> <baud/2 lo> <flags1> <flags2> <interbyte>
<kwp1281_timeout=60> <len hi> <len lo> <payload...> <cks>
```

- `flags1`: bits0-2 parity (0 none, 1 even, 2 odd), 0x08 L-line, 0x10 pulse,
  0x20 no echo (always set), 0x40 fast init, 0x80 K-line.
- `flags2` bit 0x01 = `KLINEF2_KWP1281_DETECT`: **the firmware handles the
  KWP1281 block ack/echo handshake itself** (fw >= 0x0008). This is the key
  simplification for DigiDash — no per-byte echo management in Kotlin.
- **5-baud init done by firmware**: pulse telegram with
  `dataBits=(addr<<1)|0x0200, length=10, pulseWidth=200 ms` (engine ECU
  addr = 0x01). Sync response: raw sync byte (0x55 ⇒ 9600;
  `(b&0x87)==0x85` ⇒ 10400) or measured baud/2 in auto mode; then key bytes
  (key2 = 0x8F ⇒ KWP2000, else KWP1281).
- KWP1281 auto mode: each received byte arrives as a pair
  (data byte + status byte, delay = `(status & 0x7F) * 10 ms`); firmware
  appends block-end 0x03. Constants: byte timeout 55 ms, status timeout
  1000 ms, init delay 2600 ms, ack 0x09, nack 0x0A, end-output 0x06.
- Status telegrams (all `8x F1 F1 ...`, echoed first): `FE` ignition,
  `FD` fw version, `FB` serial, `FC` battery voltage (×0.1 V).
- Escape framing (MTC head units eat 0x00): `0x00`/escape code sent as
  `0xFF, byte^0x80`; escape-config values XORed with 0x55.
- Baud range: 980/4000–25000 or 115200. KWP1281 uses 9600 (early Digifant
  4800/10400 possible — auto baud handles it).

## 5. KWP1281 session (per blafusel.de + EdInterfaceObd.cs)

- Block titles: 0x29 group reading request → 0xE7 response; DTC read 0x07 →
  0xFC; DTC clear 0x05; ACK 0x09 as keep-alive — **mandatory < ~500 ms or the
  ECU drops the session**.
- Group 000: 10 raw bytes, no type IDs. Groups 001+: 4 fields × 3 bytes
  (type, a, b) with standard VAG formulas (already in
  docs/20-Digifant2E-Research.md).

## 6. Kotlin implementation plan (ticket 13 sketch)

1. `SppSocket` (secure→insecure→channel-1 fallback, retry-once, ACL wait).
2. `AdapterProbe` (custom telegram → ELM ATI → AT@2 DEEPOBD check) storing the
   Deep OBD-style tag with the selected device.
3. `DeepObdAdapterTransport`: K-line telegram framing, pulse/5-baud init,
   escape mode, status telegrams (battery voltage card for free via `FC`!).
4. `Kwp1281Session` on top: keep-alive ACK loop, group reads, DTC read/clear.
5. All behind the existing `DiagnosticClient`; fake backend untouched.

## Not yet confirmed

- Semantics of the status-byte high bit in KWP1281 auto mode.
- BLE GATT bridge details (BtLeGattSpp.cs) — irrelevant for classic SPP dongles.
- PIC firmware sources (`CanAdapterElm/`) not reviewed; host-side C# is enough
  for a client reimplementation.

## Sources

- BmwDeepObd/DeviceListActivity.cs, AdapterTypeDetect.cs, MainActivity.cs,
  ActivityCommon.cs
- EdiabasLib/EdiabasLib/EdBluetoothInterfaceAndroid.cs, EdElmInterface.cs,
  EdCustomAdapterCommon.cs, EdInterfaceObd.cs, EscapeStreamReader/Writer.cs
- docs/Replacement_firmware_for_ELM327.md, docs/AdapterTypes.md,
  docs/Custom_Bluetooth_firmware.md
- https://www.blafusel.de/obd/obd2_kw1281.html
