# 07 — Dashboard UI/UX

## Design goal

The app must be readable and useful in a T3 during a trip. The passenger should understand engine health at a glance.

## Screens

### Home / Connection

- selected dongle
- connection status
- ECU identity
- selected ECU Model
- fake mode toggle for development

### Trip Dashboard

Large cards:

- Coolant temperature
- Battery voltage
- RPM
- Lambda status/correction
- Injection time
- Ignition advance ECU-reported
- Throttle/idle state
- DTC count
- Connection quality

Card states:

- normal
- warning
- critical
- unavailable
- stale

### Garage Dashboard

- selected measuring group
- raw fields
- interpreted fields
- refresh/poll controls
- confidence notes

### Raw Blocks

- group list
- current values
- raw string
- parsed numeric value
- timestamp

### DTC

- read faults
- show raw and interpreted DTC
- clear faults with confirmation

### Ignition Setup

See `docs/08-IgnitionSetup.md`.

### Logs

- start/stop log
- list logs
- share/export log
- delete log with confirmation

## N/A policy

Never display `0` for missing values. Use:

```text
N/A
```

or:

```text
Unsupported by ECU
```

## Units

Display units from ECU Model, not hard-coded UI.

## Staleness

Every card should show stale status when no update has arrived recently.

## Accessibility

- large text option
- high contrast states
- no reliance on color alone: include labels/icons/text

## Example Trip UI content

```text
T3 2E Digifant
ECU 037 906 024 AG | Connected | Model loaded

Coolant       88 °C        Normal
Battery       13.9 V       Normal
RPM           920 rpm      Idle
Lambda        Active       Normal
Injection     2.6 ms       Normal
Advance ECU   8 °BTDC      Info
DTC           0            OK
```

