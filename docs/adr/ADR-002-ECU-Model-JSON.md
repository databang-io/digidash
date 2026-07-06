# ADR-002 — ECU Model JSON instead of direct LBL dependency

## Status

Accepted.

## Context

Ross-Tech label files are useful but should not be redistributed directly.

## Decision

Use a normalized ECU Model JSON format. Provide a converter that consumes user-supplied `.LBL` ZIP files.

## Consequences

- App runtime is independent from `.LBL` format.
- Mappings can be validated and edited.
- Conversion can be improved independently.

