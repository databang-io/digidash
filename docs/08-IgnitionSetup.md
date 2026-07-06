# 08 — Ignition Setup Assistant

## Target vehicle context

The target vehicle is a home-built VW T3 conversion:

- engine side is VW 2E
- distributor is 2E with Hall sensor
- ECU is `037 906 024 AG`
- flywheel is JX
- clutch is JX
- bellhousing is JX
- gearbox is T3/JX

Therefore, Golf 3 flywheel timing marks must not be assumed valid. The user knows how to find real TDC and will create a reliable mark, likely on the crank pulley.

## Purpose

The assistant helps prepare and monitor the ECU while ignition timing is checked with a timing light. It does **not** adjust timing electronically.

## Mandatory warning text

Display before entering the assistant:

```text
This tool does not set ignition timing electronically. On the VW 2E Digifant engine, base timing is adjusted mechanically by rotating the distributor. Because this T3 conversion uses JX flywheel/clutch/bellhousing/gearbox, use only a TDC/timing mark that you have confirmed manually. Do not rely on Golf 3 flywheel marks unless independently validated.
```

## Checklist

The screen shall show a checklist:

- Real TDC cylinder 1 confirmed
- Timing mark created/validated on crank pulley or chosen reference
- Timing light connected to cylinder 1
- Engine at operating temperature
- Idle stable
- No active Hall sensor fault
- No active coolant temperature sensor fault
- No active throttle/idle switch fault
- Battery voltage healthy
- Basic Settings active, if supported

Checklist items can be manual or automatic. Automatic items must be based on ECU data when available.

## Displayed values

- coolant temperature
- RPM
- ECU-reported ignition advance
- battery voltage
- throttle/idle state
- lambda regulation state
- active DTC count
- Basic Settings status

## Basic Settings

If supported by Deep OBD/EdiabasLib:

- show button `Enter Basic Settings`
- require confirmation
- display active status
- show button `Exit Basic Settings`
- automatically exit on screen leave if possible

If not supported:

- display `Basic Settings unsupported by current backend`
- do not hide other assistant functions

## User guidance

Show concise guidance:

```text
1. Warm the engine.
2. Confirm stable idle.
3. Enter Basic Settings if supported.
4. Use timing light on your confirmed pulley mark.
5. Rotate the distributor mechanically if adjustment is needed.
6. Tighten distributor clamp.
7. Exit Basic Settings.
8. Re-check idle and DTCs.
```

## Safety behavior

- No automatic distributor/timing recommendation unless target spec is configured.
- If target timing value is not configured, show `Target timing not configured`.
- Do not invent timing values.
- Store user notes in log if the user records timing setup.

