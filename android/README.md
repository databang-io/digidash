# DigiDash — Android app module

Kotlin 2.0 · Gradle Kotlin DSL · Jetpack Compose · Material 3 · kotlinx.serialization · Coroutines/Flow · JUnit4.

Package: `io.databang.digidash` — minSdk 26, target/compile 35.

## Build

```bash
./gradlew :app:assembleDebug          # APK -> app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # JVM unit tests
```

Needs a JDK 17+ and the Android SDK (`local.properties` → `sdk.dir=...`).
On this machine: `JAVA_HOME=$HOME/DEV/tools/jdk21`, SDK at `~/Android/Sdk`.

## Structure (per CLAUDE.md architecture)

```
core/diagnostics/   DiagnosticClient, ConnectionState, DiagnosticError,
                    BluetoothDongles (paired-device picker), fake/ backend
core/ecumodel/      EcuModel data classes, repository with ordered sources:
                    RemoteEcuModelSource (public git repo, raw HTTPS + cache)
                    then AssetEcuModelSource (bundled)
core/interpret/     MeasurementInterpreter (formulas, thresholds, N/A policy)
data/repository/    DiagnosticSessionRepository (connect/identify/poll loop)
domain/model/       EcuIdentity, RawMeasuringBlock, InterpretedMeasurement,
                    DashboardCardState, ...
ui/home|dashboard|tech|theme
AppContainer.kt     hand-rolled DI; AppViewModel.kt shared UI state
```

The app runs fully on the fake backend (no dongle): Home → Connect →
Dashboard gauges / Tech raw blocks. Assets under `src/main/assets/ecu_models`
and `assets/samples` are synced copies of the repo-root directories — keep
them in sync when editing models.

Real Deep OBD adapter (ticket 13): see `../docs/DeepOBD-Observed-API.md`.
