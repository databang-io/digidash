# ADR-001 — Use Deep OBD / EdiabasLib as communication backend

## Status

Accepted for MVP.

## Context

Deep OBD already communicates with the target Digifant ECU. Reimplementing old VAG K-line communication is risky and time-consuming.

## Decision

The app will define its own diagnostic abstraction and implement a Deep OBD/EdiabasLib adapter. A fake backend will be implemented first.

## Consequences

- Faster route to real ECU communication.
- App remains testable without the library.
- If library limitations exist, unsupported features can be hidden/disabled.

