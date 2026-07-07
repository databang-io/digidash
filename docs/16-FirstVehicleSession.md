# 16 — First Real Vehicle Session

Use this when the first APK is ready.

## Preparation

- Phone charged
- Dongle paired/available
- Engine off initially
- Existing Deep OBD app retained for comparison
- Timing light not needed for first dashboard test

## Steps

1. Launch app in fake mode; verify UI works.
2. Disable fake mode.
3. Select dongle.
4. Connect with ignition ON, engine OFF.
5. Confirm ECU identity reads `037 906 024 AG`.
6. Read raw group 000, 001, 002, 003 if available.
7. Save raw block snapshot.
8. Start engine.
9. Record idle log cold or warm.
10. Compare RPM and voltage plausibility.
11. Warm engine and record another log.
12. Press throttle slightly and verify state changes if mapped.
13. Read DTCs.
14. Do not clear DTCs unless deliberately testing.

## Data to send back to the developer

- ECU identity screenshot/text
- raw block JSON export
- CSV log
- any error messages
- Deep OBD raw values for the same groups

