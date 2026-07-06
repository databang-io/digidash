# 00 — Vision

Digifant Dashboard is an Android diagnostic companion for older VAG ECUs where generic OBD apps are useless and existing tools display raw blocks without practical interpretation.

The immediate user problem is concrete: a VW 2E engine is mounted in a VW T3. Deep OBD already talks to the Digifant ECU and can read identification plus raw blocks, but the values are not interpreted in a useful way. The user needs a reliable application before a trip that shows critical engine health data.

The project should become a clean, open, extensible platform for older VAG ECUs, but the first deliverable must stay focused on one ECU: `037 906 024 AG`.

## Product principles

- Simple during a trip.
- Detailed in the garage.
- Conservative with ECU writes.
- Raw data never hidden.
- ECU descriptions are data, not hard-coded UI logic.
- The app must survive missing groups, unexpected raw values, and dropped connections.

## Modes

### Trip Mode

Large readable cards for critical values:

- coolant temperature
- battery voltage
- RPM
- lambda regulation state
- throttle/idle state
- injection time
- ignition advance reported by ECU
- active DTC count
- ECU communication quality

### Garage Mode

Detailed diagnostic mode:

- raw measuring blocks
- interpreted measuring blocks
- DTC read/clear
- Basic Settings entry/exit if supported
- ignition timing assistant
- CSV logging
- ECU identification

## Out of scope for MVP

- ECU remapping
- coding changes
- adaptation changes unless explicitly supported later
- power tuning
- universal OBD-II support
- cloud accounts
- automatic timing adjustment

