# 04 — Communication Protocol Notes

The target ECU uses old VAG diagnostics, likely K-line with KWP1281-style behavior. This document is not a replacement for the Deep OBD stack. It defines only the abstractions the app needs.

## Required operations

| Operation | Required for MVP | Write operation | Safety level |
|---|---:|---:|---|
| Connect dongle | yes | no | low |
| Identify ECU | yes | no | low |
| Read measuring blocks | yes | no | low |
| Read DTC | V2/MVP optional | no | low |
| Clear DTC | optional | yes | guarded |
| Basic Settings | optional | yes/special session | guarded |
| Adaptation | no | yes | forbidden MVP |
| Coding | no | yes | forbidden MVP |

## Raw measuring block model

A raw block contains:

- group number
- ECU timestamp if available
- app timestamp
- fields 1..4 where available
- raw display strings from backend
- numeric parsed values where safe

Do not assume every group has four fields.

## Timing/throughput

Old ECUs can be slow. Use a single sequential operation queue. Never launch concurrent diagnostic requests against the same ECU connection.

## Operation queue

```kotlin
class DiagnosticOperationQueue {
    suspend fun <T> execute(name: String, block: suspend () -> T): T
}
```

Use this queue for all operations: reads, DTC, Basic Settings.

## Stale values

Each interpreted value must include timestamp and freshness:

- fresh: updated within last polling interval * 2
- stale: older than threshold
- unavailable: never read or mapping failed

## Fake backend

The fake backend should simulate:

- normal operation
- missing group
- timeout
- dropped connection
- unknown ECU
- unsupported Basic Settings
- DTC present

