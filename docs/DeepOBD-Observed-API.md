# Deep OBD / EdiabasLib — Observed Facts (preliminary)

Status: preliminary web research, 2026-07-06. Full ticket 12 investigation still open.

Sources:
- https://uholeschak.github.io/ediabaslib/docs/Deep_OBD_for_BMW_and_VAG.html
- https://github.com/uholeschak/ediabaslib/blob/master/README.md

## Key facts

1. **EdiabasLib is a C#/.NET library** (Mono for Android / Xamarin). There is **no
   Java/Kotlin binding**. A pure Kotlin app cannot link it directly.
   Consequences for this project:
   - The `DiagnosticClient` abstraction stays mandatory.
   - Integration options to evaluate in ticket 12:
     a. Embed a .NET Android runtime (heavy, complex build) — probably rejected.
     b. Reuse the *adapter protocol* only: talk directly to the ELM327
        replacement-firmware adapter over Bluetooth SPP from Kotlin
        (the firmware protocol is documented in the ediabaslib repo).
     c. Custom KWP1281 implementation over a K-Line adapter (last resort,
        explicitly deprioritized by ADR-001).

2. **VAG mode is experimental** in Deep OBD and supports KWP2000, KWP1281 and
   TP2.0 — but **requires the ELM327 replacement firmware**
   (PIC18F25K80-based adapters). A stock ELM327 cannot do KWP1281 properly.
   VAG support targets vehicles built until ~2017-08 (Digifant 2E is fine).

3. **Adapter/dongle selection UX in Deep OBD**: user picks from paired
   Bluetooth devices (pairing beforehand in Android settings recommended);
   discovery of new devices is possible. DigiDash mirrors this with its
   paired-device picker on the Home screen.

4. Bluetooth classic SPP UUID used by these adapters:
   `00001101-0000-1000-8000-00805F9B34FB`.

## Open questions for ticket 12

- Exact framing of the replacement-firmware adapter protocol (escape bytes,
  baud switching for KWP1281 5-baud init).
- Whether the firmware handles the 5-baud init + 7O1 framing itself
  (docs suggest yes — that is its raison d'être).
- Licensing of protocol documentation for reimplementation in Kotlin.
