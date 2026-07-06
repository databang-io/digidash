# 17 — Pseudo-code

## Polling loop

```kotlin
while (isActive) {
    val nextGroup = pollingPlan.nextGroup()
    val raw = diagnosticClient.readMeasuringBlock(nextGroup)
    if (raw.isSuccess) {
        val interpreted = interpreter.interpret(raw.getOrThrow(), ecuModel)
        stateStore.update(interpreted)
        logger.record(raw.getOrThrow(), interpreted)
    } else {
        stateStore.markGroupError(nextGroup, raw.exceptionOrNull())
    }
    delay(pollingInterval)
}
```

## Interpret field

```kotlin
fun interpretField(rawField, fieldModel): InterpretedMeasurement {
    val value = when (fieldModel.formula.type) {
        RAW -> rawField.rawNumeric ?: rawField.rawString
        SCALE_OFFSET -> rawField.rawNumeric * scale + offset
        ENUM -> map[rawField.rawString] ?: map[rawField.rawNumeric.toString()] ?: rawField.rawString
        STRING -> rawField.rawString
        UNSUPPORTED -> rawField.rawString
    }
    val status = evaluateThreshold(value, fieldModel.thresholds)
    return InterpretedMeasurement(...)
}
```

## Safe DTC clear

```kotlin
fun onClearDtcClicked() {
    showConfirmation(
        title = "Clear ECU fault codes?",
        message = WARNING_TEXT,
        onConfirm = { viewModel.clearDtc() }
    )
}
```

