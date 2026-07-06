# ADR-003 — Fake ECU backend first

## Status

Accepted.

## Context

Real vehicle access is limited and old ECU communication is slow.

## Decision

Build fake backend before Deep OBD integration.

## Consequences

- UI, logging and ECU Model loading can be developed safely.
- Regression tests become possible.

