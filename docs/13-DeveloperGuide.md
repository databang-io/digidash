# 13 — Developer Guide

## First session plan for Claude Code

1. Create Android Gradle project in `android/`.
2. Add Kotlin, Compose, Material 3.
3. Create domain models.
4. Implement fake diagnostic backend.
5. Load sample ECU Model.
6. Build dashboard UI.
7. Add CSV logger.
8. Add raw blocks screen.
9. Add DTC screen with fake backend.
10. Add ignition setup screen.
11. Add converter CLI skeleton.
12. Add tests.

## Do not start with real Deep OBD integration

Start with interfaces and fake backend first. This prevents UI and architecture from blocking on library details.

## Dependency injection

Use simple constructor injection for MVP. Hilt/Koin optional later.

## Coroutines

Use structured concurrency. Diagnostic polling must be cancellable.

## State model

Use immutable UI state data classes:

```kotlin
data class DashboardUiState(
    val connection: ConnectionState,
    val ecuIdentity: EcuIdentity?,
    val model: EcuModelSummary?,
    val cards: List<DashboardCardState>,
    val activeLog: ActiveLogState?,
    val error: UiMessage?
)
```

## Testing without vehicle

Fake backend must be selectable in debug builds and maybe in settings.

## Commit order

Follow tickets in `tickets/`.

