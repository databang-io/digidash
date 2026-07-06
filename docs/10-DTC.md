# 10 — Diagnostic Trouble Codes

## Requirements

- Read DTCs if backend supports it.
- Display raw codes even without descriptions.
- Use ECU Model DTC catalog for descriptions if available.
- Clear DTCs only after confirmation.

## DTC model

```kotlin
data class RawDtc(
    val code: String,
    val status: String?,
    val rawText: String?
)
```

```kotlin
data class InterpretedDtc(
    val code: String,
    val title: String?,
    val description: String?,
    val severity: Severity,
    val raw: RawDtc
)
```

## Confirmation text

```text
Clear ECU fault codes? This may erase useful diagnostic evidence. Only continue if you have recorded or reviewed the current faults.
```

## Logging

Before clearing, write all current DTCs to the log/event store.

