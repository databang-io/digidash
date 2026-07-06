# 02 — System Architecture

## Layers

```text
UI Layer
  Jetpack Compose screens
  ViewModels

Domain Layer
  Use cases
  Domain models
  Safety policy

Data Layer
  DiagnosticRepository
  ECUModelRepository
  LogRepository

Integration Layer
  DeepOBD/Ediabas adapter
  Fake ECU adapter
  File system adapter

Tools
  LBL converter
  ECU Model validator
```

## Core interfaces

```kotlin
interface DiagnosticClient {
    suspend fun connect(config: ConnectionConfig): Result<Unit>
    suspend fun disconnect()
    suspend fun identifyEcu(): Result<EcuIdentity>
    suspend fun readMeasuringBlock(group: Int): Result<RawMeasuringBlock>
    suspend fun readDtc(): Result<List<RawDtc>>
    suspend fun clearDtc(): Result<Unit>
    suspend fun enterBasicSettings(group: Int?): Result<Unit>
    suspend fun exitBasicSettings(): Result<Unit>
    fun connectionState(): Flow<ConnectionState>
}
```

```kotlin
interface EcuModelRepository {
    suspend fun loadIndex(): EcuModelIndex
    suspend fun findByPartNumber(partNumber: String): EcuModel?
    suspend fun loadModel(file: String): EcuModel
}
```

```kotlin
interface MeasurementInterpreter {
    fun interpret(block: RawMeasuringBlock, model: EcuModel): InterpretedBlock
}
```

## Data flow

1. UI asks ViewModel to connect.
2. ViewModel calls DiagnosticRepository.
3. DiagnosticRepository delegates to DiagnosticClient.
4. ECU identity is read.
5. ECUModelRepository loads matching ECU Model.
6. Polling loop reads selected groups.
7. MeasurementInterpreter creates interpreted values.
8. UI displays values.
9. CsvLogger records raw + interpreted samples.

## Polling design

Old VAG ECUs are slow. Do not poll too aggressively.

Suggested MVP strategy:

- Trip Mode: poll only critical groups, one group per cycle.
- Garage Mode: poll selected group(s) only.
- Raw Blocks screen: poll one visible group at a time.
- Stop polling during DTC clear and Basic Settings transitions.

Suggested default:

- 1 group every 500-1000 ms in fake mode
- configurable slower interval for real ECU

## Error handling

All ECU operations return a typed result:

```kotlin
sealed class DiagnosticError {
    data object DongleNotFound : DiagnosticError()
    data object EcuNoResponse : DiagnosticError()
    data object UnsupportedFunction : DiagnosticError()
    data class ProtocolError(val raw: String) : DiagnosticError()
    data class Timeout(val operation: String) : DiagnosticError()
    data class Unknown(val message: String) : DiagnosticError()
}
```

The UI must never display stack traces to the user. Detailed errors go to logs.

