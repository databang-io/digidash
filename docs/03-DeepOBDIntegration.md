# 03 — Deep OBD / EdiabasLib Integration

## Goal

Deep OBD already communicates with the Digifant ECU. The app should reuse that working communication path, not reinvent the protocol first.

## Expected existing capability

The current Deep OBD setup can:

- connect to the dongle
- communicate with the Digifant ECU
- read serial number
- read part number
- read raw measuring blocks

## Integration strategy

Create a wrapper module around EdiabasLib/Deep OBD concepts. The wrapper exposes only the app's `DiagnosticClient` interface.

Claude Code must inspect the Deep OBD/EdiabasLib API and identify:

- how to open a connection to the dongle
- how ECU selection is configured
- how jobs are executed
- how results are returned
- how measuring blocks are read
- how faults are read/cleared
- whether Basic Settings can be called

## Adapter design

```kotlin
class DeepObdDiagnosticClient(
    private val config: DeepObdConfig,
    private val dispatcher: CoroutineDispatcher
) : DiagnosticClient {
    override suspend fun connect(config: ConnectionConfig): Result<Unit> = TODO()
    override suspend fun identifyEcu(): Result<EcuIdentity> = TODO()
    override suspend fun readMeasuringBlock(group: Int): Result<RawMeasuringBlock> = TODO()
    override suspend fun readDtc(): Result<List<RawDtc>> = TODO()
    override suspend fun clearDtc(): Result<Unit> = TODO()
    override suspend fun enterBasicSettings(group: Int?): Result<Unit> = TODO()
    override suspend fun exitBasicSettings(): Result<Unit> = TODO()
}
```

## Fallback strategy

If EdiabasLib cannot expose a required function directly:

1. Keep the feature as `UnsupportedFunction`.
2. Keep raw blocks working.
3. Do not implement a custom low-level protocol until the MVP dashboard/logging works.
4. Add an ADR explaining the limitation.

## Configuration files

Deep OBD may depend on `.grp`, `.ecu`, `.prg`, XML, or other configuration assets. The project must document exactly which assets are needed and where they are placed.

Create after inspection:

```text
docs/DeepOBD-Observed-API.md
```

This file must include:

- library version
- required Gradle dependency or source import method
- required Android permissions
- connection examples
- job names used for old VAG ECU communication
- known limitations

## Android permissions

Likely permissions:

- Bluetooth classic permissions for older Android
- `BLUETOOTH_CONNECT` for Android 12+
- USB host permission if USB adapter mode is supported
- storage/media permissions only for explicit log export where needed

Do not ask for unnecessary permissions.

